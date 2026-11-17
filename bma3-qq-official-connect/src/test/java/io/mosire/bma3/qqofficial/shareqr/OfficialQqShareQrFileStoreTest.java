package io.mosire.bma3.qqofficial.shareqr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialQqShareQrFileStoreTest {

    @Test
    void writesRandomPngsOnlyInsideTheQrDirectory(@TempDir Path dataDir) throws Exception {
        OfficialQqShareQrFileStore store = new OfficialQqShareQrFileStore(dataDir);
        byte[] bytes = "png-bytes".getBytes(StandardCharsets.UTF_8);

        Path first = store.writePng(bytes);
        Path second = store.writePng(bytes);

        assertThat(first).isNotEqualTo(second);
        assertThat(first.getParent()).isEqualTo(dataDir.resolve("qr").toAbsolutePath().normalize());
        assertThat(second.getParent()).isEqualTo(first.getParent());
        assertThat(Files.readAllBytes(first)).isEqualTo(bytes);
        assertThat(Files.readAllBytes(second)).isEqualTo(bytes);
        assertThat(first.getFileName().toString()).matches("qq-share-[0-9a-f]{32}\\.png");
        if (Files.getFileAttributeView(first, java.nio.file.attribute.PosixFileAttributeView.class)
                != null) {
            assertThat(Files.getPosixFilePermissions(first)).isEqualTo(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
            assertThat(Files.getPosixFilePermissions(first.getParent())).isEqualTo(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test
    void neverOverwritesAnExistingTargetAndLeavesNoTempFile(@TempDir Path dataDir)
            throws Exception {
        Path qrDir = Files.createDirectories(dataDir.resolve("qr"));
        Path existing = Files.writeString(
                qrDir.resolve("qq-share-fixed.png"),
                "original",
                StandardCharsets.UTF_8);
        OfficialQqShareQrFileStore store =
                new OfficialQqShareQrFileStore(dataDir, () -> "fixed");

        assertThatThrownBy(() -> store.writePng("replacement".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(
                        OfficialQqShareQrException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo(
                                    OfficialQqShareQrException.QR_IO_FAILURE);
                            assertThat(failure.getMessage()).doesNotContain(qrDir.toString());
                        });

        assertThat(Files.readString(existing, StandardCharsets.UTF_8)).isEqualTo("original");
        try (Stream<Path> files = Files.list(qrDir)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactly("qq-share-fixed.png");
        }
    }

    @Test
    void rejectsSymlinkedQrDirectoryWithoutWritingOutside(@TempDir Path dataDir, @TempDir Path outside)
            throws Exception {
        Files.createDirectories(dataDir);
        Path link = dataDir.resolve("qr");
        Files.createSymbolicLink(link, outside);

        OfficialQqShareQrFileStore store = new OfficialQqShareQrFileStore(dataDir);
        assertThatThrownBy(() -> store.writePng("png".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(
                        OfficialQqShareQrException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(OfficialQqShareQrException.QR_IO_FAILURE));
        try (Stream<Path> files = Files.list(outside)) {
            assertThat(files).isEmpty();
        }
    }
}
