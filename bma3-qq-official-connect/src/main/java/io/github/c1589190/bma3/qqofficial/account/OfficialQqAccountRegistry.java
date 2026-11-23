package io.github.c1589190.bma3.qqofficial.account;

import io.github.c1589190.bma3.core.account.AccountId;
import io.github.c1589190.bma3.qqofficial.api.OfficialQqApiClient;
import io.github.c1589190.bma3.qqofficial.api.OfficialQqApiException;
import io.github.c1589190.bma3.qqofficial.api.OfficialQqErrorKind;
import io.github.c1589190.bma3.qqofficial.credential.OfficialQqCredential;
import io.github.c1589190.bma3.qqofficial.credential.OfficialQqCredentialException;
import io.github.c1589190.bma3.qqofficial.credential.OfficialQqCredentialPathPolicy;
import io.github.c1589190.bma3.qqofficial.credential.OfficialQqCredentialResolver;
import io.github.c1589190.bma3.qqofficial.event.OfficialQqDispatch;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Desired-state registry for official QQ bot accounts.
 *
 * <p>One reconcile call is a complete desired-state transaction. Accounts absent from the list are
 * retired; disabled accounts never resolve credentials or create runtime clients; a changed
 * account has its previous runtime closed <em>before</em> its replacement is started. Each
 * account has at most one holder in the active generation, so a buggy reconnect timer cannot create
 * two live gateways for the same account id.</p>
 *
 * <p>Credential values are resolved only from the configured env/file reference. A digest of the
 * resolved secret is retained in memory solely to detect rotation on the next reconcile. The
 * digest and secret value are never placed in status, logs or reconcile results. Every applied
 * reconcile compares even unchanged accounts' secret fingerprints, so replacing a secret file
 * restarts that account without touching other accounts.</p>
 *
 * <p><b>Concurrency contract:</b> reconcile/remove/close and runtime retirement use one writer
 * lock, but every read path ({@link #accountStatus}, {@link #accountStatuses}, {@link #client},
 * {@link #desiredConfigs}) reads an immutable published snapshot without taking that lock. Runtime
 * {@code close()} is called only after the holder is marked non-accepting and the published
 * snapshot no longer routes to it. This avoids the cross-thread deadlock where a Gateway close
 * waits for an event sink while that sink asks the registry for status.</p>
 *
 * <p>Failures are isolated: a bad factory, credential or start exception records a DOWN status and
 * continues with the remaining accounts. The registry itself never talks to the network; production
 * and fake behavior is supplied by an {@link OfficialQqAccountClientFactory}.</p>
 */
public final class OfficialQqAccountRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OfficialQqAccountRegistry.class);

    /** Production default; test/app-root code may explicitly inject another base URI. */
    public static final URI OFFICIAL_API_BASE_URI =
            OfficialQqAccountEndpoints.OFFICIAL_API_BASE_URI;

    private final OfficialQqAccountClientFactory clientFactory;
    private final OfficialQqAccountEventSink eventSink;
    private final Supplier<Map<String, String>> environmentSource;
    private final OfficialQqCredentialPathPolicy credentialPathPolicy;
    private final URI apiBaseUri;
    private final Clock clock;
    /** Serializes writers only. Never acquired by read-only account status paths. */
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final AtomicLong generationSequence = new AtomicLong();

    /** Guarded by {@link #lifecycleLock}. Insertion order is desired-list order. */
    private final Map<String, OfficialQqAccountConfig> desired = new LinkedHashMap<>();
    /** Guarded by {@link #lifecycleLock}. At most one holder per account id. */
    private final Map<String, Holder> active = new LinkedHashMap<>();
    /** Guarded by {@link #lifecycleLock}. Disabled/rejected/failed/closed snapshots. */
    private final Map<String, OfficialQqAccountStatus> statusCache = new LinkedHashMap<>();
    /** Immutable, atomically replaced read model. No read path takes a lock. */
    private volatile RegistrySnapshot published = RegistrySnapshot.empty();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Production entry point. The app root must pass the actual Git worktree root(s) explicitly;
     * the registry never guesses one from the process cwd, and the runtime factory always uses the
     * fixed official API origin.
     */
    public OfficialQqAccountRegistry(
            OfficialQqAccountEventSink eventSink,
            OfficialQqCredentialPathPolicy credentialPathPolicy) {
        this(new OfficialQqProductionAccountRuntimeFactory(), eventSink, System::getenv,
                Objects.requireNonNull(credentialPathPolicy, "credentialPathPolicy"),
                OFFICIAL_API_BASE_URI, Clock.systemUTC());
    }

    /** Production entry point using the app's resolved environment map. */
    public OfficialQqAccountRegistry(
            OfficialQqAccountEventSink eventSink,
            OfficialQqCredentialPathPolicy credentialPathPolicy,
            Map<String, String> environment) {
        this(new OfficialQqProductionAccountRuntimeFactory(), eventSink,
                fixedEnvironment(environment),
                Objects.requireNonNull(credentialPathPolicy, "credentialPathPolicy"),
                OFFICIAL_API_BASE_URI, Clock.systemUTC());
    }

    /** Production entry point using the app's environment supplier. */
    public OfficialQqAccountRegistry(
            OfficialQqAccountEventSink eventSink,
            OfficialQqCredentialPathPolicy credentialPathPolicy,
            Supplier<Map<String, String>> environmentSource) {
        this(new OfficialQqProductionAccountRuntimeFactory(), eventSink, environmentSource,
                Objects.requireNonNull(credentialPathPolicy, "credentialPathPolicy"),
                OFFICIAL_API_BASE_URI, Clock.systemUTC());
    }

    /** Fake-factory seam with an explicit repository-path policy for credential-file tests. */
    public OfficialQqAccountRegistry(
            OfficialQqAccountClientFactory clientFactory,
            OfficialQqAccountEventSink eventSink,
            Map<String, String> environment,
            OfficialQqCredentialPathPolicy credentialPathPolicy) {
        this(clientFactory, eventSink, fixedEnvironment(environment),
                credentialPathPolicy, OFFICIAL_API_BASE_URI, Clock.systemUTC());
    }

    /** Fake-factory seam using an app-supplied environment source. */
    public OfficialQqAccountRegistry(
            OfficialQqAccountClientFactory clientFactory,
            OfficialQqAccountEventSink eventSink,
            Supplier<Map<String, String>> environmentSource,
            OfficialQqCredentialPathPolicy credentialPathPolicy) {
        this(clientFactory, eventSink, environmentSource, credentialPathPolicy,
                OFFICIAL_API_BASE_URI, Clock.systemUTC());
    }

    /** Test seam: explicit non-official base only for fake/local integration within this package. */
    OfficialQqAccountRegistry(
            OfficialQqAccountClientFactory clientFactory,
            OfficialQqAccountEventSink eventSink,
            Supplier<Map<String, String>> environmentSource,
            URI apiBaseUri) {
        this(clientFactory, eventSink, environmentSource,
                OfficialQqCredentialPathPolicy.none(), apiBaseUri, Clock.systemUTC());
    }

    OfficialQqAccountRegistry(
            OfficialQqAccountClientFactory clientFactory,
            OfficialQqAccountEventSink eventSink,
            Supplier<Map<String, String>> environmentSource,
            OfficialQqCredentialPathPolicy credentialPathPolicy,
            URI apiBaseUri,
            Clock clock) {
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
        this.eventSink = eventSink == null ? OfficialQqAccountEventSink.noop() : eventSink;
        this.environmentSource = environmentSource == null ? Map::of : environmentSource;
        this.credentialPathPolicy = credentialPathPolicy == null
                ? OfficialQqCredentialPathPolicy.none() : credentialPathPolicy;
        this.apiBaseUri = OfficialQqAccountEndpoints.requireApiBaseUri(apiBaseUri);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Applies a complete desired account set.
     *
     * @return a safe diff of successful, unchanged, disabled and failed account ids
     * @throws IllegalStateException only when called after {@link #close()}; individual account
     *     failures are reported in {@link OfficialQqReconcileResult#failed()} instead
     */
    public OfficialQqReconcileResult reconcile(List<OfficialQqAccountConfig> desiredAccounts) {
        if (closed.get()) {
            throw new IllegalStateException("official account registry 已关闭");
        }
        List<OfficialQqAccountConfig> input = desiredAccounts == null
                ? List.of()
                : new ArrayList<>(desiredAccounts);
        ParsedDesired parsed = parseDesired(input);

        lifecycleLock.lock();
        try {
            if (closed.get()) {
                throw new IllegalStateException("official account registry 已关闭");
            }
            EnvironmentSnapshot environment = readEnvironment();
            return reconcileLocked(parsed, environment.values(), environment.failure());
        } finally {
            lifecycleLock.unlock();
        }
    }

    private OfficialQqReconcileResult reconcileLocked(
            ParsedDesired parsed,
            Map<String, String> environment,
            RuntimeException environmentFailure) {
        LinkedHashSet<String> added = new LinkedHashSet<>();
        LinkedHashSet<String> removed = new LinkedHashSet<>();
        LinkedHashSet<String> restarted = new LinkedHashSet<>();
        LinkedHashSet<String> disabled = new LinkedHashSet<>();
        LinkedHashSet<String> unchanged = new LinkedHashSet<>();
        LinkedHashSet<String> rejected = new LinkedHashSet<>(parsed.rejected());
        LinkedHashSet<String> failed = new LinkedHashSet<>();

        Map<String, OfficialQqAccountConfig> previousDesired = new LinkedHashMap<>(desired);

        // Retire accounts that disappeared from the desired set, including holders that had no
        // successful desired entry (e.g. a previous start failure still represented by status).
        for (String accountId : previousDesired.keySet()) {
            if (!parsed.targets().containsKey(accountId)) {
                removed.add(accountId);
            }
        }
        for (String accountId : new ArrayList<>(active.keySet())) {
            if (!parsed.targets().containsKey(accountId)) {
                removed.add(accountId);
            }
        }
        for (String accountId : removed) {
            Holder holder = active.get(accountId);
            if (holder != null) {
                holder.markRetired();
                active.remove(accountId, holder);
            }
            statusCache.remove(accountId);
            publishLocked();
            stopRetired(holder);
        }

        // Duplicate ids are fail-closed: neither copy may own a runtime, and any old runtime for
        // that ambiguous id is retired before the next reconcile can add it again.
        for (String accountId : parsed.duplicateIds()) {
            Holder holder = active.get(accountId);
            if (holder != null) {
                holder.markRetired();
                active.remove(accountId, holder);
            }
            statusCache.put(accountId, downStatus(
                    accountId, false, OfficialQqAccountErrorKind.CONFIGURATION,
                    "DUPLICATE_ACCOUNT_ID", "", ""));
            publishLocked();
            stopRetired(holder);
        }

        desired.clear();
        desired.putAll(parsed.targets());
        publishLocked();

        for (OfficialQqAccountConfig config : parsed.targets().values()) {
            if (closed.get()) {
                break;
            }
            String accountId = config.accountId();
            Holder current = active.get(accountId);
            if (!config.enabled()) {
                if (current != null) {
                    disabled.add(accountId);
                } else if (previousDesired.get(accountId) != null
                        && previousDesired.get(accountId).enabled()) {
                    disabled.add(accountId);
                }
                retireWithStatus(current, disabledStatus(config));
                continue;
            }

            if (environmentFailure != null) {
                failed.add(accountId);
                retireWithStatus(current, downStatus(config,
                        OfficialQqAccountErrorKind.CONFIGURATION,
                        "ENVIRONMENT_UNAVAILABLE", errorType(environmentFailure), ""));
                LOG.warn("event=qq_official_account_resolve_failed accountId={} source=ENV "
                                + "kind=CONFIGURATION type={}",
                        accountId, errorType(environmentFailure));
                continue;
            }

            CredentialSnapshot credential;
            try {
                credential = resolveCredential(config, environment);
            } catch (OfficialQqCredentialException failure) {
                failed.add(accountId);
                OfficialQqAccountErrorKind kind = classify(failure);
                retireWithStatus(current, downStatus(config, kind,
                        failure.reason().name(), errorType(failure), ""));
                LOG.warn("event=qq_official_account_resolve_failed accountId={} source={} "
                                + "kind={} type={}",
                        accountId, config.secretReferenceKind(), kind, errorType(failure));
                continue;
            } catch (RuntimeException failure) {
                failed.add(accountId);
                retireWithStatus(current, downStatus(config,
                        OfficialQqAccountErrorKind.INTERNAL,
                        "CREDENTIAL_RESOLUTION_FAILED", errorType(failure), ""));
                LOG.warn("event=qq_official_account_resolve_failed accountId={} source={} "
                                + "kind=INTERNAL type={}",
                        accountId, config.secretReferenceKind(), errorType(failure));
                continue;
            }

            boolean hadRuntime = current != null;
            if (current != null
                    && current.config().equals(config)
                    && current.secretFingerprint().equals(credential.fingerprint())) {
                credential.credential().close();
                unchanged.add(accountId);
                continue;
            }

            // Publish a CONNECTING status and detach the old generation before waiting for close.
            // A sink running inside the old Gateway may query status while close waits for it, so
            // no read path is allowed to take lifecycleLock.
            retireWithStatus(current, connectingStatus(config));
            if (startLocked(config, credential)) {
                if (hadRuntime) {
                    restarted.add(accountId);
                } else {
                    added.add(accountId);
                }
            } else {
                failed.add(accountId);
            }
        }

        if (closed.get() && !active.isEmpty()) {
            // close() is waiting for this transaction. Detach every generation first, publish the
            // no-route read model, then wait for the runtime close without any read lock held.
            List<Holder> retiring = new ArrayList<>(active.values());
            for (Holder holder : retiring) {
                holder.markRetired();
            }
            active.clear();
            publishLocked();
            for (Holder holder : retiring) {
                holder.stop();
            }
        }

        return new OfficialQqReconcileResult(
                List.copyOf(added),
                List.copyOf(removed),
                List.copyOf(restarted),
                List.copyOf(disabled),
                List.copyOf(unchanged),
                List.copyOf(rejected),
                List.copyOf(failed));
    }

    private boolean startLocked(
            OfficialQqAccountConfig config,
            CredentialSnapshot credential) {
        Holder holder = new Holder(
                config,
                credential.credential(),
                credential.fingerprint(),
                generationSequence.incrementAndGet());
        active.put(config.accountId(), holder);
        statusCache.remove(config.accountId());
        publishLocked();
        OfficialQqAccountClient client = null;
        try {
            if (closed.get()) {
                return failStart(holder, OfficialQqAccountErrorKind.PROTOCOL,
                        "REGISTRY_CLOSED_BEFORE_CREATE", null);
            }
            OfficialQqAccountClientContext context = new OfficialQqAccountClientContext(
                    config,
                    credential.credential(),
                    apiBaseUri,
                    holder::dispatch,
                    holder::acceptStatus);
            client = clientFactory.create(context);
            if (client == null) {
                return failStart(holder, OfficialQqAccountErrorKind.INTERNAL,
                        "FACTORY_RETURNED_NULL", null);
            }
            holder.attach(client);
            if (closed.get()) {
                return failStart(holder, OfficialQqAccountErrorKind.PROTOCOL,
                        "REGISTRY_CLOSED_BEFORE_START", null);
            }
            String actualId = client.accountId();
            if (!config.accountId().equals(actualId)) {
                return failStart(holder, OfficialQqAccountErrorKind.ROUTING,
                        "ACCOUNT_ID_MISMATCH", null);
            }
            client.start();
            if (closed.get()) {
                return failStart(holder, OfficialQqAccountErrorKind.PROTOCOL,
                        "REGISTRY_CLOSED_DURING_START", null);
            }
            holder.acceptStatus(snapshot(holder));
            publishLocked();
            LOG.info("event=qq_official_account_started accountId={} shardIndex={} "
                            + "shardCount={} secretSource={}",
                    config.accountId(), config.shardIndex(), config.shardCount(),
                    config.secretReferenceKind());
            return true;
        } catch (Exception failure) {
            return failStart(holder, classify(failure), startErrorCode(failure), failure);
        }
    }

    private boolean failStart(
            Holder holder,
            OfficialQqAccountErrorKind kind,
            String code,
            Throwable failure) {
        String accountId = holder.config().accountId();
        holder.markRetired();
        active.remove(accountId, holder);
        statusCache.put(accountId, downStatus(holder.config(), kind, code,
                errorType(failure), ""));
        publishLocked();
        holder.stop();
        LOG.warn("event=qq_official_account_start_failed accountId={} kind={} code={} type={}",
                accountId, kind, code, errorType(failure));
        return false;
    }

    /**
     * Detach one runtime from the read model, publish the replacement status, then wait for the
     * runtime close. The returned method never holds the lifecycle lock while waiting for an event
     * sink, because {@link #accountStatus} and friends never take that lock.
     */
    private void retireWithStatus(Holder holder, OfficialQqAccountStatus status) {
        if (holder != null) {
            holder.markRetired();
            active.remove(holder.config().accountId(), holder);
        }
        statusCache.put(status.accountId(), status);
        publishLocked();
        stopRetired(holder);
    }

    private void stopRetired(Holder holder) {
        if (holder != null) {
            holder.stop();
        }
    }

    /** Resolves and fingerprints one secret without ever turning it into a diagnostic string. */
    private CredentialSnapshot resolveCredential(
            OfficialQqAccountConfig config, Map<String, String> environment)
            throws OfficialQqCredentialException {
        OfficialQqCredential credential = new OfficialQqCredentialResolver(
                config.accountId(),
                config.appId(),
                config.appSecretEnv(),
                config.appSecretFile(),
                environment,
                credentialPathPolicy)
                .resolve();
        try {
            return new CredentialSnapshot(credential, fingerprint(credential));
        } catch (RuntimeException failure) {
            credential.close();
            throw failure;
        }
    }

    private static String fingerprint(OfficialQqCredential credential) {
        char[] secret = credential.copySecret();
        try {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 不可用", impossible);
            }
            for (char ch : secret) {
                digest.update((byte) (ch >>> 8));
                digest.update((byte) ch);
            }
            return HexFormat.of().formatHex(digest.digest());
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    /**
     * Returns a safe status snapshot. The read model is immutable and lock-free; runtime status is
     * cached by the status-listener path, so a sink query cannot block on a Gateway close.
     */
    public List<OfficialQqAccountStatus> accountStatuses() {
        RegistrySnapshot snapshot = published;
        LinkedHashSet<String> ids = new LinkedHashSet<>(snapshot.desired().keySet());
        ids.addAll(snapshot.active().keySet());
        ids.addAll(snapshot.statuses().keySet());
        List<OfficialQqAccountStatus> result = new ArrayList<>(ids.size());
        for (String accountId : ids) {
            statusFor(snapshot, accountId).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    public Optional<OfficialQqAccountStatus> accountStatus(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Optional.empty();
        }
        return statusFor(published, accountId);
    }

    private Optional<OfficialQqAccountStatus> statusFor(
            RegistrySnapshot snapshot, String accountId) {
        Holder holder = snapshot.active().get(accountId);
        OfficialQqAccountConfig config = snapshot.desired().get(accountId);
        if (holder != null && holder.isAccepting()) {
            if (config == null) {
                config = holder.config();
            }
            return Optional.of(statusOf(config, holder.statusSnapshot()));
        }
        OfficialQqAccountStatus cached = snapshot.statuses().get(accountId);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (config != null) {
            return Optional.of(stoppedStatus(config));
        }
        return Optional.empty();
    }

    private OfficialQqAccountRuntimeStatus snapshot(Holder holder) {
        OfficialQqAccountClient client = holder.client();
        if (client != null) {
            try {
                OfficialQqAccountRuntimeStatus runtime = client.status();
                if (runtime != null) {
                    holder.acceptStatus(runtime);
                    return runtime;
                }
            } catch (RuntimeException failure) {
                LOG.warn("event=qq_official_account_status_failed accountId={} type={}",
                        holder.config().accountId(), errorType(failure));
                return OfficialQqAccountRuntimeStatus.down(
                        OfficialQqAccountErrorKind.INTERNAL, "STATUS_FAILED",
                        errorType(failure));
            }
        }
        return holder.statusSnapshot();
    }

    private OfficialQqAccountStatus statusOf(
            OfficialQqAccountConfig config, OfficialQqAccountRuntimeStatus runtime) {
        return new OfficialQqAccountStatus(
                config.accountId(),
                runtime.state(),
                config.enabled(),
                runtime.connected(),
                runtime.terminal(),
                config.shardIndex(),
                config.shardCount(),
                runtime.updatedAt(),
                runtime.lastConnectedAt(),
                runtime.lastEventAt(),
                runtime.lastSequence(),
                runtime.reconnectAttempts(),
                runtime.errorKind(),
                runtime.errorCode(),
                runtime.errorType(),
                runtime.traceId());
    }

    private OfficialQqAccountStatus disabledStatus(OfficialQqAccountConfig config) {
        return new OfficialQqAccountStatus(
                config.accountId(), OfficialQqAccountState.DISABLED, false, false, false,
                config.shardIndex(), config.shardCount(), clock.instant(), null, null, null, 0,
                OfficialQqAccountErrorKind.NONE, "", "", "");
    }

    private OfficialQqAccountStatus connectingStatus(OfficialQqAccountConfig config) {
        return new OfficialQqAccountStatus(
                config.accountId(), OfficialQqAccountState.CONNECTING, config.enabled(),
                false, false, config.shardIndex(), config.shardCount(), clock.instant(), null,
                null, null, 0, OfficialQqAccountErrorKind.NONE, "", "", "");
    }

    private OfficialQqAccountStatus stoppedStatus(OfficialQqAccountConfig config) {
        return new OfficialQqAccountStatus(
                config.accountId(), OfficialQqAccountState.STOPPED, config.enabled(), false,
                false, config.shardIndex(), config.shardCount(), clock.instant(), null, null,
                null, 0, OfficialQqAccountErrorKind.NONE, "", "", "");
    }

    private OfficialQqAccountStatus downStatus(
            OfficialQqAccountConfig config,
            OfficialQqAccountErrorKind kind,
            String code,
            String type,
            String traceId) {
        return new OfficialQqAccountStatus(
                config.accountId(), OfficialQqAccountState.DOWN, config.enabled(), false, true,
                config.shardIndex(), config.shardCount(), clock.instant(), null, null, null, 0,
                kind, code, type, traceId);
    }

    private OfficialQqAccountStatus downStatus(
            String accountId,
            boolean enabled,
            OfficialQqAccountErrorKind kind,
            String code,
            String type,
            String traceId) {
        return new OfficialQqAccountStatus(
                accountId, OfficialQqAccountState.DOWN, enabled, false, true, 0, 1,
                clock.instant(), null, null, null, 0, kind, code, type, traceId);
    }

    /** Desired account configs in caller order, including disabled and failed accounts. */
    public List<OfficialQqAccountConfig> desiredConfigs() {
        return List.copyOf(published.desired().values());
    }

    /** Active runtime only when it belongs to the requested account and has not been retired. */
    public Optional<OfficialQqAccountClient> client(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Optional.empty();
        }
        Holder holder = published.active().get(accountId);
        if (holder == null || !holder.isAccepting()) {
            return Optional.empty();
        }
        return Optional.ofNullable(holder.client());
    }

    public Optional<OfficialQqAccountClient> client(AccountId accountId) {
        return accountId == null ? Optional.empty() : client(accountId.value());
    }

    /**
     * Returns the active holder's account-scoped OpenAPI client, or empty when the account is
     * unknown, disabled, retired, closing, or its runtime does not expose one. Presence is an
     * active-runtime/lifetime guarantee, not a Gateway READY guarantee: API calls can still fail
     * with typed errors and must never be routed to another account's client.
     */
    public Optional<OfficialQqApiClient> apiClient(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Optional.empty();
        }
        Holder holder = published.active().get(accountId);
        if (holder == null || !holder.isAccepting() || closed.get()) {
            return Optional.empty();
        }
        OfficialQqAccountClient runtime = holder.client();
        return runtime == null ? Optional.empty() : runtime.apiClient();
    }

    public Optional<OfficialQqApiClient> apiClient(AccountId accountId) {
        return accountId == null ? Optional.empty() : apiClient(accountId.value());
    }

    public boolean connected(String accountId) {
        return accountStatus(accountId).map(OfficialQqAccountStatus::connected).orElse(false);
    }

    public boolean isClosed() {
        return closed.get();
    }

    private EnvironmentSnapshot readEnvironment() {
        try {
            Map<String, String> value = environmentSource.get();
            return new EnvironmentSnapshot(value == null ? Map.of() : Map.copyOf(value), null);
        } catch (RuntimeException failure) {
            return new EnvironmentSnapshot(Map.of(), failure);
        }
    }

    private void publishLocked() {
        published = new RegistrySnapshot(
                immutableMap(desired),
                immutableMap(active),
                immutableMap(statusCache));
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Supplier<Map<String, String>> fixedEnvironment(Map<String, String> environment) {
        Map<String, String> copy = environment == null ? Map.of() : Map.copyOf(environment);
        return () -> copy;
    }

    private static ParsedDesired parseDesired(List<OfficialQqAccountConfig> input) {
        LinkedHashMap<String, OfficialQqAccountConfig> targets = new LinkedHashMap<>();
        LinkedHashSet<String> rejected = new LinkedHashSet<>();
        LinkedHashSet<String> duplicates = new LinkedHashSet<>();
        for (OfficialQqAccountConfig config : input) {
            if (config == null) {
                rejected.add("");
                continue;
            }
            String accountId = config.accountId();
            if (duplicates.contains(accountId)) {
                continue;
            }
            if (targets.containsKey(accountId)) {
                targets.remove(accountId);
                duplicates.add(accountId);
                rejected.add(accountId);
            } else {
                targets.put(accountId, config);
            }
        }
        return new ParsedDesired(targets, rejected, duplicates);
    }

    private static OfficialQqAccountErrorKind classify(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof OfficialQqCredentialException credential) {
            return switch (credential.reason()) {
                case CONFIGURATION -> OfficialQqAccountErrorKind.CONFIGURATION;
                case MISSING -> OfficialQqAccountErrorKind.CREDENTIAL_MISSING;
                case PERMISSION -> OfficialQqAccountErrorKind.CREDENTIAL_PERMISSION;
                case FORBIDDEN_LOCATION ->
                        OfficialQqAccountErrorKind.CREDENTIAL_FORBIDDEN_LOCATION;
                case IO -> OfficialQqAccountErrorKind.CREDENTIAL_IO;
            };
        }
        if (cause instanceof OfficialQqApiException api) {
            return apiKind(api.kind());
        }
        if (cause instanceof java.net.http.HttpTimeoutException
                || cause instanceof java.util.concurrent.TimeoutException) {
            return OfficialQqAccountErrorKind.TIMEOUT;
        }
        if (cause instanceof java.io.IOException) {
            return OfficialQqAccountErrorKind.NETWORK;
        }
        if (cause instanceof IllegalArgumentException) {
            return OfficialQqAccountErrorKind.CONFIGURATION;
        }
        return OfficialQqAccountErrorKind.INTERNAL;
    }

    private static OfficialQqAccountErrorKind apiKind(OfficialQqErrorKind kind) {
        return switch (kind) {
            case CONFIGURATION -> OfficialQqAccountErrorKind.CONFIGURATION;
            case NETWORK -> OfficialQqAccountErrorKind.NETWORK;
            case TIMEOUT -> OfficialQqAccountErrorKind.TIMEOUT;
            case HTTP -> OfficialQqAccountErrorKind.HTTP;
            case AUTHENTICATION -> OfficialQqAccountErrorKind.AUTHENTICATION;
            case PERMISSION -> OfficialQqAccountErrorKind.PERMISSION;
            case NOT_FOUND -> OfficialQqAccountErrorKind.NOT_FOUND;
            case RATE_LIMITED -> OfficialQqAccountErrorKind.RATE_LIMITED;
            case SERVER -> OfficialQqAccountErrorKind.SERVER;
            case MALFORMED_RESPONSE -> OfficialQqAccountErrorKind.MALFORMED_RESPONSE;
            case BUSINESS -> OfficialQqAccountErrorKind.BUSINESS;
        };
    }

    private static String startErrorCode(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof OfficialQqCredentialException credential) {
            return credential.reason().name();
        }
        if (cause instanceof OfficialQqApiException api) {
            return api.kind().name();
        }
        if (cause instanceof java.net.http.HttpTimeoutException) {
            return "TIMEOUT";
        }
        return "START_FAILED";
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null ? failure : current;
    }

    private static String errorType(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause == null) {
            return "";
        }
        String simple = cause.getClass().getSimpleName();
        return OfficialQqAccountRuntimeStatus.safe(simple.isBlank()
                ? cause.getClass().getName() : simple);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        lifecycleLock.lock();
        try {
            List<Holder> retiring = new ArrayList<>(active.values());
            for (Holder holder : retiring) {
                holder.markRetired();
            }
            active.clear();
            for (String accountId : desired.keySet()) {
                OfficialQqAccountConfig config = desired.get(accountId);
                statusCache.put(accountId, new OfficialQqAccountStatus(
                        accountId, OfficialQqAccountState.CLOSED, config.enabled(), false, true,
                        config.shardIndex(), config.shardCount(), clock.instant(), null, null,
                        null, 0, OfficialQqAccountErrorKind.NONE, "", "", ""));
            }
            publishLocked();
            // Read paths are lock-free and already no longer route to these generations. Waiting
            // for Gateway.close here is therefore safe even while an event sink queries status.
            for (Holder holder : retiring) {
                holder.stop();
            }
            LOG.info("event=qq_official_account_registry_closed accounts={}", desired.size());
        } finally {
            lifecycleLock.unlock();
        }
    }

    private final class Holder {
        private final OfficialQqAccountConfig config;
        private final OfficialQqCredential credential;
        private final String secretFingerprint;
        private final long generation;
        private final AtomicBoolean accepting = new AtomicBoolean(true);
        private final AtomicBoolean stopped = new AtomicBoolean();
        private volatile OfficialQqAccountClient client;
        private volatile OfficialQqAccountRuntimeStatus status = new OfficialQqAccountRuntimeStatus(
                OfficialQqAccountState.CONNECTING, false, false, Instant.EPOCH,
                null, null, null, 0, OfficialQqAccountErrorKind.NONE, "", "", "");

        private Holder(
                OfficialQqAccountConfig config,
                OfficialQqCredential credential,
                String secretFingerprint,
                long generation) {
            this.config = config;
            this.credential = credential;
            this.secretFingerprint = secretFingerprint;
            this.generation = generation;
        }

        private OfficialQqAccountConfig config() {
            return config;
        }

        private String secretFingerprint() {
            return secretFingerprint;
        }

        private long generation() {
            return generation;
        }

        private OfficialQqAccountClient client() {
            return client;
        }

        private boolean isAccepting() {
            return accepting.get();
        }

        private OfficialQqAccountRuntimeStatus statusSnapshot() {
            return status;
        }

        private void attach(OfficialQqAccountClient value) {
            client = value;
        }

        private void markRetired() {
            accepting.set(false);
        }

        private void acceptStatus(OfficialQqAccountRuntimeStatus value) {
            if (value != null && accepting.get() && !closed.get()) {
                status = value;
            }
        }

        private void dispatch(OfficialQqDispatch dispatch) {
            if (dispatch == null || !accepting.get() || closed.get()) {
                return;
            }
            RegistrySnapshot currentSnapshot = published;
            Holder current = currentSnapshot.active().get(config.accountId());
            if (current != this || current.generation != generation) {
                return;
            }
            if (!accepting.get() || closed.get()) {
                return;
            }
            // Do not swallow business ingest failures. The Gateway must observe them so its
            // committed sequence can trigger a replay instead of advancing past unpersisted data.
            // The exception may contain body text, so deliberately do not log it here.
            eventSink.onEvent(new OfficialQqAccountEvent(config.accountId(), dispatch));
        }

        private void stop() {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            OfficialQqAccountClient value = client;
            client = null;
            if (value != null) {
                try {
                    value.close();
                } catch (RuntimeException failure) {
                    LOG.warn("event=qq_official_account_close_failed accountId={} type={}",
                            config.accountId(), errorType(failure));
                }
            }
            try {
                credential.close();
            } catch (RuntimeException failure) {
                LOG.warn("event=qq_official_account_credential_close_failed accountId={} type={}",
                        config.accountId(), errorType(failure));
            }
        }
    }

    private record RegistrySnapshot(
            Map<String, OfficialQqAccountConfig> desired,
            Map<String, Holder> active,
            Map<String, OfficialQqAccountStatus> statuses) {

        private static RegistrySnapshot empty() {
            return new RegistrySnapshot(Map.of(), Map.of(), Map.of());
        }
    }

    private record CredentialSnapshot(OfficialQqCredential credential, String fingerprint) {
        @Override
        public String toString() {
            return "CredentialSnapshot[credential=<redacted>, fingerprint=<redacted>]";
        }
    }

    private record EnvironmentSnapshot(Map<String, String> values, RuntimeException failure) {
        @Override
        public String toString() {
            return "EnvironmentSnapshot[size=" + values.size()
                    + ", failed=" + (failure != null) + "]";
        }
    }

    private record ParsedDesired(
            LinkedHashMap<String, OfficialQqAccountConfig> targets,
            Set<String> rejected,
            Set<String> duplicateIds) {
    }
}
