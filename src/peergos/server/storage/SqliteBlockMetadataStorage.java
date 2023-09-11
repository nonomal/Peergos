package peergos.server.storage;

import peergos.server.sql.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.storage.auth.*;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

public class SqliteBlockMetadataStorage implements BlockMetadataStore {

    private static final Logger LOG = Logging.LOG();
    private static final String CREATE = "INSERT OR IGNORE INTO blockmetadata (cid, size, links, batids, accesstime) VALUES(?, ?, ?, ?, ?)";
    private static final String GET_INFO = "SELECT * FROM blockmetadata WHERE cid = ?;";
    private static final String REMOVE = "DELETE FROM blockmetadata where cid = ?;";
    private static final String LIST = "SELECT cid FROM blockmetadata;";
    private static final String VACUUM = "VACUUM;";

    private Supplier<Connection> conn;
    private final File sqlFile;

    public SqliteBlockMetadataStorage(Supplier<Connection> conn, SqlSupplier commands, File sqlFile) {
        this.conn = conn;
        this.sqlFile = sqlFile;
        init(commands);
    }

    public long currentSize() {
        return sqlFile.length();
    }

    public void compact() {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(VACUUM)) {
            stmt.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public void remove(Cid block) {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(REMOVE)) {

            insert.setBytes(1, block.toBytes());
            insert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    private Connection getConnection() {
        Connection connection = conn.get();
        try {
            connection.setAutoCommit(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            return connection;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized void init(SqlSupplier commands) {
        try (Connection conn = getConnection()) {
            commands.createTable(commands.createBlockMetadataStoreTableCommand(), conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<BlockMetadata> get(Cid block) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_INFO)) {
            stmt.setBytes(1, block.toBytes());
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                List<Cid> links = ((CborObject.CborList) CborObject.fromByteArray(rs.getBytes("links")))
                        .map(cbor -> Cid.cast(((CborObject.CborByteArray)cbor).value));
                List<BatId> batIds = ((CborObject.CborList) CborObject.fromByteArray(rs.getBytes("batids")))
                        .map(BatId::fromCbor);
                return Optional.of(new BlockMetadata(rs.getInt("size"), links, batIds));
            }
            return Optional.empty();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public void put(Cid block, BlockMetadata meta) {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(CREATE)) {

            insert.setBytes(1, block.toBytes());
            insert.setLong(2, meta.size);
            insert.setBytes(3, new CborObject.CborList(meta.links.stream()
                    .map(Cid::toBytes)
                    .map(CborObject.CborByteArray::new)
                    .collect(Collectors.toList()))
                    .toByteArray());
            insert.setBytes(4, new CborObject.CborList(meta.batids)
                    .toByteArray());
            insert.setLong(5, System.currentTimeMillis());
            insert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public Stream<Cid> list() {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(LIST)) {
            ResultSet rs = stmt.executeQuery();
            List<Cid> res = new ArrayList<>();
            while (rs.next()) {
                res.add(Cid.cast(rs.getBytes("cid")));
            }
            return res.stream();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }
}
