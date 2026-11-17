package io.mosire.bma3.app.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.bma3.core.security.PrivateFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tiny durable store for the debug surface: one JSON file for requests (0600) and one append-only
 * audit log that never contains a body.
 *
 * <p>The volume is deliberately small (bounded by {@code debug.retention}); SQLite would be more
 * machinery than this feature needs, and the same pattern is already used by the watchdog store.</p>
 */
public final class DebugStore {

    private static final Logger LOG = LoggerFactory.getLogger(DebugStore.class);
    private final Path file;
    private final Path auditFile;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public DebugStore(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new UncheckedIOException("创建 debug 目录失败: " + directory, failure);
        }
        this.file = directory.resolve("requests.json");
        this.auditFile = directory.resolve("audit.jsonl");
    }

    Path file() {
        return file;
    }

    Path auditFile() {
        return auditFile;
    }

    synchronized List<DebugRequest> load() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            PrivateFiles.restrict(file);
            byte[] raw = Files.readAllBytes(file);
            if (raw.length == 0) {
                return List.of();
            }
            return List.of(mapper.readValue(raw, DebugRequest[].class));
        } catch (IOException failure) {
            // A corrupt debug cache must not stop the service: the ask surface is diagnostic only.
            LOG.warn("event=debug_store_load_failed file={} error={}", file,
                    failure.getClass().getSimpleName());
            return List.of();
        }
    }

    synchronized void save(List<DebugRequest> requests) {
        try {
            byte[] body = mapper.writeValueAsBytes(requests);
            Path temp = PrivateFiles.createTemp(file);
            try {
                Files.write(temp, body, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException unsupportedAtomic) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            PrivateFiles.restrict(file);
        } catch (IOException failure) {
            throw new UncheckedIOException("写入 debug 请求失败: " + file, failure);
        }
    }

    /** Append one audit line. Bodies are never passed here by construction. */
    synchronized void audit(String json) {
        try {
            if (!Files.exists(auditFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createFile(auditFile);
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // Another writer created it after the existence check.
                }
            }
            PrivateFiles.restrict(auditFile);
            Files.writeString(auditFile,
                    json.replace('\n', ' ') + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            LOG.warn("event=debug_audit_write_failed file={} error={}", auditFile,
                    failure.getClass().getSimpleName());
        }
    }
}
