package io.github.c1589190.bma3.qqofficial.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.qqofficial.send.OfficialQqSendOutcome;
import io.github.c1589190.bma3.qqofficial.send.OfficialQqSendService;
import java.util.List;

/** Shared safe JSON folding for the two official write tools. */
final class QqOfficialSendToolResults {

    private static final ObjectMapper JSON = new ObjectMapper();

    private QqOfficialSendToolResults() {
    }

    static ToolResult result(List<OfficialQqSendOutcome> outcomes) {
        ArrayNode array = JSON.createArrayNode();
        boolean boardWriteFailed = false;
        for (OfficialQqSendOutcome outcome : outcomes) {
            array.add(outcome.json());
            boardWriteFailed |= OfficialQqSendService.BOARD_WRITE_FAILED.equals(
                    outcome.errorCode());
        }
        return boardWriteFailed
                ? ToolResult.error(OfficialQqSendService.BOARD_WRITE_FAILED, array.toString())
                : ToolResult.ok(array.toString());
    }
}
