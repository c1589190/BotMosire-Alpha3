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
                300, -300, 1000, 30, 1, sourcePriority, attributes);
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
        attributes.put("qq_sender/10001", 50);
        PriorityDecision decision = resolver(sources(), attributes)
                .resolve(GROUP, "10001", "南沐秋", PriorityAttributes.none());
        assertThat(decision.priority()).isEqualTo(250);
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("qq_sender/10001"));
    }

    @Test
    void nameRuleMatchesOnDisplayNameSubstring() {
        Map<String, Integer> attributes = new LinkedHashMap<>(attributes());
        attributes.put("qq_name/管理", 40);
        PriorityDecision hit = resolver(sources(), attributes)
                .resolve(GROUP, "10001", "群管理小王", PriorityAttributes.none());
        assertThat(hit.priority()).isEqualTo(240);

        PriorityDecision miss = resolver(sources(), attributes)
                .resolve(GROUP, "10001", "路人", PriorityAttributes.none());
        assertThat(miss.priority()).isEqualTo(200);
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
        attributes.put("qq_sender/10001", 900);
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
}
