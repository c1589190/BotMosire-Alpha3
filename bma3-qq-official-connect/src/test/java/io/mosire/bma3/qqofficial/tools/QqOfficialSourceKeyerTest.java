package io.mosire.bma3.qqofficial.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.bma3.core.source.SourceAddress;
import org.junit.jupiter.api.Test;

class QqOfficialSourceKeyerTest {

    private final QqOfficialSourceKeyer keyer = new QqOfficialSourceKeyer();

    @Test
    void stripsOnlyTheAccountAndKeepsOpenidsOpaqueStrings() {
        String openid = "00123456789012345678901234567890";

        assertThat(keyer.keyFor(SourceAddress.parse(
                "qq_official_group/official-main/" + openid)))
                .isEqualTo("qq_official_group/" + openid);
        assertThat(keyer.keyFor(SourceAddress.parse(
                "qq_official_private/official-main/USER_OPENID_A")))
                .isEqualTo("qq_official_private/USER_OPENID_A");
        assertThat(keyer.keyFor(SourceAddress.parse(
                "qq_official_system/official-main/FRIEND_ADD")))
                .isEqualTo("qq_official_system/FRIEND_ADD");
    }

    @Test
    void rejectsMalformedOfficialShapesWithoutEchoingTheValue() {
        assertThatThrownBy(() -> keyer.keyFor(
                SourceAddress.parse("qq_official_group/official-main")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform/accountId/target")
                .hasMessageNotContaining("official-main");
        assertThatThrownBy(() -> keyer.keyFor(
                SourceAddress.parse("qq_official_group/official-main/GROUP/extra")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform/accountId/target");
        assertThatThrownBy(() -> keyer.keyFor(
                SourceAddress.parse("qq_official_private/acct with space/USER")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountId")
                .hasMessageNotContaining("acct with space");
        assertThatThrownBy(() -> keyer.keyFor(
                SourceAddress.parse("qq_official_group/official-main/GROUP\\nOTHER")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target")
                .hasMessageNotContaining("GROUP");
    }

    @Test
    void nonOfficialPlatformsKeepTheirGenericFallback() {
        assertThat(keyer.keyFor(SourceAddress.parse("qq_group/bot-1/955432692")))
                .isEqualTo("qq_group/955432692");
        assertThat(QqOfficialSourceKeyer.supportsPlatform("qq_official_group")).isTrue();
        assertThat(QqOfficialSourceKeyer.supportsPlatform("qq_group")).isFalse();
    }
}
