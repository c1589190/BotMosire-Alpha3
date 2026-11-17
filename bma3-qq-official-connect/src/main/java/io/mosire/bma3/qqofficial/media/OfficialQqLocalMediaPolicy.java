package io.mosire.bma3.qqofficial.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves only regular, non-symlink files under one configured data directory.
 *
 * <p>This phase deliberately supports only the official direct-upload path. Files above the
 * conservative 5 MiB cap are rejected with {@code FILE_TOO_LARGE_NEED_CHUNKED_UPLOAD}; chunked
 * upload is not implemented by this class.</p>
 */
public final class OfficialQqLocalMediaPolicy {

    public static final long MAX_DIRECT_BYTES = 5L * 1024 * 1024;
    private static final int MAX_PATH_CHARS = 4096;
    private static final int MAX_FILE_NAME_CHARS = 255;

    private final Path dataDir;
    private final Object rootFileKey;
    private final Runnable beforeFileOpen;

    public OfficialQqLocalMediaPolicy(Path dataDir) {
        this(dataDir, () -> { });
    }

    /** Deterministic race-injection seam; production never runs a callback during resolution. */
    OfficialQqLocalMediaPolicy(Path dataDir, Runnable beforeFileOpen) {
        Objects.requireNonNull(dataDir, "dataDir");
        this.beforeFileOpen = Objects.requireNonNull(beforeFileOpen, "beforeFileOpen");
        try {
            this.dataDir = dataDir.toRealPath();
            BasicFileAttributes root = Files.readAttributes(this.dataDir,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!root.isDirectory() || root.fileKey() == null) {
                throw new IllegalArgumentException("dataDir 不能安全识别");
            }
            this.rootFileKey = root.fileKey();
        } catch (IOException failure) {
            throw new IllegalArgumentException("dataDir 不可用");
        }
    }

    public OfficialQqLocalMedia resolve(String rawPath, int fileType)
            throws OfficialQqMediaException {
        if (fileType < 1 || fileType > 4) {
            throw failure("INVALID_FILE_TYPE", "fileType 必须是 1..4");
        }
        if (rawPath == null || rawPath.isBlank() || rawPath.length() > MAX_PATH_CHARS
                || !safePathText(rawPath)) {
            throw failure("INVALID_PATH", "媒体路径无效");
        }

        Path requested;
        try {
            requested = Path.of(rawPath);
        } catch (RuntimeException invalid) {
            throw failure("INVALID_PATH", "媒体路径无效");
        }
        Path candidate = (requested.isAbsolute() ? requested : dataDir.resolve(requested))
                .toAbsolutePath()
                .normalize();
        if (!candidate.startsWith(dataDir)) {
            throw failure("PATH_OUTSIDE_DATA_DIR", "媒体文件必须位于 dataDir 内");
        }

        Path relative = dataDir.relativize(candidate);
        if (relative.toString().isEmpty() || relative.getFileName() == null) {
            throw failure("NOT_REGULAR_FILE", "媒体必须是常规文件");
        }
        String fileName = safeFileName(relative.getFileName());
        byte[] bytes = readFromPinnedDirectories(relative, candidate);
        return new OfficialQqLocalMedia(fileName, fileType, bytes);
    }

    /**
     * Resolve every directory relative to an already-open directory handle. A checked path alone
     * is insufficient: a concurrent rename can otherwise replace an ancestor by a symlink before
     * the final open. If the platform cannot provide anchored opens, fail closed instead of falling
     * back to a pathname-based read.
     */
    private byte[] readFromPinnedDirectories(Path relative, Path absolute)
            throws OfficialQqMediaException {
        List<SecureDirectoryStream<Path>> opened = new ArrayList<>();
        try (DirectoryStream<Path> root = Files.newDirectoryStream(dataDir)) {
            if (!(root instanceof SecureDirectoryStream<Path> secureRoot)) {
                throw failure("SECURE_FILE_ACCESS_UNAVAILABLE", "平台不支持安全的目录句柄访问");
            }
            BasicFileAttributes rootAttributes = directoryAttributes(secureRoot);
            if (!rootFileKey.equals(rootAttributes.fileKey())) {
                throw failure("FILE_CHANGED", "媒体目录在校验期间发生变化");
            }
            requirePrivateDirectory(secureRoot);
            SecureDirectoryStream<Path> parent = secureRoot;
            for (int index = 0; index < relative.getNameCount() - 1; index++) {
                Path component = relative.getName(index);
                BasicFileAttributes attributes = childAttributes(parent, component);
                if (attributes.isSymbolicLink()) {
                    throw failure("SYMLINK_REJECTED", "不接受符号链接媒体文件");
                }
                if (!attributes.isDirectory()) {
                    throw failure("NOT_REGULAR_FILE", "媒体路径中的父项必须是目录");
                }
                SecureDirectoryStream<Path> next = parent.newDirectoryStream(
                        component, LinkOption.NOFOLLOW_LINKS);
                opened.add(next);
                BasicFileAttributes actual = directoryAttributes(next);
                if (attributes.fileKey() == null || !attributes.fileKey().equals(actual.fileKey())) {
                    throw failure("FILE_CHANGED", "媒体目录在校验期间发生变化");
                }
                requirePrivateDirectory(next);
                parent = next;
            }

            Path leaf = relative.getFileName();
            BasicFileAttributes before = childAttributes(parent, leaf);
            if (before.isSymbolicLink()) {
                throw failure("SYMLINK_REJECTED", "不接受符号链接媒体文件");
            }
            validateRegularFile(before);
            requireSingleLink(absolute, before);
            beforeFileOpen.run();
            // The final open is relative to the pinned parent and refuses a symlink leaf. Compare
            // inode and metadata again before and after reading to reject replacement/mutation.
            try (SeekableByteChannel channel = parent.newByteChannel(leaf,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                 InputStream input = Channels.newInputStream(channel)) {
                BasicFileAttributes openedAttributes = childAttributes(parent, leaf);
                ensureSameFile(before, openedAttributes);
                requireSingleLink(absolute, openedAttributes);
                byte[] bytes = readBounded(input);
                BasicFileAttributes after = childAttributes(parent, leaf);
                ensureSameFile(before, after);
                requireSingleLink(absolute, after);
                if (bytes.length != before.size()) {
                    throw failure("FILE_CHANGED", "媒体文件在读取期间发生变化");
                }
                return bytes;
            }
        } catch (OfficialQqMediaException failure) {
            throw failure;
        } catch (IOException | UnsupportedOperationException | SecurityException failure) {
            throw failure("FILE_UNREADABLE", "媒体文件不可读或无法安全校验");
        } finally {
            for (int index = opened.size() - 1; index >= 0; index--) {
                try {
                    opened.get(index).close();
                } catch (IOException ignored) {
                    // The primary operation already owns the outcome; no paths enter diagnostics.
                }
            }
        }
    }

    private static BasicFileAttributes directoryAttributes(SecureDirectoryStream<Path> directory)
            throws IOException, OfficialQqMediaException {
        BasicFileAttributeView view = directory.getFileAttributeView(BasicFileAttributeView.class);
        if (view == null) {
            throw failure("SECURE_FILE_ACCESS_UNAVAILABLE", "无法验证目录身份");
        }
        return view.readAttributes();
    }

    private static BasicFileAttributes childAttributes(SecureDirectoryStream<Path> directory,
                                                       Path child)
            throws IOException, OfficialQqMediaException {
        BasicFileAttributeView view = directory.getFileAttributeView(child,
                BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw failure("SECURE_FILE_ACCESS_UNAVAILABLE", "无法验证媒体文件身份");
        }
        return view.readAttributes();
    }

    private static void requirePrivateDirectory(SecureDirectoryStream<Path> directory)
            throws IOException, OfficialQqMediaException {
        PosixFileAttributeView view = directory.getFileAttributeView(PosixFileAttributeView.class);
        if (view == null) {
            throw failure("SECURE_FILE_ACCESS_UNAVAILABLE", "无法验证媒体目录权限");
        }
        PosixFileAttributes attributes = view.readAttributes();
        Set<PosixFilePermission> permissions = attributes.permissions();
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw failure("INSECURE_MEDIA_DIRECTORY", "媒体目录不得对其他用户可写");
        }
    }

    private static void validateRegularFile(BasicFileAttributes attributes)
            throws OfficialQqMediaException {
        if (!attributes.isRegularFile()) {
            throw failure("NOT_REGULAR_FILE", "媒体必须是常规文件");
        }
        if (attributes.fileKey() == null) {
            throw failure("SECURE_FILE_ACCESS_UNAVAILABLE", "无法验证媒体文件身份");
        }
        if (attributes.size() <= 0) {
            throw failure("EMPTY_FILE", "媒体文件不能为空");
        }
        if (attributes.size() > MAX_DIRECT_BYTES) {
            throw failure("FILE_TOO_LARGE_NEED_CHUNKED_UPLOAD",
                    "文件超过 5MiB，需要显式分片上传；本阶段尚未实现");
        }
    }

    private static void requireSingleLink(Path path, BasicFileAttributes expected)
            throws IOException, OfficialQqMediaException {
        // Java's secure-directory API does not expose unix:nlink through its relative view.
        // Compare the pathname identity with the anchored view before trusting its link count;
        // platforms without this attribute are deliberately unsupported rather than guessed safe.
        BasicFileAttributes observed = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (expected.fileKey() == null || !expected.fileKey().equals(observed.fileKey())) {
            throw failure("FILE_CHANGED", "媒体文件在校验期间发生变化");
        }
        Object count;
        try {
            count = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IllegalArgumentException failure) {
            throw failure("SECURE_FILE_ACCESS_UNAVAILABLE", "无法验证媒体文件硬链接");
        }
        if (!(count instanceof Number number) || number.longValue() != 1L) {
            throw failure("HARD_LINK_REJECTED", "不接受硬链接媒体文件");
        }
    }

    private static void ensureSameFile(BasicFileAttributes expected, BasicFileAttributes actual)
            throws OfficialQqMediaException {
        if (expected.fileKey() == null || !expected.fileKey().equals(actual.fileKey())
                || !actual.isRegularFile() || expected.size() != actual.size()
                || !expected.lastModifiedTime().equals(actual.lastModifiedTime())) {
            throw failure("FILE_CHANGED", "媒体文件在校验期间发生变化");
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException,
            OfficialQqMediaException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            total += read;
            if (total > MAX_DIRECT_BYTES) {
                throw failure("FILE_TOO_LARGE_NEED_CHUNKED_UPLOAD",
                        "文件超过 5MiB，需要显式分片上传；本阶段尚未实现");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean safePathText(String value) {
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint)
                    || Character.getType(codePoint) == Character.FORMAT) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private static String safeFileName(Path name) throws OfficialQqMediaException {
        if (name == null) {
            throw failure("INVALID_FILE_NAME", "媒体文件名无效");
        }
        String value = name.toString().strip();
        if (value.isBlank() || value.equals(".") || value.equals("..")
                || value.codePointCount(0, value.length()) > MAX_FILE_NAME_CHARS
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
            throw failure("INVALID_FILE_NAME", "媒体文件名无效");
        }
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                throw failure("INVALID_FILE_NAME", "媒体文件名无效");
            }
            offset += Character.charCount(codePoint);
        }
        return value;
    }

    private static OfficialQqMediaException failure(String code, String message) {
        return new OfficialQqMediaException(code, message);
    }
}
