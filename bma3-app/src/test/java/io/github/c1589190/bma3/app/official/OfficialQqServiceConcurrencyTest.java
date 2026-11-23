package io.github.c1589190.bma3.app.official;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.c1589190.bma3.app.config.AppConfig;
import io.github.c1589190.bma3.app.config.ConfigLoader;
import io.github.c1589190.bma3.app.priority.PriorityResolver;
import io.github.c1589190.bma3.core.source.SourceLabels;
import io.github.c1589190.bma3.core.store.SqliteMessageBoardStore;
import io.github.c1589190.bma3.inbox.core.DeliveryPolicy;
import io.github.c1589190.bma3.inbox.core.InboxService;
import io.github.c1589190.bma3.inbox.core.SqliteInboxStore;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountClient;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountClientContext;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountClientFactory;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountErrorKind;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountRuntimeStatus;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountState;
import io.github.c1589190.bma3.qqofficial.credential.OfficialQqCredentialPathPolicy;
import io.github.c1589190.bma3.qqofficial.directory.OfficialQqDirectoryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class OfficialQqServiceConcurrencyTest {

    @TempDir
    Path root;

    @Test
    void disableCannotBeOverwrittenByAnOlderPeriodicReconcile() throws Exception {
        Path dataDir = root.resolve("data").toAbsolutePath();
        Path secretFile = root.resolve("rotating-concurrency.secret");
        writeSecret(secretFile, "synthetic-concurrency-secret-one");
        AppConfig enabled = config(root.resolve("enabled"), dataDir, secretFile, true);
        AppConfig disabled = config(root.resolve("disabled"), dataDir, secretFile, false);
        Map<String, String> env = Map.of();

        BlockingFactory factory = new BlockingFactory();
        SqliteMessageBoardStore board = new SqliteMessageBoardStore(
                dataDir.resolve("bot.db"));
        SqliteInboxStore inboxStore = new SqliteInboxStore(dataDir.resolve("inbox.db"));
        InboxService inbox = new InboxService(inboxStore,
                new DeliveryPolicy(0, Duration.ofMillis(1), Duration.ofMillis(1)));
        OfficialQqDirectoryStore directory = new OfficialQqDirectoryStore(
                dataDir.resolve("official-directory.db"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        OfficialQqService service = new OfficialQqService(
                enabled, env, board, directory, inbox,
                new PriorityResolver(() -> enabled.weighting()), SourceLabels.empty(),
                factory, OfficialQqCredentialPathPolicy.none(), Duration.ofMillis(100));
        try (service) {
            service.start();
            assertThat(factory.createCount()).isEqualTo(1);
            // Rotate the file without touching app.yaml. The next periodic reconcile observes the
            // changed fingerprint and enters the factory; hold it there to create the race.
            writeSecret(secretFile, "synthetic-concurrency-secret-two");
            assertThat(factory.awaitSecondCreate()).isTrue();

            Future<?> disable = executor.submit(() -> {
                service.reconcile(disabled);
                factory.markDisableReturned();
            });
            // The in-flight periodic reconcile owns the service lock, so the disable cannot
            // finish while the stale create is still blocked.
            assertThat(disable.isDone()).isFalse();
            factory.releaseSecondCreate();
            disable.get(3, TimeUnit.SECONDS);

            assertThat(factory.events()).containsSubsequence("create-2", "disable-returned");
            assertThat(service.registry().accountStatus("official-concurrent"))
                    .get()
                    .satisfies(status -> {
                        assertThat(status.enabled()).isFalse();
                        assertThat(status.state()).isEqualTo(OfficialQqAccountState.DISABLED);
                    });

            // Several later ticks must observe the disabled snapshot and must not resurrect it.
            Thread.sleep(350L);
            assertThat(factory.createCount()).isEqualTo(2);
            assertThat(factory.events()).doesNotContain("create-3");
            assertThat(service.registry().accountStatus("official-concurrent"))
                    .get()
                    .extracting(status -> status.state())
                    .isEqualTo(OfficialQqAccountState.DISABLED);
        } finally {
            service.close();
            executor.shutdownNow();
            inbox.close();
            inboxStore.close();
            board.close();
        }
    }

    @Test
    void manualTickCannotReapplyOldConfigAfterConcurrentDisable() throws Exception {
        Path dataDir = root.resolve("manual-data").toAbsolutePath();
        Path secretFile = root.resolve("rotating-manual.secret");
        writeSecret(secretFile, "synthetic-manual-secret-one");
        AppConfig enabled = config(root.resolve("manual-enabled"), dataDir, secretFile, true);
        AppConfig disabled = config(root.resolve("manual-disabled"), dataDir, secretFile, false);

        BlockingFactory factory = new BlockingFactory();
        SqliteMessageBoardStore board = new SqliteMessageBoardStore(
                dataDir.resolve("bot.db"));
        SqliteInboxStore inboxStore = new SqliteInboxStore(dataDir.resolve("inbox.db"));
        InboxService inbox = new InboxService(inboxStore,
                new DeliveryPolicy(0, Duration.ofMillis(1), Duration.ofMillis(1)));
        OfficialQqDirectoryStore directory = new OfficialQqDirectoryStore(
                dataDir.resolve("official-directory.db"));
        OfficialQqService service = new OfficialQqService(
                enabled, Map.of(), board, directory, inbox,
                new PriorityResolver(() -> enabled.weighting()), SourceLabels.empty(),
                factory, OfficialQqCredentialPathPolicy.none(), Duration.ofMillis(100));
        AtomicReference<Throwable> disableFailure = new AtomicReference<>();
        AtomicReference<Throwable> manualFailure = new AtomicReference<>();
        Thread disableThread = null;
        Thread manualThread = null;
        try (service) {
            service.start();
            assertThat(factory.createCount()).isEqualTo(1);
            writeSecret(secretFile, "synthetic-manual-secret-two");
            assertThat(factory.awaitSecondCreate()).isTrue();

            disableThread = new Thread(() -> {
                try {
                    service.reconcile(disabled);
                } catch (Throwable failure) {
                    disableFailure.set(failure);
                }
            }, "official-disable-race");
            disableThread.start();
            awaitWaiting(disableThread);

            manualThread = new Thread(() -> {
                try {
                    service.reconcileNow();
                } catch (Throwable failure) {
                    manualFailure.set(failure);
                }
            }, "official-manual-tick-race");
            manualThread.start();
            awaitWaiting(manualThread);

            factory.releaseSecondCreate();
            disableThread.join(3_000L);
            manualThread.join(3_000L);
            assertThat(disableThread.isAlive()).isFalse();
            assertThat(manualThread.isAlive()).isFalse();
            assertThat(disableFailure.get()).isNull();
            assertThat(manualFailure.get()).isNull();

            assertThat(service.registry().accountStatus("official-concurrent"))
                    .get()
                    .extracting(status -> status.state())
                    .isEqualTo(OfficialQqAccountState.DISABLED);
            Thread.sleep(350L);
            assertThat(factory.createCount()).isEqualTo(2);
            assertThat(factory.events()).doesNotContain("create-3");
        } finally {
            if (disableThread != null && disableThread.isAlive()) {
                disableThread.interrupt();
            }
            if (manualThread != null && manualThread.isAlive()) {
                manualThread.interrupt();
            }
            service.close();
            inbox.close();
            inboxStore.close();
            board.close();
        }
    }

    private static void awaitWaiting(Thread thread) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("thread did not block on the service reconcile lock: "
                + thread.getName() + " state=" + thread.getState());
    }

    private AppConfig config(Path configRoot, Path dataDir, Path secretFile, boolean enabled)
            throws Exception {
        Path file = configRoot.resolve("config/app.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                dataDir: '%s'
                qqofficial:
                  accounts:
                    - accountId: official-concurrent
                      appId: "synthetic-app-id-concurrent"
                      appSecretFile: %s
                      enabled: %s
                      shard: [0, 1]
                schedule:
                  enabled: false
                debug:
                  enabled: false
                xvfb:
                  enabled: false
                """.formatted(dataDir.toString().replace("\\", "/")
                .replace("'", "''"), secretFile.toAbsolutePath(), enabled));
        return ConfigLoader.load(configRoot, Map.of());
    }

    private static void writeSecret(Path file, String value) throws Exception {
        Files.writeString(file, value);
        Files.setPosixFilePermissions(file, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    private static final class BlockingFactory implements OfficialQqAccountClientFactory {
        private final AtomicInteger creates = new AtomicInteger();
        private final CountDownLatch secondCreate = new CountDownLatch(1);
        private final CountDownLatch releaseSecond = new CountDownLatch(1);
        private final CountDownLatch disableReturned = new CountDownLatch(1);
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public OfficialQqAccountClient create(OfficialQqAccountClientContext context) {
            int count = creates.incrementAndGet();
            events.add("create-" + count);
            if (count == 2) {
                secondCreate.countDown();
                await(releaseSecond);
            }
            return new FakeClient(context.config().accountId());
        }

        boolean awaitSecondCreate() throws InterruptedException {
            return secondCreate.await(3, TimeUnit.SECONDS);
        }

        void releaseSecondCreate() {
            releaseSecond.countDown();
        }

        void markDisableReturned() {
            events.add("disable-returned");
            disableReturned.countDown();
        }

        int createCount() {
            return creates.get();
        }

        List<String> events() {
            synchronized (events) {
                return List.copyOf(events);
            }
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("latch timeout");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", failure);
            }
        }
    }

    private static final class FakeClient implements OfficialQqAccountClient {
        private final String accountId;

        private FakeClient(String accountId) {
            this.accountId = accountId;
        }

        @Override
        public String accountId() {
            return accountId;
        }

        @Override
        public void start() {
        }

        @Override
        public OfficialQqAccountRuntimeStatus status() {
            return new OfficialQqAccountRuntimeStatus(
                    OfficialQqAccountState.READY, true, false, Instant.now(),
                    Instant.now(), null, null, 0, OfficialQqAccountErrorKind.NONE, "", "", "");
        }

        @Override
        public Optional<io.github.c1589190.bma3.qqofficial.api.OfficialQqApiClient> apiClient() {
            return Optional.empty();
        }

        @Override
        public void close() {
        }
    }
}
