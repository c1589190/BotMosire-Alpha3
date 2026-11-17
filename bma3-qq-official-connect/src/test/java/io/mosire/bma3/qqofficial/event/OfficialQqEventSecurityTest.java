package io.mosire.bma3.qqofficial.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.bma3.core.message.Segment;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OfficialQqEventSecurityTest {

    private static final List<String> SECRETS = List.of(
            "content-secret",
            "author-secret",
            "mention-secret",
            "url-secret",
            "file-secret",
            "ark-secret",
            "client-secret",
            "reference-secret",
            "scene-secret");

    private final OfficialQqEventDecoder decoder =
            new OfficialQqEventDecoder(Clock.fixed(
                    Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void secretBearingFieldsAtAnyNestingDepthNeverReachEventOrSegments() {
        OfficialQqEvent event = decoder.decodeMessage(OfficialQqFixtures.secretBearingMessage())
                .orElseThrow();

        String eventView = event.toString();
        for (String secret : SECRETS) {
            assertThat(eventView).as(secret).doesNotContain(secret);
        }
        assertThat(eventView)
                .doesNotContain("auth_token", "access_token", "client_secret")
                .doesNotContain("普通", "https://");

        String allSegmentData = event.segments().stream()
                .flatMap(segment -> segment.data().entrySet().stream())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce("", (left, right) -> left + "\n" + right);
        for (String secret : SECRETS) {
            assertThat(allSegmentData).as(secret).doesNotContain(secret);
        }
        assertThat(allSegmentData).doesNotContain("auth_token", "access_token", "client_secret");

        assertThat(event.text())
                .contains("[REDACTED]")
                .doesNotContain("content-secret");
        assertThat(event.text()).doesNotContain("https://");
        assertThat(event.segments()).allSatisfy(segment ->
                assertThat(segment.text()).doesNotContain("https://"));
        assertThat(event.segments()).anyMatch(segment -> "image".equals(segment.type())
                && segment.data("url").equals("https://cdn.example/a.png?rkey=keep-rkey"));
        assertThat(event.segments()).anyMatch(segment -> "image".equals(segment.type())
                && segment.data("file_info").contains("file-uuid"));
        assertThat(event.refMsgIdx()).isEqualTo("REFIDX_SECRET_REF");
        assertThat(event.msgIdx()).isEqualTo("REFIDX_SECRET_MSG");
    }

    @Test
    void redactorRemovesCredentialShapedKeysRecursivelyAndRedactsInlineAssignments() throws Exception {
        JsonNode tree = new ObjectMapper().readTree("""
                {
                  "keep":"visible",
                  "auth_token":"one",
                  "nested": {
                    "access-token":"two",
                    "clientSecret":"three",
                    "list": [{"app_secret":"four", "safe":"still-visible"}]
                  },
                  "url":"https://example.test/x?auth_token=five&rkey=six",
                  "prose":"before auth_token=seven after"
                }
                """);

        String safe = OfficialQqSecretRedactor.safeJson(tree);

        assertThat(safe)
                .contains("visible", "still-visible", "rkey=six")
                .doesNotContain("one", "two", "three", "four", "five", "seven")
                .doesNotContain("auth_token", "access-token", "clientSecret", "app_secret");
        assertThat(OfficialQqSecretRedactor.redactText("x access_token=abc y"))
                .isEqualTo("x [REDACTED] y");
        assertThat(OfficialQqSecretRedactor.sanitizeUrl(
                        "https://cdn.test/a?auth_token=abc&rkey=ok&access_token=def#auth_token=ghi"))
                .isEqualTo("https://cdn.test/a?rkey=ok");
    }

    @Test
    void urlRulesRejectUnsafeShapesAndHandleEncodedKeysWithoutMisclassifyingFacts() throws Exception {
        assertThat(OfficialQqSecretRedactor.sanitizeUrl("http://cdn.test/a?rkey=ok"))
                .as("structured URLs must be HTTPS").isEmpty();
        assertThat(OfficialQqSecretRedactor.sanitizeUrl(
                        "https://user:password@cdn.test/a?rkey=ok"))
                .as("userinfo is never accepted").isEmpty();
        assertThat(OfficialQqSecretRedactor.sanitizeUrl("not a url"))
                .isEmpty();
        assertThat(OfficialQqSecretRedactor.sanitizeUrl(
                        "https://cdn.test/a?auth%5Ftoken=encoded-secret&rkey=ok"))
                .isEqualTo("https://cdn.test/a?rkey=ok");
        assertThat(OfficialQqSecretRedactor.sanitizeUrl(
                        "https://cdn.test/a?access%2Dtoken=encoded-secret&rkey=ok"))
                .isEqualTo("https://cdn.test/a?rkey=ok");
        assertThat(OfficialQqSecretRedactor.sanitizeUrl(
                        "https://cdn.test/a?auth%255Ftoken=double-secret&rkey=ok"))
                .isEqualTo("https://cdn.test/a?rkey=ok");

        assertThat(OfficialQqSecretRedactor.redactText(
                        "auth_token=\"quoted secret\" token='single secret' tokenizer=keep"))
                .contains("[REDACTED]", "tokenizer=keep")
                .doesNotContain("quoted secret", "single secret");
        assertThat(OfficialQqSecretRedactor.sensitiveKey("tokenizer")).isFalse();
        assertThat(OfficialQqSecretRedactor.sensitiveKey("token_type")).isFalse();
        assertThat(OfficialQqSecretRedactor.sensitiveKey("tokens")).isFalse();
        assertThat(OfficialQqSecretRedactor.sensitiveKey("auth_token")).isTrue();
        assertThat(OfficialQqSecretRedactor.sensitiveKey("accessToken")).isTrue();

        JsonNode deep = new ObjectMapper().readTree("""
                {
                  "tokenizer":"keep-tokenizer",
                  "token_type":"keep-token-type",
                  "tokens":"ordinary-tokens",
                  "nested": {
                    "url":"https://cdn.test/deep?auth%5Ftoken=deep-secret&rkey=ok"
                  },
                  "plainHttp":"http://cdn.test/plain",
                  "userinfo":"https://user:pass@cdn.test/private"
                }
                """);
        String safe = OfficialQqSecretRedactor.safeJson(deep);
        assertThat(safe)
                .contains("keep-tokenizer", "keep-token-type", "ordinary-tokens",
                        "rkey=ok", "[BLOCKED_URL]")
                .doesNotContain("deep-secret", "auth%5Ftoken",
                        "http://cdn.test/plain", "user:pass");
    }

    @Test
    void toStringEmitsOnlyWhitelistedTypeAndNonStringMetadataButAccessorsKeepFacts() {
        Instant receivedAt = Instant.parse("2026-11-17T08:00:00Z");
        OfficialQqEvent message = new OfficialQqEvent(
                "TOKEN-EVENT-ID\nINJECT",
                "C2C_MESSAGE_CREATE\nINJECT",
                "TOKEN-MESSAGE-ID",
                "TOKEN-GROUP-OPENID",
                "TOKEN-USER-OPENID",
                "TOKEN-MEMBER-OPENID",
                "TOKEN-USERNAME",
                "TOKEN-MEMBER-NICK",
                "TOKEN-ROLE",
                receivedAt,
                List.of(new Segment("text", "TOKEN-BODY-SECRET",
                        Map.of("url", "https://example.test/TOKEN-URL-SECRET"))),
                "TOKEN-REF",
                "TOKEN-TRACE",
                "TOKEN-MSGIDX",
                true,
                true,
                true,
                9L,
                receivedAt);
        String messageLog = message.toString();
        assertThat(messageLog)
                .contains("UNKNOWN_MESSAGE_EVENT", "segmentCount=1")
                .doesNotContain("TOKEN-", "\n");
        assertThat(message.eventId()).isEqualTo("TOKEN-EVENT-ID\nINJECT");
        assertThat(message.messageId()).isEqualTo("TOKEN-MESSAGE-ID");
        assertThat(message.traceId()).isEqualTo("TOKEN-TRACE");
        assertThat(message.text()).isEqualTo("TOKEN-BODY-SECRET");

        OfficialQqSystemEvent system = new OfficialQqSystemEvent(
                "TOKEN-SYSTEM-EVENT-ID",
                "FRIEND_ADD\nINJECT",
                10L,
                receivedAt,
                receivedAt,
                false,
                "TOKEN-GROUP-OPENID",
                "TOKEN-USER-OPENID",
                "TOKEN-MEMBER-OPENID",
                "TOKEN-OPERATOR-OPENID",
                "TOKEN-USERNAME",
                "TOKEN-MEMBER-NICK",
                "TOKEN-SESSION-ID",
                "TOKEN-TRACE");
        assertThat(system.toString())
                .contains("UNKNOWN_SYSTEM_EVENT")
                .doesNotContain("TOKEN-", "\n");
        assertThat(system.sessionId()).isEqualTo("TOKEN-SESSION-ID");
        assertThat(system.traceId()).isEqualTo("TOKEN-TRACE");

        OfficialQqUnknownEvent unknown = new OfficialQqUnknownEvent(
                "TOKEN-UNKNOWN-EVENT-ID",
                "TOKEN-UNKNOWN-TYPE\nINJECT",
                11L,
                receivedAt,
                123);
        assertThat(unknown.toString())
                .contains("payloadLength=123")
                .doesNotContain("TOKEN-", "\n");
        assertThat(unknown.eventId()).isEqualTo("TOKEN-UNKNOWN-EVENT-ID");
        assertThat(unknown.eventType()).isEqualTo("TOKEN-UNKNOWN-TYPE\nINJECT");

        OfficialQqEvent whitelistedType = new OfficialQqEvent(
                "event-ok", "C2C_MESSAGE_CREATE", "message-ok", "", "user-ok", "",
                "Alice", "", "", receivedAt, List.of(), "", "", "",
                false, false, false, 12L, receivedAt);
        assertThat(whitelistedType.toString())
                .contains("eventType=C2C_MESSAGE_CREATE")
                .doesNotContain("event-ok", "message-ok", "user-ok", "Alice");
    }

    @Test
    void unknownDispatchDoesNotStorePayloadContentOrSecrets() {
        OfficialQqUnknownEvent unknown = (OfficialQqUnknownEvent) decoder
                .decode(OfficialQqFixtures.unknownEvent()).orElseThrow();

        assertThat(unknown.toString())
                .doesNotContain("unknown body must not be retained")
                .doesNotContain("unknown-secret", "unknown-access-secret", "auth_token");
        assertThat(unknown.payloadLength()).isPositive();
    }
}
