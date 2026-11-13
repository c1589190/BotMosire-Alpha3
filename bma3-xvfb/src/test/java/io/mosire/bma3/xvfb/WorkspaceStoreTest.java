package io.mosire.bma3.xvfb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceStoreTest {

    @Test
    void createLaysOutTheWorkspaceDirectories(@TempDir Path root) {
        WorkspaceStore store = new WorkspaceStore(root);
        Workspace workspace = store.create(97, 1440, 900, 24);

        assertThat(workspace.id()).startsWith("ws-");
        assertThat(workspace.state()).isEqualTo(Workspace.STARTING);
        assertThat(workspace.display()).isEqualTo(97);
        assertThat(workspace.dir()).isDirectory();
        for (String child : List.of("home", "uploads", "downloads", "shots", "logs")) {
            assertThat(workspace.dir().resolve(child)).as(child).isDirectory();
        }
        assertThat(workspace.dir().resolve("meta.json")).exists();
        assertThat(store.list()).hasSize(1);
        assertThat(store.find(workspace.id())).isPresent();
    }

    @Test
    void metaRoundTripsThroughDisk(@TempDir Path root) {
        WorkspaceStore store = new WorkspaceStore(root);
        Workspace workspace = store.create(98, 1280, 800, 24);
        Workspace running = workspace.withState(Workspace.RUNNING)
                .withProcesses(4242L, 4243L, List.of(5000L, 5001L));
        store.save(running);

        Workspace reloaded = store.find(workspace.id()).orElseThrow();
        assertThat(reloaded.state()).isEqualTo(Workspace.RUNNING);
        assertThat(reloaded.xvfbPid()).isEqualTo(4242L);
        assertThat(reloaded.wmPid()).isEqualTo(4243L);
        assertThat(reloaded.appPids()).containsExactly(5000L, 5001L);
    }

    @Test
    void closeWithoutKeepDeletesTheWholeDirectory(@TempDir Path root) {
        WorkspaceStore store = new WorkspaceStore(root);
        Workspace workspace = store.create(99, 1024, 768, 24);

        Workspace deleted = store.close(workspace, false);

        assertThat(deleted.state()).isEqualTo(Workspace.DELETED);
        assertThat(workspace.dir()).doesNotExist();
        assertThat(store.list()).isEmpty();
    }

    @Test
    void closeWithKeepPreservesHomeAndClearsShots(@TempDir Path root) throws Exception {
        WorkspaceStore store = new WorkspaceStore(root);
        Workspace workspace = store.create(100, 1024, 768, 24);
        Files.writeString(workspace.dir().resolve("home/cookie.txt"), "login-state");
        Files.writeString(workspace.dir().resolve("shots/old.png"), "png");

        Workspace kept = store.close(workspace, true);

        assertThat(kept.state()).isEqualTo(Workspace.KEPT);
        assertThat(workspace.dir().resolve("home/cookie.txt")).hasContent("login-state");
        assertThat(workspace.dir().resolve("shots")).isDirectory();
        assertThat(workspace.dir().resolve("shots/old.png")).doesNotExist();
        assertThat(store.find(workspace.id()).orElseThrow().state()).isEqualTo(Workspace.KEPT);
    }

    @Test
    void pathsOutsideTheRootAreRejected(@TempDir Path root) {
        WorkspaceStore store = new WorkspaceStore(root);

        assertThat(store.resolve("../escape")).isNull();
        assertThat(store.resolve("/etc")).isNull();
        assertThat(store.find("../escape")).isEmpty();
        assertThat(store.resolve("ws-ok")).isNotNull();
    }
}
