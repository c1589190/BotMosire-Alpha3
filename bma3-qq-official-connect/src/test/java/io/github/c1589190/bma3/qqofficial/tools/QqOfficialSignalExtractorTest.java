package io.github.c1589190.bma3.qqofficial.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.c1589190.bma3.core.message.Segment;
import io.github.c1589190.bma3.core.priority.PriorityAttributes;
import io.github.c1589190.bma3.qqofficial.event.OfficialQqEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QqOfficialSignalExtractorTest {

    private static final Instant AT = Instant.parse("2026-11-17T08:00:00Z");

    @Test
    void groupAtAndPrivateCommandsUseTheSameSignalKeysAsOneBot() {
        OfficialQqEvent groupAt = event(true, false, "GROUP_OPENID", "USER_OPENID",
                "MEMBER_OPENID", "/help", List.of(
                        new Segment("text", "/help"),
                        new Segment("at", "@Bot", Map.of("is_you", "true"))));
        PriorityAttributes groupAttributes = QqOfficialSignalExtractor.attributes(
                groupAt, "肥鱼弗洛洛", false);
        assertThat(groupAttributes.has(QqOfficialSignalExtractor.AT_BOT)).isTrue();
        assertThat(groupAttributes.has(QqOfficialSignalExtractor.COMMAND)).isTrue();

        OfficialQqEvent privateMessage = event(false, false, "", "USER_OPENID",
                "", "/help", List.of(new Segment("text", "/help")));
        PriorityAttributes privateAttributes = QqOfficialSignalExtractor.attributes(
                privateMessage, "", false);
        assertThat(privateAttributes.has(QqOfficialSignalExtractor.AT_BOT)).isFalse();
        assertThat(privateAttributes.has(QqOfficialSignalExtractor.COMMAND)).isTrue();
    }

    @Test
    void filtersAtBotAtAllAtOtherAndUnaddressedCommandsLikeTheOneBotAdapter() {
        OfficialQqEvent atAll = event(false, true, "GROUP_OPENID", "USER_OPENID",
                "MEMBER_OPENID", "/help", List.of(
                        new Segment("text", "/help"),
                        new Segment("at", "@全体成员", Map.of("scope", "all"))));
        PriorityAttributes all = QqOfficialSignalExtractor.attributes(atAll, "", false);
        assertThat(all.has(QqOfficialSignalExtractor.AT_ALL)).isTrue();
        assertThat(all.has(QqOfficialSignalExtractor.AT_BOT)).isFalse();
        assertThat(all.has(QqOfficialSignalExtractor.AT_OTHER)).isFalse();
        assertThat(all.has(QqOfficialSignalExtractor.COMMAND)).isFalse();

        OfficialQqEvent atOther = event(false, false, "GROUP_OPENID", "USER_OPENID",
                "MEMBER_OPENID", "/help", List.of(
                        new Segment("text", "/help"),
                        new Segment("at", "@Other", Map.of("openid", "OTHER_OPENID"))));
        PriorityAttributes other = QqOfficialSignalExtractor.attributes(atOther, "", false);
        assertThat(other.has(QqOfficialSignalExtractor.AT_OTHER)).isTrue();
        assertThat(other.has(QqOfficialSignalExtractor.AT_BOT)).isFalse();
        assertThat(other.has(QqOfficialSignalExtractor.COMMAND)).isFalse();

        OfficialQqEvent plainGroup = event(false, false, "GROUP_OPENID", "USER_OPENID",
                "MEMBER_OPENID", "/help", List.of(new Segment("text", "/help")));
        assertThat(QqOfficialSignalExtractor.attributes(plainGroup, "", false).keys())
                .doesNotContain(QqOfficialSignalExtractor.AT_BOT,
                        QqOfficialSignalExtractor.AT_ALL,
                        QqOfficialSignalExtractor.AT_OTHER,
                        QqOfficialSignalExtractor.COMMAND);
    }

    @Test
    void replySelfNameAndConfiguredPrefixesAreAdditive() {
        OfficialQqEvent event = event(false, false, "", "USER_OPENID", "",
                ".rd 3d6", List.of(new Segment("text", ".rd 3d6")));

        PriorityAttributes attributes = QqOfficialSignalExtractor.attributes(
                event, "肥鱼弗洛洛", true, List.of("/", "!", "."));
        assertThat(attributes.has(QqOfficialSignalExtractor.REPLY_TO_BOT)).isTrue();
        assertThat(attributes.has(QqOfficialSignalExtractor.SELF_NAME)).isFalse();
        assertThat(attributes.has(QqOfficialSignalExtractor.COMMAND)).isTrue();

        OfficialQqEvent nameMention = event(false, false, "", "USER_OPENID", "",
                "请 肥鱼弗洛洛 看一下", List.of(new Segment("text", "请 肥鱼弗洛洛 看一下")));
        assertThat(QqOfficialSignalExtractor.attributes(nameMention, "肥鱼弗洛洛", false)
                .has(QqOfficialSignalExtractor.SELF_NAME)).isTrue();
        assertThat(QqOfficialSignalExtractor.attributes(nameMention, "  ", false)
                .has(QqOfficialSignalExtractor.SELF_NAME)).isFalse();
    }

    private static OfficialQqEvent event(boolean atBot, boolean atAll, String group,
                                         String user, String member, String text,
                                         List<Segment> segments) {
        return new OfficialQqEvent(
                "event-1", group.isBlank() ? "C2C_MESSAGE_CREATE" : "GROUP_AT_MESSAGE_CREATE",
                "message-1", group, user, member, "Alice", "AliceCard", "", AT, segments, "",
                "", "", atBot, atAll, false, 1L, AT);
    }
}
