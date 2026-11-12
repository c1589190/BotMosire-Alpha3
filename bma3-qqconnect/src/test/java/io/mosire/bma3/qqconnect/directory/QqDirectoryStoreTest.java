package io.mosire.bma3.qqconnect.directory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QqDirectoryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void storesAndSearchesGroupsAndMembers() {
        try (QqDirectoryStore store = new QqDirectoryStore(tempDir.resolve("qq-directory.db"))) {
            store.upsertGroup("bot-1", "955432692", "钓鱼群", 42);
            store.upsertMember(new QqDirectoryStore.Member("bot-1", "955432692", "20002",
                    "南沐秋", "群名片小王", "admin", "大肥鱼", Instant.now().plusSeconds(3600),
                    Instant.now()));

            assertThat(store.ping()).isTrue();
            assertThat(store.group("bot-1", "955432692").orElseThrow().name())
                    .isEqualTo("钓鱼群");
            assertThat(store.memberCount("bot-1", "955432692")).isEqualTo(1);
            assertThat(store.member("bot-1", "955432692", "20002").orElseThrow().title())
                    .isEqualTo("大肥鱼");
            assertThat(store.searchGroups("钓鱼", 10)).hasSize(1);
            assertThat(store.searchMembers("小王", 10)).hasSize(1);
            assertThat(store.searchMembers("大肥鱼", 10)).hasSize(1);
        }
    }

    @Test
    void upsertReplacesTheCardAndTitle() {
        try (QqDirectoryStore store = new QqDirectoryStore(tempDir.resolve("replace.db"))) {
            store.upsertMember(new QqDirectoryStore.Member("bot-1", "1", "20002",
                    "nick", "old-card", "member", "", null, Instant.now()));
            store.upsertMember(new QqDirectoryStore.Member("bot-1", "1", "20002",
                    "nick", "new-card", "admin", "头衔", null, Instant.now()));
            QqDirectoryStore.Member member = store.member("bot-1", "1", "20002").orElseThrow();
            assertThat(member.card()).isEqualTo("new-card");
            assertThat(member.role()).isEqualTo("admin");
            assertThat(member.title()).isEqualTo("头衔");
        }
    }
}
