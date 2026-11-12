package io.mosire.bma3.app.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.bma3.app.config.AppConfig;

/**
 * Redacted JSON view of the effective configuration.
 *
 * <p>Only names and non-secret values appear: secrets live in environment variables and the config
 * stores their <b>variable names</b> ({@code tokenEnv}), never the values.</p>
 */
final class ConfigView {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ConfigView() {
    }

    static ObjectNode effective(AppConfig config) {
        ObjectNode root = JSON.createObjectNode();
        root.put("dataDir", config.dataDir().toString());

        ObjectNode server = root.putObject("server");
        server.put("host", config.server().host());
        server.put("port", config.server().port());
        server.put("mcpPath", config.server().mcpPath());
        server.put("tokenEnv", config.server().tokenEnv());
        server.put("healthPort", config.server().healthPort());

        ObjectNode qqconnect = root.putObject("qqconnect");
        ObjectNode send = qqconnect.putObject("send");
        send.put("maxTextChars", config.qqconnect().send().maxTextChars());
        send.put("overflow", config.qqconnect().send().overflow());
        ObjectNode media = qqconnect.putObject("media");
        media.put("dir", config.qqconnect().media().dir());
        media.put("maxInlineBytes", config.qqconnect().media().maxInlineBytes());
        media.put("maxDownloadBytes", config.qqconnect().media().maxDownloadBytes());
        media.put("timeoutSeconds", config.qqconnect().media().timeoutSeconds());
        ArrayNode accounts = qqconnect.putArray("accounts");
        for (AppConfig.Account account : config.qqconnect().accounts()) {
            ObjectNode item = accounts.addObject();
            item.put("accountId", account.accountId());
            item.put("endpoint", account.endpoint());
            item.put("tokenEnv", account.tokenEnv());
            item.put("enabled", account.enabled());
            item.put("selfQq", account.selfQq());
            item.put("selfName", account.selfName());
        }

        ObjectNode weighting = root.putObject("weighting");
        weighting.put("immediateThreshold", config.weighting().immediateThreshold());
        weighting.put("dropBelow", config.weighting().dropBelow());
        weighting.put("maxPriority", config.weighting().maxPriority());
        weighting.put("normalDelaySeconds", config.weighting().normalDelaySeconds());
        weighting.put("importantDelaySeconds", config.weighting().importantDelaySeconds());
        weighting.set("sourcePriority", JSON.valueToTree(config.weighting().sourcePriority()));
        weighting.set("attributes", JSON.valueToTree(config.weighting().attributes()));
        weighting.set("rules", JSON.valueToTree(config.weighting().rules()));

        ObjectNode inbox = root.putObject("inbox");
        inbox.put("inboxId", config.inbox().inboxId());
        inbox.put("httpPort", config.inbox().httpPort());
        ObjectNode display = inbox.putObject("display");
        display.put("maxMessagesPerItem", config.inbox().display().maxMessagesPerItem());
        display.put("maxItemsPerPanel", config.inbox().display().maxItemsPerPanel());
        display.put("includeOmittedCount", config.inbox().display().includeOmittedCount());
        display.put("maxCharsPerItem", config.inbox().display().maxCharsPerItem());
        ObjectNode push = inbox.putObject("push");
        push.put("minPriority", config.inbox().push().minPriority());
        push.put("minPriorityIgnored", true);
        push.put("maxFirstPushDelaySeconds",
                config.inbox().push().maxFirstPushDelaySeconds());
        ObjectNode dismiss = inbox.putObject("dismiss");
        dismiss.put("autoEnabled", config.inbox().dismiss().autoEnabled());
        dismiss.put("autoBelowPriority", config.inbox().dismiss().autoBelowPriority());
        dismiss.put("autoAfterSeconds", config.inbox().dismiss().autoAfterSeconds());
        dismiss.put("autoMaxPerRound", config.inbox().dismiss().autoMaxPerRound());
        ObjectNode retention = inbox.putObject("retention");
        retention.put("maxAgeDays", config.inbox().retention().maxAgeDays());
        retention.put("maxItems", config.inbox().retention().maxItems());

        ObjectNode webconnect = root.putObject("webconnect");
        webconnect.put("enabled", config.webconnect().enabled());
        webconnect.put("binary", config.webconnect().binary());
        webconnect.put("headless", config.webconnect().headless());
        webconnect.set("allowedHosts", JSON.valueToTree(config.webconnect().allowedHosts()));

        ObjectNode deployer = root.putObject("deployer");
        deployer.put("qq", config.deployer().qq());
        deployer.put("enabled", config.deployer().enabled());

        ObjectNode watchdog = root.putObject("watchdog");
        watchdog.put("enabled", config.watchdog().enabled());
        watchdog.put("intervalSeconds", config.watchdog().intervalSeconds());
        watchdog.put("probeTimeoutMs", config.watchdog().probeTimeoutMs());
        watchdog.put("failureThreshold", config.watchdog().failureThreshold());
        watchdog.put("recoveryCooldownSeconds", config.watchdog().recoveryCooldownSeconds());
        watchdog.put("alertCooldownSeconds", config.watchdog().alertCooldownSeconds());
        watchdog.put("pluginHeartbeatTimeoutSeconds",
                config.watchdog().pluginHeartbeatTimeoutSeconds());
        watchdog.put("diskMinFreeMb", config.watchdog().diskMinFreeMb());
        watchdog.set("readyzCritical", JSON.valueToTree(config.watchdog().readyzCritical()));

        ObjectNode logging = root.putObject("logging");
        logging.put("level", config.logging().level());
        logging.put("dir", config.logging().dir());
        return root;
    }
}
