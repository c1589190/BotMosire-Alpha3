package io.github.c1589190.bma3.app;

import io.github.c1589190.bma3.app.config.AppConfig;
import io.github.c1589190.bma3.core.security.PrivateFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The jar-owned token bootstrap file ({@code .bma3env}) described by D-063.
 *
 * <p>BMA3 is the single owner of its own tokens: on startup it ensures the file exists, generates
 * any missing value, and from then on only reads it. Existing values are never rewritten unless a
 * rotation is explicitly requested, because silently rotating on every restart would break the
 * attached DSH plugin (SSE reconnect storms, MCP 401s).</p>
 *
 * <p>Format is a deliberately boring {@code KEY=VALUE} list so an operator — or the Agent — can read
 * it with {@code cat} without a parser. The file is written {@code 0600} through a temporary file
 * plus an atomic move, so a reader never observes a half-written token.</p>
 */
public final class Bma3Env {

    /** Bearer token guarding the MCP endpoint. */
    public static final String MCP_TOKEN = "MCP_TOKEN";

    /** Bearer token guarding the Inbox HTTP/SSE endpoint (independent of {@link #MCP_TOKEN}). */
    public static final String INBOX_TOKEN = "INBOX_TOKEN";

    /** Bearer token guarding the {@code /debug/*} ask surface (independent of the other two). */
    public static final String DEBUG_TOKEN = "DEBUG_TOKEN";

    /** Tokens are 24 random bytes rendered as 48 lowercase hex characters. */
    private static final int TOKEN_BYTES = 24;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;
    private final Map<String, String> values;
    private final boolean degraded;

    private Bma3Env(Path file, Map<String, String> values, boolean degraded) {
        this.file = file;
        this.values = Map.copyOf(values);
        this.degraded = degraded;
    }

    /**
     * Where this process keeps its token file: an explicit {@code security.envFile} wins, otherwise
     * {@code <configRoot>/.bma3env}. The current working directory is never used — an Agent-launched
     * jar (D-058) usually runs with the session workspace as cwd.
     *
     * @param config     loaded configuration
     * @param configRoot directory passed to {@code --config}
     * @return absolute path of the token file
     */
    public static Path resolve(AppConfig config, Path configRoot) {
        String configured = config.security().envFile();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path root = configRoot == null ? Path.of(".") : configRoot;
        return root.toAbsolutePath().normalize().resolve(".bma3env");
    }

    /**
     * Ensure the token file exists and holds both tokens.
     *
     * @param file   target path ({@link #resolve})
     * @param rotate when true both values are regenerated; otherwise existing values are kept
     * @return the loaded file view; {@link #degraded()} reports a failed write
     */
    public static Bma3Env ensure(Path file, boolean rotate) {
        Map<String, String> existing;
        try {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                PrivateFiles.restrict(file);
            }
            existing = read(file);
        } catch (IOException | UncheckedIOException failure) {
            return new Bma3Env(file, Map.of(), true);
        }
        Map<String, String> next = new LinkedHashMap<>(existing);
        boolean changed = false;
        for (String key : new String[] {MCP_TOKEN, INBOX_TOKEN, DEBUG_TOKEN}) {
            String current = next.get(key);
            if (rotate || current == null || current.isBlank()) {
                next.put(key, generate());
                changed = true;
            }
        }
        boolean degraded = false;
        if (changed || !Files.exists(file)) {
            try {
                write(file, next);
            } catch (IOException failure) {
                degraded = true;
            }
        }
        return new Bma3Env(file, next, degraded);
    }

    /** Read a token file without creating it; missing file yields an empty map. */
    public static Map<String, String> read(Path file) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return parsed;
        }
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int split = line.indexOf('=');
                if (split <= 0) {
                    continue;
                }
                parsed.put(line.substring(0, split).trim(), line.substring(split + 1).trim());
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("读取 token 文件失败: " + file, failure);
        }
        return parsed;
    }

    private static void write(Path file, Map<String, String> values) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = PrivateFiles.createTemp(file);
        StringBuilder body = new StringBuilder()
                .append("# BMA3 运行期 token 文件（0600）——由 jar 生成，DSH 插件只读。\n")
                .append("# 轮换：删除本文件或使用 --rotate-token 后重启；两侧需重连。\n");
        values.forEach((key, value) -> body.append(key).append('=').append(value).append('\n'));
        try {
            Files.writeString(temp, body.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
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
    }

    private static String generate() {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        StringBuilder hex = new StringBuilder(TOKEN_BYTES * 2);
        for (byte value : raw) {
            hex.append(String.format(Locale.ROOT, "%02x", value));
        }
        return hex.toString();
    }

    /** Token file path this view was loaded from. */
    public Path file() {
        return file;
    }

    /** MCP bearer token, or empty when absent. */
    public String mcpToken() {
        return values.getOrDefault(MCP_TOKEN, "");
    }

    /** Inbox bearer token, or empty when absent. */
    public String inboxToken() {
        return values.getOrDefault(INBOX_TOKEN, "");
    }

    /** Debug-surface bearer token, or empty when absent (older files are upgraded in place). */
    public String debugToken() {
        return values.getOrDefault(DEBUG_TOKEN, "");
    }

    /** True when the write failed; the caller should surface this in {@code /healthz}. */
    public boolean degraded() {
        return degraded;
    }

    /** Values as loaded (never mutated by callers). */
    public Map<String, String> values() {
        return values;
    }
}
