package io.mosire.bma3.watchdog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.bma3.watchdog.spi.ProbeResult;
import io.mosire.bma3.watchdog.spi.WatchdogEvent;
import io.mosire.bma3.watchdog.spi.WatchdogStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * File-backed watchdog state: one JSON snapshot plus an append-only, size-capped event log.
 *
 * <p>Only ids/codes/counts are written; never message bodies, tokens, URLs or file paths.</p>
 */
public final class JsonWatchdogStore implements WatchdogStore {

    private static final long MAX_EVENT_BYTES = 5L * 1024 * 1024;

    private final Path directory;
    private final Path resultsFile;
    private final Path eventsFile;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public JsonWatchdogStore(Path directory) {
        this.directory = directory == null ? Path.of("data", "watchdog") : directory;
        this.resultsFile = this.directory.resolve("state.json");
        this.eventsFile = this.directory.resolve("events.jsonl");
    }

    @Override
    public synchronized Map<String, ProbeResult> loadResults() {
        if (!Files.isRegularFile(resultsFile)) {
            return Map.of();
        }
        try {
            var node = mapper.readTree(Files.readString(resultsFile, StandardCharsets.UTF_8));
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            Map<String, ProbeResult> loaded = new LinkedHashMap<>();
            var components = node.path("components");
            if (components.isObject()) {
                components.properties().forEach(entry -> {
                    try {
                        loaded.put(entry.getKey(),
                                mapper.treeToValue(entry.getValue(), ProbeResult.class));
                    } catch (Exception ignored) {
                        // A corrupt entry must not take the whole watchdog down.
                    }
                });
            }
            return Map.copyOf(loaded);
        } catch (Exception failure) {
            return Map.of();
        }
    }

    @Override
    public synchronized void saveResults(Map<String, ProbeResult> results) {
        try {
            Files.createDirectories(directory);
            var root = mapper.createObjectNode();
            root.put("version", 1);
            root.put("updatedAt", java.time.Instant.now().toString());
            var components = root.putObject("components");
            if (results != null) {
                results.forEach((key, value) -> {
                    if (key != null && value != null) {
                        components.set(key, mapper.valueToTree(value));
                    }
                });
            }
            Path temporary = resultsFile.resolveSibling("state.json.tmp");
            Files.writeString(temporary, mapper.writeValueAsString(root), StandardCharsets.UTF_8);
            Files.move(temporary, resultsFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException ignored) {
            // State persistence is best-effort; a read-only data dir must not kill the watchdog.
        }
    }

    @Override
    public synchronized void appendEvent(WatchdogEvent event) {
        if (event == null) {
            return;
        }
        try {
            Files.createDirectories(directory);
            rotateIfNeeded();
            Files.writeString(eventsFile, mapper.writeValueAsString(event) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
    }

    @Override
    public synchronized List<WatchdogEvent> recentEvents(int limit) {
        int max = Math.max(1, limit);
        if (!Files.isRegularFile(eventsFile)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(eventsFile, StandardCharsets.UTF_8);
            Deque<WatchdogEvent> tail = new ArrayDeque<>();
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    tail.addLast(mapper.readValue(line, WatchdogEvent.class));
                } catch (Exception ignored) {
                    // skip a corrupt line
                }
                while (tail.size() > max) {
                    tail.removeFirst();
                }
            }
            return List.copyOf(new ArrayList<>(tail));
        } catch (IOException failure) {
            return List.of();
        }
    }

    private void rotateIfNeeded() throws IOException {
        if (Files.isRegularFile(eventsFile) && Files.size(eventsFile) > MAX_EVENT_BYTES) {
            Path previous = eventsFile.resolveSibling("events.jsonl.1");
            Files.move(eventsFile, previous, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
