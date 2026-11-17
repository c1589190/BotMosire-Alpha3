package io.mosire.bma3.qqofficial.credential;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit repository-path policy for a credential file.
 *
 * <p>The generic credential resolver deliberately does not guess a repository root from the
 * process working directory. Production composition may pass the actual Git worktree root(s)
 * discovered by the app layer; tests may pass a temporary root. Containment is evaluated on
 * {@link Path#toRealPath()} results so a parent-directory symlink cannot hide a file inside a
 * forbidden root.</p>
 */
public final class OfficialQqCredentialPathPolicy {

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");

    private final List<Path> forbiddenRoots;
    private final boolean detectGitWorktrees;

    private OfficialQqCredentialPathPolicy(
            List<Path> forbiddenRoots, boolean detectGitWorktrees) {
        this.forbiddenRoots = List.copyOf(forbiddenRoots);
        this.detectGitWorktrees = detectGitWorktrees;
    }

    /** No repository boundary is known; owner-only file checks still apply in the resolver. */
    public static OfficialQqCredentialPathPolicy none() {
        return new OfficialQqCredentialPathPolicy(List.of(), false);
    }

    /**
     * Build a policy from explicit, already-existing forbidden roots. This method never inspects
     * the current directory or invokes Git.
     */
    public static OfficialQqCredentialPathPolicy of(Path... roots) {
        return of(List.of(roots));
    }

    /** Build a policy from explicit, already-existing forbidden roots. */
    public static OfficialQqCredentialPathPolicy of(Collection<Path> roots) {
        return fromRoots(roots, false);
    }

    /**
     * Enable hot-reload-safe Git worktree detection in addition to explicit roots.
     *
     * <p>Every {@link #isForbidden(Path)} call walks the candidate's real-path ancestor chain and
     * rejects it when an ancestor contains any {@code .git} entry (regular file, directory or
     * symlink). The marker is never followed or read. No working-directory guess, Git process,
     * repository content scan or
     * startup-time snapshot is used. Explicit roots remain useful for repository layouts where the
     * credential path itself is not under the worktree root.</p>
     */
    public static OfficialQqCredentialPathPolicy forGitWorktrees(Path... explicitRoots) {
        return forGitWorktrees(List.of(explicitRoots));
    }

    /** Collection overload of {@link #forGitWorktrees(Path...)}. */
    public static OfficialQqCredentialPathPolicy forGitWorktrees(
            Collection<Path> explicitRoots) {
        return fromRoots(explicitRoots, true);
    }

    private static OfficialQqCredentialPathPolicy fromRoots(
            Collection<Path> roots, boolean detectGitWorktrees) {
        Objects.requireNonNull(roots, "roots");
        Set<Path> realRoots = new LinkedHashSet<>();
        for (Path root : roots) {
            Objects.requireNonNull(root, "root");
            Path absolute = root.toAbsolutePath().normalize();
            if (!Files.isDirectory(absolute)) {
                throw new IllegalArgumentException("forbidden root 必须是已存在目录");
            }
            try {
                realRoots.add(absolute.toRealPath());
            } catch (IOException failure) {
                throw new IllegalArgumentException("无法解析 forbidden root 真实路径", failure);
            }
        }
        return new OfficialQqCredentialPathPolicy(new ArrayList<>(realRoots), detectGitWorktrees);
    }

    /**
     * True when the candidate's real path is inside any configured forbidden root.
     *
     * @throws IOException when the candidate's real path cannot be resolved
     */
    public boolean isForbidden(Path candidate) throws IOException {
        Objects.requireNonNull(candidate, "candidate");
        if (forbiddenRoots.isEmpty() && !detectGitWorktrees) {
            return false;
        }
        Path realCandidate = candidate.toRealPath();
        for (Path root : forbiddenRoots) {
            if (containsPath(realCandidate, root, WINDOWS)) {
                return true;
            }
        }
        return detectGitWorktrees && hasGitWorktreeAncestor(realCandidate);
    }

    private static boolean hasGitWorktreeAncestor(Path realCandidate) {
        Path current = Files.isDirectory(realCandidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                ? realCandidate
                : realCandidate.getParent();
        while (current != null) {
            Path marker = current.resolve(".git");
            if (Files.exists(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    /** Package-visible for a deterministic case-sensitivity regression test. */
    static boolean containsPath(Path candidate, Path root, boolean caseInsensitive) {
        Path candidateAbsolute = candidate.toAbsolutePath().normalize();
        Path rootAbsolute = root.toAbsolutePath().normalize();
        if (!sameName(candidateAbsolute.getRoot(), rootAbsolute.getRoot(), caseInsensitive)) {
            return false;
        }
        int rootCount = rootAbsolute.getNameCount();
        if (candidateAbsolute.getNameCount() < rootCount) {
            return false;
        }
        for (int i = 0; i < rootCount; i++) {
            if (!sameName(candidateAbsolute.getName(i), rootAbsolute.getName(i), caseInsensitive)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameName(Path left, Path right, boolean caseInsensitive) {
        if (left == null || right == null) {
            return left == right;
        }
        String leftText = left.toString();
        String rightText = right.toString();
        return caseInsensitive
                ? leftText.equalsIgnoreCase(rightText)
                : leftText.equals(rightText);
    }
}
