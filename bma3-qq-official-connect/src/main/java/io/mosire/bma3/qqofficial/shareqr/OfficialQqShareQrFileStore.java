package io.mosire.bma3.qqofficial.shareqr;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Writes share-QR PNGs only below {@code <dataDir>/qr/} using random names.
 *
 * <p>The final path is published by creating a hard link from a fully-written temp file. The link creation fails atomically if the destination already exists, so an existing file
 * is never overwritten and a reader never observes a partially-written PNG. Temp files are
 * deleted on every failure path. Symlinked data/QR directories and any path that real-path-resolves
 * outside the QR directory are rejected.</p>
 */
public final class OfficialQqShareQrFileStore {

    private static final int MAX_NAME_ATTEMPTS = 8;
    private static final String FILE_PREFIX = "qq-share-";
    private static final String FILE_SUFFIX = ".png";
    private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS =
            Set.copyOf(EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
    private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS =
            Set.copyOf(EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));

    private final Path dataDir;
    private final Supplier<String> nameTokens;

    public OfficialQqShareQrFileStore(Path dataDir) {
        this(dataDir, randomNameSupplier());
    }

    OfficialQqShareQrFileStore(Path dataDir, Supplier<String> nameTokens) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir")
                .toAbsolutePath()
                .normalize();
        this.nameTokens = Objects.requireNonNull(nameTokens, "nameTokens");
    }

    /** Write bytes to a new random PNG and return the absolute, normalized path. */
    public Path writePng(byte[] png) throws OfficialQqShareQrException {
        if (png == null || png.length == 0) {
            throw ioFailure();
        }
        Path qrDir = ensureQrDirectory();
        for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
            Path target = qrDir.resolve(fileName(nextNameToken(nameTokens))).normalize();
            if (!qrDir.equals(target.getParent())) {
                throw ioFailure();
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }

            Path temp = null;
            boolean published = false;
            try {
                temp = Files.createTempFile(qrDir, ".qq-share-", ".tmp");
                setOwnerOnlyFilePermissions(temp);
                Files.write(
                        temp,
                        png,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                Files.createLink(target, temp);
                published = true;
                Files.delete(temp);
                temp = null;
                return target;
            } catch (FileAlreadyExistsException collision) {
                // Another writer won the random name; remove our temp and try another name.
            } catch (IOException | RuntimeException failure) {
                if (published) {
                    deleteQuietly(target);
                }
                throw ioFailure();
            } finally {
                if (temp != null) {
                    deleteQuietly(temp);
                }
            }
        }
        throw ioFailure();
    }

    private Path ensureQrDirectory() throws OfficialQqShareQrException {
        try {
            ensureDirectory(dataDir);
            Path qrDir = dataDir.resolve("qr").normalize();
            if (!dataDir.equals(qrDir.getParent())) {
                throw ioFailure();
            }
            ensureDirectory(qrDir);

            Path realDataDir = dataDir.toRealPath();
            Path realQrDir = qrDir.toRealPath();
            if (!realQrDir.startsWith(realDataDir) || realQrDir.equals(realDataDir)) {
                throw ioFailure();
            }
            setOwnerOnlyDirectoryPermissions(qrDir);
            return qrDir;
        } catch (OfficialQqShareQrException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw ioFailure();
        }
    }

    private static void ensureDirectory(Path directory) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("unsafe directory");
            }
            return;
        }
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("unsafe directory");
        }
    }

    private static void setOwnerOnlyFilePermissions(Path file) throws IOException {
        if (supportsPosix(file)) {
            Files.setPosixFilePermissions(file, OWNER_FILE_PERMISSIONS);
        }
    }

    private static void setOwnerOnlyDirectoryPermissions(Path directory) throws IOException {
        if (supportsPosix(directory)) {
            Files.setPosixFilePermissions(directory, OWNER_DIRECTORY_PERMISSIONS);
        }
    }

    private static boolean supportsPosix(Path path) {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class) != null;
    }

    private static String nextNameToken(Supplier<String> supplier) throws OfficialQqShareQrException {
        String token;
        try {
            token = supplier.get();
        } catch (RuntimeException failure) {
            throw ioFailure();
        }
        if (token == null || !token.matches("[A-Za-z0-9_-]{1,64}")) {
            throw ioFailure();
        }
        return token;
    }

    private static String fileName(String token) {
        return FILE_PREFIX + token + FILE_SUFFIX;
    }

    private static Supplier<String> randomNameSupplier() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        return () -> {
            random.nextBytes(bytes);
            return HexFormat.of().formatHex(bytes);
        };
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
            // Best effort only; the caller receives a sanitized IO failure if publication failed.
        }
    }

    private static OfficialQqShareQrException ioFailure() {
        return new OfficialQqShareQrException(
                OfficialQqShareQrException.QR_IO_FAILURE,
                "无法安全写入二维码 PNG");
    }
}
