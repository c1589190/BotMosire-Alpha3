package io.github.c1589190.bma3.qqofficial.media;

import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * One already validated local media file.
 *
 * <p>The byte array and Base64 form are intentionally package-private. Tool-facing results must
 * never expose file contents, Base64 data, or the local filesystem path.</p>
 */
public final class OfficialQqLocalMedia {

    private final String fileName;
    private final int fileType;
    private final byte[] bytes;

    OfficialQqLocalMedia(String fileName, int fileType, byte[] bytes) {
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.fileType = fileType;
        this.bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
    }

    public String fileName() {
        return fileName;
    }

    public int fileType() {
        return fileType;
    }

    public long bytes() {
        return bytes.length;
    }

    /** Full standard Base64, computed only inside the media boundary. */
    String base64Data() {
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Override
    public String toString() {
        return "OfficialQqLocalMedia[fileType=" + fileType + ", bytes=" + bytes.length + "]";
    }
}
