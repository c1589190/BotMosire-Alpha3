package io.github.c1589190.bma3.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.c1589190.bma3.qqofficial.credential.OfficialQqCredentialException;
import io.github.c1589190.bma3.qqofficial.credential.OfficialQqCredentialResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryBoundariesTest {
    @TempDir Path temp;

    @Test void findsGitDirectoryAndWorktreeFileWithoutMarkingUnrelatedInstall() throws Exception {
        Path repo = Files.createDirectory(temp.resolve("repo"));
        Files.createDirectory(repo.resolve(".git"));
        Path file = repo.resolve("secret");
        Files.writeString(file, "fake-not-a-real-secret");
        Path worktree = Files.createDirectory(temp.resolve("other-worktree"));
        Files.writeString(worktree.resolve(".git"), "gitdir: /unused\n");
        Path install = Files.createDirectory(temp.resolve("install"));

        var roots = RepositoryBoundaries.findWorktrees(List.of(file, worktree, install));
        assertThat(roots).containsExactlyInAnyOrder(repo.toRealPath(), worktree.toRealPath());
        var policy = RepositoryBoundaries.credentialPolicy(List.of(file, install));
        var resolver = new OfficialQqCredentialResolver("account", "synthetic-app-id", "", file,
                Map.of(), policy);
        assertThatThrownBy(resolver::resolve)
                .isInstanceOf(OfficialQqCredentialException.class)
                .hasMessageNotContaining("fake-not-a-real-secret");
    }

    @Test void handlesAbsentDirectoriesAndSiblingPrefixes() throws Exception {
        Path repo = Files.createDirectory(temp.resolve("repo"));
        Files.createDirectory(repo.resolve(".git"));
        Path sibling = Files.createDirectory(temp.resolve("repo2"));
        Path missing = repo.resolve("not-created").resolve("secret");
        assertThat(RepositoryBoundaries.findWorktrees(List.of(missing, sibling)))
                .containsExactly(repo.toRealPath());
        assertThat(RepositoryBoundaries.findWorktrees(List.of(sibling))).isEmpty();
    }
}
