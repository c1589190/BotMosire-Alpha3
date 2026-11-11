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

        ObjectNode qqconnect = root.putObject("qqconnect");
        ObjectNode send = qqconnect.putObject("send");
        send.put("maxTextChars", config.qqconnect().send().maxTextChars());
        send.put("overflow", config.qqconnect().send().overflow());
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

        ObjectNode inbox = root.putObject("inbox");
        inbox.put("inboxId", config.inbox().inboxId());
        inbox.put("httpPort", config.inbox().httpPort());
        ObjectNode display = inbox.putObject("display");
        display.put("maxMessagesPerItem", config.inbox().display().maxMessagesPerItem());
        display.put("maxItemsPerPanel", config.inbox().display().maxItemsPerPanel());
        display.put("includeOmittedCount", config.inbox().display().includeOmittedCount());
        ObjectNode push = inbox.putObject("push");
        push.put("minPriority", config.inbox().push().minPriority());
        ObjectNode retention = inbox.putObject("retention");
        retention.put("maxAgeDays", config.inbox().retention().maxAgeDays());
        retention.put("maxItems", config.inbox().retention().maxItems());

        ObjectNode webconnect = root.putObject("webconnect");
        webconnect.put("enabled", config.webconnect().enabled());
        webconnect.put("binary", config.webconnect().binary());
        webconnect.put("headless", config.webconnect().headless());
        webconnect.set("allowedHosts", JSON.valueToTree(config.webconnect().allowedHosts()));

        ObjectNode logging = root.putObject("logging");
        logging.put("level", config.logging().level());
        logging.put("dir", config.logging().dir());
        return root;
    }
}
