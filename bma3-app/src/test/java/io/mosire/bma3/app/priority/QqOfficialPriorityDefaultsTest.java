package io.mosire.bma3.app.priority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.mosire.bma3.app.config.AppConfig;
import io.mosire.bma3.app.config.ConfigLoader;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.priority.PriorityAttributes;
import io.mosire.bma3.qqofficial.event.OfficialQqEvent;
import io.mosire.bma3.qqofficial.tools.QqOfficialSignalExtractor;
import io.mosire.bma3.qqofficial.tools.QqOfficialSourceKeyer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QqOfficialPriorityDefaultsTest {

    private static final Instant AT = Instant.parse("2026-11-17T08:00:00Z");
    private final QqOfficialSourceKeyer keyer = new QqOfficialSourceKeyer();

    @TempDir
    Path temp;

    @Test
    void officialGroupAtBotAndPrivateAreImmediateLikeOneBot() {
        PriorityResolver resolver = new PriorityResolver(AppConfig.Weighting::defaults);

        OfficialQqEvent groupAtBot = event("GROUP_OPENID", "", true, false,
                List.of(new Segment("text", "你好"),
                        new Segment("at", "@Bot", Map.of("is_you", "true"))));
        String officialGroupKey = keyer.keyFor(groupAtBot.source("official-main"));
        PriorityDecision officialGroup = resolver.resolve(officialGroupKey, "MEMBER_OPENID",
                "Alice", QqOfficialSignalExtractor.attributes(groupAtBot, "Bot", false));
        PriorityDecision oneBotGroup = resolver.resolve("qq_group/955432692", "10001",
                "Alice", PriorityAttributes.of("qq_at_bot"));

        assertThat(officialGroupKey).isEqualTo("qq_official_group/GROUP_OPENID");
        assertThat(officialGroup.priority()).isEqualTo(oneBotGroup.priority()).isEqualTo(300);
        assertThat(officialGroup.immediate()).isTrue();
        assertThat(officialGroup.dropped()).isFalse();

        OfficialQqEvent privateMessage = event("", "USER_OPENID", false, false,
                List.of(new Segment("text", "你好")));
        String officialPrivateKey = keyer.keyFor(privateMessage.source("official-main"));
        PriorityDecision officialPrivate = resolver.resolve(officialPrivateKey, "USER_OPENID",
                "Alice", QqOfficialSignalExtractor.attributes(privateMessage, "Bot", false));
        PriorityDecision oneBotPrivate = resolver.resolve("qq_private/10001", "10001",
                "Alice", PriorityAttributes.none());

        assertThat(officialPrivateKey).isEqualTo("qq_official_private/USER_OPENID");
        assertThat(officialPrivate.priority()).isEqualTo(oneBotPrivate.priority()).isEqualTo(300);
        assertThat(officialPrivate.immediate()).isTrue();
        assertThat(officialPrivate.dropped()).isFalse();
    }

    @Test
    void officialSourcePriorityRemainsOverridableAndFilteringStillUsesDropBelow() {
        LinkedHashMap<String, Integer> overridden =
                new LinkedHashMap<>(AppConfig.Weighting.defaultSourcePriority());
        overridden.put("qq_official_group", 50);
        AppConfig.Weighting lowered = new AppConfig.Weighting(
                300, -300, 1000, 30, 1, overridden,
                AppConfig.Weighting.defaultAttributes(), List.of());
        PriorityDecision notImmediate = new PriorityResolver(() -> lowered)
                .resolve("qq_official_group/GROUP_OPENID", "MEMBER_OPENID", "Alice",
                        PriorityAttributes.of("qq_at_bot"));
        assertThat(notImmediate.priority()).isEqualTo(150);
        assertThat(notImmediate.immediate()).isFalse();

        LinkedHashMap<String, Integer> dropping =
                new LinkedHashMap<>(AppConfig.Weighting.defaultSourcePriority());
        dropping.put("qq_official_group", -1000);
        AppConfig.Weighting dropAll = new AppConfig.Weighting(
                300, -300, 1000, 30, 1, dropping,
                AppConfig.Weighting.defaultAttributes(), List.of());
        PriorityDecision dropped = new PriorityResolver(() -> dropAll)
                .resolve("qq_official_group/GROUP_OPENID", "MEMBER_OPENID", "Alice",
                        PriorityAttributes.of("qq_at_bot"));
        assertThat(dropped.priority()).isEqualTo(-900);
        assertThat(dropped.dropped()).isTrue();
        assertThat(dropped.immediate()).isFalse();
    }

    @Test
    void existingCustomSourcePriorityMapIsNotSilentlyMergedWithNewDefaults() throws Exception {
        Path configDir = Files.createDirectories(temp.resolve("config"));
        Files.writeString(configDir.resolve("app.yaml"), """
                weighting:
                  sourcePriority:
                    qq_group: 50
                """);

        AppConfig config = ConfigLoader.load(temp);

        assertThat(config.weighting().sourcePriority())
                .containsOnly(entry("qq_group", 50));
    }

    private static OfficialQqEvent event(String group, String user,
                                         boolean atBot, boolean atAll,
                                         List<Segment> segments) {
        return new OfficialQqEvent(
                "event-1", group.isBlank() ? "C2C_MESSAGE_CREATE" : "GROUP_AT_MESSAGE_CREATE",
                "message-1", group, user, group.isBlank() ? "" : "MEMBER_OPENID",
                "Alice", "AliceCard", "", AT, segments, "", "", "", atBot, atAll, false,
                1L, AT);
    }
}
