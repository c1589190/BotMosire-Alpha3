package io.mosire.bma3.qqofficial.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OfficialQqEventDeduplicatorTest {

    private final OfficialQqEventDeduplicator deduplicator = new OfficialQqEventDeduplicator(8);

    @Test
    void sameMessageIdInDifferentRawSourcesIsNotDropped() {
        String account = "official-main";
        String groupA = "qq_official_group/official-main/GROUP_A";
        String groupB = "qq_official_group/official-main/GROUP_B";

        assertThat(deduplicator.firstSeen(account, groupA, "event-a", "same-message"))
                .isTrue();
        assertThat(deduplicator.firstSeen(account, groupB, "event-b", "same-message"))
                .as("message ids are scoped by raw source, not globally")
                .isTrue();
        assertThat(deduplicator.firstSeen(account, groupA, "event-c", "same-message"))
                .as("only the same raw source is a duplicate")
                .isFalse();
    }

    @Test
    void sameEventIdInDifferentAccountsIsNotDropped() {
        assertThat(deduplicator.firstSeen(
                        "account-a", "qq_official_group/account-a/GROUP_A",
                        "shared-event-id", "message-a"))
                .isTrue();
        assertThat(deduplicator.firstSeen(
                        "account-b", "qq_official_group/account-b/GROUP_B",
                        "shared-event-id", "message-b"))
                .as("event ids are scoped by account")
                .isTrue();
        assertThat(deduplicator.firstSeen(
                        "account-a", "qq_official_private/account-a/USER_A",
                        "shared-event-id", "message-c"))
                .as("the event id is still a duplicate inside the same account")
                .isFalse();
    }

    @Test
    void compactEventOverloadDerivesTheScopedRawSource() {
        OfficialQqEvent event = new OfficialQqEvent(
                "event-1", "C2C_MESSAGE_CREATE", "message-1", "", "user-1", "",
                "Alice", "", "", Instant.EPOCH, List.of(), "", "", "",
                false, false, false, 1L, Instant.EPOCH);

        assertThat(deduplicator.firstSeen(event, "official-main")).isTrue();
        assertThat(deduplicator.firstSeen(event, "official-main")).isFalse();
        assertThat(deduplicator.firstSeen(event, "official-other")).isTrue();
    }

    @Test
    void missingOrUnsafeScopesFailClosedWithoutEchoingValues() {
        assertThatThrownBy(() -> deduplicator.firstSeen(
                        "", "qq_official_group/x/G", "event-1", "message-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountId");
        assertThatThrownBy(() -> deduplicator.firstSeen(
                        "acct/other", "qq_official_group/x/G", "event-1", "message-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountId")
                .hasMessageNotContaining("acct/other");
        assertThatThrownBy(() -> deduplicator.firstSeen(
                        "acct", "", "event-1", "message-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rawSource");
        assertThatThrownBy(() -> deduplicator.firstSeen(
                        "acct", "qq_official_group/acct/G", "event-1", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageId");
        assertThatThrownBy(() -> deduplicator.firstSeen(
                        "acct", "qq_official_group/acct/G|X", "event-1", "message-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rawSource")
                .hasMessageNotContaining("G|X");
        assertThatThrownBy(() -> deduplicator.firstSeen(
                        "acct", "qq_official_group/acct/G", "event-1", "message|1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageId")
                .hasMessageNotContaining("message|1");
    }

    @Test
    void capacityIsBoundedButNeverUsesAGlobalFallbackKey() {
        for (int index = 0; index < 10; index++) {
            assertThat(deduplicator.firstSeen(
                            "acct", "qq_official_group/acct/GROUP_" + index,
                            "event-" + index, "message-" + index))
                    .isTrue();
        }
        assertThat(deduplicator.size()).isLessThanOrEqualTo(8);

        assertThatThrownBy(() -> new OfficialQqEvent(
                        "event-blank", "C2C_MESSAGE_CREATE", " ", "", "user-1", "",
                        "Alice", "", "", Instant.EPOCH, List.of(), "", "", "",
                        false, false, false, 1L, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageId");
        assertThatThrownBy(() -> deduplicator.firstSeen(
                        "acct", "qq_official_group/acct/G", "event-blank", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageId");
    }
}
