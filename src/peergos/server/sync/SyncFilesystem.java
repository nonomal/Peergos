package peergos.server.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Consumer;

interface SyncFilesystem {

    boolean exists(Path p);

    void mkdirs(Path p);

    void delete(Path p);

    void moveTo(Path src, Path target);

    long getLastModified(Path p);

    void setModificationTime(Path p, long t);

    long size(Path p);

    void truncate(Path p, long size) throws IOException;

    void setBytes(Path p, long fileOffset, InputStream data, long size) throws IOException;

    InputStream getBytes(Path p, long fileOffset) throws IOException;

    DirectorySync.Blake3state hashFile(Path p);

    void applyToSubtree(Path start, Consumer<Path> file, Consumer<Path> dir);
}
