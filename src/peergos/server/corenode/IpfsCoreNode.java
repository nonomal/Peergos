package peergos.server.corenode;
import java.util.logging.*;

import peergos.server.util.*;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class IpfsCoreNode implements CoreNode {
	private static final Logger LOG = Logging.LOG();
	public static final int MAX_FREE_PASSWORD_CHANGES = 10;

    private final PublicKeyHash peergosIdentity;
    private final ContentAddressedStorage ipfs;
    private final Hasher hasher;
    private final MutablePointers mutable;
    private final Account account;
    private final BatCave batCave;
    private final SigningPrivateKeyAndPublicHash signer;

    private final Map<String, List<UserPublicKeyLink>> chains = new ConcurrentHashMap<>();
    private final Map<PublicKeyHash, String> reverseLookup = new ConcurrentHashMap<>();
    private final List<String> usernames = new ArrayList<>();
    private final DifficultyGenerator difficultyGenerator;

    private MaybeMultihash currentRoot;
    private Optional<Long> currentSequence;

    public IpfsCoreNode(SigningPrivateKeyAndPublicHash pkiSigner,
                        int maxSignupsPerDay,
                        MaybeMultihash currentRoot,
                        Optional<Long> currentSequence,
                        ContentAddressedStorage ipfs,
                        Hasher hasher,
                        MutablePointers mutable,
                        Account account,
                        BatCave batCave,
                        PublicKeyHash peergosIdentity) {
        this.currentRoot = MaybeMultihash.empty();
        this.currentSequence = Optional.empty();
        this.ipfs = ipfs;
        this.hasher = hasher;
        this.mutable = mutable;
        this.account = account;
        this.batCave = batCave;
        this.peergosIdentity = peergosIdentity;
        this.signer = pkiSigner;
        this.update(currentRoot, currentSequence);
        this.difficultyGenerator = new DifficultyGenerator(System.currentTimeMillis(), maxSignupsPerDay);
    }

    public static CompletableFuture<byte[]> keyHash(ByteArrayWrapper username) {
        return Futures.of(Blake2b.Digest.newInstance().digest(username.data));
    }

    /** Update the existing mappings based on the diff between the current champ and the champ with the supplied root.
     *
     * @param newRoot The root of the new champ
     */
    private synchronized void update(MaybeMultihash newRoot, Optional<Long> newSequence) {
        updateAllMappings(signer.publicKeyHash, currentRoot, newRoot, ipfs, chains, reverseLookup, usernames);
        this.currentRoot = newRoot;
        this.currentSequence = newSequence;
    }

    public static MaybeMultihash getTreeRoot(MaybeMultihash pointerTarget, ContentAddressedStorage ipfs) {
        if (! pointerTarget.isPresent())
            return MaybeMultihash.empty();
        CommittedWriterData current = WriterData.getWriterData((Cid)pointerTarget.get(), Optional.empty(), ipfs).join();
        return current.props.tree.map(MaybeMultihash::of).orElseGet(MaybeMultihash::empty);

    }

    public static void updateAllMappings(PublicKeyHash pkiSigner,
                                         MaybeMultihash currentChampRoot,
                                         MaybeMultihash newChampRoot,
                                         ContentAddressedStorage ipfs,
                                         Map<String, List<UserPublicKeyLink>> chains,
                                         Map<PublicKeyHash, String> reverseLookup,
                                         List<String> usernames) {
        try {
            MaybeMultihash currentTree = getTreeRoot(currentChampRoot, ipfs);
            MaybeMultihash updatedTree = getTreeRoot(newChampRoot, ipfs);
            Consumer<Triple<ByteArrayWrapper, Optional<CborObject.CborMerkleLink>, Optional<CborObject.CborMerkleLink>>> consumer =
                    t -> updateMapping(t.left, t.middle, t.right, ipfs, chains, reverseLookup, usernames);
            Function<Cborable, CborObject.CborMerkleLink> fromCbor = c -> (CborObject.CborMerkleLink)c;
            Champ.applyToDiff(currentTree, updatedTree, 0, IpfsCoreNode::keyHash,
                    Collections.emptyList(), Collections.emptyList(),
                    consumer, ChampWrapper.BIT_WIDTH, ipfs, fromCbor).get();
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public static void updateMapping(ByteArrayWrapper key,
                                     Optional<CborObject.CborMerkleLink> oldValue,
                                     Optional<CborObject.CborMerkleLink> newValue,
                                     ContentAddressedStorage ipfs,
                                     Map<String, List<UserPublicKeyLink>> chains,
                                     Map<PublicKeyHash, String> reverseLookup,
                                     List<String> usernames) {
        try {
            Optional<CborObject> cborOpt = ipfs.get((Cid)newValue.get().target, Optional.empty()).get();
            if (!cborOpt.isPresent()) {
                LOG.severe("Couldn't retrieve new claim chain from " + newValue);
                return;
            }

            List<UserPublicKeyLink> updatedChain = ((CborObject.CborList) cborOpt.get()).value.stream()
                    .map(UserPublicKeyLink::fromCbor)
                    .collect(Collectors.toList());

            String username = new String(key.data);

            if (oldValue.isPresent()) {
                Optional<CborObject> existingCborOpt = ipfs.get((Cid)oldValue.get().target, Optional.empty()).get();
                if (!existingCborOpt.isPresent()) {
                    LOG.severe("Couldn't retrieve existing claim chain from " + newValue);
                    return;
                }
                List<UserPublicKeyLink> existingChain = ((CborObject.CborList) existingCborOpt.get()).value.stream()
                        .map(UserPublicKeyLink::fromCbor)
                        .collect(Collectors.toList());
                // Check legality
                UserPublicKeyLink.merge(existingChain, updatedChain, ipfs).get();
            }

            for (UserPublicKeyLink link : updatedChain) {
                reverseLookup.put(link.owner, username);
            }
            chains.put(username, updatedChain);
            if (! oldValue.isPresent()) {
                // This is a new user
                usernames.add(username);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
        }
    }

    /** Replay a series of block writes and pointer updates that form a signup
     *
     * @param owner
     * @param ops
     * @param ipfs
     * @param mutable
     */
    public static void applyOpLog(String username,
                                  PublicKeyHash owner,
                                  OpLog ops,
                                  ContentAddressedStorage ipfs,
                                  MutablePointers mutable,
                                  Account account,
                                  BatCave batCave) {
        TransactionId tid = ipfs.startTransaction(owner).join();
        for (Either<OpLog.PointerWrite, OpLog.BlockWrite> op : ops.operations) {
            if (op.isA()) {
                OpLog.PointerWrite pointerUpdate = op.a();
                mutable.setPointer(owner, pointerUpdate.writer, pointerUpdate.writerSignedChampRootCas).join();
            } else {
                OpLog.BlockWrite block = op.b();
                if (block.isRaw)
                    ipfs.putRaw(owner, block.writer, block.signature, block.block, tid, x -> {}).join();
                else
                    ipfs.put(owner, block.writer, block.signature, block.block, tid).join();
            }
        }
        if (ops.loginData != null) {
            if (! ops.loginData.left.username.equals(username))
                throw new IllegalStateException("Invalid signup data!");
            account.setLoginData(ops.loginData.left, ops.loginData.right).join();
        }
        if (ops.mirrorBat.isPresent()) {
            Pair<BatWithId, byte[]> p = ops.mirrorBat.get();
            batCave.addBat(username, p.left.id(), p.left.bat, p.right);
        }
        ipfs.closeTransaction(owner, tid).join();
    }

    @Override
    public CompletableFuture<Optional<RequiredDifficulty>> signup(String username,
                                                                  UserPublicKeyLink chain,
                                                                  OpLog setupOperations,
                                                                  ProofOfWork proof,
                                                                  String token) {
        Optional<RequiredDifficulty> pkiResult = updateChain(username, Arrays.asList(chain), proof, token).join();
        if (pkiResult.isPresent())
            return Futures.of(pkiResult);

        applyOpLog(username, chain.owner, setupOperations, ipfs, mutable, account, batCave);

        return Futures.of(Optional.empty());
    }

    /** Update a user's public key chain, keeping the in memory mappings correct and committing the new pki root
     *
     * @param username
     * @param updatedChain
     * @return
     */
    @Override
    public synchronized CompletableFuture<Optional<RequiredDifficulty>> updateChain(String username,
                                                                                    List<UserPublicKeyLink> updatedChain,
                                                                                    ProofOfWork proof,
                                                                                    String token) {
        if (! UsernameValidator.isValidUsername(username))
            throw new IllegalStateException("Invalid username");

        try {
            CommittedWriterData current = WriterData.getWriterData((Cid)currentRoot.get(), currentSequence, ipfs).get();
            MaybeMultihash currentTree = current.props.tree.map(MaybeMultihash::of).orElseGet(MaybeMultihash::empty);

            ChampWrapper<CborObject.CborMerkleLink> champ = currentTree.isPresent() ?
                    ChampWrapper.create((Cid)currentTree.get(), IpfsCoreNode::keyHash, ipfs, hasher, c -> (CborObject.CborMerkleLink)c).get() :
                    IpfsTransaction.call(peergosIdentity,
                            tid -> ChampWrapper.create(signer.publicKeyHash, signer, IpfsCoreNode::keyHash, tid, ipfs, hasher, c -> (CborObject.CborMerkleLink)c),
                            ipfs).get();
            Optional<CborObject.CborMerkleLink> existing = champ.get(username.getBytes()).get();
            Optional<CborObject> cborOpt = existing.isPresent() ?
                    ipfs.get((Cid) existing.get().target, Optional.empty()).get() :
                    Optional.empty();
            if (! cborOpt.isPresent() && existing.isPresent()) {
                LOG.severe("Couldn't retrieve existing claim chain from " + existing + " for " + username);
                return Futures.of(Optional.empty());
            }
            List<UserPublicKeyLink> existingChain = cborOpt.map(cbor -> ((CborObject.CborList) cbor).value.stream()
                    .map(UserPublicKeyLink::fromCbor)
                    .collect(Collectors.toList()))
                    .orElse(Collections.emptyList());

            // Check proof of work is sufficient, unless it is a password change
            byte[] hash = hasher.sha256(ArrayOps.concat(proof.prefix, new CborObject.CborList(updatedChain).serialize())).join();
            difficultyGenerator.updateTime(System.currentTimeMillis());
            int requiredDifficulty = difficultyGenerator.currentDifficulty();
            if (existingChain.isEmpty() || existingChain.size() > MAX_FREE_PASSWORD_CHANGES) {
                if (!ProofOfWork.satisfiesDifficulty(requiredDifficulty, hash)) {
                    LOG.log(Level.INFO, "Rejected request with insufficient proof of work for difficulty: " +
                            requiredDifficulty + " and username " + username);
                    return Futures.of(Optional.of(new RequiredDifficulty(requiredDifficulty)));
                }
            }

            difficultyGenerator.addEvent();

            List<UserPublicKeyLink> mergedChain = UserPublicKeyLink.merge(existingChain, updatedChain, ipfs).get();
            CborObject.CborList mergedChainCbor = new CborObject.CborList(mergedChain.stream()
                    .map(Cborable::toCbor)
                    .collect(Collectors.toList()));
            Multihash mergedChainHash = IpfsTransaction.call(peergosIdentity,
                    tid -> ipfs.put(peergosIdentity, signer, mergedChainCbor.toByteArray(), hasher, tid),
                    ipfs).get();
            synchronized (this) {
                return IpfsTransaction.call(peergosIdentity,
                        tid -> champ.put(signer.publicKeyHash, signer, username.getBytes(), existing, new CborObject.CborMerkleLink(mergedChainHash), tid)
                                .thenCompose(newPkiRoot -> current.props.withChamp(newPkiRoot)
                                        .commit(peergosIdentity, signer, currentRoot, currentSequence, mutable, ipfs, hasher, tid)),
                        ipfs
                ).thenApply(committed -> {
                    if (existingChain.isEmpty())
                        usernames.add(username);
                    PublicKeyHash owner = updatedChain.get(updatedChain.size() - 1).owner;
                    reverseLookup.put(owner, username);
                    chains.put(username, mergedChain);
                    currentRoot = committed.get(signer).hash;
                    currentSequence = committed.get(signer).sequence;
                    return Optional.empty();
                });
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public synchronized CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        return CompletableFuture.completedFuture(chains.getOrDefault(username, Collections.emptyList()));
    }

    @Override
    public synchronized CompletableFuture<String> getUsername(PublicKeyHash key) {
        return CompletableFuture.completedFuture(Optional.ofNullable(reverseLookup.get(key))
                .orElseThrow(() -> new IllegalStateException("Unknown identity key: " + key)));
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        return CompletableFuture.completedFuture(usernames);
    }

    @Override
    public CompletableFuture<UserSnapshot> migrateUser(String username,
                                                       List<UserPublicKeyLink> newChain,
                                                       Multihash currentStorageId,
                                                       Optional<BatWithId> mirrorBat) {
        throw new IllegalStateException("Migration from pki node unimplemented!");
    }

    @Override
    public void close() throws IOException {}
}
