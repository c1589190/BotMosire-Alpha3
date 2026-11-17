package io.mosire.bma3.core.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Owner-only file permissions for sensitive runtime state. */
public final class PrivateFiles {

    private static final Set<PosixFilePermission> POSIX_OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private PrivateFiles() {
    }

    /** Restrict an existing regular file before reading or writing sensitive contents. */
    public static void restrict(Path file) throws IOException {
        try {
            apply(file);
        } catch (UnsupportedOperationException failure) {
            throw new IOException("文件系统不支持敏感文件权限保护: " + file, failure);
        }
    }

    private static void apply(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("敏感文件必须是普通文件，不能是符号链接: " + file);
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(
                file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(POSIX_OWNER_ONLY);
            if (!posix.readAttributes().permissions().equals(POSIX_OWNER_ONLY)) {
                throw new IOException("无法确认敏感文件权限: " + file);
            }
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(
                file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) {
            throw new IOException("文件系统不支持敏感文件权限保护: " + file);
        }
        UserPrincipal owner = Files.getOwner(file, LinkOption.NOFOLLOW_LINKS);
        AclEntry ownerOnly = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        acl.setAcl(List.of(ownerOnly));
        List<AclEntry> actual = acl.getAcl();
        if (actual.size() != 1 || actual.getFirst().type() != AclEntryType.ALLOW
                || !actual.getFirst().principal().equals(owner)) {
            throw new IOException("无法确认敏感文件 ACL: " + file);
        }
    }

    /** Create an empty private sibling; callers may write content only after this returns. */
    public static Path createTemp(Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("敏感文件缺少父目录: " + target);
        }
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, absolute.getFileName() + ".tmp-", ".part");
        try {
            restrict(temp);
            return temp;
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temp);
            throw failure;
        }
    }
}
