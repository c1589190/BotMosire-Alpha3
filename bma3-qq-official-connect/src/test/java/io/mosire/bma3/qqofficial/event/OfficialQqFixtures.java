package io.mosire.bma3.qqofficial.event;

/** Inline Gateway fixtures; keeping them in Java avoids a test-resource dependency. */
final class OfficialQqFixtures {

    private OfficialQqFixtures() {
    }

    static String c2cText() {
        return """
                {
                  "id": "event-c2c-001",
                  "op": 0,
                  "s": 42,
                  "t": "C2C_MESSAGE_CREATE",
                  "d": {
                    "id": "ROBOT1.0_00123456789012345678901234567890",
                    "content": "你好 official",
                    "timestamp": "2026-11-17T08:09:10.123Z",
                    "author": {
                      "user_openid": "00123456789012345678901234567890",
                      "username": "Alice"
                    },
                    "message_scene": {
                      "ext": {
                        "msg_idx": "REFIDX_C2C",
                        "auth_token": "c2c-scene-secret"
                      }
                    }
                  }
                }
                """;
    }

    static String groupAtWithAttachmentsAndMentions() {
        return """
                {
                  "id": "event-group-at-001",
                  "op": 0,
                  "s": 43,
                  "t": "GROUP_AT_MESSAGE_CREATE",
                  "d": {
                    "id": "ROBOT1.0_GROUP_AT_001",
                    "content": "看图",
                    "timestamp": "2026-11-17T16:09:10+08:00",
                    "group_openid": "0011223344556677889900112233445566778899",
                    "author": {
                      "member_openid": "MEMBER_00112233445566778899",
                      "user_openid": "USER_00112233445566778899",
                      "username": "Bob",
                      "member_nickname": "群名片Bob",
                      "member_role": "admin"
                    },
                    "mentions": [
                      {
                        "member_openid": "MEMBER_00112233445566778899",
                        "member_nickname": "Bot",
                        "is_you": true
                      },
                      {"scope": "all"}
                    ],
                    "attachments": [
                      {
                        "content_type": "image/png",
                        "filename": "cat.png",
                        "size": 1234,
                        "width": 640,
                        "height": 480,
                        "url": "https://cdn.example/cat.png?rkey=keep&auth_token=image-secret"
                      },
                      {
                        "content_type": "video/mp4",
                        "filename": "clip.mp4",
                        "url": "https://cdn.example/clip.mp4?token=video-secret&rkey=keep2"
                      },
                      {
                        "content_type": "audio/silk",
                        "filename": "voice.silk",
                        "url": "https://cdn.example/voice.silk"
                      },
                      {
                        "content_type": "application/octet-stream",
                        "filename": "报告.pdf",
                        "url": "https://cdn.example/report.pdf"
                      }
                    ]
                  }
                }
                """;
    }

    static String groupFullMessage() {
        return """
                {
                  "id": "event-group-full-001",
                  "op": 0,
                  "s": 44,
                  "t": "GROUP_MESSAGE_CREATE",
                  "d": {
                    "id": "ROBOT1.0_GROUP_FULL_001",
                    "content": "群全量消息",
                    "timestamp": "2026-11-17T08:10:00Z",
                    "group_openid": "GROUP_FULL_OPENID",
                    "author": {
                      "member_openid": "MEMBER_FULL",
                      "member_nickname": "Carol"
                    },
                    "mentions": [
                      {"member_openid": "MEMBER_OTHER", "member_nickname": "Other"}
                    ]
                  }
                }
                """;
    }

    static String arkQuoteAndNestedElements() {
        return """
                {
                  "id": "event-ark-001",
                  "op": 0,
                  "s": 45,
                  "t": "GROUP_MESSAGE_CREATE",
                  "d": {
                    "id": "ROBOT1.0_ARK_001",
                    "content": "外层文本",
                    "timestamp": "2026-11-17T08:11:00Z",
                    "group_openid": "GROUP_ARK_OPENID",
                    "message_type": 103,
                    "author": {
                      "member_openid": "MEMBER_ARK",
                      "member_nickname": "Dave"
                    },
                    "ark_data": {
                      "app": "com.tencent.test",
                      "appid": "123456",
                      "type": "card",
                      "title": "卡片标题",
                      "desc": "卡片描述",
                      "jump_url": "https://example.com/card?auth_token=ark-url-secret&x=1",
                      "nested": {
                        "auth_token": "ark-nested-secret",
                        "accessToken": "ark-access-secret",
                        "keep": "ok"
                      }
                    },
                    "message_scene": {
                      "ext": {
                        "msg_idx": "REFIDX_MSG",
                        "ref_msg_idx": "REFIDX_REF",
                        "auth_token": "scene-secret"
                      }
                    },
                    "msg_elements": [
                      {"content": "引用内文", "msg_idx": "REFIDX_NESTED"},
                      {
                        "attachments": [
                          {
                            "content_type": "image/jpeg",
                            "filename": "nested.jpg",
                            "url": "https://cdn.example/nested.jpg?auth_token=nested-url-secret&rkey=nested-key"
                          }
                        ]
                      },
                      {
                        "ark_data": {
                          "title": "嵌套卡片",
                          "url": "https://example.com/n?auth_token=nested-card-secret"
                        }
                      }
                    ]
                  }
                }
                """;
    }

    static String secretBearingMessage() {
        return """
                {
                  "id": "event-secret-001",
                  "op": 0,
                  "s": 46,
                  "t": "GROUP_MESSAGE_CREATE",
                  "d": {
                    "id": "ROBOT1.0_SECRET_001",
                    "content": "普通 auth_token=content-secret 文本",
                    "timestamp": "2026-11-17T08:12:00Z",
                    "group_openid": "GROUP_SECRET_OPENID",
                    "author": {
                      "member_openid": "MEMBER_SECRET",
                      "member_nickname": "nick",
                      "auth_token": "author-secret"
                    },
                    "mentions": [
                      {"member_openid": "MEMBER_MENTION", "auth_token": "mention-secret"}
                    ],
                    "attachments": [
                      {
                        "content_type": "image/png",
                        "url": "https://cdn.example/a.png?auth_token=url-secret&rkey=keep-rkey",
                        "file_info": {
                          "access_token": "file-secret",
                          "file_uuid": "file-uuid"
                        }
                      }
                    ],
                    "ark_data": {
                      "title": "安全卡片",
                      "auth_token": "ark-secret",
                      "nested": {"client_secret": "client-secret"}
                    },
                    "message_reference": {
                      "message_id": "REF_MESSAGE",
                      "auth_token": "reference-secret"
                    },
                    "message_scene": {
                      "ext": {
                        "msg_idx": "REFIDX_SECRET_MSG",
                        "ref_msg_idx": "REFIDX_SECRET_REF",
                        "auth_token": "scene-secret"
                      }
                    }
                  }
                }
                """;
    }

    static String messageWithoutTimestamp(String content) {
        return """
                {
                  "id": "event-no-time",
                  "op": 0,
                  "s": 47,
                  "t": "C2C_MESSAGE_CREATE",
                  "d": {
                    "id": "ROBOT1.0_NO_TIME",
                    "content": "%s",
                    "author": {"user_openid": "USER_NO_TIME", "username": "Eve"}
                  }
                }
                """.formatted(content);
    }

    static String messageWithInvalidTimestamp() {
        return """
                {
                  "id": "event-bad-time",
                  "op": 0,
                  "s": 48,
                  "t": "C2C_MESSAGE_CREATE",
                  "d": {
                    "id": "ROBOT1.0_BAD_TIME",
                    "content": "time fallback",
                    "timestamp": "not-an-rfc3339-time",
                    "author": {"user_openid": "USER_BAD_TIME", "username": "Mallory"}
                  }
                }
                """;
    }


    static String messageWithGroupOpenid(String jsonEscapedGroupOpenid) {
        return """
                {
                  "id": "event-source-validation",
                  "op": 0,
                  "s": 51,
                  "t": "GROUP_MESSAGE_CREATE",
                  "d": {
                    "id": "ROBOT1.0_SOURCE_VALIDATION",
                    "content": "source validation",
                    "timestamp": "2026-11-17T08:13:00Z",
                    "group_openid": "%s",
                    "author": {"member_openid": "MEMBER_SOURCE", "member_nickname": "Source"}
                  }
                }
                """.formatted(jsonEscapedGroupOpenid);
    }

    static String messageWithoutMessageId() {
        return """
                {
                  "id": "event-no-message-id",
                  "op": 0,
                  "s": 52,
                  "t": "C2C_MESSAGE_CREATE",
                  "d": {
                    "content": "must not reach the board without an id",
                    "timestamp": "2026-11-17T08:14:00Z",
                    "author": {"user_openid": "USER_NO_MESSAGE_ID", "username": "NoId"}
                  }
                }
                """;
    }

    static String messageWithWhitespaceMessageId() {
        return """
                {
                  "id": "event-invalid-message-id",
                  "op": 0,
                  "s": 53,
                  "t": "GROUP_MESSAGE_CREATE",
                  "d": {
                    "id": "message id with space",
                    "content": "invalid message id",
                    "timestamp": "2026-11-17T08:15:00Z",
                    "group_openid": "GROUP_INVALID_MESSAGE_ID",
                    "author": {"member_openid": "MEMBER_INVALID", "member_nickname": "Invalid"}
                  }
                }
                """;
    }

    static String messageWithMaliciousTimestampMetadata() {
        return """
                {
                  "id": "event\\nINJECTED auth_token=event-secret",
                  "op": 0,
                  "s": 54,
                  "t": "C2C_MESSAGE_CREATE",
                  "d": {
                    "id": "ROBOT1.0_LOG_SAFETY",
                    "content": "log body must not appear",
                    "timestamp": "definitely-not-rfc3339",
                    "author": {"user_openid": "USER_LOG_SAFETY", "username": "LogSafety"}
                  }
                }
                """;
    }


    static String c2cMessageWithContent(String content) {
        return """
                {
                  "id": "event-content-budget",
                  "op": 0,
                  "s": 60,
                  "t": "C2C_MESSAGE_CREATE",
                  "d": {
                    "id": "ROBOT1.0_CONTENT_BUDGET",
                    "content": "%s",
                    "timestamp": "2026-11-17T08:20:00Z",
                    "author": {"user_openid": "USER_CONTENT_BUDGET", "username": "Budget"}
                  }
                }
                """.formatted(content);
    }

    static String groupMessageWithAttachments(int count) {
        StringBuilder json = new StringBuilder("""
                {
                  "id": "event-attachment-budget",
                  "op": 0,
                  "s": 61,
                  "t": "GROUP_MESSAGE_CREATE",
                  "d": {
                    "id": "ROBOT1.0_ATTACHMENT_BUDGET",
                    "content": "attachments",
                    "timestamp": "2026-11-17T08:21:00Z",
                    "group_openid": "GROUP_ATTACHMENT_BUDGET",
                    "author": {"member_openid": "MEMBER_ATTACHMENT", "member_nickname": "Attach"},
                    "attachments": [
                """);
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"content_type\":\"image/png\",\"filename\":\"a")
                    .append(index)
                    .append(".png\",\"url\":\"https://cdn.example/a")
                    .append(index)
                    .append(".png?rkey=ok\"}");
        }
        json.append("]}}");
        return json.toString();
    }

    static String groupMessageWithMentions(int count) {
        StringBuilder json = new StringBuilder("""
                {
                  "id": "event-mention-budget",
                  "op": 0,
                  "s": 62,
                  "t": "GROUP_MESSAGE_CREATE",
                  "d": {
                    "id": "ROBOT1.0_MENTION_BUDGET",
                    "content": "mentions",
                    "timestamp": "2026-11-17T08:22:00Z",
                    "group_openid": "GROUP_MENTION_BUDGET",
                    "author": {"member_openid": "MEMBER_MENTION", "member_nickname": "Mention"},
                    "mentions": [
                """);
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"member_openid\":\"MEMBER_")
                    .append(index)
                    .append("\",\"member_nickname\":\"User")
                    .append(index)
                    .append("\"}");
        }
        json.append("]}}");
        return json.toString();
    }

    static String groupMessageWithNestedContents(int count) {
        StringBuilder json = new StringBuilder("""
                {
                  "id": "event-segment-budget",
                  "op": 0,
                  "s": 63,
                  "t": "GROUP_MESSAGE_CREATE",
                  "d": {
                    "id": "ROBOT1.0_SEGMENT_BUDGET",
                    "content": "segments",
                    "timestamp": "2026-11-17T08:23:00Z",
                    "group_openid": "GROUP_SEGMENT_BUDGET",
                    "author": {"member_openid": "MEMBER_SEGMENT", "member_nickname": "Segment"},
                    "msg_elements": [
                """);
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"content\":\"nested-").append(index).append("\"}");
        }
        json.append("]}}");
        return json.toString();
    }

    static String groupMessageWithNestedDepth(int depth) {
        String nested = "[".repeat(depth) + "{\"content\":\"deep\"}" + "]".repeat(depth);
        return """
                {
                  "id": "event-depth-budget",
                  "op": 0,
                  "s": 64,
                  "t": "GROUP_MESSAGE_CREATE",
                  "d": {
                    "id": "ROBOT1.0_DEPTH_BUDGET",
                    "content": "depth",
                    "timestamp": "2026-11-17T08:24:00Z",
                    "group_openid": "GROUP_DEPTH_BUDGET",
                    "author": {"member_openid": "MEMBER_DEPTH", "member_nickname": "Depth"},
                    "msg_elements": %s
                  }
                }
                """.formatted(nested);
    }

    static String groupMessageWithEmptyNestedElements(int count) {
        StringBuilder json = new StringBuilder("""
                {
                  "id": "event-nested-count-budget",
                  "op": 0,
                  "s": 65,
                  "t": "GROUP_MESSAGE_CREATE",
                  "d": {
                    "id": "ROBOT1.0_NESTED_COUNT_BUDGET",
                    "content": "nested count",
                    "timestamp": "2026-11-17T08:25:00Z",
                    "group_openid": "GROUP_NESTED_COUNT_BUDGET",
                    "author": {"member_openid": "MEMBER_NESTED", "member_nickname": "Nested"},
                    "msg_elements": [
                """);
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{}");
        }
        json.append("]}}");
        return json.toString();
    }

    static String unknownEventWithHugePayload(int contentLength) {
        return """
                {
                  "id": "event-huge-unknown",
                  "op": 0,
                  "s": 66,
                  "t": "FUTURE_HUGE_EVENT",
                  "d": {
                    "content": "%s"
                  }
                }
                """.formatted("x".repeat(contentLength));
    }


    static String groupMessageWithGroupOpenidAndMessageId(
            String jsonEscapedGroupOpenid, String jsonEscapedMessageId) {
        return """
                {
                  "id": "event-board-key-validation",
                  "op": 0,
                  "s": 67,
                  "t": "GROUP_MESSAGE_CREATE",
                  "d": {
                    "id": "%s",
                    "content": "board key validation",
                    "timestamp": "2026-11-17T08:26:00Z",
                    "group_openid": "%s",
                    "author": {"member_openid": "MEMBER_BOARD_KEY", "member_nickname": "Board"}
                  }
                }
                """.formatted(jsonEscapedMessageId, jsonEscapedGroupOpenid);
    }

    static String systemEvent(String eventType, String body) {
        return """
                {
                  "id": "event-%s",
                  "op": 0,
                  "s": 49,
                  "t": "%s",
                  "d": %s
                }
                """.formatted(eventType.toLowerCase(), eventType, body);
    }

    static String unknownEvent() {
        return """
                {
                  "id": "event-future-001",
                  "op": 0,
                  "s": 50,
                  "t": "FUTURE_MESSAGE_CREATE",
                  "d": {
                    "content": "unknown body must not be retained",
                    "auth_token": "unknown-secret",
                    "nested": {"access_token": "unknown-access-secret"}
                  }
                }
                """;
    }

    static String helloControlFrame() {
        return """
                {"op": 10, "d": {"heartbeat_interval": 30000, "auth_token": "hello-secret"}}
                """;
    }

    static String malformedWithSecret() {
        return """
                {"id":"event-bad","op":0,"t":"C2C_MESSAGE_CREATE","d":{
                  "auth_token":"malformed-secret"
                """;
    }
}
