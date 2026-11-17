package io.mosire.bma3.app.security;

import io.mosire.bma3.qqofficial.credential.OfficialQqCredentialPathPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** Discover actual Git worktree boundaries for the credential-file prohibition. */
public final class RepositoryBoundaries {
    private RepositoryBoundaries() { }

    /**
     * Find the innermost Git worktree for each starting point, resolving symlinks first. A Git
     * worktree can use either a {@code .git} directory or a {@code .git} indirection file.
     * Starting points may include the application working/config directories, the location of
     * the code and the configured credential files. No subprocess or network is used.
     *
     * <p>A regular installation outside Git is not itself made forbidden; each configured
     * credential path is also checked as a starting point so that starting the installed app
     * outside a checkout does not permit a secret stored inside a checkout.</p>
     */
    public static OfficialQqCredentialPathPolicy credentialPolicy(Collection<Path> startingPoints) {
        return OfficialQqCredentialPathPolicy.forGitWorktrees(findWorktrees(startingPoints));
    }

    public static Set<Path> findWorktrees(Collection<Path> startingPoints) {
        Set<Path> roots = new LinkedHashSet<>();
        if (startingPoints == null) return Set.of();
        for (Path start : startingPoints) {
            if (start == null) continue;
            Path current = absoluteRealOrNormalized(start);
            if (!Files.isDirectory(current)) current = current.getParent();
            while (current != null) {
                Path marker = current.resolve(".git");
                if (Files.isDirectory(marker) || Files.isRegularFile(marker)) {
                    roots.add(absoluteRealOrNormalized(current));
                    break;
                }
                current = current.getParent();
            }
        }
        return Set.copyOf(roots);
    }

    private static Path absoluteRealOrNormalized(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException failure) {
            return path.toAbsolutePath().normalize();
        }
    }
}
