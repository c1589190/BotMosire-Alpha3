package io.mosire.bma3.app.config;

import io.mosire.bma3.core.config.ConfigException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls the config file and emits only valid snapshots. An invalid edit leaves the previous
 * snapshot in effect; the next valid edit is emitted.
 */
public final class ConfigWatcher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigWatcher.class);

    private final Path root;
    private final Map<String, String> env;
    private final Consumer<AppConfig> listener;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile String lastSignature;

    private ConfigWatcher(Path root, Map<String, String> env, Consumer<AppConfig> listener) {
        this.root = root;
        this.env = Map.copyOf(env);
        this.listener = listener;
        this.lastSignature = signature();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bma3-config-watch");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::poll, 50, 50, TimeUnit.MILLISECONDS);
    }

    public static ConfigWatcher watch(Path root, Consumer<AppConfig> listener) {
        return watch(root, Map.of(), listener);
    }

    public static ConfigWatcher watch(Path root, Map<String, String> env,
                                      Consumer<AppConfig> listener) {
        return new ConfigWatcher(root, env, listener);
    }

    private void poll() {
        if (closed.get()) {
            return;
        }
        String current = signature();
        if (current.equals(lastSignature)) {
            return;
        }
        lastSignature = current;
        try {
            AppConfig config = ConfigLoader.load(root, env);
            listener.accept(config);
        } catch (ConfigException failure) {
            LOG.warn("event=config_reload_rejected reason={}", failure.getMessage());
        } catch (RuntimeException failure) {
            LOG.warn("event=config_reload_failed type={}", failure.getClass().getSimpleName());
        }
    }

    /**
     * Change signature: modification time <b>plus size</b>. Two writes inside the same millisecond
     * would otherwise look identical on filesystems with coarse timestamps, and an atomic replace
     * that keeps the size unchanged is still caught by the mtime component.
     */
    private String signature() {
        Path file = ConfigLoader.configFile(root);
        try {
            if (!Files.exists(file)) {
                return "missing";
            }
            FileTime time = Files.getLastModifiedTime(file);
            return time.toMillis() + ":" + Files.size(file);
        } catch (Exception failure) {
            return "unreadable";
        }
    }

    @Override
    public void close() {
        closed.set(true);
        scheduler.shutdownNow();
    }
}
