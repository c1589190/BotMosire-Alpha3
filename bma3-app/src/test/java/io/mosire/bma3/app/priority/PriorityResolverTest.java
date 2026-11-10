package io.mosire.bma3.app.priority;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.bma3.app.config.AppConfig;
import io.mosire.bma3.core.priority.PrioritySignals;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PriorityResolverTest {

    private static PriorityResolver resolver() {
        AppConfig.Weighting weighting = new AppConfig.Weighting(300, 0, 200, 30, 1,
                Map.of("qq_private", 300, "qq_group", 0, "bilibili", 120),
                new AppConfig.Weighting.SignalWeights(300, 300, 200, 300));
        return new PriorityResolver(() -> weighting);
    }

    @Test
    void groupMentionBreaksTheGlobalSilence() {
        PriorityDecision decision = resolver().resolve("qq_group", null, null, null,
                new PrioritySignals(true, false, false, false));
        assertThat(decision.priority()).isEqualTo(300);
        assertThat(decision.notified()).isTrue();
    }

    @Test
    void ordinaryGroupStaysBelowThreshold() {
        PriorityDecision decision = resolver().resolve("qq_group", null, null, null,
                PrioritySignals.none());
        assertThat(decision.priority()).isZero();
        assertThat(decision.notified()).isFalse();
    }

    @Test
    void sourceThresholdAndNoteAreHonored() {
        PriorityDecision threshold = resolver().resolve("qq_group", 100, 400, null,
                new PrioritySignals(true, false, false, false));
        assertThat(threshold.priority()).isEqualTo(300);
        assertThat(threshold.notified()).isFalse();

        PriorityDecision note = resolver().resolve("qq_group", null, null, 350,
                PrioritySignals.none());
        assertThat(note.priority()).isEqualTo(350);
        assertThat(note.notified()).isTrue();
    }

    @Test
    void platformDefaultIsGeneric() {
        PriorityDecision bili = resolver().resolve("bilibili", null, null, null,
                PrioritySignals.none());
        assertThat(bili.priority()).isEqualTo(120);
        assertThat(bili.notified()).isFalse();

        PriorityDecision privateChat = resolver().resolve("qq_private", null, null, null,
                PrioritySignals.none());
        assertThat(privateChat.priority()).isEqualTo(300);
        assertThat(privateChat.notified()).isTrue();
    }
}
