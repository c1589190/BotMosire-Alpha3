package io.github.c1589190.bma3.qqofficial.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GatewayDispatchSecurityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void toStringRendersOnlyWhitelistedTypeCountAndSequence() throws Exception {
        String body = "body-must-never-appear";
        String authToken = "fake-auth-token-must-never-appear";
        String eventId = "event-id-must-never-appear";
        JsonNode data = JSON.readTree("""
                {
                  "content": "%s",
                  "message_scene": {"ext": {"auth_token": "%s"}},
                  "malicious_field_name": "malicious-field-value",
                  "nested": {"secret": "nested-secret-value"},
                  "id": "message-id-must-never-appear"
                }
                """.formatted(body, authToken));

        GatewayDispatch dispatch = new GatewayDispatch(
                eventId, "C2C_MESSAGE_CREATE", 42L, data, Instant.now());
        String rendered = dispatch.toString();

        assertThat(rendered)
                .contains("eventType=C2C_MESSAGE_CREATE")
                .contains("dataFields=5")
                .contains("sequence=42")
                .doesNotContain(body, authToken, eventId, "message-id-must-never-appear",
                        "malicious_field_name", "malicious-field-value",
                        "nested", "nested-secret-value", "content", "message_scene",
                        "auth_token");
    }

    @Test
    void maliciousEventTypeAndUnknownPayloadNeverEscape() throws Exception {
        String maliciousType = "EVIL_TYPE\npayload=secret-value";
        JsonNode data = JSON.readTree("""
                {"content": "secret-body", "auth_token": "secret-token", "x": "secret-x"}
                """);

        GatewayDispatch dispatch = new GatewayDispatch(
                "secret-event-id", maliciousType, 7L, data, Instant.EPOCH);
        String rendered = dispatch.toString();

        assertThat(rendered)
                .isEqualTo("GatewayDispatch[eventType=UNKNOWN, dataFields=3, sequence=7]")
                .doesNotContain("EVIL_TYPE", "payload", "secret-value", "secret-body",
                        "secret-token", "secret-x", "secret-event-id", "auth_token", "content");
    }
}
