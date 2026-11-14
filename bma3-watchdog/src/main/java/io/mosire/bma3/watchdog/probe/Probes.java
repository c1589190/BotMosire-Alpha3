package io.mosire.bma3.watchdog.probe;

import io.mosire.bma3.agentlib.Bma3McpServer;
import io.mosire.bma3.inbox.core.ControlCommand;
import io.mosire.bma3.inbox.core.InboxService;
import io.mosire.bma3.inbox.core.InboxStatus;
import io.mosire.bma3.inbox.core.PluginHeartbeat;
import io.mosire.bma3.inbox.http.InboxHttpServer;
import io.mosire.bma3.qqconnect.account.AccountConfig;
import io.mosire.bma3.qqconnect.account.AccountRegistry;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import io.mosire.bma3.watchdog.spi.Probe;
import io.mosire.bma3.watchdog.spi.ProbeResult;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Concrete BMA3 probes. Everything here is content-free: ids, counts, health codes and ages only.
 */
public final class Probes {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private Probes() {
    }

    public static Probe process() {
        return probe("process", () -> {
            long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
            int threads = ManagementFactory.getThreadMXBean().getThreadCount();
            Runtime runtime = Runtime.getRuntime();
            long used = runtime.totalMemory() - runtime.freeMemory();
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("uptimeSeconds", Long.toString(uptimeMs / 1000));
            facts.put("threads", Integer.toString(threads));
            facts.put("heapUsedMb", Long.toString(used / (1024 * 1024)));
            return ProbeResult.up("process", "alive", facts);
        });
    }

    public static Probe disk(Path dataDir) {
        return probe("disk", () -> {
            Path directory = dataDir == null ? Path.of("data") : dataDir;
            try {
                Files.createDirectories(directory);
                if (!Files.isWritable(directory)) {
                    return ProbeResult.down("disk", "NOT_WRITABLE", "dataDir 不可写");
                }
                FileStore store = Files.getFileStore(directory);
                long usable = store.getUsableSpace();
                Map<String, String> facts = new LinkedHashMap<>();
                facts.put("usableMb", Long.toString(usable / (1024 * 1024)));
                if (usable < 200L * 1024 * 1024) {
                    return ProbeResult.degraded("disk", "LOW_SPACE", "剩余空间不足 200MB", facts);
                }
                return ProbeResult.up("disk", "writable", facts);
            } catch (IOException failure) {
                return ProbeResult.down("disk", "IO_ERROR", failure.getClass().getSimpleName());
            }
        });
    }

    public static Probe db(java.util.function.BooleanSupplier boardPing,
                           java.util.function.BooleanSupplier inboxPing) {
        return probe("db", () -> {
            boolean board = boardPing != null && boardPing.getAsBoolean();
            boolean inbox = inboxPing != null && inboxPing.getAsBoolean();
            if (!board || !inbox) {
                return ProbeResult.down("db", "DB_UNAVAILABLE",
                        "board=" + board + " inbox=" + inbox);
            }
            return ProbeResult.up("db", "both stores answer SELECT 1");
        });
    }

    public static Probe inboxHttp(String host, int port, String inboxId, String token,
                                  java.util.function.BooleanSupplier running) {
        return probe("inboxHttp", () -> {
            if (running != null && !running.getAsBoolean()) {
                return ProbeResult.down("inboxHttp", "NOT_RUNNING", "HTTP listener is down");
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create("http://" + host + ":" + port + "/healthz"))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<Void> response = HTTP.send(request,
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() != 200) {
                    return ProbeResult.down("inboxHttp", "HTTP_" + response.statusCode(),
                            "healthz 返回非 200");
                }
                return ProbeResult.up("inboxHttp", "healthz 200",
                        Map.of("port", Integer.toString(port)));
            } catch (IOException failure) {
                return ProbeResult.down("inboxHttp", "UNREACHABLE",
                        failure.getClass().getSimpleName());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return ProbeResult.down("inboxHttp", "INTERRUPTED", "interrupted");
            } catch (RuntimeException failure) {
                return ProbeResult.down("inboxHttp", "INVALID_URL",
                        failure.getClass().getSimpleName());
            }
        });
    }

    public static Probe inboxBacklog(InboxService service, long staleAfterMillis) {
        return probe("inboxBacklog", () -> {
            InboxStatus status = service.status();
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("available", Integer.toString(status.available()));
            facts.put("pendingPush", Integer.toString(status.pendingPush()));
            facts.put("pushRevision", Long.toString(status.pushRevision()));
            if (status.pendingPush() > 0 && !status.nextPushAt().isBlank()) {
                try {
                    Instant due = Instant.parse(status.nextPushAt());
                    if (due.isBefore(Instant.now().minusMillis(staleAfterMillis))) {
                        return ProbeResult.degraded("inboxBacklog", "PUSH_STALLED",
                                "pending 已过期仍未 flush", facts);
                    }
                } catch (RuntimeException ignored) {
                    // unparsable timestamp is not a health failure by itself
                }
            }
            if (status.pendingPush() > 100) {
                return ProbeResult.degraded("inboxBacklog", "BACKLOG_HIGH",
                        "pendingPush > 100", facts);
            }
            return ProbeResult.up("inboxBacklog", "ok", facts);
        });
    }

    public static Probe mcp(Bma3McpServer server) {
        return probe("mcp", () -> {
            if (server == null || !server.healthy()) {
                return ProbeResult.down("mcp", "NOT_RUNNING", "MCP listener is down");
            }
            int port;
            try {
                port = server.boundPort();
            } catch (RuntimeException failure) {
                return ProbeResult.down("mcp", "NO_PORT", failure.getClass().getSimpleName());
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                return ProbeResult.up("mcp", "tcp connect ok",
                        Map.of("port", Integer.toString(port)));
            } catch (IOException failure) {
                return ProbeResult.down("mcp", "UNREACHABLE",
                        failure.getClass().getSimpleName());
            }
        });
    }

    public static Probe qqAccount(AccountRegistry accounts) {
        return probe("qqAccount", () -> {
            List<AccountConfig> configs = accounts.desiredConfigs();
            if (configs.isEmpty()) {
                return ProbeResult.degraded("qqAccount", "NO_ACCOUNTS", "没有配置的 QQ 账号");
            }
            List<String> disconnected = new ArrayList<>();
            for (AccountConfig config : configs) {
                if (!accounts.connected(config.accountId())) {
                    disconnected.add(config.accountId());
                }
            }
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("accounts", Integer.toString(configs.size()));
            facts.put("disconnected", Integer.toString(disconnected.size()));
            if (!disconnected.isEmpty()) {
                return ProbeResult.down("qqAccount", "DISCONNECTED",
                        String.join(",", disconnected), facts);
            }
            return ProbeResult.up("qqAccount", "all accounts connected", facts);
        });
    }

    /**
     * Rotating application-level probe: a TCP socket can look open while the OneBot peer is a
     * zombie. Each tick checks at most one account so a slow peer cannot stall the watchdog.
     */
    public static Probe qqZombie(AccountRegistry accounts) {
        AtomicInteger cursor = new AtomicInteger();
        return probe("qqZombie", () -> {
            List<AccountConfig> configs = accounts.desiredConfigs();
            if (configs.isEmpty()) {
                return ProbeResult.up("qqZombie", "no accounts");
            }
            int index = Math.floorMod(cursor.getAndIncrement(), configs.size());
            AccountConfig config = configs.get(index);
            String accountId = config.accountId();
            if (!accounts.connected(accountId)) {
                return ProbeResult.degraded("qqZombie", "NOT_CONNECTED", accountId);
            }
            OneBot11Client client = accounts.client(
                    new io.mosire.bma3.core.account.AccountId(accountId)).orElse(null);
            if (client == null) {
                return ProbeResult.degraded("qqZombie", "NO_CLIENT", accountId);
            }
            try {
                client.sendAction("get_status", Map.of())
                        .orTimeout(1500, TimeUnit.MILLISECONDS)
                        .join();
                return ProbeResult.up("qqZombie", "get_status answered",
                        Map.of("accountId", accountId));
            } catch (RuntimeException failure) {
                return ProbeResult.degraded("qqZombie", "ZOMBIE_SOCKET", accountId);
            }
        });
    }

    public static Probe dshPlugin(InboxService service, Duration heartbeatTimeout) {
        return probe("dshPlugin", () -> {
            PluginHeartbeat heartbeat = service.lastHeartbeat();
            if (heartbeat == null) {
                return ProbeResult.degraded("dshPlugin", "NO_HEARTBEAT",
                        "插件还没有上报心跳");
            }
            long ageSeconds = Math.max(0,
                    Duration.between(heartbeat.at(), Instant.now()).toSeconds());
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("sessionId", heartbeat.sessionId().isBlank() ? "-" : heartbeat.sessionId());
            facts.put("sse", heartbeat.sse());
            facts.put("ageSeconds", Long.toString(ageSeconds));
            facts.put("bound", Boolean.toString(heartbeat.bound()));
            facts.put("tokenSource", heartbeat.tokenSource());
            facts.put("tokenOk", Boolean.toString(heartbeat.tokenOk()));
            if (!heartbeat.tokenOk()) {
                return ProbeResult.degraded("dshPlugin", "TOKEN_MISMATCH",
                        "插件上报 token 校验失败（来源 " + heartbeat.tokenSource() + "）", facts);
            }
            if (ageSeconds > Math.max(5, heartbeatTimeout.toSeconds())) {
                return ProbeResult.degraded("dshPlugin", "HEARTBEAT_STALE",
                        "心跳超过 " + heartbeatTimeout.toSeconds() + "s", facts);
            }
            if (!heartbeat.bound()) {
                return ProbeResult.degraded("dshPlugin", "UNBOUND",
                        "插件没有绑定会话", facts);
            }
            ControlCommand control = service.pendingControl();
            if (control != null && !control.id().equals(heartbeat.appliedControlId())) {
                return ProbeResult.degraded("dshPlugin", "CONTROL_PENDING",
                        "控制命令尚未被插件应用", facts);
            }
            return ProbeResult.up("dshPlugin", "heartbeat fresh and bound", facts);
        });
    }

    public static Probe webconnect(boolean enabled, String binary) {
        return probe("webconnect", () -> {
            if (!enabled) {
                return ProbeResult.up("webconnect", "disabled");
            }
            if (binary == null || binary.isBlank() || !Files.isExecutable(Path.of(binary))) {
                return ProbeResult.degraded("webconnect", "BINARY_MISSING",
                        "Chromium 不可执行");
            }
            return ProbeResult.up("webconnect", "binary present");
        });
    }

    /** Wraps a lambda so {@link Probe#name()} returns a stable component id. */
    public static Probe named(String name, java.util.function.Supplier<ProbeResult> supplier) {
        return new Probe() {
            @Override
            public ProbeResult check() {
                return supplier.get();
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    private static Probe probe(String name, java.util.function.Supplier<ProbeResult> supplier) {
        return named(name, supplier);
    }
}
