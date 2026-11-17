package io.mosire.bma3.qqofficial.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.qqofficial.gateway.GatewayDispatch;
import io.mosire.bma3.qqofficial.gateway.GatewayEventSink;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OfficialQqEventDecoderTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-11-17T00:00:00Z");
    private final OfficialQqEventDecoder decoder =
            new OfficialQqEventDecoder(Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));

    @Test
    void decodesC2CTextAndKeepsEveryOfficialIdAsAnExactString() {
        OfficialQqEvent event = decoder.decodeMessage(OfficialQqFixtures.c2cText()).orElseThrow();

        assertThat(event.eventId()).isEqualTo("event-c2c-001");
        assertThat(event.eventType()).isEqualTo("C2C_MESSAGE_CREATE");
        assertThat(event.messageId()).isEqualTo("ROBOT1.0_00123456789012345678901234567890");
        assertThat(event.userOpenid())
                .isEqualTo("00123456789012345678901234567890")
                .doesNotStartWith("1.234");
        assertThat(event.groupOpenid()).isEmpty();
        assertThat(event.memberOpenid()).isEmpty();
        assertThat(event.username()).isEqualTo("Alice");
        assertThat(event.at()).isEqualTo(Instant.parse("2026-11-17T08:09:10.123Z"));
        assertThat(event.timestampFallback()).isFalse();
        assertThat(event.sequence()).isEqualTo(42L);
        assertThat(event.receivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(event.text()).isEqualTo("你好 official");
        assertThat(event.source("account-1").format())
                .isEqualTo("qq_official_private/account-1/00123456789012345678901234567890");
        assertThat(event.senderId()).isEqualTo("00123456789012345678901234567890");
        assertThat(event.msgIdx()).isEqualTo("REFIDX_C2C");
        assertThat(event.refMsgIdx()).isEmpty();
        assertThat(event.atBot()).isFalse();
        assertThat(event.atAll()).isFalse();
        assertThat(event.contentComplete()).isTrue();
        assertThat(event.segments()).anyMatch(segment -> "quote".equals(segment.type())
                && "REFIDX_C2C".equals(segment.data("msg_idx")));
    }

    @Test
    void decodesGroupAtMentionsAndAllAttachmentKindsWithoutLeakingUrlsIntoBody() {
        OfficialQqEvent event =
                decoder.decodeMessage(OfficialQqFixtures.groupAtWithAttachmentsAndMentions())
                        .orElseThrow();

        assertThat(event.eventType()).isEqualTo("GROUP_AT_MESSAGE_CREATE");
        assertThat(event.groupMessage()).isTrue();
        assertThat(event.privateMessage()).isFalse();
        assertThat(event.groupOpenid()).isEqualTo("0011223344556677889900112233445566778899");
        assertThat(event.memberOpenid()).isEqualTo("MEMBER_00112233445566778899");
        assertThat(event.memberNickname()).isEqualTo("群名片Bob");
        assertThat(event.memberRole()).isEqualTo("admin");
        assertThat(event.at()).isEqualTo(Instant.parse("2026-11-17T08:09:10Z"));
        assertThat(event.source("official-main").format())
                .isEqualTo("qq_official_group/official-main/"
                        + "0011223344556677889900112233445566778899");
        assertThat(event.atBot()).isTrue();
        assertThat(event.atAll()).isTrue();
        assertThat(event.contentComplete()).isTrue();
        assertThat(event.text()).isEqualTo("看图");

        assertThat(event.segments()).extracting(Segment::type)
                .contains("at", "image", "video", "record", "file");
        assertThat(event.segments()).extracting(Segment::text)
                .contains("[图片]", "[视频]", "[语音]", "[文件]");
        assertThat(event.segments()).allSatisfy(segment ->
                assertThat(segment.text()).doesNotContain("https://"));

        Segment image = first(event, "image");
        assertThat(image.text()).isEqualTo("[图片]");
        assertThat(image.data("url"))
                .isEqualTo("https://cdn.example/cat.png?rkey=keep")
                .doesNotContain("image-secret");
        assertThat(image.data("filename")).isEqualTo("cat.png");
        assertThat(image.data("content_type")).isEqualTo("image/png");
        assertThat(image.data("width")).isEqualTo("640");
        assertThat(image.data("height")).isEqualTo("480");
        assertThat(first(event, "video").data("url")).doesNotContain("video-secret");
        assertThat(first(event, "record").data("filename")).isEqualTo("voice.silk");
        assertThat(first(event, "file").data("filename")).isEqualTo("报告.pdf");
        assertThat(event.toString()).doesNotContain("image-secret", "video-secret");
    }

    @Test
    void decodesGroupWideMessageWithoutClaimingAtBot() {
        OfficialQqEvent event = decoder.decodeMessage(OfficialQqFixtures.groupFullMessage())
                .orElseThrow();

        assertThat(event.eventType()).isEqualTo("GROUP_MESSAGE_CREATE");
        assertThat(event.groupOpenid()).isEqualTo("GROUP_FULL_OPENID");
        assertThat(event.memberOpenid()).isEqualTo("MEMBER_FULL");
        assertThat(event.memberNickname()).isEqualTo("Carol");
        assertThat(event.atBot()).isFalse();
        assertThat(event.atAll()).isFalse();
        assertThat(event.text()).isEqualTo("群全量消息");
        assertThat(event.segments()).anyMatch(segment -> "at".equals(segment.type())
                && "MEMBER_OTHER".equals(segment.data("openid")));
    }

    @Test
    void decodesArkReferencesAndNestedElementsIntoStructuredSegments() {
        OfficialQqEvent event = decoder.decodeMessage(OfficialQqFixtures.arkQuoteAndNestedElements())
                .orElseThrow();

        assertThat(event.msgIdx()).isEqualTo("REFIDX_MSG");
        assertThat(event.refMsgIdx()).isEqualTo("REFIDX_REF");
        assertThat(event.segments().getFirst().type()).isEqualTo("quote");
        assertThat(event.segments().getFirst().data("msg_idx")).isEqualTo("REFIDX_MSG");
        assertThat(event.segments().getFirst().data("ref_msg_idx")).isEqualTo("REFIDX_REF");
        assertThat(event.text()).contains("外层文本", "引用内文");
        assertThat(event.contentComplete()).isFalse();

        List<Segment> cards = event.segments().stream()
                .filter(segment -> "card".equals(segment.type())).toList();
        assertThat(cards).hasSize(2);
        assertThat(cards.getFirst().text()).contains("卡片标题");
        assertThat(cards.getFirst().data("url")).isEqualTo("https://example.com/card?x=1");
        assertThat(cards.getFirst().data("raw")).contains("\"keep\":\"ok\"");
        assertThat(cards.get(1).text()).contains("嵌套卡片");
        assertThat(cards.get(1).data("url")).isEqualTo("https://example.com/n");
        assertThat(event.segments()).anyMatch(segment -> "image".equals(segment.type())
                && segment.data("url").equals("https://cdn.example/nested.jpg?rkey=nested-key"));
    }

    @Test
    void contentCompletenessRejectsRedactionCollapseAndUnknownStructure() {
        OfficialQqEvent plain = decoder.decodeMessage(
                OfficialQqFixtures.c2cMessageWithContent("ordinary plain text")).orElseThrow();
        assertThat(plain.contentComplete()).isTrue();
        assertThat(plain.text()).isEqualTo("ordinary plain text");

        OfficialQqEvent first = decoder.decodeMessage(
                OfficialQqFixtures.c2cMessageWithContent(
                        "before auth_token=first-secret after")).orElseThrow();
        OfficialQqEvent second = decoder.decodeMessage(
                OfficialQqFixtures.c2cMessageWithContent(
                        "before auth_token=second-secret after")).orElseThrow();
        assertThat(first.text())
                .isEqualTo(second.text())
                .isEqualTo("before [REDACTED] after");
        assertThat(first.contentComplete()).isFalse();
        assertThat(second.contentComplete()).isFalse();
        assertThat(first.toString())
                .doesNotContain("first-secret", "second-secret", "before [REDACTED]");

        OfficialQqEvent structured = decoder.decodeMessage(
                OfficialQqFixtures.arkQuoteAndNestedElements()).orElseThrow();
        assertThat(structured.contentComplete()).isFalse();
    }

    @Test
    void fallsBackToServerReceiveTimeForMissingOrInvalidTimestamp() {
        OfficialQqEvent missing =
                decoder.decodeMessage(OfficialQqFixtures.messageWithoutTimestamp("no time"))
                        .orElseThrow();
        OfficialQqEvent invalid =
                decoder.decodeMessage(OfficialQqFixtures.messageWithInvalidTimestamp())
                        .orElseThrow();

        assertThat(missing.timestampFallback()).isTrue();
        assertThat(missing.at()).isEqualTo(RECEIVED_AT);
        assertThat(invalid.timestampFallback()).isTrue();
        assertThat(invalid.at()).isEqualTo(RECEIVED_AT);
        assertThat(invalid.text()).isEqualTo("time fallback");
    }

    @Test
    void decodesReadyResumedDirectoryAndReceiveSwitchEvents() {
        record Case(String type, String body, String expectedGroup, String expectedUser,
                    String expectedOperator, String expectedSession) {
        }
        List<Case> cases = List.of(
                new Case("READY",
                        """
                        {"session_id":"session-001","user_openid":"BOT_USER",
                         "timestamp":"2026-11-17T08:00:00Z"}
                        """,
                        "", "BOT_USER", "", "session-001"),
                new Case("RESUMED",
                        """
                        {"session_id":"session-001","timestamp":"2026-11-17T08:00:01Z"}
                        """,
                        "", "", "", "session-001"),
                new Case("FRIEND_ADD",
                        """
                        {"openid":"FRIEND_OPENID_001","timestamp":"2026-11-17T08:00:02Z"}
                        """,
                        "", "FRIEND_OPENID_001", "", ""),
                new Case("FRIEND_DEL",
                        """
                        {"openid":"FRIEND_OPENID_002","timestamp":"2026-11-17T08:00:03Z"}
                        """,
                        "", "FRIEND_OPENID_002", "", ""),
                new Case("GROUP_ADD_ROBOT",
                        """
                        {"group_openid":"GROUP_ADD_OPENID","op_member_openid":"OP_ADD",
                         "timestamp":"2026-11-17T08:00:04Z"}
                        """,
                        "GROUP_ADD_OPENID", "", "OP_ADD", ""),
                new Case("GROUP_DEL_ROBOT",
                        """
                        {"group_openid":"GROUP_DEL_OPENID","op_member_openid":"OP_DEL",
                         "timestamp":"2026-11-17T08:00:05Z"}
                        """,
                        "GROUP_DEL_OPENID", "", "OP_DEL", ""),
                new Case("GROUP_MSG_RECEIVE",
                        """
                        {"group_openid":"GROUP_RECEIVE_OPENID","op_member_openid":"OP_RECEIVE",
                         "timestamp":"2026-11-17T08:00:06Z"}
                        """,
                        "GROUP_RECEIVE_OPENID", "", "OP_RECEIVE", ""),
                new Case("GROUP_MSG_REJECT",
                        """
                        {"group_openid":"GROUP_REJECT_OPENID","op_member_openid":"OP_REJECT",
                         "timestamp":"2026-11-17T08:00:07Z"}
                        """,
                        "GROUP_REJECT_OPENID", "", "OP_REJECT", ""));

        for (Case testCase : cases) {
            OfficialQqSystemEvent event = decoder.decodeSystem(
                    OfficialQqFixtures.systemEvent(testCase.type(), testCase.body())).orElseThrow();
            assertThat(event.eventType()).as(testCase.type()).isEqualTo(testCase.type());
            assertThat(event.groupOpenid()).as(testCase.type())
                    .isEqualTo(testCase.expectedGroup());
            assertThat(event.userOpenid()).as(testCase.type())
                    .isEqualTo(testCase.expectedUser());
            assertThat(event.operatorOpenid()).as(testCase.type())
                    .isEqualTo(testCase.expectedOperator());
            assertThat(event.sessionId()).as(testCase.type())
                    .isEqualTo(testCase.expectedSession());
            assertThat(event.timestampFallback()).as(testCase.type()).isFalse();
            assertThat(event.receivedAt()).isEqualTo(RECEIVED_AT);
        }
    }

    @Test
    void unknownEventRetainsOnlyMetadataAndPayloadLength() {
        OfficialQqDispatch dispatch = decoder.decode(OfficialQqFixtures.unknownEvent()).orElseThrow();

        assertThat(dispatch).isInstanceOf(OfficialQqUnknownEvent.class);
        OfficialQqUnknownEvent unknown = (OfficialQqUnknownEvent) dispatch;
        assertThat(unknown.eventId()).isEqualTo("event-future-001");
        assertThat(unknown.eventType()).isEqualTo("FUTURE_MESSAGE_CREATE");
        assertThat(unknown.sequence()).isEqualTo(50L);
        assertThat(unknown.receivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(unknown.payloadLength()).isPositive();
        assertThat(unknown.toString())
                .doesNotContain("unknown body must not be retained")
                .doesNotContain("unknown-secret", "unknown-access-secret");
    }

    @Test
    void nonDispatchControlFramesAreIgnored() {
        assertThat(decoder.decode(OfficialQqFixtures.helloControlFrame())).isEmpty();
        assertThat(decoder.decodeMessage(OfficialQqFixtures.helloControlFrame())).isEmpty();
        assertThat(decoder.decodeSystem(OfficialQqFixtures.helloControlFrame())).isEmpty();
    }

    @Test
    void malformedJsonFailsSafelyWithoutEchoingInput() {
        assertThatThrownBy(() -> decoder.decode(OfficialQqFixtures.malformedWithSecret()))
                .isInstanceOf(OfficialQqDecodeException.class)
                .satisfies(failure -> assertThat(((OfficialQqDecodeException) failure).code())
                        .isEqualTo(OfficialQqDecodeException.MALFORMED_JSON))
                .hasMessageContaining("JSON")
                .hasMessageNotContaining("malformed-secret");
        assertThatThrownBy(() -> decoder.decode("[]"))
                .isInstanceOf(OfficialQqDecodeException.class)
                .hasMessageNotContaining("[]");
    }

    @Test
    void injectableHandlerReceivesExactlyOneDecodedDispatch() {
        List<OfficialQqDispatch> received = new ArrayList<>();

        boolean emitted = decoder.dispatch(
                OfficialQqFixtures.groupFullMessage(), received::add);

        assertThat(emitted).isTrue();
        assertThat(received).hasSize(1).first().isInstanceOf(OfficialQqEvent.class);
        assertThat(((OfficialQqEvent) received.getFirst()).text()).isEqualTo("群全量消息");
    }

    @Test
    void ordinaryContentAtTheLimitIsPreservedAndOverLimitFailsClosedWithoutEcho() {
        String atLimit = "x".repeat(OfficialQqEventDecoder.MAX_MESSAGE_TEXT_CHARS);
        OfficialQqEvent event = decoder.decodeMessage(
                OfficialQqFixtures.c2cMessageWithContent(atLimit)).orElseThrow();
        assertThat(event.text()).hasSize(OfficialQqEventDecoder.MAX_MESSAGE_TEXT_CHARS);

        String overLimit = "y".repeat(OfficialQqEventDecoder.MAX_MESSAGE_TEXT_CHARS + 1);
        assertThatThrownBy(() -> decoder.decodeMessage(
                        OfficialQqFixtures.c2cMessageWithContent(overLimit)))
                .isInstanceOf(OfficialQqDecodeException.class)
                .satisfies(failure -> assertThat(((OfficialQqDecodeException) failure).code())
                        .isEqualTo(OfficialQqDecodeException.CONTENT_TOO_LARGE))
                .hasMessageContaining("正文")
                .hasMessageNotContaining("yyyyyyyy");
    }

    @Test
    void excessiveAttachmentsMentionsSegmentsAndNestedDepthFailClosed() {
        assertThatThrownBy(() -> decoder.decodeMessage(
                        OfficialQqFixtures.groupMessageWithAttachments(
                                OfficialQqEventDecoder.MAX_ATTACHMENTS + 1)))
                .isInstanceOf(OfficialQqDecodeException.class)
                .satisfies(failure -> assertThat(((OfficialQqDecodeException) failure).code())
                        .isEqualTo(OfficialQqDecodeException.ATTACHMENT_LIMIT_EXCEEDED));

        assertThatThrownBy(() -> decoder.decodeMessage(
                        OfficialQqFixtures.groupMessageWithMentions(
                                OfficialQqEventDecoder.MAX_MENTIONS + 1)))
                .isInstanceOf(OfficialQqDecodeException.class)
                .satisfies(failure -> assertThat(((OfficialQqDecodeException) failure).code())
                        .isEqualTo(OfficialQqDecodeException.MENTION_LIMIT_EXCEEDED));

        assertThatThrownBy(() -> decoder.decodeMessage(
                        OfficialQqFixtures.groupMessageWithNestedContents(
                                OfficialQqEventDecoder.MAX_SEGMENTS + 1)))
                .isInstanceOf(OfficialQqDecodeException.class)
                .satisfies(failure -> assertThat(((OfficialQqDecodeException) failure).code())
                        .isEqualTo(OfficialQqDecodeException.SEGMENT_LIMIT_EXCEEDED));

        assertThatThrownBy(() -> decoder.decodeMessage(
                        OfficialQqFixtures.groupMessageWithNestedDepth(
                                OfficialQqEventDecoder.MAX_NESTING_DEPTH + 2)))
                .isInstanceOf(OfficialQqDecodeException.class)
                .satisfies(failure -> assertThat(((OfficialQqDecodeException) failure).code())
                        .isEqualTo(OfficialQqDecodeException.NESTING_LIMIT_EXCEEDED));

        assertThatThrownBy(() -> decoder.decodeMessage(
                        OfficialQqFixtures.groupMessageWithEmptyNestedElements(
                                OfficialQqEventDecoder.MAX_NESTED_ELEMENTS + 1)))
                .isInstanceOf(OfficialQqDecodeException.class)
                .satisfies(failure -> assertThat(((OfficialQqDecodeException) failure).code())
                        .isEqualTo(OfficialQqDecodeException.NESTING_LIMIT_EXCEEDED));
    }

    @Test
    void unknownPayloadLengthIsBoundedWithoutSerializingTheWholeData() {
        OfficialQqUnknownEvent unknown = (OfficialQqUnknownEvent) decoder.decode(
                OfficialQqFixtures.unknownEventWithHugePayload(200_000)).orElseThrow();

        assertThat(unknown.payloadLength())
                .isPositive()
                .isLessThanOrEqualTo(OfficialQqEventDecoder.MAX_UNKNOWN_PAYLOAD_LENGTH);
    }

    @Test
    void sourceRejectsUnsafeAccountAndOpenidSegmentsWithoutEchoingThem() {
        OfficialQqEvent event = decoder.decodeMessage(
                OfficialQqFixtures.messageWithGroupOpenid("GROUP/OTHER")).orElseThrow();
        assertThat(event.groupOpenid()).isEqualTo("GROUP/OTHER");

        assertThatThrownBy(() -> event.source(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountId");
        assertThatThrownBy(() -> event.source("acct/../other"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountId")
                .hasMessageNotContaining("acct/../other");
        assertThatThrownBy(() -> event.source("acct with space"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountId")
                .hasMessageNotContaining("acct with space");
        assertThatThrownBy(() -> event.source("acct\nother"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountId")
                .hasMessageNotContaining("acct");

        assertThatThrownBy(() -> event.source("official-main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("groupOpenid")
                .hasMessageNotContaining("GROUP/OTHER");

        OfficialQqEvent newlineOpenid = decoder.decodeMessage(
                OfficialQqFixtures.messageWithGroupOpenid("GROUP\\nOTHER")).orElseThrow();
        assertThat(newlineOpenid.groupOpenid()).isEqualTo("GROUP\nOTHER");
        assertThatThrownBy(() -> newlineOpenid.source("official-main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("groupOpenid")
                .hasMessageNotContaining("GROUP");

        OfficialQqEvent privateEvent = new OfficialQqEvent(
                "event-private-invalid", "C2C_MESSAGE_CREATE", "ROBOT1.0_PRIVATE",
                "", "USER/OTHER", "", "Alice", "", "", Instant.parse("2026-11-17T08:13:00Z"),
                List.of(), "", "", "", false, false, false, 1L,
                Instant.parse("2026-11-17T08:13:00Z"));
        assertThatThrownBy(() -> privateEvent.source("official-main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userOpenid")
                .hasMessageNotContaining("USER/OTHER");
    }

    @Test
    void pipeCannotCollapseDifferentSourceAndMessageIdPairsBeforeTheBoard() {
        String firstSource = "qq_official_group/official-main/G";
        String firstMessageId = "ROBOT1.0_M|N";
        String secondSource = "qq_official_group/official-main/G|ROBOT1.0_M";
        String secondMessageId = "N";
        assertThat(firstSource + "|" + firstMessageId)
                .as("fixture pair must be capable of colliding in the board key")
                .isEqualTo(secondSource + "|" + secondMessageId);

        assertThatThrownBy(() -> decoder.decodeMessage(
                        OfficialQqFixtures.groupMessageWithGroupOpenidAndMessageId(
                                "G", firstMessageId)))
                .isInstanceOf(OfficialQqDecodeException.class)
                .satisfies(failure -> assertThat(((OfficialQqDecodeException) failure).code())
                        .isEqualTo(OfficialQqDecodeException.INVALID_MESSAGE_ID))
                .hasMessageNotContaining(firstMessageId);

        OfficialQqEvent sourceSide = decoder.decodeMessage(
                        OfficialQqFixtures.groupMessageWithGroupOpenidAndMessageId(
                                "G|ROBOT1.0_M", secondMessageId))
                .orElseThrow();
        assertThatThrownBy(() -> sourceSide.source("official-main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("groupOpenid")
                .hasMessageNotContaining("G|ROBOT1.0_M");
        assertThatThrownBy(() -> sourceSide.source("official|main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountId")
                .hasMessageNotContaining("official|main");

        assertThatThrownBy(() -> OfficialQqSourceIds.requireMessageId("ROBOT|ID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageId")
                .hasMessageNotContaining("ROBOT|ID");

        assertThatThrownBy(() -> new OfficialQqEvent(
                        "event-pipe", "C2C_MESSAGE_CREATE", "ROBOT|ID", "",
                        "USER_PIPE", "", "Pipe", "", "", Instant.parse("2026-11-17T08:26:00Z"),
                        List.of(), "", "", "", false, false, false, 1L,
                        Instant.parse("2026-11-17T08:26:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageId")
                .hasMessageNotContaining("ROBOT|ID");
    }

    @Test
    void messageEventsWithoutAUsableMessageIdFailClosed() {
        assertThatThrownBy(() -> decoder.decodeMessage(OfficialQqFixtures.messageWithoutMessageId()))
                .isInstanceOf(OfficialQqDecodeException.class)
                .satisfies(failure -> assertThat(((OfficialQqDecodeException) failure).code())
                        .isEqualTo(OfficialQqDecodeException.MISSING_MESSAGE_ID))
                .hasMessageContaining("fail-closed")
                .hasMessageNotContaining("must not reach the board");

        assertThatThrownBy(
                        () -> decoder.decodeMessage(OfficialQqFixtures.messageWithWhitespaceMessageId()))
                .isInstanceOf(OfficialQqDecodeException.class)
                .satisfies(failure -> assertThat(((OfficialQqDecodeException) failure).code())
                        .isEqualTo(OfficialQqDecodeException.INVALID_MESSAGE_ID))
                .hasMessageNotContaining("message id with space");
    }

    @Test
    void directGatewayDispatchBridgeUsesParsedDataAndInjectedHandler() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(OfficialQqFixtures.c2cText());
        Instant gatewayReceivedAt = Instant.parse("2026-11-17T09:30:00Z");
        GatewayDispatch gatewayDispatch = new GatewayDispatch(
                root.path("id").asText(),
                root.path("t").asText(),
                root.path("s").asLong(),
                root.path("d"),
                gatewayReceivedAt);

        OfficialQqEvent event = decoder.decodeMessage(gatewayDispatch).orElseThrow();
        assertThat(event.text()).isEqualTo("你好 official");
        assertThat(event.sequence()).isEqualTo(42L);
        assertThat(event.receivedAt()).isEqualTo(gatewayReceivedAt);
        assertThat(event.messageId()).isEqualTo("ROBOT1.0_00123456789012345678901234567890");

        List<OfficialQqDispatch> handled = new ArrayList<>();
        assertThat(decoder.dispatch(gatewayDispatch, handled::add)).isTrue();
        assertThat(handled).hasSize(1).first().isInstanceOf(OfficialQqEvent.class);

        List<OfficialQqDispatch> sunk = new ArrayList<>();
        GatewayEventSink sink = decoder.gatewayEventSink(sunk::add);
        sink.onDispatch(gatewayDispatch);
        assertThat(sunk).hasSize(1).first().isInstanceOf(OfficialQqEvent.class);
    }

    @Test
    void timestampFallbackWarningsDoNotContainUntrustedMetadataOrBody() {
        List<String> warnings = new ArrayList<>();
        OfficialQqEventDecoder capturedDecoder = new OfficialQqEventDecoder(
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC), warnings::add);

        OfficialQqEvent event = capturedDecoder.decodeMessage(
                OfficialQqFixtures.messageWithMaliciousTimestampMetadata()).orElseThrow();

        assertThat(event.eventId()).contains("INJECTED");
        assertThat(event.timestampFallback()).isTrue();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst())
                .contains("timestamp")
                .doesNotContain("INJECTED", "event-secret", "log body must not appear", "\n");
    }

    private static Segment first(OfficialQqEvent event, String type) {
        return event.segments().stream().filter(segment -> type.equals(segment.type()))
                .findFirst().orElseThrow();
    }
}
