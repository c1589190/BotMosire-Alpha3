package io.mosire.bma3.qqofficial.media;

import io.mosire.agentlib.tool.AgentTool;
import java.util.Objects;

/**
 * Factories for the Phase 6 media tools. They are intentionally not registered in App in this
 * sub-step; wiring and host exposure remain a later responsibility.
 */
public final class QqOfficialMediaTools {

    public static final String IMAGE_TOOL_NAME = "qq_official_send_image";
    public static final String FILE_TOOL_NAME = "qq_official_send_file";

    private QqOfficialMediaTools() {
    }

    public static AgentTool sendImage(OfficialQqMediaService service) {
        return new QqOfficialSendMediaTool(
                Objects.requireNonNull(service, "service"), 1, IMAGE_TOOL_NAME);
    }

    public static AgentTool sendFile(OfficialQqMediaService service) {
        return new QqOfficialSendMediaTool(
                Objects.requireNonNull(service, "service"), 4, FILE_TOOL_NAME);
    }
}
