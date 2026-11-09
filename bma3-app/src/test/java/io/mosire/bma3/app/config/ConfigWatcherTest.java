package io.mosire.bma3.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigWatcherTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsLastKnownGoodWhenAChangeIsInvalid() throws Exception {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Path configFile = configDir.resolve("app.yaml");
        Files.writeString(configFile, "server:\n  port: 8765\n");

        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<AppConfig> last = new AtomicReference<>();
        CountDownLatch validChange = new CountDownLatch(1);

        try (ConfigWatcher watcher = ConfigWatcher.watch(tempDir, Map.of(), config -> {
            callbacks.incrementAndGet();
            last.set(config);
            validChange.countDown();
        })) {
            Thread.sleep(250);
            Files.writeString(configFile, "server:\n  port: 70000\n");
            Thread.sleep(300);
            assertThat(callbacks.get()).isZero();

            Files.writeString(configFile, "server:\n  port: 8766\n");
            assertThat(validChange.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(last.get().server().port()).isEqualTo(8766);
        }
    }
}
