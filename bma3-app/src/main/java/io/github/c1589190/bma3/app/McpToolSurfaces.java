package io.github.c1589190.bma3.app;

import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The tools registered on the daily endpoint and its complementary operations endpoint. */
final class McpToolSurfaces {

    private static final Set<String> DAILY = Set.of(
            "qq_account_list", "qq_friend_list", "qq_group_list", "qq_thread_list",
            "qq_conversation_read", "qq_conversation_ack", "qq_conversation_history",
            "qq_message_get", "qq_search", "qq_media_view", "qq_conversation_send", "qq_reply",
            "qq_conversation_send_image", "qq_conversation_send_file",
            "qq_channel_thread_list", "qq_thread_history",
            "qq_official_conversation_list", "qq_official_conversation_history",
            "qq_official_send_text", "qq_official_reply",
            "inbox_status", "inbox_items", "inbox_get", "inbox_ack", "inbox_wait_quiet",
            "memory_write", "memory_search", "memory_view", "memory_rate", "memory_context");

    private McpToolSurfaces() {
    }

    static Map<String, List<AgentTool>> additionalRoutes(String fullPath, List<AgentTool> tools) {
        return Map.of(
                dailyPath(fullPath), tools.stream().filter(tool -> DAILY.contains(tool.name())).toList(),
                opsPath(fullPath), tools.stream().filter(tool -> !DAILY.contains(tool.name())).toList());
    }

    static String dailyPath(String fullPath) {
        return fullPath.trim().replaceFirst("/+$", "") + "/daily";
    }

    static String opsPath(String fullPath) {
        return fullPath.trim().replaceFirst("/+$", "") + "/ops";
    }
}
