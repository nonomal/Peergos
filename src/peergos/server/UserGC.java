package peergos.server;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class UserGC {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Main.initCrypto();
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("https://peergos.net"), true).get();
        String username = args[0];
        String password = args[1];
        UserContext context = UserContext.signIn(username, password, network, crypto).get();
        long usage = context.getSpaceUsage().join();
        checkRawUsage(context);
//        clearUnreachableChampNodes(context);
    }

    public static void clearUnreachableChampNodes(UserContext c) {
        //  First clear any failed uploads
//        c.cleanPartialUploads(t -> true).join();

        ContentAddressedStorage storage = c.network.dhtClient;
        MutablePointers mutable = c.network.mutable;
        Hasher hasher = c.crypto.hasher;
        PublicKeyHash owner = c.signer.publicKeyHash;
        FileWrapper root = c.getUserRoot().join();
        Set<PublicKeyHash> writers = WriterData.getOwnedKeysRecursive(owner, owner, mutable, storage, hasher).join()
                .stream()
                .filter(w ->  !w.equals(owner))
                .collect(Collectors.toSet());

        Map<PublicKeyHash, PublicKeyHash> toParent = new HashMap<>();
        for (PublicKeyHash writer : writers) {
            Set<PublicKeyHash> owned = WriterData.getDirectOwnedKeys(owner, writer, mutable, storage, hasher).join();
            for (PublicKeyHash child : owned) {
                toParent.put(child, writer);
            }
        }

        Map<SigningPrivateKeyAndPublicHash, Map<String, ByteArrayWrapper>> reachableKeys = new HashMap<>();
        traverseDescendants(root, "/" + c.username, (s, m, p) -> {
            reachableKeys.putIfAbsent(s, new HashMap<>());
            reachableKeys.get(s).put(p, new ByteArrayWrapper(m));
            return true;
        }, c);

        // handle each writing space separately
        for (PublicKeyHash writer : writers) {
            Map<ByteArrayWrapper, CborObject.CborMerkleLink> allKeys = new HashMap<>();
            Set<ByteArrayWrapper> emptyKeys = new HashSet<>();
            CommittedWriterData wd = WriterData.getWriterData(owner, writer, mutable, storage).join();
            ChampWrapper<CborObject.CborMerkleLink> champ = ChampWrapper.create((Cid) wd.props.tree.get(),
                    x -> Futures.of(x.data), storage, hasher, b -> (CborObject.CborMerkleLink) b).join();
            champ.applyToAllMappings(p -> {
                if (p.right.isPresent())
                    allKeys.put(p.left, p.right.get());
                else
                    emptyKeys.add(p.left);
                return Futures.of(true);
            }).join();

            Set<ByteArrayWrapper> unreachableKeys = new HashSet<>(allKeys.keySet());
            Optional<SigningPrivateKeyAndPublicHash> keypair = reachableKeys.keySet()
                    .stream()
                    .filter(s -> s.publicKeyHash.equals(writer))
                    .findFirst();
            if (keypair.isEmpty()) {
                // writing space is unreachable, but non-empty. Remove it by orphaning it.
                PublicKeyHash parent = toParent.get(writer);
                Optional<SigningPrivateKeyAndPublicHash> parentKeypair = reachableKeys.keySet()
                    .stream()
                    .filter(s -> s.publicKeyHash.equals(parent))
                    .findFirst();
                if (parentKeypair.isEmpty())
                    continue;
                CommittedWriterData pwd = WriterData.getWriterData(owner, parent, mutable, storage).join();
                c.network.synchronizer.applyComplexComputation(owner, parentKeypair.get(), (s, comm) -> {
                    TransactionId tid = storage.startTransaction(owner).join();
                    WriterData newPwd = pwd.props.removeOwnedKey(owner, parentKeypair.get(), writer, storage, hasher).join();
                    Snapshot updated = comm.commit(owner, parentKeypair.get(), newPwd, pwd, tid).join();
                    storage.closeTransaction(owner, tid).join();
                    return Futures.of(new Pair<>(updated, true));
                }).join();

                continue;
            }
            SigningPrivateKeyAndPublicHash signer = keypair.get();
            unreachableKeys.removeAll(reachableKeys.get(signer).values());

            if (! emptyKeys.isEmpty()) {
                c.network.synchronizer.applyComplexComputation(owner, signer, (s, comm) -> {
                    TransactionId tid = storage.startTransaction(owner).join();
                    WriterData current = wd.props;

                    for (ByteArrayWrapper key : emptyKeys) {
                        current = c.network.tree.remove(current, owner, signer, key.data, MaybeMultihash.empty(), tid).join();
                    }
                    Snapshot updated = comm.commit(owner, signer, current, wd, tid).join();
                    storage.closeTransaction(owner, tid).join();
                    return Futures.of(new Pair<>(updated, true));
                }).join();
            }

            if (unreachableKeys.isEmpty())
                continue;

            c.network.synchronizer.applyComplexComputation(owner, signer, (s, comm) -> {
                TransactionId tid = storage.startTransaction(owner).join();
                WriterData current = wd.props;

                for (ByteArrayWrapper key : unreachableKeys) {
                    current = c.network.tree.remove(current, owner, signer, key.data, MaybeMultihash.of(allKeys.get(key).target), tid).join();
                }
                Snapshot updated = comm.commit(owner, signer, current, wd, tid).join();
                storage.closeTransaction(owner, tid).join();
                return Futures.of(new Pair<>(updated, true));
            }).join();
        }
    }

    public static void checkRawUsage(UserContext c) {
        PublicKeyHash owner = c.signer.publicKeyHash;
        long serverCalculatedUsage = c.getSpaceUsage().join();
        Optional<BatWithId> mirror = c.getMirrorBat().join();
        Set<PublicKeyHash> writers = WriterData.getOwnedKeysRecursive(owner, owner, c.network.mutable, c.network.dhtClient, c.crypto.hasher).join();
        checkRawUsage(owner, writers, mirror, serverCalculatedUsage, c.network.dhtClient, c.network.mutable);
    }

    public static void checkRawUsage(PublicKeyHash owner,
                                     Set<PublicKeyHash> writers,
                                     Optional<BatWithId> mirror,
                                     long serverCalculatedUsage,
                                     ContentAddressedStorage storage,
                                     MutablePointers mutable) {
        Map<Cid, Long> blockSizes = new HashMap<>();
        Map<Cid, List<Cid>> linkedFrom = new HashMap<>();

        for (PublicKeyHash writer : writers) {
            getAllBlocksWithSize((Cid) mutable.getPointerTarget(owner, writer, storage).join().updated.get(),
                    mirror, storage, blockSizes, linkedFrom);
        }

        long totalFromBlocks = blockSizes.values().stream().mapToLong(i -> i).sum();
        if (totalFromBlocks != serverCalculatedUsage)
            throw new IllegalStateException("Incorrect usage! Expected: " + serverCalculatedUsage + ", actual: " + totalFromBlocks);
    }

    public static void getAllBlocks(Cid root,
                                    Optional<BatWithId> mirror,
                                    ContentAddressedStorage dht,
                                    Map<Cid, byte[]> blocks) {
        Optional<byte[]> raw = dht.getRaw(root, mirror).join();
        if (raw.isEmpty())
            return;

        byte[] block = raw.get();
        blocks.put(root, raw.get());
        if (! root.isRaw()) {
            List<Cid> children = CborObject.fromByteArray(block).links().stream().map(c -> (Cid) c).collect(Collectors.toList());
            for (Cid child : children) {
                if (child.isIdentity())
                    continue;
                getAllBlocks(child, mirror, dht, blocks);
            }
        }
    }

    private static void getAllBlocksWithSize(Cid root,
                                             Optional<BatWithId> mirror,
                                             ContentAddressedStorage dht,
                                             Map<Cid,  Long> res,
                                             Map<Cid, List<Cid>> linkedFrom) {
        Optional<byte[]> raw = dht.getRaw(root, mirror).join();
        if (raw.isEmpty())
            return;
        byte[] block = raw.get();
        res.put(root, (long) block.length);
        if (! root.isRaw()) {
            List<Cid> children = CborObject.fromByteArray(block).links().stream().map(c -> (Cid) c).collect(Collectors.toList());
            for (Cid child : children) {
                if (child.isIdentity())
                    continue;
                linkedFrom.putIfAbsent(child, new ArrayList<>());
                linkedFrom.get(child).add(root);
                getAllBlocksWithSize(child, mirror, dht, res, linkedFrom);
            }
        }
    }

    private static void traverseDescendants(FileWrapper dir,
                                            String path,
                                            TriFunction<SigningPrivateKeyAndPublicHash, byte[], String, Boolean> f,
                                            UserContext c) {
        f.apply(dir.signingPair(), dir.writableFilePointer().getMapKey(), path);
        Set<FileWrapper> children = dir.getChildren(c.crypto.hasher, c.network).join();
        List<ForkJoinTask> subtasks = new ArrayList<>();
        for (FileWrapper child : children) {
            if (!child.isDirectory()) {
                byte[] firstChunk = child.writableFilePointer().getMapKey();
                SigningPrivateKeyAndPublicHash childSigner = child.signingPair();
                f.apply(childSigner, firstChunk, path + "/" + child.getName());
                for (int i=0; i < child.getSize()/ (5*1024*1024); i++) {
                    byte[] streamSecret = child.getFileProperties().streamSecret.get();
                    byte[] mapKey = FileProperties.calculateMapKey(streamSecret, firstChunk, Optional.empty(), 5 * 1024 * 1024 * (i + 1), c.crypto.hasher).join().left;
                    f.apply(childSigner, mapKey, path + "/" + child.getName() + "[" + i + "]");
                }
            } else
                subtasks.add(ForkJoinPool.commonPool().submit(() -> traverseDescendants(child, path + "/" + child.getName(), f, c)));
        }
        for (ForkJoinTask subtask : subtasks) {
            subtask.join();
        }
    }
}
