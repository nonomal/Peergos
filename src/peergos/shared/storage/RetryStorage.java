package peergos.shared.storage;

import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.Multihash;
import peergos.shared.storage.auth.*;
import peergos.shared.user.fs.FragmentWithHash;
import peergos.shared.util.*;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class RetryStorage implements ContentAddressedStorage {

    private final ContentAddressedStorage target;
    private final int maxAttempts;

    public RetryStorage(ContentAddressedStorage target, int maxAttempts) {
        this.target = target;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return new RetryStorage(target.directToOrigin(), maxAttempts);
    }

    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return RetryHelper.runWithRetry(maxAttempts, () -> target.blockStoreProperties());
    }
    @Override
    public CompletableFuture<Cid> id() {
        return RetryHelper.runWithRetry(maxAttempts, () -> target.id());
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        return RetryHelper.runWithRetry(maxAttempts, () -> target.startTransaction(owner));
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        return RetryHelper.runWithRetry(maxAttempts, () -> target.closeTransaction(owner, tid));
    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks, TransactionId tid) {
        return RetryHelper.runWithRetry(maxAttempts, () -> target.put(owner, writer, signatures, blocks, tid));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Cid hash, Optional<BatWithId> bat) {
        return RetryHelper.runWithRetry(maxAttempts, () -> target.get(hash, bat));
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signatures,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressCounter) {
        return RetryHelper.runWithRetry(maxAttempts, () -> target.putRaw(owner, writer, signatures, blocks, tid, progressCounter));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, Optional<BatWithId> bat) {
        return RetryHelper.runWithRetry(maxAttempts, () -> target.getRaw(hash, bat));
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat) {
        return RetryHelper.runWithRetry(maxAttempts, () -> target.getChampLookup(owner, root, champKey, bat));
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        return RetryHelper.runWithRetry(maxAttempts, () -> target.getSize(block));
    }

    @Override
    public CompletableFuture<List<FragmentWithHash>> downloadFragments(PublicKeyHash owner,
                                                                       List<Cid> hashes,
                                                                       List<BatWithId> bats,
                                                                       Hasher h,
                                                                       ProgressConsumer<Long> monitor,
                                                                       double spaceIncreaseFactor) {
        return RetryHelper.runWithRetry(maxAttempts, () -> target.downloadFragments(owner, hashes, bats, h, monitor, spaceIncreaseFactor));
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authReads(List<MirrorCap> blocks) {
        return RetryHelper.runWithRetry(maxAttempts, () -> target.authReads(blocks));
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authWrites(PublicKeyHash owner,
                                                            PublicKeyHash writer,
                                                            List<byte[]> signedHashes,
                                                            List<Integer> blockSizes,
                                                            boolean isRaw,
                                                            TransactionId tid) {
        return RetryHelper.runWithRetry(maxAttempts, () -> target.authWrites(owner, writer, signedHashes, blockSizes, isRaw, tid));
    }
}
