package io.mosire.bma3.qqofficial.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.source.SourceLabels;
import io.mosire.bma3.qqofficial.directory.OfficialQqDirectoryStore;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QqOfficialSourceLabelerTest {

    private static final Instant T0 = Instant.parse("2026-11-17T08:00:00Z");
    private static final String SHARED_OPENID = "abcdefghijklmnopqrstuvwxyz";

    @TempDir
    Path temp;

    @Test
    void aliasesAndNamesAreAccountScopedAndWinOverOpenids() {
        try (OfficialQqDirectoryStore store =
                     new OfficialQqDirectoryStore(temp.resolve("labels.db"))) {
            store.setGroupAlias("official-a", SHARED_OPENID, "甲群别名", T0);
            store.setGroupName("official-a", SHARED_OPENID, "甲群名", T0);
            store.setGroupName("official-b", SHARED_OPENID, "乙群名", T0);
            store.observeUser("official-a", "USER_SHARED", "甲用户", T0);
            store.observeUser("official-b", "USER_SHARED", "乙用户", T0);

            SourceLabels labels = SourceLabels.builder()
                    .register(new QqOfficialSourceLabeler(() -> store))
                    .build();

            assertThat(labels.label(SourceAddress.parse(
                    "qq_official_group/official-a/" + SHARED_OPENID)))
                    .isEqualTo("QQ群 甲群别名");
            assertThat(labels.label(SourceAddress.parse(
                    "qq_official_group/official-b/" + SHARED_OPENID)))
                    .isEqualTo("QQ群 乙群名");
            assertThat(labels.label(SourceAddress.parse(
                    "qq_official_private/official-a/USER_SHARED")))
                    .isEqualTo("QQ私聊 甲用户");
            assertThat(labels.label(SourceAddress.parse(
                    "qq_official_private/official-b/USER_SHARED")))
                    .isEqualTo("QQ私聊 乙用户");
        }
    }

    @Test
    void fallsBackToAShortOpenidAndKeepsSupportScopedToOfficialPlatforms() {
        SourceLabels labels = SourceLabels.builder()
                .register(new QqOfficialSourceLabeler())
                .build();

        String rendered = labels.label(SourceAddress.parse(
                "qq_official_group/official-a/" + SHARED_OPENID));
        assertThat(rendered).isEqualTo("QQ群 abcdefgh…");
        assertThat(rendered).doesNotContain(SHARED_OPENID);

        assertThat(labels.label(SourceAddress.parse("qq_group/bot-1/955432692")))
                .isEqualTo("qq_group · 955432692");
        assertThat(new QqOfficialSourceLabeler().supports("qq_official_private")).isTrue();
        assertThat(new QqOfficialSourceLabeler().supports("qq_private")).isFalse();
    }
}
