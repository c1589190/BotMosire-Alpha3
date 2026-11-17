package io.mosire.bma3.qqconnect.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.ToolResult;
import org.junit.jupiter.api.Test;

class QqGroupRequestListToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsBothClassifiedAndFlatProviderShapesWithoutLeakingFlags() throws Exception {
        JsonNode classified = mapper.readTree("""
                {"status":"ok","retcode":0,"data":{
                  "invited_requests":[{"request_id":1,"group_id":10}],
                  "join_requests":[{"request_id":2,"group_id":20}]
                }}
                """);
        ToolResult legacy = QqGroupRequestListTool.parseResponse(classified, 10);
        assertThat(legacy.success()).isTrue();
        JsonNode legacyRows = mapper.readTree(legacy.message()).path("requests");
        assertThat(legacyRows).hasSize(2);
        assertThat(legacyRows.get(0).path("kind").asText()).isEqualTo("INVITE");
        assertThat(legacyRows.get(1).path("kind").asText()).isEqualTo("JOIN");

        JsonNode flat = mapper.readTree("""
                {"status":"ok","retcode":0,"data":[
                  {"request_id":3,"group_id":30,"flag":"provider-secret-flag"},
                  {"request_id":4,"group_id":40,"sub_type":"invite","flag":"another-flag"}
                ]}
                """);
        ToolResult snow = QqGroupRequestListTool.parseResponse(flat, 10);
        assertThat(snow.success()).isTrue();
        assertThat(snow.message()).doesNotContain("provider-secret-flag", "another-flag");
        JsonNode flatRows = mapper.readTree(snow.message()).path("requests");
        assertThat(flatRows).hasSize(2);
        assertThat(flatRows.get(0).path("kind").asText()).isEqualTo("UNKNOWN");
        assertThat(flatRows.get(0).path("decisionSupported").asBoolean()).isFalse();
        assertThat(flatRows.get(1).path("kind").asText()).isEqualTo("INVITE");
    }

    @Test
    void rejectsFailedAndUnknownShapesInsteadOfReportingAnEmptyList() throws Exception {
        for (String json : new String[] {
                "{\"status\":\"failed\",\"retcode\":1200,\"data\":null}",
                "{\"status\":\"ok\",\"retcode\":0,\"data\":{\"other\":[]}}",
                "{\"status\":\"ok\",\"retcode\":0,\"data\":{\"join_requests\":{}}}",
                "{\"status\":\"ok\",\"retcode\":0,\"data\":[null]}"
        }) {
            ToolResult result = QqGroupRequestListTool.parseResponse(mapper.readTree(json), 10);
            assertThat(result.success()).as(json).isFalse();
            assertThat(result.code()).isEqualTo("GROUP_REQUEST_LIST_FAILED");
        }
    }

    @Test
    void honoursLimitForFlatAndClassifiedShapes() throws Exception {
        JsonNode response = mapper.readTree("""
                {"status":"ok","retcode":0,"data":[{"request_id":1},{"request_id":2}]}
                """);
        ToolResult result = QqGroupRequestListTool.parseResponse(response, 1);
        assertThat(mapper.readTree(result.message()).path("requests")).hasSize(1);
        assertThat(mapper.readTree(result.message()).path("total").asInt()).isEqualTo(1);
    }
}
