package io.mosire.bma3.qqofficial.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.bma3.qqofficial.api.OfficialQqApiClient;
import io.mosire.bma3.qqofficial.auth.OfficialQqTokenManager;
import io.mosire.bma3.qqofficial.gateway.GatewayFailureClassifier;
import io.mosire.bma3.qqofficial.gateway.GatewayOptions;
import io.mosire.bma3.qqofficial.gateway.GatewayPermanentException;
import io.mosire.bma3.qqofficial.gateway.GatewayScheduler;
import io.mosire.bma3.qqofficial.gateway.GatewayState;
import io.mosire.bma3.qqofficial.gateway.OfficialQqGatewayClient;
import io.mosire.bma3.qqofficial.credential.OfficialQqCredential;
import io.mosire.bma3.qqofficial.credential.OfficialQqCredentialPathPolicy;
import io.mosire.bma3.qqofficial.credential.OfficialQqCredentialResolver;
import io.mosire.bma3.qqofficial.event.OfficialQqDispatch;
import io.mosire.bma3.qqofficial.event.OfficialQqUnknownEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialQqAccountRegistryTest {

    private static final String SECRET_A = "synthetic-secret-a-do-not-leak";
    private static final String SECRET_B = "synthetic-secret-b-do-not-leak";
    private static final String SECRET_C = "synthetic-secret-c-do-not-leak";

    @TempDir
    Path temp;

    @Test
    void startsUniqueEnabledAccountsWithInjectedEnvAndRoutesEventsByOwner() {
        FakeFactory factory = new FakeFactory();
        List<OfficialQqAccountEvent> events = new CopyOnWriteArrayList<>();
        Map<String, String> environment = Map.of(
                "BMA3_TEST_SECRET_A", SECRET_A,
                "BMA3_TEST_SECRET_B", SECRET_B);

        try (OfficialQqAccountRegistry registry =
                     new OfficialQqAccountRegistry(factory, events::add, environment,
                             OfficialQqCredentialPathPolicy.none())) {
            OfficialQqReconcileResult result = registry.reconcile(List.of(
                    envConfig("account-a", "app-a", "BMA3_TEST_SECRET_A"),
                    envConfig("account-b", "app-b", "BMA3_TEST_SECRET_B")));

            assertThat(result.added()).containsExactly("account-a", "account-b");
            assertThat(result.failed()).isEmpty();
            assertThat(factory.contexts).hasSize(2);
            assertThat(factory.contexts)
                    .allSatisfy(context -> assertThat(context.apiBaseUri())
                            .isEqualTo(OfficialQqAccountEndpoints.OFFICIAL_API_BASE_URI));
            assertThat(secretOf(factory.contexts.get(0).credential())).isEqualTo(SECRET_A);
            assertThat(secretOf(factory.contexts.get(1).credential())).isEqualTo(SECRET_B);
            assertThat(factory.contexts.get(0).toString())
                    .doesNotContain(SECRET_A, SECRET_B, "BMA3_TEST_SECRET_A");
            assertThat(registry.desiredConfigs().toString())
                    .doesNotContain(SECRET_A, SECRET_B, "BMA3_TEST_SECRET_A");
            assertThat(result.toString()).doesNotContain(SECRET_A, SECRET_B);

            factory.client("account-a").emit(unknown("event-a"));
            factory.client("account-b").emit(unknown("event-b"));

            assertThat(events).extracting(OfficialQqAccountEvent::accountId)
                    .containsExactly("account-a", "account-b");
            assertThat(events).extracting(event -> event.dispatch().eventId())
                    .containsExactly("event-a", "event-b");
            assertThat(registry.accountStatuses()).extracting(OfficialQqAccountStatus::accountId)
                    .containsExactly("account-a", "account-b");
            assertThat(registry.connected("account-a")).isTrue();
            assertThat(registry.connected("account-b")).isTrue();
        }
    }

    @Test
    void disabledAccountsResolveNoCredentialAndCreateNoClient() {
        FakeFactory factory = new FakeFactory();
        List<OfficialQqAccountEvent> events = new CopyOnWriteArrayList<>();
        OfficialQqAccountConfig disabled = new OfficialQqAccountConfig(
                "disabled-a",
                "app-disabled",
                "BMA3_TEST_MISSING_SECRET",
                (Path) null,
                false);

        try (OfficialQqAccountRegistry registry =
                     new OfficialQqAccountRegistry(factory, events::add, Map.of(),
                             OfficialQqCredentialPathPolicy.none())) {
            OfficialQqReconcileResult result = registry.reconcile(List.of(disabled));

            assertThat(result.added()).isEmpty();
            assertThat(result.failed()).isEmpty();
            assertThat(factory.contexts).isEmpty();
            assertThat(factory.operations).isEmpty();
            assertThat(registry.accountStatus("disabled-a"))
                    .get()
                    .satisfies(status -> {
                        assertThat(status.state()).isEqualTo(OfficialQqAccountState.DISABLED);
                        assertThat(status.enabled()).isFalse();
                        assertThat(status.connected()).isFalse();
                    });
        }
    }

    @Test
    void duplicateAccountIdsAreRejectedAndNeverProduceDuplicateRuntimes() {
        FakeFactory factory = new FakeFactory();
        List<OfficialQqAccountEvent> events = new CopyOnWriteArrayList<>();
        OfficialQqAccountConfig duplicate = envConfig("duplicate-a", "app-a", "SECRET_A");

        try (OfficialQqAccountRegistry registry =
                     new OfficialQqAccountRegistry(factory, events::add,
                             Map.of("SECRET_A", SECRET_A),
                             OfficialQqCredentialPathPolicy.none())) {
            OfficialQqReconcileResult result = registry.reconcile(List.of(
                    duplicate,
                    duplicate,
                    envConfig("healthy-b", "app-b", "SECRET_A")));

            assertThat(result.rejected()).containsExactly("duplicate-a");
            assertThat(result.added()).containsExactly("healthy-b");
            assertThat(factory.operations).contains("create:healthy-b");
            assertThat(factory.operations).doesNotContain("create:duplicate-a");
            assertThat(registry.accountStatus("duplicate-a"))
                    .get()
                    .satisfies(status -> {
                        assertThat(status.state()).isEqualTo(OfficialQqAccountState.DOWN);
                        assertThat(status.errorKind())
                                .isEqualTo(OfficialQqAccountErrorKind.CONFIGURATION);
                        assertThat(status.errorCode()).isEqualTo("DUPLICATE_ACCOUNT_ID");
                    });
            assertThat(registry.client("duplicate-a")).isEmpty();
        }
    }

    @Test
    void changedAccountRetiresOldRuntimeBeforeStartingReplacementAndIgnoresOldCallbacks() {
        FakeFactory factory = new FakeFactory();
        List<OfficialQqAccountEvent> events = new CopyOnWriteArrayList<>();
        Map<String, String> environment = Map.of("SECRET_A", SECRET_A, "SECRET_B", SECRET_B);

        try (OfficialQqAccountRegistry registry =
                     new OfficialQqAccountRegistry(factory, events::add, environment,
                             OfficialQqCredentialPathPolicy.none())) {
            OfficialQqAccountConfig firstA = envConfig("account-a", "app-a", "SECRET_A");
            OfficialQqAccountConfig accountB = envConfig("account-b", "app-b", "SECRET_B");
            registry.reconcile(List.of(firstA, accountB));
            FakeClient oldA = factory.client("account-a");
            FakeClient untouchedB = factory.client("account-b");

            OfficialQqAccountConfig changedA = envConfig("account-a", "app-a-v2", "SECRET_A");
            OfficialQqReconcileResult result = registry.reconcile(List.of(changedA, accountB));

            assertThat(result.restarted()).containsExactly("account-a");
            assertThat(result.unchanged()).containsExactly("account-b");
            assertThat(oldA.closed).isTrue();
            assertThat(untouchedB.closed).isFalse();
            int closeA = factory.operations.lastIndexOf("close:account-a");
            int createA = factory.operations.lastIndexOf("create:account-a");
            assertThat(closeA).isGreaterThanOrEqualTo(0).isLessThan(createA);

            int before = events.size();
            oldA.emit(unknown("stale-event"));
            assertThat(events).hasSize(before);

            factory.client("account-a").emit(unknown("fresh-event"));
            assertThat(events).hasSize(before + 1);
            assertThat(events.getLast().accountId()).isEqualTo("account-a");
            assertThat(events.getLast().dispatch().eventId()).isEqualTo("fresh-event");
        }
    }

    @Test
    void runtimeOptionsArePartOfConfigIdentityAndReconcileRestartsOnChange() {
        FakeFactory factory = new FakeFactory();
        List<OfficialQqAccountEvent> events = new CopyOnWriteArrayList<>();
        OfficialQqAccountRuntimeOptions firstOptions =
                OfficialQqAccountRuntimeOptions.ofSeconds(3, 5, 250, 2_000, false);
        OfficialQqAccountRuntimeOptions changedOptions =
                OfficialQqAccountRuntimeOptions.ofSeconds(4, 6, 500, 3_000, true);
        OfficialQqAccountConfig first = new OfficialQqAccountConfig(
                "account-a", "app-a", "SECRET_A", (Path) null, true, 0, 1, firstOptions);

        try (OfficialQqAccountRegistry registry = new OfficialQqAccountRegistry(
                factory, events::add, Map.of("SECRET_A", SECRET_A),
                OfficialQqCredentialPathPolicy.none())) {
            registry.reconcile(List.of(first));
            FakeClient oldClient = factory.client("account-a");
            assertThat(registry.desiredConfigs()).singleElement()
                    .extracting(OfficialQqAccountConfig::runtimeOptions)
                    .isEqualTo(firstOptions);

            OfficialQqAccountConfig changed = new OfficialQqAccountConfig(
                    "account-a", "app-a", "SECRET_A", (Path) null, true, 0, 1,
                    changedOptions);
            OfficialQqReconcileResult result = registry.reconcile(List.of(changed));

            assertThat(result.restarted()).containsExactly("account-a");
            assertThat(oldClient.closed).isTrue();
            assertThat(registry.desiredConfigs()).singleElement()
                    .extracting(OfficialQqAccountConfig::runtimeOptions)
                    .isEqualTo(changedOptions);
        }
    }

    @Test
    void environmentSupplierIsUsedOnEveryReconcileAndRotationRestarts() {
        FakeFactory factory = new FakeFactory();
        List<OfficialQqAccountEvent> events = new CopyOnWriteArrayList<>();
        Map<String, String> environment = new HashMap<>();
        environment.put("SECRET_A", "env-rotation-one");
        OfficialQqAccountConfig config = envConfig("account-a", "app-a", "SECRET_A");

        try (OfficialQqAccountRegistry registry = new OfficialQqAccountRegistry(
                factory, events::add, () -> environment,
                OfficialQqCredentialPathPolicy.none())) {
            registry.reconcile(List.of(config));
            FakeClient firstClient = factory.client("account-a");

            environment.put("SECRET_A", "env-rotation-two");
            OfficialQqReconcileResult result = registry.reconcile(List.of(config));

            assertThat(result.restarted()).containsExactly("account-a");
            assertThat(firstClient.closed).isTrue();
            assertThat(factory.contexts).hasSize(2);
            assertThat(secretOf(factory.contexts.get(1).credential()))
                    .isEqualTo("env-rotation-two");
            assertThat(registry.accountStatuses().toString())
                    .doesNotContain("env-rotation-one", "env-rotation-two");
        }
    }

    @Test
    void productionFactoryUsesConfiguredConnectTimeoutAndBackoff() throws Exception {
        AtomicReference<Duration> observedConnectTimeout = new AtomicReference<>();
        OfficialQqAccountRuntimeOptions options =
                OfficialQqAccountRuntimeOptions.ofSeconds(7, 9, 250, 2_000, false);
        OfficialQqProductionAccountRuntimeFactory factory =
                new OfficialQqProductionAccountRuntimeFactory(
                        timeout -> {
                            observedConnectTimeout.set(timeout);
                            return HttpClient.newBuilder().connectTimeout(timeout).build();
                        },
                        new ObjectMapper(),
                        Clock.systemUTC());
        OfficialQqCredential credential = new OfficialQqCredentialResolver(
                "account-a", "app-a", "SECRET_A", null, Map.of("SECRET_A", SECRET_A))
                .resolve();
        OfficialQqAccountConfig config = new OfficialQqAccountConfig(
                "account-a", "app-a", "SECRET_A", (Path) null, true, 0, 1, options);
        OfficialQqAccountClientContext context = new OfficialQqAccountClientContext(
                config,
                credential,
                OfficialQqAccountEndpoints.OFFICIAL_API_BASE_URI,
                dispatch -> {
                },
                status -> {
                });
        OfficialQqAccountClient runtime = factory.create(context);
        try {
            assertThat(observedConnectTimeout).hasValue(Duration.ofSeconds(7));
            var backoff = OfficialQqProductionAccountRuntimeFactory.backoffFor(options);
            assertThat(backoff.delayForAttempt(1)).isEqualTo(Duration.ofMillis(250));
            assertThat(backoff.delayForAttempt(10)).isEqualTo(Duration.ofSeconds(2));
        } finally {
            runtime.close();
            credential.close();
        }
    }

    @Test
    void secretFileRotationIsDetectedOnNextReconcileWithoutExposingSecret() throws Exception {
        FakeFactory factory = new FakeFactory();
        List<OfficialQqAccountEvent> events = new CopyOnWriteArrayList<>();
        Path secretFile = temp.resolve("official-account.secret");
        String firstSecret = "rotation-secret-one-sentinel";
        String secondSecret = "rotation-secret-two-sentinel";
        Files.writeString(secretFile, firstSecret, StandardCharsets.UTF_8);
        OfficialQqAccountConfig config = new OfficialQqAccountConfig(
                "rotating-a", "app-rotating", null, secretFile, true);

        try (OfficialQqAccountRegistry registry =
                     new OfficialQqAccountRegistry(factory, events::add, Map.of(),
                             OfficialQqCredentialPathPolicy.none())) {
            registry.reconcile(List.of(config));
            FakeClient firstClient = factory.client("rotating-a");
            assertThat(secretOf(factory.contexts.getFirst().credential())).isEqualTo(firstSecret);

            Files.writeString(secretFile, secondSecret, StandardCharsets.UTF_8);
            OfficialQqReconcileResult rotated = registry.reconcile(List.of(config));

            assertThat(rotated.restarted()).containsExactly("rotating-a");
            assertThat(firstClient.closed).isTrue();
            assertThat(factory.contexts).hasSize(2);
            assertThat(secretOf(factory.contexts.get(1).credential())).isEqualTo(secondSecret);
            assertThat(registry.accountStatuses().toString())
                    .doesNotContain(firstSecret, secondSecret);
            assertThat(rotated.toString()).doesNotContain(firstSecret, secondSecret);

            Files.writeString(secretFile, secondSecret, StandardCharsets.UTF_8);
            OfficialQqReconcileResult sameContent = registry.reconcile(List.of(config));
            assertThat(sameContent.unchanged()).containsExactly("rotating-a");
            assertThat(factory.contexts).hasSize(2);
        }
    }

    @Test
    void disablingAndRemovingAccountsCloseRuntimesAndDropRemovedStatus() {
        FakeFactory factory = new FakeFactory();
        List<OfficialQqAccountEvent> events = new CopyOnWriteArrayList<>();
        Map<String, String> environment = Map.of("SECRET_A", SECRET_A, "SECRET_B", SECRET_B);

        try (OfficialQqAccountRegistry registry =
                     new OfficialQqAccountRegistry(factory, events::add, environment,
                             OfficialQqCredentialPathPolicy.none())) {
            registry.reconcile(List.of(
                    envConfig("account-a", "app-a", "SECRET_A"),
                    envConfig("account-b", "app-b", "SECRET_B")));
            FakeClient a = factory.client("account-a");
            FakeClient b = factory.client("account-b");
            OfficialQqAccountConfig disabledA = new OfficialQqAccountConfig(
                    "account-a", "app-a", "SECRET_A", (Path) null, false);

            OfficialQqReconcileResult result = registry.reconcile(List.of(disabledA));

            assertThat(result.disabled()).containsExactly("account-a");
            assertThat(result.removed()).containsExactly("account-b");
            assertThat(a.closed).isTrue();
            assertThat(b.closed).isTrue();
            assertThat(factory.operations).doesNotContain("create:account-a-v2");
            assertThat(registry.accountStatus("account-a"))
                    .get()
                    .extracting(OfficialQqAccountStatus::state)
                    .isEqualTo(OfficialQqAccountState.DISABLED);
            assertThat(registry.accountStatus("account-b")).isEmpty();

            OfficialQqReconcileResult emptied = registry.reconcile(List.of());
            assertThat(emptied.removed()).containsExactly("account-a");
            assertThat(registry.accountStatuses()).isEmpty();
        }
    }

    @Test
    void oneAccountFailureBecomesDownWithoutStoppingOtherAccounts() {
        FakeFactory factory = new FakeFactory();
        factory.failCreate.add("broken-create");
        factory.failStart.add("broken-start");
        List<OfficialQqAccountEvent> events = new CopyOnWriteArrayList<>();
        Map<String, String> environment = Map.of(
                "SECRET_A", SECRET_A,
                "SECRET_B", SECRET_B,
                "SECRET_C", SECRET_C);

        try (OfficialQqAccountRegistry registry =
                     new OfficialQqAccountRegistry(factory, events::add, environment,
                             OfficialQqCredentialPathPolicy.none())) {
            assertThatCode(() -> {
                OfficialQqReconcileResult result = registry.reconcile(List.of(
                        envConfig("broken-create", "app-a", "SECRET_A"),
                        envConfig("broken-start", "app-b", "SECRET_B"),
                        envConfig("healthy-c", "app-c", "SECRET_C")));

                assertThat(result.added()).containsExactly("healthy-c");
                assertThat(result.failed()).containsExactly("broken-create", "broken-start");
                assertThat(result.clean()).isFalse();
            }).doesNotThrowAnyException();

            assertThat(registry.accountStatus("broken-create"))
                    .get()
                    .satisfies(status -> {
                        assertThat(status.state()).isEqualTo(OfficialQqAccountState.DOWN);
                        assertThat(status.terminal()).isTrue();
                        assertThat(status.errorKind())
                                .isEqualTo(OfficialQqAccountErrorKind.INTERNAL);
                        assertThat(status.errorType()).isEqualTo("IllegalStateException");
                        assertThat(status.toString()).doesNotContain(SECRET_A);
                    });
            assertThat(registry.accountStatus("broken-start"))
                    .get()
                    .satisfies(status -> assertThat(status.state())
                            .isEqualTo(OfficialQqAccountState.DOWN));
            assertThat(registry.accountStatus("healthy-c"))
                    .get()
                    .satisfies(status -> assertThat(status.state())
                            .isEqualTo(OfficialQqAccountState.READY));

            factory.client("healthy-c").emit(unknown("healthy-event"));
            assertThat(events).singleElement()
                    .extracting(OfficialQqAccountEvent::accountId)
                    .isEqualTo("healthy-c");
        }
    }

    @Test
    void factoryRuntimeAccountIdMismatchFailsClosedAndCannotEmitEvents() {
        FakeFactory factory = new FakeFactory();
        factory.accountIdOverrides.put("account-a", "account-b");
        List<OfficialQqAccountEvent> events = new CopyOnWriteArrayList<>();

        try (OfficialQqAccountRegistry registry =
                     new OfficialQqAccountRegistry(factory, events::add,
                             Map.of("SECRET_A", SECRET_A),
                             OfficialQqCredentialPathPolicy.none())) {
            OfficialQqReconcileResult result = registry.reconcile(List.of(
                    envConfig("account-a", "app-a", "SECRET_A")));

            assertThat(result.failed()).containsExactly("account-a");
            assertThat(factory.client("account-a").closed).isTrue();
            assertThat(registry.client("account-a")).isEmpty();
            assertThat(registry.accountStatus("account-a"))
                    .get()
                    .satisfies(status -> {
                        assertThat(status.state()).isEqualTo(OfficialQqAccountState.DOWN);
                        assertThat(status.errorKind())
                                .isEqualTo(OfficialQqAccountErrorKind.ROUTING);
                        assertThat(status.errorCode()).isEqualTo("ACCOUNT_ID_MISMATCH");
                    });

            factory.client("account-a").emit(unknown("misrouted-event"));
            assertThat(events).isEmpty();
        }
    }

    @Test
    void sinkQueryingStatusWhileGatewayCloseWaitsDoesNotDeadlockAndOldGenerationStaysDead()
            throws Exception {
        BlockingCloseFactory factory = new BlockingCloseFactory();
        AtomicReference<OfficialQqAccountRegistry> registryRef = new AtomicReference<>();
        CountDownLatch sinkInside = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
        OfficialQqAccountEventSink sink = event -> {
            // This models an ingest predicate that asks the registry for routing/status while the
            // Gateway is waiting for the event delivery lock to drain before close returns.
            OfficialQqAccountRegistry registry = registryRef.get();
            assertThat(registry.accountStatus(event.accountId())).isPresent();
            assertThat(registry.accountStatuses()).isNotEmpty();
            assertThat(registry.client(event.accountId())).isPresent();
            delivered.incrementAndGet();
            sinkInside.countDown();
            try {
                if (!releaseSink.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("test did not release the sink");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        OfficialQqAccountRegistry registry = new OfficialQqAccountRegistry(
                factory, sink, Map.of("SECRET_A", SECRET_A),
                OfficialQqCredentialPathPolicy.none());
        registryRef.set(registry);
        try {
            registry.reconcile(List.of(envConfig("account-a", "app-a", "SECRET_A")));
            BlockingCloseClient client = factory.client;

            Thread eventThread = new Thread(client::emitHeldEvent, "held-event-sink");
            eventThread.start();
            assertThat(sinkInside.await(2, TimeUnit.SECONDS)).isTrue();

            Future<OfficialQqReconcileResult> reconcile = executor.submit(
                    () -> registry.reconcile(List.of()));
            assertThat(client.closeAttempted.await(2, TimeUnit.SECONDS)).isTrue();
            releaseSink.countDown();

            assertThat(reconcile.get(2, TimeUnit.SECONDS).removed())
                    .containsExactly("account-a");
            eventThread.join(2_000L);
            assertThat(eventThread.isAlive()).isFalse();
            assertThat(client.closed).isTrue();
            assertThat(delivered).hasValue(1);

            client.emitHeldEvent();
            assertThat(delivered).hasValue(1);
            assertThat(registry.accountStatus("account-a")).isEmpty();
        } finally {
            releaseSink.countDown();
            registry.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeWhileSinkQueriesStatusDoesNotDeadlockAndFutureReconcileIsSuppressed()
            throws Exception {
        BlockingCloseFactory factory = new BlockingCloseFactory();
        AtomicReference<OfficialQqAccountRegistry> registryRef = new AtomicReference<>();
        CountDownLatch sinkInside = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
        OfficialQqAccountEventSink sink = event -> {
            OfficialQqAccountRegistry registry = registryRef.get();
            assertThat(registry.accountStatus(event.accountId())).isPresent();
            assertThat(registry.accountStatuses()).isNotEmpty();
            delivered.incrementAndGet();
            sinkInside.countDown();
            try {
                releaseSink.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        OfficialQqAccountRegistry registry = new OfficialQqAccountRegistry(
                factory, sink, Map.of("SECRET_A", SECRET_A),
                OfficialQqCredentialPathPolicy.none());
        registryRef.set(registry);
        try {
            registry.reconcile(List.of(envConfig("account-a", "app-a", "SECRET_A")));
            BlockingCloseClient client = factory.client;

            Thread eventThread = new Thread(client::emitHeldEvent, "close-held-event-sink");
            eventThread.start();
            assertThat(sinkInside.await(2, TimeUnit.SECONDS)).isTrue();

            Future<?> closing = executor.submit(registry::close);
            assertThat(client.closeAttempted.await(2, TimeUnit.SECONDS)).isTrue();
            releaseSink.countDown();
            closing.get(2, TimeUnit.SECONDS);
            eventThread.join(2_000L);

            assertThat(eventThread.isAlive()).isFalse();
            assertThat(client.closed).isTrue();
            assertThat(registry.isClosed()).isTrue();
            client.emitHeldEvent();
            assertThat(delivered).hasValue(1);
            assertThatThrownBy(() -> registry.reconcile(List.of(
                    envConfig("account-a", "app-a", "SECRET_A"))))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            releaseSink.countDown();
            registry.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeClosesAllRuntimesDropsOldCallbacksAndSuppressesFutureReconcile() {
        FakeFactory factory = new FakeFactory();
        List<OfficialQqAccountEvent> events = new CopyOnWriteArrayList<>();
        OfficialQqAccountRegistry registry = new OfficialQqAccountRegistry(
                factory, events::add, Map.of("SECRET_A", SECRET_A),
                OfficialQqCredentialPathPolicy.none());
        registry.reconcile(List.of(envConfig("account-a", "app-a", "SECRET_A")));
        FakeClient client = factory.client("account-a");

        registry.close();
        registry.close();

        assertThat(registry.isClosed()).isTrue();
        assertThat(client.closed).isTrue();
        assertThat(registry.accountStatus("account-a"))
                .get()
                .extracting(OfficialQqAccountStatus::state)
                .isEqualTo(OfficialQqAccountState.CLOSED);
        client.emit(unknown("after-close"));
        assertThat(events).isEmpty();
        assertThatThrownBy(() -> registry.reconcile(List.of(
                envConfig("account-a", "app-a", "SECRET_A"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(factory.operations).doesNotContain("start:account-a-after-close");
    }

    @Test
    void defaultEndpointIsOfficialAndGatewayUrisMustStayOnOfficialDomain() {
        FakeFactory factory = new FakeFactory();
        try (OfficialQqAccountRegistry registry = new OfficialQqAccountRegistry(
                factory, OfficialQqAccountEventSink.noop(),
                Map.of("SECRET_A", SECRET_A), OfficialQqCredentialPathPolicy.none())) {
            registry.reconcile(List.of(envConfig("account-a", "app-a", "SECRET_A")));
        }

        assertThat(factory.contexts).singleElement().satisfies(context -> {
            assertThat(context.apiBaseUri())
                    .isEqualTo(URI.create("https://api.bot.qq.com"));
            assertThat(OfficialQqAccountEndpoints.isOfficialApiBaseUri(context.apiBaseUri()))
                    .isTrue();
        });
        assertThat(OfficialQqAccountEndpoints.requireOfficialGatewayUri(
                URI.create("wss://api.sgroup.qq.com/websocket")))
                .isEqualTo(URI.create("wss://api.sgroup.qq.com/websocket"));
        assertThatThrownBy(() -> OfficialQqAccountEndpoints.requireOfficialGatewayUri(
                URI.create("wss://gateway.evil.example/websocket")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OfficialQqAccountEndpoints.requireApiBaseUri(
                URI.create("https://user:pass@example.test")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventSinkFailurePropagatesToRuntimeAndReplayCanSucceed() {
        FakeFactory factory = new FakeFactory();
        AtomicInteger attempts = new AtomicInteger();
        CopyOnWriteArrayList<String> delivered = new CopyOnWriteArrayList<>();
        OfficialQqAccountEventSink sink = event -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                // The message intentionally resembles body/credential content: the registry must
                // propagate this exception without copying its details into status/log fields.
                throw new IllegalStateException("ingest failed body-secret-sentinel");
            }
            delivered.add(event.dispatch().eventId());
        };

        try (OfficialQqAccountRegistry registry = new OfficialQqAccountRegistry(
                factory, sink, Map.of("SECRET_A", SECRET_A),
                OfficialQqCredentialPathPolicy.none())) {
            registry.reconcile(List.of(envConfig("account-a", "app-a", "SECRET_A")));
            FakeClient client = factory.client("account-a");

            client.emitWithReplay(unknown("replay-event"));

            assertThat(client.sinkFailures).isEqualTo(1);
            assertThat(client.replays).isEqualTo(1);
            assertThat(attempts).hasValue(2);
            assertThat(delivered).containsExactly("replay-event");
            assertThat(registry.accountStatuses().toString())
                    .doesNotContain("body-secret-sentinel");
        }
    }

    @Test
    void accountEventToStringUsesOnlyKnownTypeWhitelistAndSafeCounts() {
        String hostileId = "event-id\nsecret=" + SECRET_A;
        String hostileType = "C2C_MESSAGE_CREATE\r\nAuthorization: Bearer " + SECRET_B;
        OfficialQqAccountEvent event = new OfficialQqAccountEvent(
                "account-a",
                new OfficialQqUnknownEvent(hostileId, hostileType, 7L, Instant.EPOCH, 0));

        assertThat(event.toString())
                .contains("eventType=UNKNOWN")
                .contains("eventIdLength=" + hostileId.length())
                .contains("sequence=7")
                .doesNotContain(SECRET_A, SECRET_B, "\n", "\r", "Authorization");
        assertThat(event.accountId()).isEqualTo("account-a");

        OfficialQqAccountEvent knownType = new OfficialQqAccountEvent(
                "account-a",
                new OfficialQqUnknownEvent(hostileId, "C2C_MESSAGE_CREATE",
                        8L, Instant.EPOCH, 0));
        assertThat(knownType.toString())
                .contains("eventType=C2C_MESSAGE_CREATE")
                .contains("eventIdLength=" + hostileId.length())
                .doesNotContain(SECRET_A, SECRET_B, "\n", "\r");
    }

    @Test
    void credentialFileInsideExplicitForbiddenRootFailsClosedAsTypedDown() throws Exception {
        Path repositoryRoot = temp.resolve("worktree");
        Files.createDirectories(repositoryRoot);
        Path forbiddenSecret = repositoryRoot.resolve("qq.secret");
        Files.writeString(forbiddenSecret, "forbidden-location-secret-sentinel",
                StandardCharsets.UTF_8);
        FakeFactory factory = new FakeFactory();
        List<OfficialQqAccountEvent> events = new CopyOnWriteArrayList<>();
        OfficialQqAccountConfig config = new OfficialQqAccountConfig(
                "policy-a", "app-policy", null, forbiddenSecret, true);

        try (OfficialQqAccountRegistry registry = new OfficialQqAccountRegistry(
                factory,
                events::add,
                Map.of(),
                OfficialQqCredentialPathPolicy.of(repositoryRoot))) {
            OfficialQqReconcileResult result = registry.reconcile(List.of(config));

            assertThat(result.failed()).containsExactly("policy-a");
            assertThat(factory.contexts).isEmpty();
            assertThat(registry.accountStatus("policy-a"))
                    .get()
                    .satisfies(status -> {
                        assertThat(status.state()).isEqualTo(OfficialQqAccountState.DOWN);
                        assertThat(status.errorKind()).isEqualTo(
                                OfficialQqAccountErrorKind.CREDENTIAL_FORBIDDEN_LOCATION);
                        assertThat(status.errorCode()).isEqualTo("FORBIDDEN_LOCATION");
                        assertThat(status.toString()).doesNotContain(
                                "forbidden-location-secret-sentinel");
                    });
        }
    }

    @Test
    void productionGatewayDiscoveryAcceptsOnlySingleShardOfficialWss() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertThat(OfficialQqProductionAccountRuntimeFactory.gatewayUriFromResponse(
                mapper.readTree("""
                        {"url":"wss://api.sgroup.qq.com/websocket","shards":1}
                        """)))
                .isEqualTo(URI.create("wss://api.sgroup.qq.com/websocket"));
        assertThat(OfficialQqProductionAccountRuntimeFactory.gatewayUriFromResponse(
                mapper.readTree("""
                        {"data":{"url":"wss://api.sgroup.qq.com/websocket","shards":1}}
                        """)))
                .isEqualTo(URI.create("wss://api.sgroup.qq.com/websocket"));

        assertThatThrownBy(() -> OfficialQqProductionAccountRuntimeFactory.gatewayUriFromResponse(
                mapper.readTree("""
                        {"url":"wss://api.sgroup.qq.com/websocket","shards":2}
                        """)))
                .isInstanceOfSatisfying(
                        io.mosire.bma3.qqofficial.gateway.GatewayPermanentException.class,
                        failure -> assertThat(failure.code()).isEqualTo("UNSUPPORTED_SHARD"));
        assertThatThrownBy(() -> OfficialQqProductionAccountRuntimeFactory.gatewayUriFromResponse(
                mapper.readTree("""
                        {"url":"wss://gateway.evil.example/websocket","shards":1}
                        """)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OfficialQqProductionAccountRuntimeFactory.gatewayUriFromResponse(
                mapper.readTree("""
                        {"url":"ws://api.sgroup.qq.com/websocket","shards":1}
                        """)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OfficialQqProductionAccountRuntimeFactory.gatewayUriFromResponse(
                mapper.readTree("""
                        {"shards":1}
                        """)))
                .isInstanceOfSatisfying(
                        io.mosire.bma3.qqofficial.gateway.GatewayPermanentException.class,
                        failure -> assertThat(failure.code()).isEqualTo("INVALID_GATEWAY_RESPONSE"));
    }

    @Test
    void registryApiClientIsActiveAccountScopedAndRemovedOnRetirement() {
        FakeFactory factory = new FakeFactory();
        factory.exposeApi = true;
        List<OfficialQqAccountEvent> events = new CopyOnWriteArrayList<>();
        Map<String, String> environment = Map.of("SECRET_A", SECRET_A, "SECRET_B", SECRET_B);

        OfficialQqAccountRegistry registry = new OfficialQqAccountRegistry(
                factory, events::add, environment,
                OfficialQqCredentialPathPolicy.none());
        try {
            registry.reconcile(List.of(
                    envConfig("account-a", "app-a", "SECRET_A"),
                    envConfig("account-b", "app-b", "SECRET_B")));

            OfficialQqApiClient apiA = registry.apiClient("account-a").orElseThrow();
            OfficialQqApiClient apiB = registry.apiClient("account-b").orElseThrow();
            assertThat(apiA).isNotSameAs(apiB);
            assertThat(registry.apiClient("missing-account")).isEmpty();

            registry.reconcile(List.of(envConfig("account-a", "app-a", "SECRET_A")));
            assertThat(registry.apiClient("account-a")).isPresent();
            assertThat(registry.apiClient("account-b")).isEmpty();
        } finally {
            registry.close();
        }
        assertThat(registry.apiClient("account-a")).isEmpty();
    }

    @Test
    void permanentGatewayDiscoveryFailureBecomesDownAndSchedulesNoRetry() {
        AtomicInteger scheduled = new AtomicInteger();
        GatewayScheduler scheduler = (delay, action) -> {
            scheduled.incrementAndGet();
            return () -> {
            };
        };
        GatewayPermanentException permanent = new GatewayPermanentException("UNSUPPORTED_SHARD");
        assertThat(GatewayFailureClassifier.defaults().classify(permanent).permanent()).isTrue();

        try (OfficialQqGatewayClient gateway = OfficialQqGatewayClient.builder(
                        GatewayOptions.defaults("account-a"),
                        () -> "unused-token",
                        () -> {
                            throw permanent;
                        })
                .scheduler(scheduler)
                .transportFactory((uri, listener) -> {
                    throw new AssertionError("must not open a transport after permanent discovery failure");
                })
                .build()) {
            gateway.start();

            assertThat(gateway.state()).isEqualTo(GatewayState.DOWN);
            assertThat(gateway.status().terminal()).isTrue();
            assertThat(gateway.status().lastErrorCode()).isEqualTo("UNSUPPORTED_SHARD");
            assertThat(scheduled).hasValue(0);
        }
    }

    @Test
    void productionRuntimeExposesAccountApiOnlyUntilClose() throws Exception {
        OfficialQqCredential credential = new OfficialQqCredentialResolver(
                "account-a", "app-a", "SECRET_A", null, Map.of("SECRET_A", SECRET_A))
                .resolve();
        OfficialQqAccountClientContext context = new OfficialQqAccountClientContext(
                envConfig("account-a", "app-a", "SECRET_A"),
                credential,
                OfficialQqAccountEndpoints.OFFICIAL_API_BASE_URI,
                dispatch -> {
                },
                status -> {
                });
        OfficialQqAccountClient runtime =
                new OfficialQqProductionAccountRuntimeFactory().create(context);
        try {
            assertThat(runtime.apiClient()).containsInstanceOf(OfficialQqApiClient.class);
            assertThat(runtime.toString()).doesNotContain(SECRET_A);
        } finally {
            runtime.close();
            credential.close();
        }
        assertThat(runtime.apiClient()).isEmpty();
    }

    @Test
    void productionFactoryRejectsInjectedNonOfficialBaseBeforeItCanConnect() throws Exception {
        OfficialQqCredential credential = new OfficialQqCredentialResolver(
                "account-a", "app-a", "SECRET_A", null, Map.of("SECRET_A", SECRET_A))
                .resolve();
        try {
            OfficialQqAccountClientContext context = new OfficialQqAccountClientContext(
                    envConfig("account-a", "app-a", "SECRET_A"),
                    credential,
                    URI.create("http://127.0.0.1:12345"),
                    dispatch -> {
                    },
                    status -> {
                    });
            assertThatThrownBy(() -> new OfficialQqProductionAccountRuntimeFactory()
                    .create(context))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("官方");
        } finally {
            credential.close();
        }
    }

    private static OfficialQqAccountConfig envConfig(
            String accountId, String appId, String envName) {
        return new OfficialQqAccountConfig(
                accountId, appId, envName, (Path) null, true);
    }

    private static OfficialQqDispatch unknown(String eventId) {
        return new OfficialQqUnknownEvent(eventId, "UNKNOWN_EVENT", 1L, Instant.EPOCH, 0);
    }

    private static String secretOf(OfficialQqCredential credential) {
        char[] secret = credential.copySecret();
        try {
            return new String(secret);
        } finally {
            java.util.Arrays.fill(secret, '\0');
        }
    }

    private static final class BlockingCloseFactory implements OfficialQqAccountClientFactory {
        volatile BlockingCloseClient client;

        @Override
        public OfficialQqAccountClient create(OfficialQqAccountClientContext context) {
            BlockingCloseClient created = new BlockingCloseClient(context);
            client = created;
            return created;
        }

        BlockingCloseClient client() {
            return client;
        }
    }

    private static final class BlockingCloseClient implements OfficialQqAccountClient {
        private final OfficialQqAccountClientContext context;
        private final Object eventDeliveryLock = new Object();
        final CountDownLatch closeAttempted = new CountDownLatch(1);
        volatile boolean closed;

        BlockingCloseClient(OfficialQqAccountClientContext context) {
            this.context = context;
        }

        @Override
        public String accountId() {
            return context.config().accountId();
        }

        @Override
        public void start() {
            context.statusSink().accept(new OfficialQqAccountRuntimeStatus(
                    OfficialQqAccountState.READY, true, false, Instant.EPOCH,
                    Instant.EPOCH, null, 1L, 0, OfficialQqAccountErrorKind.NONE,
                    "", "", ""));
        }

        @Override
        public OfficialQqAccountRuntimeStatus status() {
            return new OfficialQqAccountRuntimeStatus(
                    closed ? OfficialQqAccountState.CLOSED : OfficialQqAccountState.READY,
                    !closed, closed, Instant.EPOCH, Instant.EPOCH, null, 1L, 0,
                    OfficialQqAccountErrorKind.NONE, "", "", "");
        }

        @Override
        public void close() {
            closeAttempted.countDown();
            synchronized (eventDeliveryLock) {
                closed = true;
            }
        }

        void emitHeldEvent() {
            synchronized (eventDeliveryLock) {
                context.dispatchSink().accept(unknown("held-event"));
            }
        }
    }

    private static final class FakeFactory implements OfficialQqAccountClientFactory {
        final List<OfficialQqAccountClientContext> contexts = new CopyOnWriteArrayList<>();
        final List<String> operations = new CopyOnWriteArrayList<>();
        final List<FakeClient> clients = new CopyOnWriteArrayList<>();
        final ConcurrentHashMap<String, FakeClient> latestClients = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, String> accountIdOverrides = new ConcurrentHashMap<>();
        final java.util.Set<String> failCreate = ConcurrentHashMap.newKeySet();
        final java.util.Set<String> failStart = ConcurrentHashMap.newKeySet();
        volatile boolean exposeApi;

        @Override
        public OfficialQqAccountClient create(OfficialQqAccountClientContext context) {
            String accountId = context.config().accountId();
            contexts.add(context);
            operations.add("create:" + accountId);
            if (failCreate.contains(accountId)) {
                throw new IllegalStateException(
                        "factory failure with " + SECRET_A + " / " + SECRET_B);
            }
            OfficialQqTokenManager tokenManager = null;
            OfficialQqApiClient apiClient = null;
            if (exposeApi) {
                tokenManager = OfficialQqTokenManager.production(
                        accountId,
                        context.credential(),
                        HttpClient.newHttpClient(),
                        new ObjectMapper(),
                        Clock.systemUTC(),
                        Duration.ofSeconds(5));
                apiClient = OfficialQqApiClient.production(
                        accountId,
                        tokenManager,
                        HttpClient.newHttpClient(),
                        new ObjectMapper(),
                        Duration.ofSeconds(5));
            }
            FakeClient client = new FakeClient(
                    this,
                    accountIdOverrides.getOrDefault(accountId, accountId),
                    context,
                    failStart.contains(accountId),
                    apiClient,
                    tokenManager);
            clients.add(client);
            latestClients.put(accountId, client);
            return client;
        }

        FakeClient client(String accountId) {
            FakeClient client = latestClients.get(accountId);
            if (client == null) {
                throw new AssertionError("no fake client for " + accountId);
            }
            return client;
        }
    }

    private static final class FakeClient implements OfficialQqAccountClient {
        private final FakeFactory factory;
        private final String accountId;
        private final OfficialQqAccountClientContext context;
        private final boolean failStart;
        private final OfficialQqApiClient exposedApiClient;
        private final OfficialQqTokenManager exposedTokenManager;
        private final AtomicBoolean started = new AtomicBoolean();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile int sinkFailures;
        volatile int replays;
        private volatile OfficialQqAccountRuntimeStatus status = new OfficialQqAccountRuntimeStatus(
                OfficialQqAccountState.CONNECTING, false, false, Instant.EPOCH,
                null, null, null, 0, OfficialQqAccountErrorKind.NONE, "", "", "");

        FakeClient(
                FakeFactory factory,
                String accountId,
                OfficialQqAccountClientContext context,
                boolean failStart,
                OfficialQqApiClient exposedApiClient,
                OfficialQqTokenManager exposedTokenManager) {
            this.factory = factory;
            this.accountId = accountId;
            this.context = context;
            this.failStart = failStart;
            this.exposedApiClient = exposedApiClient;
            this.exposedTokenManager = exposedTokenManager;
        }

        @Override
        public String accountId() {
            return accountId;
        }

        @Override
        public void start() {
            if (failStart) {
                throw new IllegalStateException("start failure with " + SECRET_C);
            }
            started.set(true);
            factory.operations.add("start:" + accountId);
            context.statusSink().accept(new OfficialQqAccountRuntimeStatus(
                    OfficialQqAccountState.READY, true, false, Instant.EPOCH,
                    Instant.EPOCH, null, 1L, 0, OfficialQqAccountErrorKind.NONE,
                    "", "", "trace-safe"));
            status = new OfficialQqAccountRuntimeStatus(
                    OfficialQqAccountState.READY, true, false, Instant.EPOCH,
                    Instant.EPOCH, null, 1L, 0, OfficialQqAccountErrorKind.NONE,
                    "", "", "trace-safe");
        }

        @Override
        public OfficialQqAccountRuntimeStatus status() {
            return status;
        }

        @Override
        public Optional<OfficialQqApiClient> apiClient() {
            return closed.get() || exposedApiClient == null
                    ? Optional.empty() : Optional.of(exposedApiClient);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                factory.operations.add("close:" + accountId);
                if (exposedTokenManager != null) {
                    exposedTokenManager.close();
                }
                status = new OfficialQqAccountRuntimeStatus(
                        OfficialQqAccountState.CLOSED, false, true, Instant.EPOCH,
                        null, null, null, 0, OfficialQqAccountErrorKind.NONE, "", "", "");
            }
        }

        void emit(OfficialQqDispatch dispatch) {
            context.dispatchSink().accept(dispatch);
        }

        void emitWithReplay(OfficialQqDispatch dispatch) {
            try {
                context.dispatchSink().accept(dispatch);
            } catch (RuntimeException failure) {
                sinkFailures++;
                // Models the Gateway's replay path: the failure must reach it, then the same
                // committed dispatch is delivered again after the sink is healthy.
                context.dispatchSink().accept(dispatch);
                replays++;
            }
        }
    }
}
