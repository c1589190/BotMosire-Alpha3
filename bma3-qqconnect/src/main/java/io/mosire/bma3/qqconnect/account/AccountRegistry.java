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
 */
public final class AccountRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AccountRegistry.class);

    private final OneBotClientFactory factory;
    private final Consumer<AccountEvent> eventSink;
    private final Map<String, Holder> active = new HashMap<>();

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
            } catch (RuntimeException failure) {
                replacement.close();
                rejected.add(accountId);
                LOG.warn("event=qq_account_restart_failed accountId={} errorType={}",
                        accountId, failure.getClass().getSimpleName());
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
            return true;
        } catch (RuntimeException failure) {
            client.close();
            LOG.warn("event=qq_account_start_failed accountId={} errorType={}",
                    config.accountId(), failure.getClass().getSimpleName());
            return false;
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
