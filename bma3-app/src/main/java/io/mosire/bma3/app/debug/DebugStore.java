package io.mosire.bma3.app.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
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
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

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
            Path temp = file.resolveSibling(file.getFileName() + ".tmp-"
                    + ProcessHandle.current().pid());
            try {
                Files.write(temp, body);
                try {
                    Files.setPosixFilePermissions(temp, OWNER_ONLY);
                } catch (UnsupportedOperationException ignored) {
                    // Non-POSIX filesystem.
                }
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException unsupportedAtomic) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            try {
                Files.setPosixFilePermissions(file, OWNER_ONLY);
            } catch (IOException | UnsupportedOperationException ignored) {
                // Best effort.
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("写入 debug 请求失败: " + file, failure);
        }
    }

    /** Append one audit line. Bodies are never passed here by construction. */
    synchronized void audit(String json) {
        try {
            Files.writeString(auditFile,
                    json.replace('\n', ' ') + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            try {
                Files.setPosixFilePermissions(auditFile, OWNER_ONLY);
            } catch (IOException | UnsupportedOperationException ignored) {
                // Best effort.
            }
        } catch (IOException failure) {
            LOG.warn("event=debug_audit_write_failed file={} error={}", auditFile,
                    failure.getClass().getSimpleName());
        }
    }
}
