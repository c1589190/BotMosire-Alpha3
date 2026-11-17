package io.mosire.bma3.qqofficial.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialQqCredentialResolverTest {

    private static final String SECRET = "app-secret-do-not-leak";
    private static final String APP_ID = "synthetic-app-id-main";
    private static final String JSON_SECRET = "synthetic-onboarding-json-secret";

    @TempDir
    Path temp;

    @Test
    void resolvesASecretFromAnInjectedEnvironmentWithoutExposingIt() throws Exception {
        OfficialQqCredential credential = new OfficialQqCredentialResolver(
                        "qq-official-main",
                        "synthetic-app-id-main",
                        "BMA3_TEST_SECRET",
                        null,
                        Map.of("BMA3_TEST_SECRET", SECRET))
                .resolve();

        assertThat(credential.appId()).isEqualTo("synthetic-app-id-main");
        char[] secret = credential.copySecret();
        try {
            assertThat(new String(secret)).isEqualTo(SECRET);
        } finally {
            java.util.Arrays.fill(secret, '\0');
        }
        assertThat(credential.toString()).doesNotContain(SECRET).contains("<redacted>");
        credential.close();
        assertThat(credential.isClosed()).isTrue();
    }

    @Test
    void aMissingEnvironmentVariableFailsClosed() {
        assertThatThrownBy(() -> new OfficialQqCredentialResolver(
                        "acc", "app", "BMA3_TEST_SECRET", null, Map.of())
                .resolve())
                .isInstanceOfSatisfying(
                        OfficialQqCredentialException.class,
                        failure -> {
                            assertThat(failure.reason())
                                    .isEqualTo(OfficialQqCredentialException.Reason.MISSING);
                            assertThat(failure.getMessage()).contains("source=ENV");
                        });
    }

    @Test
    void configuringEnvironmentAndFileTogetherFailsClosed() {
        assertThatThrownBy(() -> new OfficialQqCredentialResolver(
                        "acc", "app", "BMA3_TEST_SECRET", temp.resolve("secret"), Map.of())
                .resolve())
                .isInstanceOfSatisfying(
                        OfficialQqCredentialException.class,
                        failure -> {
                            assertThat(failure.reason())
                                    .isEqualTo(OfficialQqCredentialException.Reason.CONFIGURATION);
                            assertThat(failure.getMessage()).contains("source=BOTH");
                        });
    }

    @Test
    void neitherSourceFailsClosed() {
        assertThatThrownBy(() -> new OfficialQqCredentialResolver(
                        "acc", "app", null, null, Map.of())
                .resolve())
                .isInstanceOfSatisfying(
                        OfficialQqCredentialException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(OfficialQqCredentialException.Reason.MISSING));
    }

    @Test
    void anOwnerOnlyFileIsTightenedBeforeReading() throws Exception {
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "需要 POSIX 权限语义");
        Path secretFile = temp.resolve("qq-official.secret");
        Files.writeString(secretFile, SECRET + "\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(
                secretFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));

        OfficialQqCredential credential = new OfficialQqCredentialResolver(
                        "acc", "app", null, secretFile, Map.of())
                .resolve();

        assertThat(Files.getPosixFilePermissions(secretFile))
                .containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        char[] secret = credential.copySecret();
        try {
            assertThat(new String(secret)).isEqualTo(SECRET);
        } finally {
            java.util.Arrays.fill(secret, '\0');
            credential.close();
        }
    }

    @Test
    void aSymlinkedCredentialFileIsRejectedBeforeReading() throws Exception {
        Path target = temp.resolve("target.secret");
        Path link = temp.resolve("linked.secret");
        Files.writeString(target, SECRET, StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.assumeTrue(false, "当前文件系统不支持符号链接");
        }

        assertThatThrownBy(() -> new OfficialQqCredentialResolver(
                        "acc", "app", null, link, Map.of())
                .resolve())
                .isInstanceOfSatisfying(
                        OfficialQqCredentialException.class,
                        failure -> {
                            assertThat(failure.reason())
                                    .isEqualTo(OfficialQqCredentialException.Reason.PERMISSION);
                            assertThat(failure.getMessage()).doesNotContain(SECRET);
                        });
    }

    @Test
    void missingAndEmptyFilesAreDiagnosedWithoutSecretContents() throws Exception {
        assertThatThrownBy(() -> new OfficialQqCredentialResolver(
                        "acc", "app", null, temp.resolve("absent"), Map.of())
                .resolve())
                .isInstanceOfSatisfying(
                        OfficialQqCredentialException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(OfficialQqCredentialException.Reason.MISSING));

        Path empty = temp.resolve("empty");
        Files.writeString(empty, "   \n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new OfficialQqCredentialResolver(
                        "acc", "app", null, empty, Map.of())
                .resolve())
                .isInstanceOfSatisfying(
                        OfficialQqCredentialException.class,
                        failure -> {
                            assertThat(failure.reason())
                                    .isEqualTo(OfficialQqCredentialException.Reason.MISSING);
                            assertThat(failure.getMessage()).doesNotContain("   ");
                        });
    }

    @Test
    void onboardingJsonIsAcceptedAndTheFileIsTightenedToOwnerOnly() throws Exception {
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "需要 POSIX 权限语义");
        Path file = temp.resolve("onboarding-credentials.json");
        Files.writeString(
                file,
                "{\n  \"appId\": \"" + APP_ID + "\",\n"
                        + "  \"appSecret\": \"" + JSON_SECRET + "\"\n}\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(
                file,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.OTHERS_READ));

        OfficialQqCredential credential = resolver(APP_ID, file).resolve();

        assertThat(Files.getPosixFilePermissions(file))
                .containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(credential.appId()).isEqualTo(APP_ID);
        assertThat(secretText(credential)).isEqualTo(JSON_SECRET);
        assertThat(credential.toString()).doesNotContain(JSON_SECRET).contains("<redacted>");
        credential.close();
    }

    @Test
    void onboardingJsonWithAMismatchedAppIdFailsClosedWithoutEchoingContents() throws Exception {
        String otherAppId = "synthetic-other-app-id";
        String sentinel = "synthetic-mismatch-secret-sentinel";
        Path file = temp.resolve("mismatched.json");
        Files.writeString(
                file,
                "{\"appId\":\"" + otherAppId + "\",\"appSecret\":\"" + sentinel + "\"}",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> resolver(APP_ID, file).resolve())
                .isInstanceOfSatisfying(
                        OfficialQqCredentialException.class,
                        failure -> {
                            assertThat(failure.reason())
                                    .isEqualTo(OfficialQqCredentialException.Reason.CONFIGURATION);
                            assertThat(failure.getCause()).isNull();
                            assertThat(failure.getMessage())
                                    .doesNotContain(otherAppId)
                                    .doesNotContain(sentinel)
                                    .contains("appId 与配置不一致");
                            assertThat(failure.toString()).doesNotContain(sentinel);
                        });
    }

    @Test
    void malformedOrInexactOnboardingJsonFailsClosedWithoutFileContents() throws Exception {
        String sentinel = "synthetic-bad-json-secret-sentinel";
        List<String> badDocuments = List.of(
                "{\"appId\":\"" + APP_ID + "\",\"appSecret\":\"" + sentinel,
                "{\"appId\":\"" + APP_ID + "\"}",
                "{\"appId\":\"" + APP_ID + "\",\"appSecret\":\"" + sentinel
                        + "\",\"extra\":true}",
                "{\"appId\":\"" + APP_ID + "\",\"appSecret\":{\"leak\":\"" + sentinel + "\"}}",
                "{\"appId\":\"" + APP_ID + "\",\"appSecret\":\"" + sentinel
                        + "\"} trailing",
                "{\"appId\":\"" + APP_ID + "\",\"appSecret\":\"" + sentinel
                        + "\",\"appSecret\":\"second-secret\"}");
        Path file = temp.resolve("bad-onboarding.json");
        for (String badDocument : badDocuments) {
            Files.writeString(file, badDocument, StandardCharsets.UTF_8);
            assertThatThrownBy(() -> resolver(APP_ID, file).resolve())
                    .as("bad document must fail closed: %s", badDocument)
                    .isInstanceOfSatisfying(
                            OfficialQqCredentialException.class,
                            failure -> {
                                assertThat(failure.reason())
                                        .isEqualTo(OfficialQqCredentialException.Reason.CONFIGURATION);
                                assertThat(failure.getCause()).isNull();
                                assertThat(failure.getMessage()).doesNotContain(sentinel);
                                assertThat(failure.toString()).doesNotContain(sentinel);
                            });
        }
    }

    @Test
    void onboardingJsonWithABlankAppSecretFailsClosed() throws Exception {
        Path file = temp.resolve("blank-secret.json");
        Files.writeString(
                file,
                "{\"appId\":\"" + APP_ID + "\",\"appSecret\":\"   \"}",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> resolver(APP_ID, file).resolve())
                .isInstanceOfSatisfying(
                        OfficialQqCredentialException.class,
                        failure -> {
                            assertThat(failure.reason())
                                    .isEqualTo(OfficialQqCredentialException.Reason.CONFIGURATION);
                            assertThat(failure.getCause()).isNull();
                            assertThat(failure.getMessage()).contains("appSecret 为空");
                        });
    }

    @Test
    void braceContainingAndUnrelatedJsonSecretsAreStillTreatedAsPlainText() throws Exception {
        List<String> plainSecrets = List.of(
                "{literal-secret-with-braces}",
                "{\"foo\":\"bar\"}",
                "{\"note\":\"appId\"}",
                "{\"note\":\"appSecret\"}",
                "prefix-{\"appId\":\"not-a-config-object\"}");
        Path file = temp.resolve("plain-secret");
        for (String plainSecret : plainSecrets) {
            Files.writeString(file, plainSecret, StandardCharsets.UTF_8);
            OfficialQqCredential credential = resolver(APP_ID, file).resolve();
            assertThat(secretText(credential)).as("必须原样作为普通 secret").isEqualTo(plainSecret);
            credential.close();
        }
    }

    @Test
    void explicitlyInjectedForbiddenRootRejectsAnInsideCredentialWithoutLeakingPath() throws Exception {
        Path root = temp.resolve("synthetic-repo");
        Files.createDirectories(root);
        Path secretFile = root.resolve("qq-official.secret");
        Files.writeString(secretFile, SECRET, StandardCharsets.UTF_8);
        OfficialQqCredentialPathPolicy policy = OfficialQqCredentialPathPolicy.of(root);

        assertThatThrownBy(() -> new OfficialQqCredentialResolver(
                        "acc", APP_ID, null, secretFile, Map.of(), policy)
                .resolve())
                .isInstanceOfSatisfying(
                        OfficialQqCredentialException.class,
                        failure -> {
                            assertThat(failure.reason())
                                    .isEqualTo(OfficialQqCredentialException.Reason.FORBIDDEN_LOCATION);
                            assertThat(failure.getMessage())
                                    .doesNotContain(SECRET)
                                    .doesNotContain(root.toString());
                        });
    }

    @Test
    void parentDirectorySymlinkCannotHideAFileInsideAForbiddenRoot() throws Exception {
        Path root = temp.resolve("synthetic-repo");
        Files.createDirectories(root);
        Path secretFile = root.resolve("qq-official.secret");
        Files.writeString(secretFile, SECRET, StandardCharsets.UTF_8);
        Path link = temp.resolve("outside-link");
        try {
            Files.createSymbolicLink(link, root);
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.assumeTrue(false, "当前文件系统不支持符号链接");
        }
        Path lexicallyOutside = link.resolve("qq-official.secret");
        OfficialQqCredentialPathPolicy policy = OfficialQqCredentialPathPolicy.of(root);

        assertThatThrownBy(() -> new OfficialQqCredentialResolver(
                        "acc", APP_ID, null, lexicallyOutside, Map.of(), policy)
                .resolve())
                .isInstanceOfSatisfying(
                        OfficialQqCredentialException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(OfficialQqCredentialException.Reason.FORBIDDEN_LOCATION));
    }

    @Test
    void siblingWithTheSamePrefixIsNotTreatedAsInsideTheForbiddenRoot() throws Exception {
        Path root = temp.resolve("synthetic-repo");
        Files.createDirectories(root);
        Path sibling = temp.resolve("synthetic-repo2");
        Files.createDirectories(sibling);
        Path secretFile = sibling.resolve("qq-official.secret");
        Files.writeString(secretFile, SECRET, StandardCharsets.UTF_8);

        OfficialQqCredential credential = new OfficialQqCredentialResolver(
                        "acc", APP_ID, null, secretFile, Map.of(),
                        OfficialQqCredentialPathPolicy.of(root))
                .resolve();
        assertThat(secretText(credential)).isEqualTo(SECRET);
        credential.close();
    }

    @Test
    void genericResolverDoesNotGuessTheCurrentDirectoryAsAForbiddenRoot() throws Exception {
        Path repoLike = temp.resolve("synthetic-repo");
        Files.createDirectories(repoLike);
        Path secretFile = repoLike.resolve("qq-official.secret");
        Files.writeString(secretFile, SECRET, StandardCharsets.UTF_8);

        OfficialQqCredential credential = new OfficialQqCredentialResolver(
                        "acc", APP_ID, null, secretFile, Map.of())
                .resolve();
        assertThat(secretText(credential)).isEqualTo(SECRET);
        credential.close();
    }

    @Test
    void sharedWritablePosixParentDirectoryIsRejected() throws Exception {
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "需要 POSIX 权限语义");
        Path shared = temp.resolve("shared-parent");
        Files.createDirectories(shared);
        Path secretFile = shared.resolve("qq-official.secret");
        Files.writeString(secretFile, SECRET, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(
                shared,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_WRITE,
                        PosixFilePermission.OTHERS_WRITE));

        assertThatThrownBy(() -> new OfficialQqCredentialResolver(
                        "acc", APP_ID, null, secretFile, Map.of())
                .resolve())
                .isInstanceOfSatisfying(
                        OfficialQqCredentialException.class,
                        failure -> {
                            assertThat(failure.reason())
                                    .isEqualTo(OfficialQqCredentialException.Reason.PERMISSION);
                            assertThat(failure.getMessage()).doesNotContain(SECRET);
                        });
    }

    @Test
    void windowsStyleContainmentComparisonIsCaseInsensitiveAndPrefixSafe() {
        assertThat(OfficialQqCredentialPathPolicy.containsPath(
                        Path.of("/CaseRoot/sub"), Path.of("/caseroot"), true))
                .isTrue();
        assertThat(OfficialQqCredentialPathPolicy.containsPath(
                        Path.of("/CaseRoot/sub"), Path.of("/caseroot"), false))
                .isFalse();
        assertThat(OfficialQqCredentialPathPolicy.containsPath(
                        Path.of("/repo2/file"), Path.of("/repo"), true))
                .isFalse();
    }

    @Test
    void gitWorktreeDetectionRejectsACredentialInsideARepository() throws Exception {
        Path repo = temp.resolve("synthetic-hot-repo");
        Files.createDirectories(repo.resolve(".git"));
        Path secretFile = repo.resolve("qq-official.secret");
        Files.writeString(secretFile, SECRET, StandardCharsets.UTF_8);
        OfficialQqCredentialPathPolicy policy =
                OfficialQqCredentialPathPolicy.forGitWorktrees(List.of());

        assertThatThrownBy(() -> new OfficialQqCredentialResolver(
                        "acc", APP_ID, null, secretFile, Map.of(), policy)
                .resolve())
                .isInstanceOfSatisfying(
                        OfficialQqCredentialException.class,
                        failure -> {
                            assertThat(failure.reason())
                                    .isEqualTo(OfficialQqCredentialException.Reason.FORBIDDEN_LOCATION);
                            assertThat(failure.getMessage())
                                    .doesNotContain(SECRET)
                                    .doesNotContain(repo.toString());
                        });
    }

    @Test
    void aWorktreeGitFileMarkerIsAlsoRejected() throws Exception {
        Path worktree = temp.resolve("synthetic-linked-worktree");
        Files.createDirectories(worktree);
        Files.writeString(
                worktree.resolve(".git"),
                "gitdir: /synthetic/git/dir\n",
                StandardCharsets.UTF_8);
        Path secretFile = worktree.resolve("qq-official.secret");
        Files.writeString(secretFile, SECRET, StandardCharsets.UTF_8);
        OfficialQqCredentialPathPolicy policy =
                OfficialQqCredentialPathPolicy.forGitWorktrees(List.of());

        assertThatThrownBy(() -> new OfficialQqCredentialResolver(
                        "acc", APP_ID, null, secretFile, Map.of(), policy)
                .resolve())
                .isInstanceOfSatisfying(
                        OfficialQqCredentialException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(OfficialQqCredentialException.Reason.FORBIDDEN_LOCATION));
    }

    @Test
    void aSymlinkedGitMarkerAlsoDefinesAForbiddenWorktree() throws Exception {
        Path worktree = temp.resolve("synthetic-symlink-git-marker");
        Files.createDirectories(worktree);
        Path marker = worktree.resolve(".git");
        Path markerTarget = temp.resolve("synthetic-external-git-metadata");
        try {
            // Deliberately leave the target absent: detection must not follow or read the marker.
            Files.createSymbolicLink(marker, markerTarget);
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.assumeTrue(false, "当前文件系统不支持符号链接");
        }
        Path secretFile = worktree.resolve("qq-official.secret");
        Files.writeString(secretFile, SECRET, StandardCharsets.UTF_8);
        OfficialQqCredentialPathPolicy policy =
                OfficialQqCredentialPathPolicy.forGitWorktrees(List.of());

        assertThatThrownBy(() -> new OfficialQqCredentialResolver(
                        "acc", APP_ID, null, secretFile, Map.of(), policy)
                .resolve())
                .isInstanceOfSatisfying(
                        OfficialQqCredentialException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(OfficialQqCredentialException.Reason.FORBIDDEN_LOCATION));
    }

    @Test
    void gitWorktreeDetectionFollowsRealPathAcrossASymlinkedParent() throws Exception {
        Path repo = temp.resolve("synthetic-symlink-repo");
        Files.createDirectories(repo.resolve(".git"));
        Path secretFile = repo.resolve("qq-official.secret");
        Files.writeString(secretFile, SECRET, StandardCharsets.UTF_8);
        Path link = temp.resolve("repo-link");
        try {
            Files.createSymbolicLink(link, repo);
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.assumeTrue(false, "当前文件系统不支持符号链接");
        }
        Path lexicallyOutside = link.resolve("qq-official.secret");
        OfficialQqCredentialPathPolicy policy =
                OfficialQqCredentialPathPolicy.forGitWorktrees(List.of());

        assertThatThrownBy(() -> new OfficialQqCredentialResolver(
                        "acc", APP_ID, null, lexicallyOutside, Map.of(), policy)
                .resolve())
                .isInstanceOfSatisfying(
                        OfficialQqCredentialException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(OfficialQqCredentialException.Reason.FORBIDDEN_LOCATION));
    }

    @Test
    void gitWorktreeDetectionAllowsSiblingRepoNamesAndPlainDirectories() throws Exception {
        Path repo = temp.resolve("synthetic-repo");
        Files.createDirectories(repo.resolve(".git"));
        Path siblingRepo = temp.resolve("synthetic-repo2");
        Files.createDirectories(siblingRepo);
        Path siblingSecret = siblingRepo.resolve("qq-official.secret");
        Files.writeString(siblingSecret, SECRET, StandardCharsets.UTF_8);
        Path plainDir = temp.resolve("synthetic-plain-directory");
        Files.createDirectories(plainDir);
        Path plainSecret = plainDir.resolve("qq-official.secret");
        Files.writeString(plainSecret, SECRET, StandardCharsets.UTF_8);
        OfficialQqCredentialPathPolicy policy =
                OfficialQqCredentialPathPolicy.forGitWorktrees(List.of(repo));

        OfficialQqCredential siblingCredential = new OfficialQqCredentialResolver(
                        "acc", APP_ID, null, siblingSecret, Map.of(), policy)
                .resolve();
        assertThat(secretText(siblingCredential)).isEqualTo(SECRET);
        siblingCredential.close();

        OfficialQqCredential plainCredential = new OfficialQqCredentialResolver(
                        "acc", APP_ID, null, plainSecret, Map.of(), policy)
                .resolve();
        assertThat(secretText(plainCredential)).isEqualTo(SECRET);
        plainCredential.close();
    }

    @Test
    void gitWorktreeDetectionCatchesAWorktreeAddedAfterThePolicyWasBuilt() throws Exception {
        OfficialQqCredentialPathPolicy policy =
                OfficialQqCredentialPathPolicy.forGitWorktrees(List.of());
        Path laterRepo = temp.resolve("synthetic-hot-added-repo");
        Files.createDirectories(laterRepo.resolve(".git"));
        Path secretFile = laterRepo.resolve("qq-official.secret");
        Files.writeString(secretFile, SECRET, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new OfficialQqCredentialResolver(
                        "acc", APP_ID, null, secretFile, Map.of(), policy)
                .resolve())
                .isInstanceOfSatisfying(
                        OfficialQqCredentialException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(OfficialQqCredentialException.Reason.FORBIDDEN_LOCATION));
    }

    @Test
    void copySecretAndCloseAreThreadSafe() throws Exception {
        OfficialQqCredential credential = new OfficialQqCredentialResolver(
                        "acc", APP_ID, "BMA3_TEST_SECRET", null,
                        Map.of("BMA3_TEST_SECRET", SECRET))
                .resolve();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<String> corruptCopy = new AtomicReference<>();
        try {
            Future<?> copier = workers.submit(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < 20_000; i++) {
                    try {
                        char[] copy = credential.copySecret();
                        try {
                            String text = new String(copy);
                            if (!SECRET.equals(text)) {
                                corruptCopy.compareAndSet(null, text);
                            }
                        } finally {
                            java.util.Arrays.fill(copy, '\0');
                        }
                    } catch (IllegalStateException closed) {
                        return;
                    }
                }
            });
            Future<?> closer = workers.submit(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                credential.close();
            });
            start.countDown();
            copier.get(5, TimeUnit.SECONDS);
            closer.get(5, TimeUnit.SECONDS);

            assertThat(corruptCopy.get()).as("copy/close 竞态不能产生部分或清零后的 secret").isNull();
            assertThat(credential.isClosed()).isTrue();
            assertThatThrownBy(credential::copySecret).isInstanceOf(IllegalStateException.class);
        } finally {
            credential.close();
            workers.shutdownNow();
        }
    }

    private static OfficialQqCredentialResolver resolver(String appId, Path file) {
        return new OfficialQqCredentialResolver("acc", appId, null, file, Map.of());
    }

    private static String secretText(OfficialQqCredential credential) {
        char[] secret = credential.copySecret();
        try {
            return new String(secret);
        } finally {
            java.util.Arrays.fill(secret, '\0');
        }
    }
}
