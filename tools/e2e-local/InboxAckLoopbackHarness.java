import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.bma3.core.source.SourceView;
import io.github.c1589190.bma3.inbox.core.AppendItem;
import io.github.c1589190.bma3.inbox.core.DeliveryPolicy;
import io.github.c1589190.bma3.inbox.core.InboxEntry;
import io.github.c1589190.bma3.inbox.core.InboxItem;
import io.github.c1589190.bma3.inbox.core.InboxService;
import io.github.c1589190.bma3.inbox.core.SqliteInboxStore;
import io.github.c1589190.bma3.inbox.http.InboxHttpServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loopback fixture for the Inbox ACK contract test. It starts the real
 * {@link SqliteInboxStore} + {@link InboxService} + {@link InboxHttpServer} on 127.0.0.1:0.
 *
 * <p>This is deliberately NOT a fake server: the Java side is the production code path. The
 * fixture only controls when one message is appended, so the Node side can observe real SSE,
 * real panel JSON and real ACK commits. stdin commands (one per line):</p>
 * <ul>
 *   <li>{@code seed-a} — append the first source item; emits an {@code item} event with its id.</li>
 *   <li>{@code flush} — run the real push loop once for the pending item (freezes it for the next
 *       push revision); used before the first SSE client connects.</li>
 *   <li>{@code append-b} — append a second source item; emits an {@code item} event. A connected
 *       SSE client's own awaitPush loop performs this push.</li>
 *   <li>{@code shutdown} — close HTTP/service/store and exit. EOF is equivalent.</li>
 * </ul>
 *
 * <p>The bearer token is taken only from {@code BMA3_INBOX_TOKEN}; it is never accepted as an
 * argument and never printed. The output is line-oriented JSON for a parent process to parse.</p>
 */
public final class InboxAckLoopbackHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INBOX_ID = "bma3";
    private static final String TOKEN_FILE = "token.env";

    private InboxAckLoopbackHarness() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: InboxAckLoopbackHarness <isolated-data-directory>");
            System.exit(2);
        }
        Path dataDir = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(dataDir) || !Files.isRegularFile(dataDir.resolve(TOKEN_FILE))) {
            System.err.println("refusing to run: isolated data directory/TOKEN_FILE missing");
            System.exit(2);
        }
        String token = System.getenv("BMA3_INBOX_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("refusing to run: BMA3_INBOX_TOKEN missing");
            System.exit(2);
        }

        Path databaseFile = dataDir.resolve("data").resolve("inbox.db");
        Files.createDirectories(databaseFile.getParent());
        SqliteInboxStore store = new SqliteInboxStore(databaseFile);
        // The delays only shorten the production push/batch timer for this fixture. They do not
        // bypass freeze()/pushRevision; the plugin still sees ordinary frozen panel items.
        InboxService service = new InboxService(
                store, new DeliveryPolicy(300, Duration.ofMillis(200), Duration.ofMillis(200)),
                INBOX_ID);
        InboxHttpServer server = new InboxHttpServer("127.0.0.1", 0, INBOX_ID, token, service);

        boolean closed = false;
        try {
            server.start();
            emit(Map.of(
                    "event", "ready",
                    "port", server.boundPort(),
                    "inboxId", INBOX_ID,
                    "database", databaseFile.toString()));

            try (BufferedReader input = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    String command = line.trim();
                    if (command.isEmpty()) {
                        continue;
                    }
                    switch (command) {
                        case "seed-a" -> emitItem("seed-a",
                                append(service, "qq_group/bot-e2e/900001", "E2E-ACK-OLD",
                                        "E2E-ACK-OLD-MARKER", 400));
                        case "flush" -> {
                            var panel = service.awaitPush(service.pushRevision(),
                                    Duration.ofSeconds(2));
                            emit(Map.of(
                                    "event", "flushed",
                                    "revision", panel.revision(),
                                    "pushRevision", panel.pushRevision(),
                                    "items", panel.items().size()));
                        }
                        case "append-b" -> emitItem("append-b",
                                append(service, "qq_group/bot-e2e/900002", "E2E-ACK-NEW",
                                        "E2E-ACK-NEW-MARKER", 400));
                        case "shutdown" -> {
                            emit(Map.of("event", "shutdown"));
                            return;
                        }
                        default -> emit(Map.of("event", "error", "error", "unknown_command"));
                    }
                }
            }
            emit(Map.of("event", "shutdown", "reason", "eof"));
        } catch (RuntimeException failure) {
            emit(Map.of("event", "error", "error", failure.getClass().getSimpleName()));
            throw failure;
        } finally {
            if (!closed) {
                server.close();
                service.close();
                store.close();
                closed = true;
                emit(Map.of("event", "closed"));
            }
        }
    }

    private static InboxItem append(InboxService service, String source, String label,
                                    String text, int priority) {
        InboxEntry entry = new InboxEntry(1, Instant.now(), "fixture", text, List.of());
        return service.appendOrMerge(new AppendItem(
                SourceView.of(source, label), entry, entry.render(), priority, Instant.now()));
    }

    private static void emitItem(String command, InboxItem item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "item");
        payload.put("command", command);
        payload.put("id", item.id());
        payload.put("source", item.source());
        emit(payload);
    }

    private static void emit(Map<String, Object> payload) {
        try {
            System.out.println(MAPPER.writeValueAsString(payload));
            System.out.flush();
        } catch (Exception ignored) {
            // A broken stdout means the parent is gone; the caller will hit EOF and shut down.
        }
    }
}
