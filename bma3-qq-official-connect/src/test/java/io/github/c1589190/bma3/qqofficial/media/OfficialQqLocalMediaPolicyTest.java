package io.github.c1589190.bma3.qqofficial.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialQqLocalMediaPolicyTest {

    @TempDir
    Path temp;

    @Test
    void resolvesOnlyRegularFilesInsideDataDirWithStandardBase64() throws Exception {
        Path data = Files.createDirectory(temp.resolve("data"));
        Path file = data.resolve("picture.png");
        byte[] bytes = "local-image-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(file, bytes);
        OfficialQqLocalMediaPolicy policy = new OfficialQqLocalMediaPolicy(data);

        OfficialQqLocalMedia media = policy.resolve(file.toString(), 1);

        assertThat(media.fileName()).isEqualTo("picture.png");
        assertThat(media.fileType()).isEqualTo(1);
        assertThat(media.bytes()).isEqualTo(bytes.length);
        assertThat(media.base64Data()).isEqualTo(Base64.getEncoder().encodeToString(bytes));
        assertThat(media.toString()).doesNotContain(file.toString());
    }

    @Test
    void rejectsPathEscapeDirectoryEmptyAndTooLargeFiles() throws Exception {
        Path data = Files.createDirectory(temp.resolve("data"));
        Path outside = temp.resolve("outside.txt");
        Files.writeString(outside, "outside");
        OfficialQqLocalMediaPolicy policy = new OfficialQqLocalMediaPolicy(data);

        assertThatThrownBy(() -> policy.resolve("../outside.txt", 4))
                .isInstanceOfSatisfying(OfficialQqMediaException.class,
                        failure -> assertThat(failure.code()).isEqualTo("PATH_OUTSIDE_DATA_DIR"));
        assertThatThrownBy(() -> policy.resolve(data.toString(), 4))
                .isInstanceOfSatisfying(OfficialQqMediaException.class,
                        failure -> assertThat(failure.code()).isEqualTo("NOT_REGULAR_FILE"));

        Files.write(data.resolve("empty.txt"), new byte[0]);
        assertThatThrownBy(() -> policy.resolve("empty.txt", 4))
                .isInstanceOfSatisfying(OfficialQqMediaException.class,
                        failure -> assertThat(failure.code()).isEqualTo("EMPTY_FILE"));

        Files.write(data.resolve("large.bin"),
                new byte[(int) OfficialQqLocalMediaPolicy.MAX_DIRECT_BYTES + 1]);
        assertThatThrownBy(() -> policy.resolve("large.bin", 4))
                .isInstanceOfSatisfying(OfficialQqMediaException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("FILE_TOO_LARGE_NEED_CHUNKED_UPLOAD"));
    }

    @Test
    void rejectsSymbolicLinkEvenWhenItPointsInsideDataDir() throws Exception {
        Path data = Files.createDirectory(temp.resolve("data"));
        Path target = data.resolve("target.txt");
        Files.writeString(target, "target");
        Path link = data.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            Assumptions.abort("当前文件系统不支持符号链接测试");
        }
        OfficialQqLocalMediaPolicy policy = new OfficialQqLocalMediaPolicy(data);

        assertThatThrownBy(() -> policy.resolve(link.toString(), 4))
                .isInstanceOfSatisfying(OfficialQqMediaException.class,
                        failure -> assertThat(failure.code()).isEqualTo("SYMLINK_REJECTED"));
    }

    @Test
    void rejectsHardLinkToAFileOutsideDataDir() throws Exception {
        Path data = Files.createDirectory(temp.resolve("data"));
        Path outside = Files.writeString(temp.resolve("outside.txt"), "outside content");
        try {
            Files.createLink(data.resolve("hardlink.txt"), outside);
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            Assumptions.abort("当前文件系统不支持硬链接测试");
        }
        OfficialQqLocalMediaPolicy policy = new OfficialQqLocalMediaPolicy(data);

        assertThatThrownBy(() -> policy.resolve("hardlink.txt", 4))
                .isInstanceOfSatisfying(OfficialQqMediaException.class,
                        failure -> assertThat(failure.code()).isEqualTo("HARD_LINK_REJECTED"));
    }

    @Test
    void rejectsSameSizedReplacementAfterValidation() throws Exception {
        Path data = Files.createDirectory(temp.resolve("data"));
        Path file = Files.writeString(data.resolve("upload.txt"), "first");
        OfficialQqLocalMediaPolicy policy = new OfficialQqLocalMediaPolicy(data, () -> {
            try {
                Files.move(file, data.resolve("old.txt"));
                Files.writeString(file, "other");
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
        });

        assertThatThrownBy(() -> policy.resolve("upload.txt", 4))
                .isInstanceOfSatisfying(OfficialQqMediaException.class,
                        failure -> assertThat(failure.code()).isEqualTo("FILE_CHANGED"));
    }

    @Test
    void rejectsParentDirectoryReplacedWithExternalSymlinkDuringOpen() throws Exception {
        Path data = Files.createDirectory(temp.resolve("data"));
        Path child = Files.createDirectory(data.resolve("child"));
        Files.writeString(child.resolve("upload.txt"), "safe");
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("upload.txt"), "evil");
        Path probe = temp.resolve("symlink-probe");
        try {
            Files.createSymbolicLink(probe, outside);
            Files.delete(probe);
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            Assumptions.abort("当前文件系统不支持符号链接测试");
        }
        OfficialQqLocalMediaPolicy policy = new OfficialQqLocalMediaPolicy(data, () -> {
            try {
                Files.move(child, data.resolve("original-child"));
                Files.createSymbolicLink(child, outside);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
        });

        assertThatThrownBy(() -> policy.resolve("child/upload.txt", 4))
                .isInstanceOf(OfficialQqMediaException.class)
                .hasMessageNotContaining("evil")
                .hasMessageNotContaining(outside.toString());
    }

    @Test
    void rejectsMediaDirectoryWritableByOtherUsers() throws Exception {
        Path data = Files.createDirectory(temp.resolve("data"));
        Files.writeString(data.resolve("upload.txt"), "data");
        try {
            Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rwxrwxr-x"));
        } catch (UnsupportedOperationException unsupported) {
            Assumptions.abort("当前文件系统不支持 POSIX 权限测试");
        }
        try {
            OfficialQqLocalMediaPolicy policy = new OfficialQqLocalMediaPolicy(data);
            assertThatThrownBy(() -> policy.resolve("upload.txt", 4))
                    .isInstanceOfSatisfying(OfficialQqMediaException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("INSECURE_MEDIA_DIRECTORY"));
        } finally {
            Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void unreadableFileIsRejectedWhenPosixPermissionsAreAvailable() throws Exception {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
        Path data = Files.createDirectory(temp.resolve("data"));
        Path file = data.resolve("secret.txt");
        Files.writeString(file, "secret");
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        } catch (UnsupportedOperationException unsupported) {
            Assumptions.abort("当前文件系统不支持 POSIX 权限测试");
        }
        try {
            OfficialQqLocalMediaPolicy policy = new OfficialQqLocalMediaPolicy(data);
            assertThatThrownBy(() -> policy.resolve("secret.txt", 4))
                    .isInstanceOfSatisfying(OfficialQqMediaException.class,
                            failure -> assertThat(failure.code()).isEqualTo("FILE_UNREADABLE"));
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
