import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One-shot, READ-ONLY LLBot capability probe.
 *
 * <p>It never sends a message, never accepts a request and never prints message bodies. It answers
 * the 0.0.4 NOT_RUN questions: history pagination shape, member title field, forward response
 * shape and the group-system request list.</p>
 */
public final class LlbotProbe {

    private static OneBot11Client client;
    private static int failures;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: LlbotProbe <ws-url> <token> [groupId]");
            return;
        }
        String endpoint = args[0];
        String token = args[1];
        String groupId = args.length > 2 ? args[2] : "955432692";
        client = new OneBot11Client(URI.create(endpoint), token, event -> { });
        client.start();
        client.awaitReady(Duration.ofSeconds(10));
        System.out.println("CONNECTED " + endpoint);

        JsonNode login = call("get_login_info", Map.of());
        if (login != null) {
            System.out.println("get_login_info retcode=" + login.path("retcode").asInt(-1)
                    + " userIdPresent=" + !login.path("data").path("user_id").asText("").isBlank()
                    + " nicknamePresent=" + !login.path("data").path("nickname").asText("").isBlank());
        }
        JsonNode status = call("get_status", Map.of());
        if (status != null) {
            System.out.println("get_status retcode=" + status.path("retcode").asInt(-1)
                    + " online=" + status.path("data").path("online").asBoolean(false)
                    + " good=" + status.path("data").path("good").asBoolean(false));
        }

        JsonNode groups = call("get_group_list", Map.of());
        if (groups != null) {
            JsonNode data = groups.path("data");
            System.out.println("get_group_list retcode=" + groups.path("retcode").asInt(-1)
                    + " count=" + (data.isArray() ? data.size() : -1));
            if (data.isArray()) {
                int shown = 0;
                for (JsonNode group : data) {
                    if (shown++ >= 10) {
                        break;
                    }
                    System.out.println("  group id=" + group.path("group_id").asText("")
                            + " namePresent=" + !group.path("group_name").asText("").isBlank()
                            + " memberCount=" + group.path("member_count").asInt(-1));
                }
            }
        }

        JsonNode members = call("get_group_member_list", Map.of("group_id", Long.parseLong(groupId)));
        Integer sampleUser = null;
        if (members != null) {
            JsonNode data = members.path("data");
            int total = data.isArray() ? data.size() : -1;
            int titles = 0;
            int cards = 0;
            Map<String, Integer> roles = new LinkedHashMap<>();
            if (data.isArray()) {
                for (JsonNode member : data) {
                    if (!member.path("title").asText("").isBlank()) {
                        titles++;
                        if (sampleUser == null) {
                            sampleUser = member.path("user_id").asInt();
                        }
                    }
                    if (!member.path("card").asText("").isBlank()) {
                        cards++;
                    }
                    String role = member.path("role").asText("member");
                    roles.merge(role, 1, Integer::sum);
                }
            }
            System.out.println("get_group_member_list retcode=" + members.path("retcode").asInt(-1)
                    + " members=" + total + " withTitle=" + titles + " withCard=" + cards
                    + " roles=" + roles);
        }
        if (sampleUser != null) {
            JsonNode info = call("get_group_member_info", Map.of(
                    "group_id", Long.parseLong(groupId), "user_id", sampleUser));
            if (info != null) {
                JsonNode data = info.path("data");
                System.out.println("get_group_member_info retcode=" + info.path("retcode").asInt(-1)
                        + " titlePresent=" + !data.path("title").asText("").isBlank()
                        + " titleLength=" + data.path("title").asText("").length()
                        + " cardPresent=" + !data.path("card").asText("").isBlank()
                        + " rolePresent=" + !data.path("role").asText("").isBlank());
            }
        }

        for (int count : new int[]{100, 300, 1000}) {
            JsonNode history = call("get_group_msg_history", Map.of(
                    "group_id", Long.parseLong(groupId), "count", count,
                    "reverse_order", true, "parse_mult_msg", true,
                    "disable_get_url", false));
            if (history == null) {
                continue;
            }
            JsonNode data = history.path("data");
            JsonNode messages = data.path("messages");
            System.out.println("get_group_msg_history count=" + count
                    + " retcode=" + history.path("retcode").asInt(-1)
                    + " status=" + history.path("status").asText("")
                    + " messages=" + (messages.isArray() ? messages.size() : -1)
                    + " dataKeys=" + keys(data));
            if (messages.isArray() && messages.size() > 0) {
                JsonNode first = messages.get(0);
                System.out.println("  message keys=" + keys(first)
                        + " hasMessageSeq=" + !first.path("message_seq").asText("").isBlank()
                        + " hasMessageId=" + !first.path("message_id").asText("").isBlank());
            }
        }

        String forwardId = null;
        if (groups != null && groups.path("data").isArray()) {
            int scanned = 0;
            for (JsonNode group : groups.path("data")) {
                if (scanned++ >= 22 || (forwardId != null && !forwardId.isBlank())) {
                    break;
                }
                String scanGroup = group.path("group_id").asText("");
                if (scanGroup.isBlank()) {
                    continue;
                }
                JsonNode forwardHistory = call("get_group_msg_history", Map.of(
                        "group_id", Long.parseLong(scanGroup), "count", 30,
                        "reverse_order", true, "parse_mult_msg", true,
                        "disable_get_url", false));
                if (forwardHistory == null
                        || !forwardHistory.path("data").path("messages").isArray()) {
                    continue;
                }
                JsonNode scanMessages = forwardHistory.path("data").path("messages");
                for (JsonNode message : scanMessages) {
                    JsonNode content = message.path("content");
                    if (!content.isArray()) {
                        content = message.path("message");
                    }
                    if (!content.isArray()) {
                        continue;
                    }
                    for (JsonNode segment : content) {
                        if ("forward".equals(segment.path("type").asText(""))) {
                            forwardId = segment.path("data").path("id").asText("");
                            break;
                        }
                    }
                    if (forwardId != null && !forwardId.isBlank()) {
                        break;
                    }
                }
                System.out.println("forward_scan group=" + scanGroup
                        + " messages=" + scanMessages.size()
                        + " found=" + (forwardId != null && !forwardId.isBlank()));
            }
        }
        if (forwardId == null || forwardId.isBlank()) {
            System.out.println("get_forward_msg SKIP no forward segment in recent history");
        } else {
            JsonNode forward = call("get_forward_msg", Map.of("id", forwardId));
            if (forward != null) {
                JsonNode data = forward.path("data");
                JsonNode messages = data.path("messages");
                JsonNode message = data.path("message");
                JsonNode nodes = messages.isArray() ? messages : message;
                System.out.println("get_forward_msg retcode=" + forward.path("retcode").asInt(-1)
                        + " dataKeys=" + keys(data)
                        + " shape=" + (messages.isArray() ? "messages" : message.isArray()
                        ? "message" : "unknown")
                        + " nodes=" + (nodes.isArray() ? nodes.size() : -1));
                if (nodes.isArray() && nodes.size() > 0) {
                    System.out.println("  node keys=" + keys(nodes.get(0))
                            + " contentArray=" + nodes.get(0).path("content").isArray()
                            + " messageArray=" + nodes.get(0).path("message").isArray());
                }
            }
        }

        JsonNode system = call("get_group_system_msg", Map.of());
        if (system != null) {
            JsonNode data = system.path("data");
            System.out.println("get_group_system_msg retcode=" + system.path("retcode").asInt(-1)
                    + " invited=" + (data.path("invited_requests").isArray()
                    ? data.path("invited_requests").size() : -1)
                    + " joins=" + (data.path("join_requests").isArray()
                    ? data.path("join_requests").size() : -1));
        }

        client.close();
        System.out.println("PROBE_DONE failures=" + failures);
    }

    private static JsonNode call(String action, Map<String, Object> params) {
        try {
            JsonNode response = client.sendAction(action, params)
                    .orTimeout(15, TimeUnit.SECONDS)
                    .join();
            return response;
        } catch (RuntimeException failure) {
            failures++;
            System.out.println(action + " ERROR chain=" + causeChain(failure)
                    + " message=" + rootMessage(failure));
            return null;
        }
    }

    private static String keys(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "not-object";
        }
        StringBuilder builder = new StringBuilder("[");
        node.fieldNames().forEachRemaining(name -> {
            if (builder.length() > 1) {
                builder.append(',');
            }
            builder.append(name);
        });
        return builder.append(']').toString();
    }

    private static String causeChain(Throwable failure) {
        StringBuilder builder = new StringBuilder();
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 6) {
            if (builder.length() > 0) {
                builder.append(">");
            }
            builder.append(current.getClass().getSimpleName());
            current = current.getCause();
        }
        return builder.toString();
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? "" : message.substring(0, Math.min(120, message.length()));
    }
}
