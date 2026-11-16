package io.mosire.bma3.qqconnect.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Content-free media pipeline counters. Counts, bytes, types and error codes only; never file
 * paths, bodies, URLs or credentials.
 */
public final class QqMediaDiagnostics {

    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong downloadedBytes = new AtomicLong();
    private final AtomicLong splitImages = new AtomicLong();
    private final Map<String, AtomicLong> byKind = new ConcurrentHashMap<>();

    public QqMediaDiagnostics() {
        for (String kind : new String[]{"image", "file", "record", "video"}) {
            byKind.put(kind, new AtomicLong());
        }
    }
    private final Map<String, AtomicLong> errors = new ConcurrentHashMap<>();

    public void attempt(String kind) {
        attempts.incrementAndGet();
        byKind.computeIfAbsent(safe(kind), ignored -> new AtomicLong()).incrementAndGet();
    }

    public void success(long bytes, boolean split) {
        successes.incrementAndGet();
        if (bytes > 0) {
            downloadedBytes.addAndGet(bytes);
        }
        if (split) {
            splitImages.incrementAndGet();
        }
    }

    public void failure(String code) {
        failures.incrementAndGet();
        errors.computeIfAbsent(safe(code), ignored -> new AtomicLong()).incrementAndGet();
    }

    /** Snapshot with in-process counters plus a bounded download-directory inventory. */
    public Map<String, Object> snapshot(Path dir) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attempts", attempts.get());
        body.put("successes", successes.get());
        body.put("failures", failures.get());
        body.put("downloadedBytes", downloadedBytes.get());
        body.put("splitImages", splitImages.get());
        Map<String, Long> kinds = new LinkedHashMap<>();
        byKind.forEach((kind, count) -> kinds.put(kind, count.get()));
        body.put("byKind", kinds);
        Map<String, Long> errorCounts = new LinkedHashMap<>();
        errors.forEach((code, count) -> errorCounts.put(code, count.get()));
        body.put("errors", errorCounts);
        body.put("downloads", directoryStats(dir));
        return body;
    }

    /** Flat facts for a watchdog ProbeResult. */
    public Map<String, String> facts(Path dir) {
        Map<String, Object> snapshot = snapshot(dir);
        Map<String, String> facts = new LinkedHashMap<>();
        snapshot.forEach((key, value) -> {
            if (value instanceof Number number) {
                facts.put(key, Long.toString(number.longValue()));
            }
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> downloads = (Map<String, Object>) snapshot.get("downloads");
        if (downloads != null) {
            downloads.forEach((key, value) -> {
                if (value instanceof Number number) {
                    facts.put("downloads." + key, Long.toString(number.longValue()));
                }
            });
        }
        return facts;
    }

    private static Map<String, Object> directoryStats(Path dir) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("files", 0L);
        stats.put("bytes", 0L);
        if (dir == null) {
            return stats;
        }
        long files = 0;
        long bytes = 0;
        try (Stream<Path> paths = Files.walk(dir, 2)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (Files.isRegularFile(path)) {
                    files++;
                    try {
                        bytes += Files.size(path);
                    } catch (IOException ignored) {
                        // best effort only
                    }
                }
            }
        } catch (IOException ignored) {
            // absent/unreadable directory is represented by zero counts
        }
        stats.put("files", files);
        stats.put("bytes", bytes);
        return stats;
    }

    private static String safe(String value) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? "unknown" : (text.length() <= 64 ? text : text.substring(0, 64));
    }
}
