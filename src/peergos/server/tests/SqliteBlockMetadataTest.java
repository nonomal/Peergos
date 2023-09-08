package peergos.server.tests;

import org.junit.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.Assert.assertTrue;

public class SqliteBlockMetadataTest {

    private static final Random r = new Random(666);

    private static Cid randomCid() {
        byte[] hash = new byte[32];
        r.nextBytes(hash);
        return new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, hash);
    }

    private static List<Cid> randomCids(int count) {
        byte[] hash = new byte[32];
        r.nextBytes(hash);
        return IntStream.range(0, count).mapToObj(i -> randomCid()).collect(Collectors.toList());
    }

    @Test
    public void basicUsage() throws Exception {
        Path dir = Files.createTempDirectory("peergos-block-metadata");
        File storeFile = dir.resolve("metadata.sql" + System.currentTimeMillis()).toFile();
        String sqlFilePath = storeFile.getPath();
        Connection memory = Sqlite.build(sqlFilePath);
        Connection instance = new Sqlite.UncloseableConnection(memory);
        SqliteBlockMetadataStorage store = new SqliteBlockMetadataStorage(() -> instance, new SqliteCommands(), storeFile);
        long initialSize = store.currentSize();
        assertTrue(initialSize == 12288);
        Cid cid = randomCid();
        BlockMetadata meta = new BlockMetadata(10240, randomCids(20), Collections.emptyList());
        store.put(cid, meta);
        long sizeWithBlock = store.currentSize();

        // add same cid again
        store.put(cid, meta);
    }

    @Test
    public void compaction() throws Exception {
        Path dir = Files.createTempDirectory("peergos-block-metadata");
        File storeFile = dir.resolve("metadata.sql" + System.currentTimeMillis()).toFile();
        String sqlFilePath = storeFile.getPath();
        Connection memory = Sqlite.build(sqlFilePath);
        Connection instance = new Sqlite.UncloseableConnection(memory);
        SqliteBlockMetadataStorage store = new SqliteBlockMetadataStorage(() -> instance, new SqliteCommands(), storeFile);
        long initialSize = store.currentSize();
        assertTrue(initialSize == 12288);
        for (int i=0; i < 1500; i++)
            store.put(randomCid(), new BlockMetadata(10240, randomCids(20), Collections.emptyList()));
        long sizeWithBlocks = store.currentSize();
        store.compact();
        long sizeAfterCompaction = store.currentSize();
        assertTrue(sizeAfterCompaction < sizeWithBlocks);
    }
}
