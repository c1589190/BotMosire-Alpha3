package io.mosire.bma3.watchdog.remediation;

import io.mosire.bma3.agentlib.Bma3McpServer;
import io.mosire.bma3.inbox.core.InboxService;
import io.mosire.bma3.inbox.http.InboxHttpServer;
import io.mosire.bma3.qqconnect.account.AccountConfig;
import io.mosire.bma3.qqconnect.account.AccountRegistry;
import io.mosire.bma3.watchdog.spi.ProbeResult;
import io.mosire.bma3.watchdog.spi.Remediation;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/** Concrete recovery actions. Each one is bounded and idempotent. */
public final class Remediations {

    private Remediations() {
    }

    public static Remediation reconnectQq(AccountRegistry accounts) {
        return remediation("reconnectQq", "qqAccount", Duration.ofSeconds(20),
                result -> {
                    int attempted = 0;
                    int connected = 0;
                    List<AccountConfig> configs = accounts.desiredConfigs();
                    for (AccountConfig config : configs) {
                        if (accounts.connected(config.accountId())) {
                            continue;
                        }
                        attempted++;
                        if (accounts.forceReconnect(config.accountId())) {
                            connected++;
                        }
                    }
                    if (attempted == 0) {
                        return Remediation.RemediationResult.unchanged("reconnectQq",
                                "no disconnected account");
                    }
                    return new Remediation.RemediationResult("reconnectQq", connected > 0,
                            "attempted=" + attempted + " connected=" + connected,
                            java.time.Instant.now());
                });
    }

    public static Remediation restartInboxHttp(InboxHttpServer server) {
        return remediation("restartInboxHttp", "inboxHttp", Duration.ofSeconds(30),
                result -> {
                    try {
                        server.restart();
                        return Remediation.RemediationResult.changed("restartInboxHttp",
                                "rebound");
                    } catch (RuntimeException failure) {
                        return new Remediation.RemediationResult("restartInboxHttp", false,
                                "failed:" + failure.getClass().getSimpleName(),
                                java.time.Instant.now());
                    }
                });
    }

    public static Remediation restartMcp(Bma3McpServer server) {
        return remediation("restartMcp", "mcp", Duration.ofSeconds(30),
                result -> {
                    try {
                        server.restart();
                        return Remediation.RemediationResult.changed("restartMcp", "restarted");
                    } catch (RuntimeException failure) {
                        return new Remediation.RemediationResult("restartMcp", false,
                                "failed:" + failure.getClass().getSimpleName(),
                                java.time.Instant.now());
                    }
                });
    }

    /**
     * Asks the plugin to bind/resume the persisted session. The watchdog does not resume the DSH
     * session itself: the plugin owns the agent lifecycle and the resume handle.
     */
    public static Remediation nudgePlugin(InboxService inbox) {
        return remediation("nudgePlugin", "dshPlugin", Duration.ofSeconds(20),
                result -> {
                    var control = inbox.requestControl("restore", inbox.pluginSessionId(),
                            "BMA3 watchdog：inbox 注入中断，请恢复绑定并确认 MCP 工具可用。");
                    if (control == null) {
                        return Remediation.RemediationResult.unchanged("nudgePlugin",
                                "no control created");
                    }
                    return Remediation.RemediationResult.changed("nudgePlugin",
                            "control=" + control.id());
                });
    }

    private static Remediation remediation(String name, String component, Duration cooldown,
                                           Function<ProbeResult, Remediation.RemediationResult> action) {
        return new Remediation() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String component() {
                return component;
            }

            @Override
            public Duration cooldown() {
                return cooldown;
            }

            @Override
            public RemediationResult remediate(ProbeResult result) {
                return action.apply(result);
            }
        };
    }
}
