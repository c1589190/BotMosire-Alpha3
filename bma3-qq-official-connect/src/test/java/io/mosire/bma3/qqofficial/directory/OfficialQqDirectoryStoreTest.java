package io.mosire.bma3.qqofficial.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.bma3.qqofficial.directory.OfficialQqDirectoryStore.State;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialQqDirectoryStoreTest {
    @TempDir Path temp;
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test void observationsAndAliasesAreLocalAccountScopedAndDurable() {
        Path db = temp.resolve("directory.db");
        try (var store = new OfficialQqDirectoryStore(db)) {
            assertThat(store.groups("bot-a", 10)).isEmpty();
            store.setGroupAlias("bot-a", "abcdefghijkl", "别名", T0);
            var before = store.group("bot-a", "abcdefghijkl").orElseThrow();
            assertThat(before.label()).isEqualTo("别名");
            assertThat(before.observed()).isFalse();
            assertThat(before.joined()).isEqualTo(State.UNKNOWN);
            store.observeGroup("bot-a", "abcdefghijkl", T0.plusSeconds(1));
            store.setGroupName("bot-a", "abcdefghijkl", "群名", T0.plusSeconds(2));
            store.observeGroup("bot-b", "abcdefghijkl", T0.plusSeconds(3));
            assertThat(store.groups("bot-a", 10)).singleElement().satisfies(group -> {
                assertThat(group.observed()).isTrue();
                assertThat(group.name()).isEqualTo("群名");
                assertThat(group.label()).isEqualTo("别名");
            });
            assertThat(store.group("bot-b", "abcdefghijkl").orElseThrow().label())
                    .isEqualTo("QQ群 · abcdefgh…");
            assertThat(store.ping()).isTrue();
        }
        try (var reopened = new OfficialQqDirectoryStore(db)) {
            assertThat(reopened.group("bot-a", "abcdefghijkl")).get()
                    .satisfies(g -> {
                        assertThat(g.alias()).isEqualTo("别名");
                        assertThat(g.observed()).isTrue();
                        assertThat(g.lastObservedAt()).isEqualTo(T0.plusSeconds(2));
                    });
        }
    }

    @Test void aliasAloneDoesNotPretendTheSourceWasObserved() {
        try (var store = new OfficialQqDirectoryStore(temp.resolve("alias.db"))) {
            store.setGroupAlias("bot", "group", "alias", T0.plusSeconds(20));
            assertThat(store.group("bot", "group")).get().satisfies(g ->
                    assertThat(g.observed()).isFalse());
            store.observeGroup("bot", "group", T0);
            assertThat(store.group("bot", "group")).get().satisfies(g -> {
                assertThat(g.observed()).isTrue();
                assertThat(g.lastObservedAt()).isEqualTo(T0);
            });
        }
    }

    @Test void outOfOrderEventsDoNotRollBackKnownStateOrNames() {
        try (var store = new OfficialQqDirectoryStore(temp.resolve("state.db"))) {
            store.setGroupJoined("bot", "group", true, T0.plusSeconds(10));
            store.setGroupJoined("bot", "group", false, T0.plusSeconds(1));
            store.setGroupReceiveMessages("bot", "group", false, T0.plusSeconds(10));
            store.setGroupReceiveMessages("bot", "group", true, T0.plusSeconds(1));
            store.observeUser("bot", "user", "new", T0.plusSeconds(10));
            store.observeUser("bot", "user", "old", T0.plusSeconds(1));
            store.observeUser("bot", "user", "", T0.plusSeconds(20));
            store.setFriend("bot", "user", true, T0.plusSeconds(10));
            store.setFriend("bot", "user", false, T0.plusSeconds(1));
            assertThat(store.group("bot", "group")).get().satisfies(g -> {
                assertThat(g.joined()).isEqualTo(State.YES);
                assertThat(g.receiveMessages()).isEqualTo(State.NO);
                assertThat(g.lastObservedAt()).isEqualTo(T0.plusSeconds(10));
            });
            assertThat(store.user("bot", "user")).get().satisfies(u -> {
                assertThat(u.friend()).isEqualTo(State.YES);
                assertThat(u.name()).isEqualTo("new");
                assertThat(u.lastObservedAt()).isEqualTo(T0.plusSeconds(20));
            });
        }
    }

    @Test void sourceSegmentsAreValidatedWithoutEchoingUntrustedValues() {
        try (var store = new OfficialQqDirectoryStore(temp.resolve("bad.db"))) {
            assertThatThrownBy(() -> store.observeGroup("bot", "bad/identifier", T0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("bad/identifier");
            assertThatThrownBy(() -> store.observeUser("bot\nadmin", "user", "", T0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("admin");
            assertThatThrownBy(() -> store.observeGroup("bot", "group|id", T0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("group|id");
            assertThatThrownBy(() -> store.observeGroup("bot", "group\\id", T0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(store.groups("bot", 10)).isEmpty();
        }
    }
}
