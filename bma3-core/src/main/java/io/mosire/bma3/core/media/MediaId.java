package io.mosire.bma3.core.media;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Stable, self-describing handle for one segment's media payload.
 *
 * <p>A file id is just an encoding of {@code source + messageId + segmentIndex} — the three
 * things already stored on the message board. That means:</p>
 *
 * <ul>
 *   <li>it survives restarts without another table,</li>
 *   <li>it cannot be forged into an arbitrary URL: a viewer only ever resolves media that came
 *       through the board, so there is no user-supplied-URL fetch surface,</li>
 *   <li>the bytes behind it can be re-resolved from the platform when the CDN link expires.</li>
 * </ul>
 */
public record MediaId(String source, String messageId, int index) {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String PREFIX = "file-";

    public MediaId {
        source = source == null ? "" : source;
        messageId = messageId == null ? "" : messageId;
        if (index < 0) {
            throw new IllegalArgumentException("index 不能为负");
        }
    }

    public static MediaId of(String source, String messageId, int index) {
        return new MediaId(source, messageId, index);
    }

    /** Encodes as {@code file-<base64url(source\nmessageId\nindex)>}. */
    public String encode() {
        String raw = source + "\n" + messageId + "\n" + index;
        return PREFIX + ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Optional<MediaId> decode(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String value = id.startsWith(PREFIX) ? id.substring(PREFIX.length()) : id;
        try {
            String raw = new String(DECODER.decode(value), StandardCharsets.UTF_8);
            String[] parts = raw.split("\n", -1);
            if (parts.length != 3) {
                return Optional.empty();
            }
            return Optional.of(new MediaId(parts[0], parts[1], Integer.parseInt(parts[2])));
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    /** Deterministic, path-safe file name: never derived from platform-supplied strings. */
    public String fileName(String extension) {
        String suffix = extension == null || extension.isBlank() ? "" : "." + extension;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    (source + "|" + messageId + "|" + index).getBytes(StandardCharsets.UTF_8));
            return "media-" + HexFormat.of().formatHex(hash, 0, 8) + suffix;
        } catch (Exception failure) {
            return "media-" + Math.abs((source + messageId + index).hashCode()) + suffix;
        }
    }
}
