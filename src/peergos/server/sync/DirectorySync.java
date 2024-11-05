package peergos.server.sync;

import peergos.server.Builder;
import peergos.server.Main;
import peergos.server.storage.FileBlockCache;
import peergos.server.user.JavaImageThumbnailer;
import peergos.server.util.Args;
import peergos.server.util.Logging;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.corenode.HTTPCoreNode;
import peergos.shared.login.mfa.MultiFactorAuthMethod;
import peergos.shared.login.mfa.MultiFactorAuthRequest;
import peergos.shared.login.mfa.MultiFactorAuthResponse;
import peergos.shared.mutable.HttpMutablePointers;
import peergos.shared.social.HttpSocialNetwork;
import peergos.shared.storage.HttpSpaceUsage;
import peergos.shared.storage.UnauthedCachingStorage;
import peergos.shared.user.LinkProperties;
import peergos.shared.user.TrieNodeImpl;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.*;
import peergos.shared.util.Either;
import peergos.shared.util.Futures;
import peergos.shared.util.Pair;
import peergos.shared.util.PathUtil;

import java.io.*;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DirectorySync {
    private static final Logger LOG = Logging.LOG();

    private static void disableLogSpam() {
        // disable log spam
        TrieNodeImpl.disableLog();
        HttpMutablePointers.disableLog();
        NetworkAccess.disableLog();
        HTTPCoreNode.disableLog();
        HttpSocialNetwork.disableLog();
        HttpSpaceUsage.disableLog();
        FileUploader.disableLog();
        LazyInputStreamCombiner.disableLog();
    }

    public static boolean syncDir(Args args) {
        try {
            disableLogSpam();

            String address = args.getArg("peergos-url");
            URL serverURL = new URL(address);
            Crypto crypto = Main.initCrypto();
            long blockCacheSizeBytes = args.getLong("block-cache-size-bytes", 1024 * 1024 * 1024L);
            NetworkAccess network = Builder.buildJavaNetworkAccess(serverURL, address.startsWith("https")).join()
                    .withStorage(s -> new UnauthedCachingStorage(s, new FileBlockCache(args.fromPeergosDir("block-cache-dir", "block-cache"), blockCacheSizeBytes), crypto.hasher));
            ThumbnailGenerator.setInstance(new JavaImageThumbnailer());
            List<String> links = Arrays.asList(args.getArg("links").split(","));
            Supplier<CompletableFuture<String>> linkUserPassword = () -> Futures.of("");
            List<Supplier<CompletableFuture<String>>> linkPasswords = IntStream.range(0, links.size())
                    .mapToObj(i -> linkUserPassword)
                    .collect(Collectors.toList());
            UserContext context = UserContext.fromSecretLinksV2(links, linkPasswords, network, crypto).join();
            String linkPath = context.getEntryPath().join();
            int maxDownloadParallelism = args.getInt("mac-parallelism", 32);

            PeergosSyncFS remote = new PeergosSyncFS(context);
            LocalFileSystem local = new LocalFileSystem();
            SyncState syncedState = new JdbcTreeState(args.fromPeergosDir("sync-state-file", "dir-sync-state.sql").toString());
            List<String> localDirs = Arrays.asList(args.getArg("local-dirs").split(","));
            if (links.size() != localDirs.size())
                throw new IllegalArgumentException("Mismatched number of local dirs and links");

            while (true) {
                try {
                    for (int i=0; i < links.size(); i++) {
                        Path localDir = Paths.get(localDirs.get(i));
                        Path remoteDir = PathUtil.get(linkPath);
                        log("Syncing " + localDir + " to+from " + remoteDir);
                        long t0 = System.currentTimeMillis();
                        syncDirs(local, localDir, remote, remoteDir, syncedState, maxDownloadParallelism);
                        long t1 = System.currentTimeMillis();
                        log("Dir sync took " + (t1 - t0) / 1000 + "s");
                    }
                    Thread.sleep(30_000);
                } catch (Exception e) {
                    e.printStackTrace();
                    LOG.log(Level.WARNING, e, e::getMessage);
                    Thread.sleep(30_000);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, e, e::getMessage);
            throw new RuntimeException(e);
        }
    }

    public static boolean init(Args args) {
        disableLogSpam();
        Console console = System.console();
        String username = new String(console.readLine("Enter username:"));
        String password = new String(console.readPassword("Enter password:"));
        String address = args.getArg("peergos-url");
        try {
            URL serverURL = new URL(address);
            NetworkAccess network = Builder.buildJavaNetworkAccess(serverURL, address.startsWith("https")).join();
            Crypto crypto = Main.initCrypto();
            UserContext context = UserContext.signIn(username, password, mfar -> mfa(mfar), network, crypto).join();
            String peergosPath = new String(console.readLine("Enter the peergos path you want to sync to (e.g. /demo/media/images):"));
            Path toSync = PathUtil.get(peergosPath).normalize();
            if (toSync.getNameCount() < 2)
                throw new IllegalArgumentException("You cannot sync to your Peergos home directory, please make a sub-directory.");
            Optional<FileWrapper> dir = context.getByPath(toSync).join();
            if (dir.isEmpty())
                throw new IllegalArgumentException("Directory "+toSync+" does not exist in Peergos!");
            // ensure directory is in its own writing space
            if (dir.get().owner().equals(context.signer.publicKeyHash)) {
                // our file
                context.shareWriteAccessWith(toSync, Collections.emptySet()).join();
            } else {
                // something we have write access to
                if (! dir.get().isWritable())
                    throw new IllegalArgumentException("You do not have write access to this directory!");
            }
            LinkProperties link = context.createSecretLink(toSync.toString(), true, Optional.empty(), Optional.empty(), "", false).join();

            String cap = link.toLinkString(context.signer.publicKeyHash);

            System.out.println("Run the sync dir command with the following args: -links " + cap + " -local-dirs $LOCAL_DIR");
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static CompletableFuture<MultiFactorAuthResponse> mfa(MultiFactorAuthRequest req) {
        Optional<MultiFactorAuthMethod> anyTotp = req.methods.stream().filter(m -> m.type == MultiFactorAuthMethod.Type.TOTP).findFirst();
        if (anyTotp.isEmpty())
            throw new IllegalStateException("No supported 2 factor auth method! " + req.methods);
        MultiFactorAuthMethod totp = anyTotp.get();
        Console console = System.console();
        String code = new String(console.readLine("Enter TOTP code for login:"));
        return Futures.of(new MultiFactorAuthResponse(totp.credentialId, Either.a(code)));
    }

    public static void syncDirs(SyncFilesystem localFS, Path localDir,
                                SyncFilesystem remoteFS, Path remoteDir,
                                SyncState syncedVersions,
                                int maxParallelism) throws IOException {
        // first complete any failed in progress copy ops
        List<CopyOp> ops = syncedVersions.getInProgressCopies();
        if (! ops.isEmpty())
            log("Rerunning failed copy operations...");
        for (CopyOp op : ops) {
            applyCopyOp(op.isLocalTarget ? remoteFS : localFS, op.isLocalTarget ? localFS : remoteFS, op, syncedVersions);
            syncedVersions.finishCopies(List.of(op));
        }

        RamTreeState localState = new RamTreeState();
        buildDirState(localFS, localDir, localState, syncedVersions);
        log("Found " + localState.filesByPath.size() + " local files");

        RamTreeState remoteState = new RamTreeState();
        buildDirState(remoteFS, remoteDir, remoteState, syncedVersions);
        log("Found " + remoteState.filesByPath.size() + " remote files");

        TreeSet<String> allPaths = new TreeSet<>(localState.filesByPath.keySet());
        allPaths.addAll(remoteState.filesByPath.keySet());
        Set<String> doneFiles = Collections.synchronizedSet(new HashSet<>());

        List<ForkJoinTask<?>> downloads = new ArrayList<>();
        AtomicInteger maxDownloadConcurrency = new AtomicInteger(maxParallelism);

        for (String relativePath : allPaths) {
            if (doneFiles.contains(relativePath))
                continue;
            FileState synced = syncedVersions.byPath(relativePath);
            FileState local = localState.byPath(relativePath);
            FileState remote = remoteState.byPath(relativePath);
            boolean isLocalCopy = synced == null && local == null;
            if (isLocalCopy) {
                while (maxDownloadConcurrency.get() == 0) {
                    try {Thread.sleep(5_000); } catch (InterruptedException e) {}
                }
                maxDownloadConcurrency.decrementAndGet();
                downloads.add(ForkJoinPool.commonPool().submit(() -> {
                    try {
                        syncFile(synced, local, remote, localFS, localDir, remoteFS, remoteDir, syncedVersions, localState, remoteState, doneFiles);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    } finally {
                        maxDownloadConcurrency.incrementAndGet();
                    }
                }));
            } else
                syncFile(synced, local, remote, localFS, localDir, remoteFS, remoteDir, syncedVersions, localState, remoteState, doneFiles);
        }

        for (ForkJoinTask<?> download : downloads) {
            download.join();
        }

        // all files are in sync, now sync dirs
        Comparator<String> longestFirst = (a, b) -> -a.compareTo(b);
        SortedSet<String> allDirs = new TreeSet<>(longestFirst);
        allDirs.addAll(remoteState.getDirs());
        allDirs.addAll(localState.getDirs());
        allDirs.addAll(syncedVersions.getDirs());
        for (String dirPath : allDirs) {
            boolean hasLocal = localState.hasDir(dirPath);
            boolean hasRemote = remoteState.hasDir(dirPath);
            boolean hasSynced = syncedVersions.hasDir(dirPath);
            if (hasLocal && hasRemote) {
                syncedVersions.addDir(dirPath);
            } else if (! hasLocal && ! hasRemote) {
                syncedVersions.removeDir(dirPath);
            } else if (hasLocal) {
                if (hasSynced) { // delete
                    log("Sync local: delete dir " + dirPath);
                    localFS.delete(localDir.resolve(dirPath));
                } else {
                    log("Sync Remote: mkdir " + dirPath);
                    remoteFS.mkdirs(remoteDir.resolve(dirPath));
                }
            } else {
                if (hasSynced) { // delete
                    log("Sync Remote: delete dir " + dirPath);
                    remoteFS.delete(remoteDir.resolve(dirPath));
                } else {
                    log("Sync Local: mkdir " + dirPath);
                    localFS.mkdirs(localDir.resolve(dirPath));
                }
            }
        }
    }

    private static void log(String msg) {
        System.out.println(msg);
        LOG.info(msg);
    }

    public static void syncFile(FileState synced, FileState local, FileState remote,
                                SyncFilesystem localFs, Path localDir,
                                SyncFilesystem remoteFs, Path remoteDir,
                                SyncState syncedVersions, RamTreeState localTree, RamTreeState remoteTree,
                                Set<String> doneFiles) throws IOException {

        if (synced == null) {
            if (local == null) { // remotely added or renamed
                List<FileState> byHash = localTree.byHash(remote.hash);
                Optional<FileState> remoteAtHashedPath = byHash.size() == 1 ?
                        Optional.ofNullable(remoteTree.byPath(byHash.get(0).relPath)) :
                        Optional.empty();
                if (byHash.size() == 1 && remoteAtHashedPath.isEmpty()) {// rename
                    FileState toMove = byHash.get(0);
                    log("Sync Local: Moving " + toMove.relPath + " ==> " + remote.relPath);
                    localFs.moveTo(localDir.resolve(toMove.relPath), localDir.resolve(remote.relPath));
                    syncedVersions.remove(toMove.relPath);
                    doneFiles.add(toMove.relPath);
                } else {
                    log("Sync Local: Copying " + remote.relPath);
                    List<Pair<Long, Long>> diffs = remote.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(true, remoteDir.resolve(remote.relPath), localDir.resolve(remote.relPath), remote, null, d.left, d.right))
                            .collect(Collectors.toList());
                    copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions);
                }
                syncedVersions.add(remote);
            } else if (remote == null) { // locally added or renamed
                List<FileState> byHash = remoteTree.byHash(local.hash);
                Optional<FileState> localAtHashedPath = byHash.size() == 1 ?
                        Optional.ofNullable(localTree.byPath(byHash.get(0).relPath)) :
                        Optional.empty();
                if (byHash.size() == 1 && localAtHashedPath.isEmpty()) {// rename
                    FileState toMove = byHash.get(0);
                    log("Sync Remote: Moving " + toMove.relPath + " ==> " + local.relPath);
                    remoteFs.moveTo(remoteDir.resolve(toMove.relPath), remoteDir.resolve(Paths.get(local.relPath)));
                    syncedVersions.remove(toMove.relPath);
                    doneFiles.add(toMove.relPath);
                } else {
                    log("Sync Remote: Copying " + local.relPath);
                    List<Pair<Long, Long>> diffs = local.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(false, localDir.resolve(local.relPath), remoteDir.resolve(local.relPath), local, null, d.left, d.right))
                            .collect(Collectors.toList());
                    copyFileDiffAndTruncate(localFs, remoteFs, ops, syncedVersions);
                }
                syncedVersions.add(local);
            } else {
                // concurrent addition, rename 1 if contents are different
                if (remote.hash.equals(local.hash)) {
                    if (local.modificationTime > remote.modificationTime) {
                        log("Remote: Set mod time " + local.relPath);
                        remoteFs.setModificationTime(remoteDir.resolve(local.relPath), local.modificationTime);
                        syncedVersions.add(local);
                        return;
                    } else if (remote.modificationTime > local.modificationTime) {
                        log("Sync Local: Set mod time " + local.relPath);
                        localFs.setModificationTime(localDir.resolve(local.relPath), remote.modificationTime);
                        syncedVersions.add(remote);
                        return;
                    }
                    syncedVersions.add(local);
                } else {
                    log("Sync Remote: Concurrent file addition: " + local.relPath + " renaming local version");
                    FileState renamed = renameOnConflict(localFs, localDir.resolve(local.relPath), local);
                    List<Pair<Long, Long>> diffs = remote.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(true, remoteDir.resolve(remote.relPath), localDir.resolve(remote.relPath), remote, null, d.left, d.right))
                            .collect(Collectors.toList());
                    copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions);
                    syncedVersions.add(remote);

                    List<Pair<Long, Long>> diffs2 = local.diffRanges(null);
                    List<CopyOp> ops2 = diffs2.stream()
                            .map(d -> new CopyOp(false, localDir.resolve(renamed.relPath), remoteDir.resolve(renamed.relPath), local, null, d.left, d.right))
                            .collect(Collectors.toList());
                    copyFileDiffAndTruncate(localFs, remoteFs, ops2, syncedVersions);
                    syncedVersions.add(renamed);
                }
            }
        } else {
            if (synced.equals(local)) { // remote change only
                if (remote == null) { // deletion or rename
                    List<FileState> byHash = remoteTree.byHash(local.hash);
                    Optional<FileState> localAtHashedPath = byHash.size() == 1 ?
                            Optional.ofNullable(localTree.byPath(byHash.get(0).relPath)) :
                            Optional.empty();
                    if (byHash.size() == 1 && localAtHashedPath.isEmpty()) {// rename
                        // we will do the local rename when we process the new remote entry
                    } else {
                        log("Sync Local: delete " + local.relPath);
                        localFs.delete(localDir.resolve(local.relPath));
                        syncedVersions.remove(local.relPath);
                    }
                } else if (remote.hash.equals(local.hash)) {
                    // already synced
                } else {
                    log("Sync Local: Copying changes to " + remote.relPath);
                    List<Pair<Long, Long>> diffs = remote.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(true, remoteDir.resolve(remote.relPath), localDir.resolve(remote.relPath), remote, null, d.left, d.right))
                            .collect(Collectors.toList());
                    copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions);
                    syncedVersions.add(remote);
                }
            } else if (synced.equals(remote)) { // local only change
                if (local == null) { // deletion or rename
                    List<FileState> byHash = localTree.byHash(remote.hash);
                    Optional<FileState> remoteAtHashedPath = byHash.size() == 1 ?
                            Optional.ofNullable(remoteTree.byPath(byHash.get(0).relPath)) :
                            Optional.empty();
                    if (byHash.size() == 1 && remoteAtHashedPath.isEmpty()) {// rename
                        // we will do the local rename when we process the new remote entry
                    } else {
                        log("Sync Remote: delete " + remote.relPath);
                        remoteFs.delete(remoteDir.resolve(remote.relPath));
                        syncedVersions.remove(remote.relPath);
                    }
                } else if (remote.hash.equals(local.hash)) {
                    // already synced
                } else {
                    log("Sync Remote: Copying changes to " + local.relPath);
                    List<Pair<Long, Long>> diffs = local.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(false, localDir.resolve(local.relPath), remoteDir.resolve(local.relPath), local, null, d.left, d.right))
                            .collect(Collectors.toList());
                    copyFileDiffAndTruncate(localFs, remoteFs, ops, syncedVersions);
                    syncedVersions.add(local);
                }
            } else { // concurrent change/deletion
                if (local == null && remote == null) {// concurrent deletes
                    log("Sync Concurrent delete on " + synced.relPath);
                    syncedVersions.remove(synced.relPath);
                }
                if (local == null) { // local delete, copy changed remote
                    log("Sync Local: deleted, copying changed remote " + remote.relPath);
                    List<Pair<Long, Long>> diffs = remote.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(true, remoteDir.resolve(remote.relPath), localDir.resolve(remote.relPath), remote, null, d.left, d.right))
                            .collect(Collectors.toList());
                    copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions);
                    syncedVersions.add(remote);
                }
                if (remote == null) { // remote delete, copy changed local
                    log("Sync Remote: deleted, copying changed local " + local.relPath);
                    List<Pair<Long, Long>> diffs = local.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(false, localDir.resolve(local.relPath), remoteDir.resolve(local.relPath), local, null, d.left, d.right))
                            .collect(Collectors.toList());
                    copyFileDiffAndTruncate(localFs, remoteFs, ops, syncedVersions);
                    syncedVersions.add(local);
                }
                // concurrent change, rename one sync the other
                log("Sync Remote: Concurrent change: " + local.relPath + " renaming local version");
                FileState renamed = renameOnConflict(localFs, localDir.resolve(local.relPath), local);
                List<Pair<Long, Long>> diffs = remote.diffRanges(null);
                List<CopyOp> ops = diffs.stream()
                        .map(d -> new CopyOp(true, remoteDir.resolve(remote.relPath), localDir.resolve(remote.relPath), remote, null, d.left, d.right))
                        .collect(Collectors.toList());
                copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions);
                syncedVersions.add(remote);

                List<Pair<Long, Long>> diffs2 = local.diffRanges(null);
                List<CopyOp> ops2 = diffs2.stream()
                        .map(d -> new CopyOp(false, localDir.resolve(renamed.relPath), remoteDir.resolve(renamed.relPath), local, null, d.left, d.right))
                        .collect(Collectors.toList());
                copyFileDiffAndTruncate(localFs, remoteFs, ops2, syncedVersions);
                syncedVersions.add(renamed);
            }
        }
    }

    public static FileState renameOnConflict(SyncFilesystem fs, Path f, FileState s) {
        String name = f.getFileName().toString();
        String newName;
        if (name.contains("[conflict-")) {
            int start = name.lastIndexOf("[conflict-");
            int end = name.indexOf("]", start);
            int version = Integer.parseInt(name.substring(start + "[conflict-".length(), end));
            while (true) {
                newName = name.substring(0, start) + "[conflict-" + (version + 1) + "]" + name.substring(end + 1);
                if (! fs.exists(f.getParent().resolve(newName)))
                    break;
                version++;
            }
        } else {
            int version = 0;
            while (true) {
                if (name.contains(".")) {
                    int dot = name.lastIndexOf(".");
                    newName = name.substring(0, dot) + "[conflict-" + version + "]" + name.substring(dot);
                } else
                    newName = name + "[conflict-" + version + "]";
                if (! fs.exists(f.getParent().resolve(newName)))
                    break;
                version++;
            }
        }
        fs.moveTo(f, f.getParent().resolve(newName));
        return new FileState(s.relPath.substring(0, s.relPath.length() - name.length()) + newName, s.modificationTime, s.size, s.hash);
    }

    public static void copyFileDiffAndTruncate(SyncFilesystem srcFs, SyncFilesystem targetFs, List<CopyOp> ops, SyncState syncDb) throws IOException {
        // first write the operation to the db
        syncDb.startCopies(ops);

        for (CopyOp op : ops) {
            applyCopyOp(srcFs, targetFs, op, syncDb);
        }

        // now remove the operation from the db
        syncDb.finishCopies(ops);
    }

    public static void applyCopyOp(SyncFilesystem srcFs, SyncFilesystem targetFs, CopyOp op, SyncState syncDb) throws IOException {
        log("COPY from " + op.source + " to " + op.target + " range=[" + op.diffStart +", " + op.diffEnd+"]");
        targetFs.mkdirs(op.target.getParent());
        long priorSize = op.targetState != null ? op.targetState.size : 0;
        long size = op.sourceState.size;
        long lastModified = op.sourceState.modificationTime;

        long start = op.diffStart;
        long end = op.diffEnd;
        try (AsyncReader fin = srcFs.getBytes(op.source, start)) {
            targetFs.setBytes(op.target, start, fin, end - start);
        }
        if (priorSize > size) {
            log("Sync Truncating file " + op.sourceState.relPath + " from " + priorSize + " to " + size);
            targetFs.truncate(op.target, size);
        }

        targetFs.setModificationTime(op.target, lastModified);
        targetFs.setHash(op.target, op.sourceState.hash);
    }

    public static void buildDirState(SyncFilesystem fs, Path dir, RamTreeState res, SyncState synced) throws IOException {
        if (! fs.exists(dir))
            throw new IllegalStateException("Dir does not exist: " + dir);
        fs.applyToSubtree(dir, f -> {
            String relPath = f.toString().substring(dir.toString().length() + 1);
            FileState atSync = synced.byPath(relPath);
            long modified = fs.getLastModified(f);
            if (atSync != null && atSync.modificationTime == modified) {
                res.add(atSync);
            } else {
                Blake3state b3 = fs.hashFile(f);
                FileState fstat = new FileState(relPath, modified, fs.size(f), b3);
                res.add(fstat);
            }
        }, d -> {
            String relPath = d.toString().substring(dir.toString().length() + 1);
            res.addDir(relPath);
        });
    }
}