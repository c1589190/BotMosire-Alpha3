package io.github.c1589190.bma3.app.onebot;

import io.github.c1589190.bma3.app.config.AppConfig;
import io.github.c1589190.bma3.core.config.ConfigException;
import io.github.c1589190.bma3.qqconnect.account.AccountConfig;
import io.github.c1589190.bma3.qqconnect.account.AccountRegistry;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Coordinates OneBot configuration and credential changes against the same account registry. */
public final class OneBotSettings {
    private final AccountRegistry registry;
    private final Map<String, String> env;
    private final OneBotCredentialStore store;

    public OneBotSettings(AccountRegistry registry, Map<String, String> env, Path dataDir) {
        this.registry = registry;
        this.env = Map.copyOf(env);
        this.store = new OneBotCredentialStore(dataDir);
    }

    public synchronized void reconcile(AppConfig config, AppConfig previous) {
        List<AccountConfig> ready = new ArrayList<>();
        Map<String, AccountRegistry.Fault> blocked = new LinkedHashMap<>();
        Map<String, OneBotCredentialStore.Entry> credentials = readReferenced(config);
        for (AppConfig.Account account : config.qqconnect().accounts()) {
            if (!account.enabled()) {
                continue;
            }
            if (!account.tokenRef().isEmpty()) {
                var entry = credentials == null ? null : credentials.get(account.tokenRef());
                if (entry == null) {
                    blocked.put(account.accountId(), credentialFault(credentials == null
                            ? "CREDENTIAL_STORE_UNAVAILABLE" : "CREDENTIAL_MISSING", account.tokenRef()));
                } else {
                    ready.add(resolved(account, entry.value()));
                }
                continue;
            }
            String tokenEnv = account.tokenEnv();
            boolean usesTokenEnv = tokenEnv != null && !tokenEnv.isBlank();
            String token = usesTokenEnv ? env.get(tokenEnv) : "";
            if (usesTokenEnv && (token == null || token.isBlank())) {
                if (previous != null && previous.qqconnect().accounts().stream().noneMatch(old ->
                        old.enabled() && old.accountId().equals(account.accountId()) && tokenEnv.equals(old.tokenEnv()))) {
                    throw new ConfigException("qqconnect.accounts.tokenEnv", "环境变量未设置: " + tokenEnv);
                }
                blocked.put(account.accountId(), credentialFault("TOKEN_ENV_MISSING", tokenEnv));
            } else {
                ready.add(resolved(account, token));
            }
        }
        registry.reconcile(ready, blocked);
    }

    synchronized List<Map<String, String>> listCredentials() throws IOException {
        List<Map<String, String>> credentials = new ArrayList<>();
        store.read().forEach((name, entry) -> credentials.add(Map.of("name", name, "source", entry.source())));
        return credentials;
    }

    synchronized boolean changeCredential(String name, OneBotCredentialStore.Entry entry,
                                          Supplier<AppConfig> current) throws IOException {
        var entries = store.read();
        var old = entry == null ? entries.remove(name) : entries.put(name, entry);
        boolean changed = !Objects.equals(old, entry);
        if (changed) {
            store.write(entries);
        }
        for (AppConfig.Account account : current.get().qqconnect().accounts()) {
            if (account.enabled() && name.equals(account.tokenRef())) {
                if (entry == null) {
                    registry.markUnavailable(account.accountId(), credentialFault("CREDENTIAL_MISSING", name));
                } else {
                    registry.reconcileAccount(resolved(account, entry.value()));
                    if (!account.selfQq().isBlank() || !account.selfName().isBlank()) {
                        registry.self(account.accountId(), new AccountRegistry.SelfIdentity(
                                account.selfQq(), account.selfName()));
                    }
                }
            }
        }
        return changed;
    }

    String importWindowsCredential() throws IOException {
        return OneBotCredentialStore.importWindows(env.get("LOCALAPPDATA"));
    }

    private Map<String, OneBotCredentialStore.Entry> readReferenced(AppConfig config) {
        if (config.qqconnect().accounts().stream().noneMatch(account ->
                account.enabled() && !account.tokenRef().isEmpty())) {
            return Map.of();
        }
        try {
            return store.read();
        } catch (IOException failure) {
            return null;
        }
    }

    private static AccountRegistry.Fault credentialFault(String code, String reference) {
        return new AccountRegistry.Fault("credentials", -1, code + " (" + reference + ")", Instant.now());
    }

    private static AccountConfig resolved(AppConfig.Account account, String token) {
        return new AccountConfig(account.accountId(), URI.create(account.endpoint()), token, true);
    }
}
