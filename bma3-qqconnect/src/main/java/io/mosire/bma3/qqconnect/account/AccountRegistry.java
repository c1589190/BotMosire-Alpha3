package io.mosire.bma3.qqconnect.account;

import io.mosire.bma3.core.account.AccountId;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import io.mosire.bma3.qqconnect.onebot.OneBotEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Desired-state account registry. A reconcile adds/removes/restarts only the affected
 * connections; a failed new connection leaves the previous one alive.
 *
 * <p>Each live connection also learns its own identity ({@code get_login_info}) so priority rules
 * such as "the bot's own nickname was mentioned" and outbound sender attribution do not need the
 * operator to hand-write the bot's QQ number.</p>
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
    private final Map<String, Holder> active = new HashMap<>();
    private final Map<String, SelfIdentity> selfIdentities = new HashMap<>();
    private final Map<String, Fault> faults = new HashMap<>();

    public AccountRegistry(Consumer<AccountEvent> eventSink) {
        this((config, listener) -> new OneBot11Client(config.endpoint(), config.token(), listener),
                eventSink);
    }

    public AccountRegistry(OneBotClientFactory factory, Consumer<AccountEvent> eventSink) {
        this.factory = factory;
        this.eventSink = eventSink;
    }

    public synchronized ReconcileResult reconcile(List<AccountConfig> desired) {
        LinkedHashMap<String, AccountConfig> target = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        for (AccountConfig config : desired) {
            if (!valid(config)) {
                rejected.add(config.accountId());
                faults.put(config.accountId(), new Fault("config", -1,
                        "endpoint 必须是 ws:// 或 wss://（OneBot 正向 WS）；当前: "
                                + (config == null ? "null" : config.endpoint()), java.time.Instant.now()));
                continue;
            }
            if (target.putIfAbsent(config.accountId(), config) != null) {
                rejected.add(config.accountId());
            }
        }

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
            if (current.config.equals(config)) {
                continue;
            }
            OneBot11Client replacement = create(config);
            if (replacement == null) {
                rejected.add(accountId);
                continue;
            }
            try {
                replacement.start();
                replacement.awaitReady(Duration.ofSeconds(5));
                current.client.close();
                active.put(accountId, new Holder(config, replacement));
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

    public synchronized Optional<OneBot11Client> client(AccountId accountId) {
        Holder holder = active.get(accountId.value());
        return holder == null ? Optional.empty() : Optional.of(holder.client);
    }

    public synchronized List<AccountId> accounts() {
        return active.keySet().stream().map(AccountId::new).toList();
    }

    /** Last known connection fault, cleared on a successful connect. */
    public synchronized Optional<Fault> fault(String accountId) {
        return Optional.ofNullable(faults.get(accountId));
    }

    /** True when the account has a live socket (not merely a registry entry). */
    public synchronized boolean connected(String accountId) {
        Holder holder = active.get(accountId);
        return holder != null && holder.client.connected();
    }

    /**
     * Records a connect failure: maps it to an actionable hint and, when the operator allowed it,
     * probes the same host:port with a plain HTTP GET to tell "HTTP API port" apart from "wrong
     * path/token".
     */
    private void recordFault(String accountId, String phase, RuntimeException failure,
                             OneBot11Client client) {
        var diagnosis = io.mosire.bma3.qqconnect.onebot.OneBotDiagnostics.of(failure);
        String hint = diagnosis.hint();
        if (client != null && client.endpoint() != null) {
            int probe = io.mosire.bma3.qqconnect.onebot.OneBotDiagnostics.probeHttp(
                    client.endpoint(), java.time.Duration.ofSeconds(2));
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

    /** The bot's own identity for an account, once {@code get_login_info} answered. */
    public synchronized Optional<SelfIdentity> self(String accountId) {
        return Optional.ofNullable(selfIdentities.get(accountId));
    }

    /** Snapshot of learned/overridden identities, for status tools. */
    public synchronized Map<String, SelfIdentity> selfIdentitySummary() {
        return Map.copyOf(selfIdentities);
    }

    /** Records an identity learned elsewhere (for example an explicit config override). */
    public synchronized void self(String accountId, SelfIdentity identity) {
        if (accountId == null || accountId.isBlank() || identity == null || !identity.known()) {
            return;
        }
        selfIdentities.put(accountId, identity);
    }

    @Override
    public synchronized void close() {
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
            holder.client.close();
        }
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
