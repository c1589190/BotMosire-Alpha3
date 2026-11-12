package io.mosire.bma3.app.priority;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.bma3.app.config.AppConfig;
import io.mosire.bma3.core.priority.PriorityAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PriorityResolverTest {

    private static final String PRIVATE = "qq_private/132843233";
    private static final String GROUP = "qq_group/955432692";

    private PriorityResolver resolver(Map<String, Integer> sourcePriority,
                                      Map<String, Integer> attributes) {
        AppConfig.Weighting weighting = new AppConfig.Weighting(
                300, -300, 1000, 30, 1, sourcePriority, attributes, java.util.List.of());
        return new PriorityResolver(() -> weighting);
    }

    private static Map<String, Integer> sources() {
        return AppConfig.Weighting.defaultSourcePriority();
    }

    private static Map<String, Integer> attributes() {
        return AppConfig.Weighting.defaultAttributes();
    }

    @Test
    void privateMessageIsImmediate() {
        PriorityDecision decision = resolver(sources(), attributes())
                .resolve(PRIVATE, "132843233", "南沐秋", PriorityAttributes.none());
        assertThat(decision.priority()).isEqualTo(300);
        assertThat(decision.immediate()).isTrue();
        assertThat(decision.dropped()).isFalse();
    }

    @Test
    void ordinaryGroupMessageWaitsForTheBatch() {
        PriorityDecision decision = resolver(sources(), attributes())
                .resolve(GROUP, "10001", "路人", PriorityAttributes.none());
        assertThat(decision.priority()).isEqualTo(200);
        assertThat(decision.immediate()).isFalse();
        assertThat(decision.dropped()).isFalse();
    }

    @Test
    void atBotCrossesTheImmediateLineButAtAllDoesNot() {
        PriorityResolver resolver = resolver(sources(), attributes());
        PriorityDecision atBot = resolver.resolve(GROUP, "10001", "路人",
                PriorityAttributes.of("qq_at_bot"));
        assertThat(atBot.priority()).isEqualTo(300);
        assertThat(atBot.immediate()).isTrue();

        PriorityDecision atAll = resolver.resolve(GROUP, "10001", "路人",
                PriorityAttributes.of("qq_at_all"));
        assertThat(atAll.priority()).isEqualTo(220);
        assertThat(atAll.immediate()).isFalse();
    }

    @Test
    void senderAndSourceRulesStack() {
        Map<String, Integer> attributes = new LinkedHashMap<>(attributes());
        attributes.put("qq_person_id/10001", 50);
        PriorityDecision decision = resolver(sources(), attributes)
                .resolve(GROUP, "10001", "南沐秋", PriorityAttributes.none());
        assertThat(decision.priority()).isEqualTo(250);
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("qq_person_id/10001"));
    }

    @Test
    void nicknameRulesAreNotScoreKeysAnymore() {
        // D-048: qq_name/ was retired. A display-name match must not move the score; name
        // resolution belongs to qq_search, and per-person weight uses the stable QQ number.
        Map<String, Integer> attributes = new LinkedHashMap<>(attributes());
        attributes.put("qq_name/管理", 40);
        PriorityDecision decision = resolver(sources(), attributes)
                .resolve(GROUP, "10001", "群管理小王", PriorityAttributes.none());
        assertThat(decision.priority()).isEqualTo(200);
    }

    @Test
    void theMostSpecificSourceRuleWins() {
        Map<String, Integer> sources = new LinkedHashMap<>(sources());
        sources.put(GROUP, 350);
        PriorityResolver resolver = resolver(sources, attributes());
        assertThat(resolver.resolve(GROUP, "10001", "路人", PriorityAttributes.none()).priority())
                .isEqualTo(350);
        assertThat(resolver.resolve("qq_group/999", "10001", "路人", PriorityAttributes.none())
                .priority()).isEqualTo(200);
    }

    @Test
    void scoreBelowDropIsDroppedBeforeItIsStored() {
        Map<String, Integer> sources = new LinkedHashMap<>(sources());
        sources.put(GROUP, -301);
        PriorityDecision decision = resolver(sources, attributes())
                .resolve(GROUP, "10001", "路人", PriorityAttributes.none());
        assertThat(decision.priority()).isEqualTo(-301);
        assertThat(decision.dropped()).isTrue();
        assertThat(decision.immediate()).isFalse();
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("dropBelow"));
    }

    @Test
    void scoreIsClampedByMaxPriority() {
        Map<String, Integer> attributes = new LinkedHashMap<>(attributes());
        attributes.put("qq_person_id/10001", 900);
        PriorityDecision decision = resolver(sources(), attributes)
                .resolve(GROUP, "10001", "路人", PriorityAttributes.of("qq_at_bot"));
        assertThat(decision.priority()).isEqualTo(1000);
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("clamp"));
    }

    @Test
    void unknownPlatformFallsBackToTheFallbackRule() {
        PriorityDecision decision = resolver(sources(), attributes())
                .resolve("bilibili/123", "", "", PriorityAttributes.none());
        assertThat(decision.priority()).isZero();
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("→ * 0"));
    }

    @Test
    void contentRulesStackInDeclaredOrder() {
        AppConfig.Weighting weighting = new AppConfig.Weighting(
                300, -300, 1000, 30, 1,
                java.util.Map.of("*", 0, "qq_group", 0, "qq_private", 300),
                AppConfig.Weighting.defaultAttributes(),
                java.util.List.of(
                        new AppConfig.ContentRule("/", "prefix", -300, null),
                        new AppConfig.ContentRule("@bot", "contains", 350, null),
                        new AppConfig.ContentRule("大肥鱼", "contains", 150, null)));
        PriorityResolver resolver = new PriorityResolver(() -> weighting);

        // 群聊 0 + "/" 前缀 -300 + "@bot" +350 + "大肥鱼" +150 = 200
        PriorityDecision decision = resolver.resolve(GROUP, "20002", "LvtN",
                PriorityAttributes.none(), "/test 现在我来试试at大肥鱼：@bot");
        assertThat(decision.priority()).isEqualTo(200);
        assertThat(decision.dropped()).isFalse();
        assertThat(decision.immediate()).isFalse();
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("rule:prefix:/"));
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("rule:contains:大肥鱼"));
    }

    @Test
    void contentRulesCanDropAMessageEntirely() {
        AppConfig.Weighting weighting = new AppConfig.Weighting(
                300, -300, 1000, 30, 1,
                java.util.Map.of("*", 0, "qq_group", 0),
                AppConfig.Weighting.defaultAttributes(),
                java.util.List.of(new AppConfig.ContentRule(".rd", "prefix", -601, null)));
        PriorityResolver resolver = new PriorityResolver(() -> weighting);

        PriorityDecision decision = resolver.resolve(GROUP, "20002", "LvT",
                PriorityAttributes.none(), ".rd 暗骰");
        assertThat(decision.priority()).isEqualTo(-601);
        assertThat(decision.dropped()).isTrue();
    }

    @Test
    void contentRuleMatchingIsCaseInsensitiveByDefault() {
        AppConfig.ContentRule rule = new AppConfig.ContentRule("BOT", "contains", 10, null);
        assertThat(rule.matches("/test @bot")).isTrue();
        assertThat(new AppConfig.ContentRule("BOT", "contains", 10, false).matches("/test @bot"))
                .isFalse();
    }
}
