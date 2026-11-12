package io.mosire.bma3.qqconnect.account;

import io.mosire.bma3.core.account.AccountId;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import io.mosire.bma3.qqconnect.onebot.OneBotEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Desired-state account registry.
 *
 * <p>Important: reconcile performs slow network work (connect up to 5s, diagnostic probe up to 2s)
 * and therefore must <b>not</b> hold a lock that inbound ingestion needs. Everything that the
 * ingest path touches — {@link #self(String)}, {@link #client(AccountId)}, {@link #connected} — is
 * a concurrent-map read, so a hot reload that re-probes a broken account cannot stall message
 * ingestion (this exactly happened in the local end-to-end run).</p>
 */
public final class AccountRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AccountRegistry.class);

    /** The bot's own platform identity for one account. */
    public record SelfIdentity(String userId, String nickname) {

        public SelfIdentity {
            userId = userId == null ? "" : userId;
            nickname = nickname == null ? "" : nickname;
        }

        public boolean known() {
            return !userId.isBlank() || !nickname.isBlank();
        }
    }

    /** Why an account is not connected. {@code hint} is operator-facing and never carries a token. */
    public record Fault(String phase, int httpStatus, String hint, java.time.Instant at) {
    }

    private final OneBotClientFactory factory;
    private final Consumer<AccountEvent> eventSink;
    private final Map<String, Holder> active = new ConcurrentHashMap<>();
    private final Map<String, SelfIdentity> selfIdentities = new ConcurrentHashMap<>();
    private final Map<String, Fault> faults = new ConcurrentHashMap<>();
    /** Accounts the operator asked for, including ones that never connected. */
    private final Set<String> configured = ConcurrentHashMap.newKeySet();
    /** Last desired config per account; used by the watchdog to reconnect without a config change. */
    private final Map<String, AccountConfig> desiredByAccount = new ConcurrentHashMap<>();

    public AccountRegistry(Consumer<AccountEvent> eventSink) {
        this((config, listener) -> new OneBot11Client(config.endpoint(), config.token(), listener),
                eventSink);
    }

    public AccountRegistry(OneBotClientFactory factory, Consumer<AccountEvent> eventSink) {
        this.factory = factory;
        this.eventSink = eventSink;
    }

    public ReconcileResult reconcile(List<AccountConfig> desired) {
        LinkedHashMap<String, AccountConfig> target = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        for (AccountConfig config : desired) {
            if (!valid(config)) {
                rejected.add(config == null ? "" : config.accountId());
                if (config != null) {
                    faults.put(config.accountId(), new Fault("config", -1,
                            "endpoint 必须是 ws:// 或 wss://（OneBot 正向 WS）；当前: "
                                    + config.endpoint(), java.time.Instant.now()));
                }
                continue;
            }
            if (target.putIfAbsent(config.accountId(), config) != null) {
                rejected.add(config.accountId());
            }
        }
        configured.clear();
        configured.addAll(target.keySet());
        desiredByAccount.clear();
        desiredByAccount.putAll(target);
        desired.stream()
                .filter(config -> config != null && config.accountId() != null
                        && !config.accountId().isBlank())
                .map(AccountConfig::accountId)
                .forEach(configured::add);

        List<String> removed = new ArrayList<>();
        Set<String> targetIds = new LinkedHashSet<>(target.keySet());
        for (String accountId : new ArrayList<>(active.keySet())) {
            if (!targetIds.contains(accountId)) {
                remove(accountId);
                removed.add(accountId);
            }
        }

        List<String> added = new ArrayList<>();
        List<String> restarted = new ArrayList<>();
        for (Map.Entry<String, AccountConfig> entry : target.entrySet()) {
            String accountId = entry.getKey();
            AccountConfig config = entry.getValue();
            Holder current = active.get(accountId);
            if (current == null) {
                if (start(config)) {
                    added.add(accountId);
                } else {
                    rejected.add(accountId);
                }
                continue;
            }
            if (current.config().equals(config)) {
                // Same config but the socket is gone: this is exactly the "dead until restart"
                // case the watchdog exists to fix. Reconnect eagerly here as well.
                if (!current.client().connected()) {
                    forceReconnect(accountId);
                }
                continue;
            }
            // Slow path (connect + awaitReady) runs without any registry lock held.
            OneBot11Client replacement = create(config);
            if (replacement == null) {
                rejected.add(accountId);
                continue;
            }
            try {
                replacement.start();
                replacement.awaitReady(Duration.ofSeconds(5));
                Holder previous = active.put(accountId, new Holder(config, replacement));
                if (previous != null) {
                    previous.client().close();
                }
                faults.remove(accountId);
                restarted.add(accountId);
                learnIdentity(accountId, replacement);
            } catch (RuntimeException failure) {
                replacement.close();
                rejected.add(accountId);
                recordFault(accountId, "restart", failure, replacement);
            }
        }
        return new ReconcileResult(added, removed, restarted, rejected);
    }

    /**
     * Replaces one account's client with a fresh connection, even when its config did not change.
     *
     * @return true when the replacement connected; false when the account is unknown or the
     *         replacement failed (the fault is recorded and the old holder is left in place)
     */
    public boolean forceReconnect(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return false;
        }
        AccountConfig config = desiredByAccount.get(accountId);
        Holder current = active.get(accountId);
        if (config == null && current != null) {
            config = current.config();
        }
        if (config == null) {
            return false;
        }
        OneBot11Client replacement = create(config);
        if (replacement == null) {
            return false;
        }
        try {
            replacement.start();
            replacement.awaitReady(Duration.ofSeconds(5));
            Holder previous = active.put(accountId, new Holder(config, replacement));
            if (previous != null && previous.client() != replacement) {
                previous.client().close();
            }
            faults.remove(accountId);
            learnIdentity(accountId, replacement);
            LOG.info("event=qq_account_reconnected accountId={}", accountId);
            return true;
        } catch (RuntimeException failure) {
            replacement.close();
            recordFault(accountId, "reconnect", failure, replacement);
            LOG.info("event=qq_account_reconnect_failed accountId={} type={}",
                    accountId, failure.getClass().getSimpleName());
            return false;
        }
    }

    /** Desired configurations, for the watchdog's account probe. */
    public List<AccountConfig> desiredConfigs() {
        return List.copyOf(desiredByAccount.values());
    }

    public Optional<OneBot11Client> client(AccountId accountId) {
        Holder holder = active.get(accountId.value());
        return holder == null ? Optional.empty() : Optional.of(holder.client());
    }

    /** Desired accounts (connected or not), so a failed one still shows up with its lastError. */
    public List<AccountId> accounts() {
        if (!configured.isEmpty()) {
            return configured.stream().map(AccountId::new).toList();
        }
        return active.keySet().stream().map(AccountId::new).toList();
    }

    /** Last known connection fault, cleared on a successful connect. */
    public Optional<Fault> fault(String accountId) {
        return Optional.ofNullable(faults.get(accountId));
    }

    /** True when the account has a live socket (not merely a registry entry). */
    public boolean connected(String accountId) {
        Holder holder = active.get(accountId);
        return holder != null && holder.client().connected();
    }

    /** The bot's own identity for an account, once {@code get_login_info} answered. */
    public Optional<SelfIdentity> self(String accountId) {
        return Optional.ofNullable(selfIdentities.get(accountId));
    }

    /** Snapshot of learned/overridden identities, for status tools. */
    public Map<String, SelfIdentity> selfIdentitySummary() {
        return Map.copyOf(selfIdentities);
    }

    /** Records an identity learned elsewhere (for example an explicit config override). */
    public void self(String accountId, SelfIdentity identity) {
        if (accountId == null || accountId.isBlank() || identity == null || !identity.known()) {
            return;
        }
        selfIdentities.put(accountId, identity);
    }

    @Override
    public void close() {
        for (String accountId : new ArrayList<>(active.keySet())) {
            remove(accountId);
        }
    }

    private boolean start(AccountConfig config) {
        OneBot11Client client = create(config);
        if (client == null) {
            return false;
        }
        try {
            client.start();
            client.awaitReady(Duration.ofSeconds(5));
            active.put(config.accountId(), new Holder(config, client));
            faults.remove(config.accountId());
            learnIdentity(config.accountId(), client);
            return true;
        } catch (RuntimeException failure) {
            client.close();
            recordFault(config.accountId(), "connect", failure, client);
            return false;
        }
    }

    /**
     * Asks the live connection who it is. Failure is non-fatal: priority rules that depend on the
     * bot nickname simply stay disabled and say so in the decision trace.
     */
    private void learnIdentity(String accountId, OneBot11Client client) {
        try {
            client.sendAction("get_login_info", Map.of())
                    .orTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((response, failure) -> {
                        if (failure != null || response == null) {
                            LOG.warn("event=qq_self_identity_unavailable accountId={} errorType={}",
                                    accountId, failure == null ? "empty" : "timeout");
                            return;
                        }
                        var data = response.path("data");
                        String userId = data.path("user_id").asText("");
                        String nickname = data.path("nickname").asText("");
                        if (!userId.isBlank() || !nickname.isBlank()) {
                            self(accountId, new SelfIdentity(userId, nickname));
                        }
                    });
        } catch (RuntimeException failure) {
            LOG.warn("event=qq_self_identity_unavailable accountId={} errorType={}",
                    accountId, failure.getClass().getSimpleName());
        }
    }

    private OneBot11Client create(AccountConfig config) {
        try {
            return factory.create(config,
                    event -> eventSink.accept(new AccountEvent(config.accountId(), event)));
        } catch (RuntimeException failure) {
            LOG.warn("event=qq_account_create_failed accountId={} errorType={}",
                    config.accountId(), failure.getClass().getSimpleName());
            return null;
        }
    }

    private void remove(String accountId) {
        Holder holder = active.remove(accountId);
        selfIdentities.remove(accountId);
        if (holder != null) {
            holder.client().close();
        }
    }

    /**
     * Records a connect failure: maps it to an actionable hint and, when the operator allowed it,
     * probes the same host:port with a plain HTTP GET to tell "HTTP API port" apart from "wrong
     * path/token". Runs outside any lock.
     */
    private void recordFault(String accountId, String phase, RuntimeException failure,
                             OneBot11Client client) {
        var diagnosis = io.mosire.bma3.qqconnect.onebot.OneBotDiagnostics.of(failure);
        String hint = diagnosis.hint();
        if (client != null && client.endpoint() != null) {
            int probe = io.mosire.bma3.qqconnect.onebot.OneBotDiagnostics.probeHttp(
                    client.endpoint(), Duration.ofSeconds(2));
            if (probe > 0) {
                hint = hint + "（探测 " + client.endpoint().getHost() + ":"
                        + (client.endpoint().getPort() > 0 ? client.endpoint().getPort() : 80)
                        + " 的普通 HTTP 返回 " + probe + "：该端口是 HTTP 服务，不是 WS 端点）";
            }
        }
        faults.put(accountId, new Fault(phase, diagnosis.httpStatus(), hint,
                java.time.Instant.now()));
        LOG.error("event=qq_account_connect_failed accountId={} phase={} status={} hint={}",
                accountId, phase, diagnosis.httpStatus(), hint);
    }

    private static boolean valid(AccountConfig config) {
        if (config == null || config.accountId() == null || config.accountId().isBlank()) {
            return false;
        }
        String scheme = config.endpoint().getScheme();
        return "ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme);
    }

    private record Holder(AccountConfig config, OneBot11Client client) {
    }
}
