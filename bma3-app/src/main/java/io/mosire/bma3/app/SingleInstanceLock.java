package io.mosire.bma3.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * One live BMA3 process per data directory (D-061).
 *
 * <p>Agent-launched jars (D-058) and a systemd unit can otherwise coexist and write the same SQLite
 * message board, which corrupts it. The lock is an advisory {@link FileLock} on
 * {@code <dataDir>/.bma3.lock}; the lock file itself is <b>never deleted</b>, because unlinking it
 * lets a second process lock a fresh inode and bypass the guard.</p>
 */
public final class SingleInstanceLock implements AutoCloseable {

    /** Lock file name inside the data directory. */
    public static final String LOCK_FILE = ".bma3.lock";

    private final Path file;
    private final FileChannel channel;
    private final FileLock lock;

    private SingleInstanceLock(Path file, FileChannel channel, FileLock lock) {
        this.file = file;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Take the exclusive lock for one data directory.
     *
     * @param dataDir directory holding the message board
     * @return the held lock, to be closed on shutdown
     * @throws IOException              when the lock file cannot be opened
     * @throws IllegalStateException    when another process already holds it
     */
    public static SingleInstanceLock acquire(Path dataDir) throws IOException {
        Path directory = dataDir.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path file = directory.resolve(LOCK_FILE);
        FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException heldByThisJvm) {
            channel.close();
            throw new IllegalStateException("已有 BMA3 实例持有 " + file + "（同一进程内重复启动）");
        } catch (IOException failure) {
            channel.close();
            throw failure;
        }
        if (lock == null) {
            String owner = readOwner(file);
            channel.close();
            throw new IllegalStateException("已有 BMA3 实例运行中（" + owner + "），锁文件: " + file);
        }
        try {
            channel.truncate(0);
            channel.write(StandardCharsets.UTF_8.encode(describeOwner()), 0);
            channel.force(true);
        } catch (IOException ignored) {
            // The lock is what matters; a failed pid hint must not block startup.
        }
        return new SingleInstanceLock(file, channel, lock);
    }

    private static String describeOwner() {
        return "pid=" + ProcessHandle.current().pid() + "\n";
    }

    private static String readOwner(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? "pid 未知" : text;
        } catch (IOException unreadable) {
            return "pid 未知";
        }
    }

    /** Lock file being held. */
    public Path file() {
        return file;
    }

    @Override
    public void close() {
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
            // Releasing a lock that is already gone is not an error on shutdown.
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Same.
        }
    }
}
