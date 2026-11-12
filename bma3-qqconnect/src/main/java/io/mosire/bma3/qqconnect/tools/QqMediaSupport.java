package io.mosire.bma3.qqconnect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.bma3.core.media.MediaId;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Resolves one media segment to bytes.
 *
 * <p>Order of business, cheapest and most reliable first:</p>
 * <ol>
 *   <li>find the message on the board by its platform id (the file id encodes which one),</li>
 *   <li>try to refresh the CDN link — stored {@code rkey} URLs expire, so ask the platform via
 *       {@code get_msg}, then {@code get_image}, and only then fall back to the stored URL,</li>
 *   <li>fetch with a hard byte cap and decide inline-text vs. on-disk file.</li>
 * </ol>
 *
 * <p>The tool never accepts a URL from the caller — only a file id that maps back to a row we
 * received. That is what keeps "view media" from becoming a generic fetch proxy.</p>
 */
final class QqMediaSupport {

    private static final List<String> MEDIA_TYPES = List.of("image", "file", "record", "video");

    private QqMediaSupport() {
    }

    record Resolved(Segment segment, String fileId) {
    }

    record Fetched(byte[] bytes, String contentType) {
    }

    static boolean isMedia(String type) {
        return MEDIA_TYPES.contains(type);
    }

    static Optional<Resolved> resolve(QqToolContext context, String fileId) {
        Resolved resolved = resolveAny(context, fileId).orElseThrow();
        if (!isMedia(resolved.segment().type())) {
            throw new IllegalArgumentException(
                    "UNSUPPORTED_MEDIA: 该段不是媒体: " + resolved.segment().type());
        }
        return Optional.of(resolved);
    }

    /** Resolves any segment by fileId (media, card or forward); callers decide what they accept. */
    static Optional<Resolved> resolveAny(QqToolContext context, String fileId) {
        Optional<MediaId> decoded = MediaId.decode(fileId);
        if (decoded.isEmpty()) {
            throw new IllegalArgumentException("INVALID_FILE_ID: " + fileId);
        }
        MediaId media = decoded.get();
        SourceAddress source = SourceAddress.parse(media.source());
        Message message = context.board()
                .findByExternalMessageId(source, media.messageId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "MEDIA_NOT_FOUND: 消息板里没有 " + media.messageId()));
        List<Segment> segments = message.inbound().segments();
        if (media.index() >= segments.size()) {
            throw new IllegalArgumentException("MEDIA_NOT_FOUND: 段落序号越界 " + media.index());
        }
        return Optional.of(new Resolved(segments.get(media.index()), fileId));
    }

    /** Fresh URL if the platform can still resolve it, otherwise the stored one. */
    static String freshUrl(QqToolContext context, MediaId media, Segment segment) {
        String stored = segment.data("url");
        OneBot11Client client = context.accounts()
                .client(new io.mosire.bma3.core.account.AccountId(
                        SourceAddress.parse(media.source()).accountId()))
                .orElse(null);
        if (client == null) {
            return stored;
        }
        String fromMessage = urlFromMessage(client, media, segment);
        if (!fromMessage.isBlank()) {
            return fromMessage;
        }
        String file = segment.data("file");
        if (!file.isBlank()) {
            String fromImage = urlFromImage(client, file);
            if (!fromImage.isBlank()) {
                return fromImage;
            }
        }
        return stored;
    }

    private static String urlFromMessage(OneBot11Client client, MediaId media, Segment segment) {
        if (!media.messageId().matches("-?\\d+")) {
            return "";
        }
        try {
            JsonNode response = client.sendAction("get_msg",
                            java.util.Map.of("message_id", Long.parseLong(media.messageId())))
                    .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .join();
            JsonNode message = response.path("data").path("message");
            if (!message.isArray()) {
                return "";
            }
            List<JsonNode> nodes = new java.util.ArrayList<>();
            message.forEach(nodes::add);
            if (media.index() >= nodes.size()) {
                return "";
            }
            JsonNode data = nodes.get(media.index()).path("data");
            return data.path("url").asText("");
        } catch (RuntimeException failure) {
            return "";
        }
    }

    private static String urlFromImage(OneBot11Client client, String file) {
        try {
            JsonNode response = client.sendAction("get_image", java.util.Map.of("file", file))
                    .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .join();
            return response.path("data").path("url").asText("");
        } catch (RuntimeException failure) {
            return "";
        }
    }

    static Fetched fetch(String url, QqMediaPolicy policy) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(
                    "MEDIA_UNAVAILABLE: 平台没有给出可下载地址（rkey 可能已过期）");
        }
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(policy.timeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(policy.timeout())
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalArgumentException(
                        "MEDIA_FETCH_FAILED: HTTP " + response.statusCode());
            }
            byte[] bytes = readBounded(response.body(), policy.maxDownloadBytes());
            String contentType = response.headers().firstValue("content-type").orElse("");
            return new Fetched(bytes, contentType);
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "MEDIA_FETCH_FAILED: " + failure.getClass().getSimpleName());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("MEDIA_FETCH_FAILED: interrupted");
        }
    }

    private static byte[] readBounded(InputStream input, long maxBytes) throws IOException {
        try (input; ByteArrayOutputStream sink = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalArgumentException(
                            "MEDIA_TOO_LARGE: 超过上限 " + maxBytes + " bytes");
                }
                sink.write(buffer, 0, read);
            }
            return sink.toByteArray();
        }
    }

    static boolean isImage(String contentType) {
        return contentType != null && contentType.toLowerCase(java.util.Locale.ROOT)
                .startsWith("image/");
    }

    static boolean isText(String contentType, byte[] bytes) {
        String type = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        if (type.startsWith("text/") || type.contains("json") || type.contains("xml")) {
            return true;
        }
        if (isImage(type) || type.startsWith("video/") || type.startsWith("audio/")) {
            return false;
        }
        return looksLikeUtf8(bytes);
    }

    private static boolean looksLikeUtf8(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException failure) {
            return false;
        }
    }

    static String extension(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        int semicolon = type.indexOf(';');
        if (semicolon >= 0) {
            type = type.substring(0, semicolon).trim();
        }
        return switch (type) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "text/plain" -> "txt";
            case "application/json" -> "json";
            case "application/pdf" -> "pdf";
            default -> "bin";
        };
    }

    static Path write(QqMediaPolicy policy, String fileName, byte[] bytes) {
        try {
            Files.createDirectories(policy.dir());
            Path target = policy.dir().resolve(fileName).normalize();
            if (!target.startsWith(policy.dir().toAbsolutePath().normalize())
                    && !target.startsWith(policy.dir().normalize())) {
                throw new IllegalArgumentException("INVALID_FILE_ID: 非法落盘路径");
            }
            Files.write(target, bytes);
            return target;
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "MEDIA_WRITE_FAILED: " + failure.getClass().getSimpleName());
        }
    }

    static Duration timeout(QqMediaPolicy policy) {
        return policy.timeout();
    }
}
