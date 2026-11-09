package io.mosire.bma3.inbox.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.inbox.core.InboxService;
import java.util.List;

/** Tool catalog for the inbox module. */
public final class InboxTools {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private InboxTools() {
    }

    public static List<AgentTool> all(InboxService service) {
        return List.of(new InboxStatusTool(service), new InboxListTool(service),
                new InboxAckTool(service));
    }

    static ToolResult json(Object value) {
        try {
            return ToolResult.ok(MAPPER.writeValueAsString(value));
        } catch (Exception failure) {
            return ToolResult.error("SERIALIZATION_FAILED", "无法序列化结果");
        }
    }
}
