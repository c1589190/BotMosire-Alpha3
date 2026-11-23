package io.github.c1589190.bma3.qqconnect.onebot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.c1589190.bma3.core.message.Segment;
import org.junit.jupiter.api.Test;

class OneBot11CodecTest {

    private final OneBot11Codec codec = new OneBot11Codec();

    @Test
    void parsesPrivateTextMessage() {
        OneBotEvent event = codec.decode("""
                {
                  "post_type": "message",
                  "message_type": "private",
                  "sub_type": "friend",
                  "self_id": 10001,
                  "user_id": 20002,
                  "message_id": 9001,
                  "time": 1762700000,
                  "message": [{"type": "text", "data": {"text": "hello"}}],
                  "sender": {"nickname": "Alice"}
                }
                """).orElseThrow();

        assertThat(event.selfId()).isEqualTo(10001L);
        assertThat(event.userId()).isEqualTo(20002L);
        assertThat(event.text()).isEqualTo("hello");
        assertThat(event.source("bot-1").format()).isEqualTo("qq_private/bot-1/20002");
        assertThat(event.at()).isEqualTo(java.time.Instant.ofEpochSecond(1762700000L));
        assertThat(event.timestampFallback()).isFalse();
        assertThat(event.contentComplete()).isTrue();
    }

    @Test
    void parsesGroupTextAndReply() {
        OneBotEvent event = codec.decode("""
                {
                  "post_type": "message",
                  "message_type": "group",
                  "self_id": 10001,
                  "group_id": 955432692,
                  "user_id": 20002,
                  "message_id": 9002,
                  "time": 1762700001,
                  "message": [
                    {"type": "reply", "data": {"id": "8000"}},
                    {"type": "text", "data": {"text": "hi group"}}
                  ],
                  "sender": {"nickname": "Bob"}
                }
                """).orElseThrow();

        assertThat(event.source("bot-1").format()).isEqualTo("qq_group/bot-1/955432692");
        assertThat(event.text()).isEqualTo("hi group");
        assertThat(event.replyToMessageId()).isEqualTo("8000");
    }

    @Test
    void keepsStructuredPayloadForMediaAndRelations() {
        OneBotEvent event = codec.decode("""
                {
                  "post_type": "message",
                  "message_type": "group",
                  "self_id": 10001,
                  "group_id": 955432692,
                  "user_id": 20002,
                  "message_id": 9003,
                  "time": 1762700002,
                  "message": [
                    {"type": "at", "data": {"qq": "10001", "name": "Bot"}},
                    {"type": "image", "data": {"file": "abc.image", "url": "https://cdn/x?rkey=1",
                                               "sub_type": 1, "summary": "[动画表情]"}},
                    {"type": "text", "data": {"text": "看图"}}
                  ],
                  "sender": {"nickname": "Bob"}
                }
                """).orElseThrow();

        var image = event.segments().stream()
                .filter(segment -> "image".equals(segment.type())).findFirst().orElseThrow();
        assertThat(image.text()).as("正文仍只放占位，URL 不进 text").isEqualTo("[图片]");
        assertThat(image.data("file")).isEqualTo("abc.image");
        assertThat(image.data("url")).isEqualTo("https://cdn/x?rkey=1");
        assertThat(image.data("summary")).isEqualTo("[动画表情]");

        var at = event.segments().stream()
                .filter(segment -> "at".equals(segment.type())).findFirst().orElseThrow();
        assertThat(at.data("qq")).isEqualTo("10001");
        assertThat(at.data("name")).isEqualTo("Bot");
    }

    @Test
    void fallsBackForMissingInvalidOrImpossibleTimestamp() {
        OneBotEvent missing = codec.decode(messageJson("", "[{\"type\":\"text\","
                + "\"data\":{\"text\":\"hello\"}}]")).orElseThrow();
        assertThat(missing.timestampFallback()).isTrue();
        assertThat(missing.at()).isNotNull();

        for (String badTime : java.util.List.of(
                "\"time\":\"1762700000\",",
                "\"time\":1762700000.5,",
                "\"time\":0,",
                "\"time\":-1,",
                "\"time\":null,",
                "\"time\":999999999999999999999999999999,")) {
            OneBotEvent event = codec.decode(messageJson(badTime,
                    "[{\"type\":\"text\",\"data\":{\"text\":\"fallback\"}}]"))
                    .orElseThrow();
            assertThat(event.timestampFallback()).as(badTime).isTrue();
            assertThat(event.contentComplete()).as(badTime).isTrue();
        }
    }

    @Test
    void contentCompletenessIsConservativeForStringsUnknownAndIncompleteSegments() {
        OneBotEvent cqString = codec.decode(messageJson("\"time\":1762700010,",
                "\"[CQ:face,id=123] raw text\"")).orElseThrow();
        assertThat(cqString.contentComplete()).isFalse();
        assertThat(cqString.text()).isEqualTo("[CQ:face,id=123] raw text");

        OneBotEvent unknown = codec.decode(messageJson("\"time\":1762700011,",
                "[{\"type\":\"markdown\",\"data\":{\"text\":\"**hi**\"}}]"))
                .orElseThrow();
        assertThat(unknown.contentComplete()).isFalse();
        assertThat(unknown.segments()).anyMatch(segment -> "markdown".equals(segment.type()));

        OneBotEvent missingData = codec.decode(messageJson("\"time\":1762700012,",
                "[{\"type\":\"text\"}]")).orElseThrow();
        assertThat(missingData.contentComplete()).isFalse();

        OneBotEvent scalarElement = codec.decode(messageJson("\"time\":1762700013,",
                "[\"not-an-object\"]")).orElseThrow();
        assertThat(scalarElement.contentComplete()).isFalse();

        OneBotEvent objectMessage = codec.decode(messageJson("\"time\":1762700014,",
                "{\"type\":\"text\",\"data\":{\"text\":\"hi\"}}")).orElseThrow();
        assertThat(objectMessage.contentComplete()).isFalse();
    }

    @Test
    void knownCompleteStructuredArrayStaysComplete() {
        OneBotEvent event = codec.decode(messageJson("\"time\":1762700015,",
                "[{\"type\":\"at\",\"data\":{\"qq\":\"10001\"}},"
                        + "{\"type\":\"image\",\"data\":{\"file\":\"a.png\"}},"
                        + "{\"type\":\"text\",\"data\":{\"text\":\"ok\"}}]"))
                .orElseThrow();
        assertThat(event.contentComplete()).isTrue();
        assertThat(event.timestampFallback()).isFalse();
    }

    @Test
    void legacyConstructorsDoNotClaimContentCompleteness() {
        OneBotEvent elevenArgs = new OneBotEvent(
                "message", "private", "", 10001L, 20002L, 0L, 9001L,
                java.time.Instant.parse("2026-11-09T11:00:00Z"), "Alice",
                java.util.List.of(new Segment("text", "ping")), "");
        assertThat(elevenArgs.timestampFallback()).isFalse();
        assertThat(elevenArgs.contentComplete()).isFalse();

        OneBotEvent thirteenArgs = new OneBotEvent(
                "message", "group", "normal", 10001L, 20002L, 955432692L, 9002L,
                java.time.Instant.parse("2026-11-09T11:00:01Z"), "Bob",
                java.util.List.of(new Segment("text", "pong")), "", "群名片", "member");
        assertThat(thirteenArgs.timestampFallback()).isFalse();
        assertThat(thirteenArgs.contentComplete()).isFalse();
    }

    @Test
    void malformedJsonErrorsDoNotEchoContent() {
        String malformed = """
                {"post_type":"message","message_type":"private","time":1762700020,
                 "message":[{"type":"text","data":{"text":"TOP-SECRET-CONTENT"}}]
                """;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> codec.decode(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OneBot")
                .hasMessageNotContaining("TOP-SECRET-CONTENT")
                .satisfies(failure -> assertThat(failure.getCause()).isNull());
    }

    @Test
    void ignoresNonMessageEvents() {
        assertThat(codec.decode("""
                {"post_type": "meta_event", "meta_event_type": "lifecycle", "self_id": 10001}
                """)).isEmpty();
    }

    @Test
    void capturesTheGroupCardAndRoleWithoutLosingTheNickname() {
        OneBotEvent event = codec.decode("""
                {
                  "post_type": "message",
                  "message_type": "group",
                  "self_id": 10001,
                  "group_id": 955432692,
                  "user_id": 20002,
                  "message_id": 9004,
                  "time": 1762700003,
                  "message": [{"type": "text", "data": {"text": "hi"}}],
                  "sender": {"nickname": "南沐秋", "card": "群名片小王", "role": "admin"}
                }
                """).orElseThrow();
        assertThat(event.senderName()).isEqualTo("南沐秋");
        assertThat(event.senderCard()).isEqualTo("群名片小王");
        assertThat(event.senderRole()).isEqualTo("admin");
        assertThat(event.displayName()).isEqualTo("群名片小王");
    }

    @Test
    void parsesAJsonCardAndNeverStoresTheInviteTicket() {
        OneBotEvent event = codec.decode("""
                {
                  "post_type": "message",
                  "message_type": "group",
                  "self_id": 10001,
                  "group_id": 955432692,
                  "user_id": 20002,
                  "message_id": 9005,
                  "time": 1762700004,
                  "message": [{
                    "type": "json",
                    "data": {"data": "{\\\"title\\\":\\\"群邀请\\\",\\\"desc\\\":\\\"来玩\\\",\\\"bizsrc\\\":\\\"qun.invite\\\",\\\"jumpUrl\\\":\\\"https://example.com/invite\\\",\\\"meta\\\":{\\\"news\\\":{\\\"title\\\":\\\"群邀请\\\",\\\"desc\\\":\\\"来玩\\\"}}, \\\"url\\\":\\\"mqqapi://group/invite_join?groupcode=123456&msgseq=789&token=SECRET\\\"}"}
                  }],
                  "sender": {"nickname": "Bob"}
                }
                """).orElseThrow();
        var card = event.segments().stream()
                .filter(segment -> "json".equals(segment.type())).findFirst().orElseThrow();
        assertThat(card.text()).isEqualTo("[卡片] 群邀请");
        assertThat(card.data("title")).isEqualTo("群邀请");
        assertThat(card.data("desc")).isEqualTo("来玩");
        assertThat(card.data("invite_group")).isEqualTo("123456");
        assertThat(card.data("invite_seq")).isEqualTo("789");
        assertThat(card.data("url")).as("普通 https 链接保留，mqqapi ticket 链接不落库")
                .isEqualTo("https://example.com/invite");
        assertThat(card.data("raw")).doesNotContain("SECRET");
        assertThat(card.data("raw")).contains("[群邀请链接已移除]");
    }

    private static String messageJson(String timeField, String messageJson) {
        return """
                {
                  "post_type": "message",
                  "message_type": "private",
                  "sub_type": "friend",
                  "self_id": 10001,
                  "user_id": 20002,
                  "message_id": 9999,
                  %s
                  "message": %s,
                  "sender": {"nickname": "Tester"}
                }
                """.formatted(timeField, messageJson);
    }
}
