package io.mosire.bma3.qqconnect.tools;

import io.mosire.bma3.core.source.SourceAddress;
import java.util.List;
import java.util.function.Consumer;

/**
 * Sends a watchdog alert to the deployer's QQ over the normal outbound path.
 *
 * <p>It uses the same message-board-first, three-state send path as every other QQ send: an OUT row
 * is written before the action, {@code UNKNOWN} is never retried, and the alert text is not logged.
 * The notifier is intentionally tiny: the watchdog supplies the text, the QQ module owns delivery.</p>
 */
public final class QqDeployerNotifier implements Consumer<String> {

    private final QqToolContext context;
    private final String deployerQq;
    private final boolean enabled;

    public QqDeployerNotifier(QqToolContext context, String deployerQq, boolean enabled) {
        this.context = context;
        this.deployerQq = deployerQq == null ? "" : deployerQq.trim();
        this.enabled = enabled;
    }

    @Override
    public void accept(String text) {
        send(text);
    }

    /** @return true when an account was available and the send path ran (state may still be UNKNOWN) */
    public boolean send(String text) {
        if (!enabled || deployerQq.isBlank() || text == null || text.isBlank()) {
            return false;
        }
        String accountId = firstConnectedAccount();
        if (accountId == null) {
            org.slf4j.LoggerFactory.getLogger(QqDeployerNotifier.class)
                    .warn("event=deployer_alert_skipped reason=no_connected_account");
            return false;
        }
        try {
            SourceAddress source = SourceAddress.parse(
                    "qq_private/" + accountId + "/" + deployerQq);
            String states = QqSendSupport.sendText(context, source, text, "", "send_private_msg");
            org.slf4j.LoggerFactory.getLogger(QqDeployerNotifier.class)
                    .info("event=deployer_alert_sent accountId={} states={}", accountId, states);
            return true;
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(QqDeployerNotifier.class)
                    .warn("event=deployer_alert_failed type={}",
                            failure.getClass().getSimpleName());
            return false;
        }
    }

    private String firstConnectedAccount() {
        List<io.mosire.bma3.core.account.AccountId> accounts = context.accounts().accounts();
        for (var account : accounts) {
            if (context.accounts().connected(account.value())) {
                return account.value();
            }
        }
        return null;
    }
}
