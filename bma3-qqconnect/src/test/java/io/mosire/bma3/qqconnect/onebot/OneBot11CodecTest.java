package io.mosire.bma3.qqconnect.onebot;

import static org.assertj.core.api.Assertions.assertThat;

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
    void ignoresNonMessageEvents() {
        assertThat(codec.decode("""
                {"post_type": "meta_event", "meta_event_type": "lifecycle", "self_id": 10001}
                """)).isEmpty();
    }
}
