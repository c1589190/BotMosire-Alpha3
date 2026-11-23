package io.github.c1589190.bma3.qqofficial.credential;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.bma3.core.security.PrivateFiles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolves exactly one AppSecret source: a named environment variable or an owner-only file.
 *
 * <p>Both sources configured at the same time is a configuration error rather than a precedence
 * choice. A file is passed through {@link PrivateFiles#restrict(Path)} before it is read, so an
 * insecure file can never silently become an accepted credential source.</p>
 *
 * <p>An {@code appSecretFile} may contain either a plain-text AppSecret or the Phase-7 onboarding
 * JSON object {@code {"appId":"...","appSecret":"..."}}. JSON mode is considered only when the
 * trimmed file begins with an opening brace and contains an {@code appId}/{@code appSecret} JSON
 * key followed by a colon; unrelated text or JSON-looking text is still accepted verbatim as a
 * plain secret. In JSON mode
 * the document, field set and field types are strict, {@code appId} must exactly equal the
 * configured AppID and {@code appSecret} must be non-blank. Parse failures never include file
 * contents in their exception or cause.</p>
 *
 * <p>An optional, explicitly injected {@link OfficialQqCredentialPathPolicy} rejects credential
 * files whose real path falls inside known repository roots; the generic constructor never guesses
 * a root from the current directory. POSIX shared-writable parent directories are rejected. The
 * owner-only file check and a NOFOLLOW, bounded channel read with before/after file identity checks
 * narrow TOCTOU exposure, but filesystems without a stable fileKey cannot prove its complete
 * elimination.</p>
 */
public final class OfficialQqCredentialResolver {

    private static final long MAX_SECRET_FILE_BYTES = 16L * 1024L;
    private static final ObjectMapper ONBOARDING_JSON = new ObjectMapper(
            JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());
    private static final Pattern ONBOARDING_FIELD =
            Pattern.compile("\"(?:appId|appSecret)\"\\s*:");

    private final String accountId;
    private final String appId;
    private final String appSecretEnv;
    private final Path appSecretFile;
    private final Map<String, String> environment;
    private final OfficialQqCredentialPathPolicy pathPolicy;

    public OfficialQqCredentialResolver(
            String accountId,
            String appId,
            String appSecretEnv,
            Path appSecretFile,
            Map<String, String> environment) {
        this(
                accountId,
                appId,
                appSecretEnv,
                appSecretFile,
                environment,
                OfficialQqCredentialPathPolicy.none());
    }

    /**
     * Production composition should pass the actual repository root(s) discovered by the app layer.
     * The generic constructors above intentionally do not guess the current working directory.
     */
    public OfficialQqCredentialResolver(
            String accountId,
            String appId,
            String appSecretEnv,
            Path appSecretFile,
            Map<String, String> environment,
            OfficialQqCredentialPathPolicy pathPolicy) {
        this.accountId = normalize(accountId);
        this.appId = normalize(appId);
        this.appSecretEnv = normalize(appSecretEnv);
        this.appSecretFile = appSecretFile;
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.pathPolicy = Objects.requireNonNull(pathPolicy, "pathPolicy");
    }

    public OfficialQqCredentialResolver(
            String accountId, String appId, String appSecretEnv, Path appSecretFile) {
        this(accountId, appId, appSecretEnv, appSecretFile, System.getenv());
    }

    /** Convenience constructor for an explicitly injected path policy. */
    public OfficialQqCredentialResolver(
            String accountId,
            String appId,
            String appSecretEnv,
            Path appSecretFile,
            OfficialQqCredentialPathPolicy pathPolicy) {
        this(accountId, appId, appSecretEnv, appSecretFile, System.getenv(), pathPolicy);
    }

    public OfficialQqCredential resolve() throws OfficialQqCredentialException {
        if (appId.isEmpty()) {
            throw failure(
                    OfficialQqCredentialException.Reason.CONFIGURATION,
                    "CONFIG",
                    "appId 为空",
                    null);
        }
        boolean hasEnv = !appSecretEnv.isEmpty();
        boolean hasFile = appSecretFile != null;
        if (hasEnv && hasFile) {
            throw failure(
                    OfficialQqCredentialException.Reason.CONFIGURATION,
                    "BOTH",
                    "appSecretEnv 与 appSecretFile 只能配置一个",
                    null);
        }
        if (!hasEnv && !hasFile) {
            throw failure(
                    OfficialQqCredentialException.Reason.MISSING,
                    "NONE",
                    "未配置 appSecretEnv/appSecretFile",
                    null);
        }
        return hasEnv ? fromEnvironment() : fromFile();
    }

    private OfficialQqCredential fromEnvironment() throws OfficialQqCredentialException {
        String secret = environment.get(appSecretEnv);
        if (secret == null || secret.isBlank()) {
            throw failure(
                    OfficialQqCredentialException.Reason.MISSING,
                    "ENV",
                    "环境变量未设置或为空: " + appSecretEnv,
                    null);
        }
        return credential(secret);
    }

    private OfficialQqCredential fromFile() throws OfficialQqCredentialException {
        Path file = appSecretFile.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(file)) {
            throw failure(
                    OfficialQqCredentialException.Reason.PERMISSION,
                    "FILE",
                    "凭据文件不能是符号链接",
                    null);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(
                    OfficialQqCredentialException.Reason.MISSING,
                    "FILE",
                    "凭据文件不存在或不是普通文件",
                    null);
        }
        try {
            if (pathPolicy.isForbidden(file)) {
                throw failure(
                        OfficialQqCredentialException.Reason.FORBIDDEN_LOCATION,
                        "FILE",
                        "凭据文件不得位于仓库目录内",
                        null);
            }
            String fileText = readSecretFile(file).strip();
            if (fileText.isEmpty()) {
                throw failure(
                        OfficialQqCredentialException.Reason.MISSING,
                        "FILE",
                        "凭据文件为空",
                        null);
            }
            return credential(secretFromFile(fileText));
        } catch (OfficialQqCredentialException failure) {
            throw failure;
        } catch (IOException failure) {
            throw failure(
                    OfficialQqCredentialException.Reason.PERMISSION,
                    "FILE",
                    "无法收紧或读取 owner-only 凭据文件",
                    null);
        } catch (RuntimeException failure) {
            throw failure(
                    OfficialQqCredentialException.Reason.IO,
                    "FILE",
                    "读取凭据文件失败",
                    failure);
        }
    }

    /**
     * Read from one NOFOLLOW file channel after tightening permissions, with a bounded buffer and
     * before/after basic-attribute checks. POSIX shared-writable parent directories are rejected.
     * On filesystems without a usable fileKey/size identity this narrows but cannot fully prove
     * the absence of a concurrent replacement; owner-only permissions remain the primary control.
     */
    private String readSecretFile(Path file) throws IOException, OfficialQqCredentialException {
        BasicFileAttributes before = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        requirePrivateParent(file);
        // The permission tightening must succeed before any secret byte is read.
        PrivateFiles.restrict(file);
        BasicFileAttributes afterRestrict = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!sameSnapshot(before, afterRestrict)) {
            throw failure(
                    OfficialQqCredentialException.Reason.PERMISSION,
                    "FILE",
                    "凭据文件在权限校验期间发生变化",
                    null);
        }
        byte[] bytes;
        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() > MAX_SECRET_FILE_BYTES) {
                throw failure(
                        OfficialQqCredentialException.Reason.CONFIGURATION,
                        "FILE",
                        "凭据文件超过 16 KiB 上限",
                        null);
            }
            bytes = readBounded(channel, MAX_SECRET_FILE_BYTES);
        }
        BasicFileAttributes afterRead = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!sameSnapshot(before, afterRead)
                || afterRead.size() != bytes.length) {
            throw failure(
                    OfficialQqCredentialException.Reason.PERMISSION,
                    "FILE",
                    "凭据文件在读取期间发生变化",
                    null);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void requirePrivateParent(Path file) throws IOException, OfficialQqCredentialException {
        Path parent = file.getParent();
        if (parent == null) {
            throw failure(
                    OfficialQqCredentialException.Reason.PERMISSION,
                    "FILE",
                    "凭据文件缺少父目录",
                    null);
        }
        Path realParent = parent.toRealPath();
        PosixFileAttributeView posix = Files.getFileAttributeView(
                realParent, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix == null) {
            // Windows DACL handling is delegated to PrivateFiles for the file itself. There is no
            // portable Java parent-DACL verifier, so do not invent a rejection that could break
            // legitimate current-user profile directories.
            return;
        }
        Set<PosixFilePermission> permissions = posix.readAttributes().permissions();
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw failure(
                    OfficialQqCredentialException.Reason.PERMISSION,
                    "FILE",
                    "凭据文件父目录可被共享写入",
                    null);
        }
    }

    private static byte[] readBounded(FileChannel channel, long maxBytes) throws IOException {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream((int) Math.min(maxBytes, 16 * 1024));
        ByteBuffer buffer = ByteBuffer.allocate(8 * 1024);
        long total = 0;
        while (true) {
            buffer.clear();
            int read = channel.read(buffer);
            if (read < 0) {
                return output.toByteArray();
            }
            if (read == 0) {
                continue;
            }
            total += read;
            if (total > maxBytes) {
                throw new IOException("凭据文件超过限制");
            }
            output.write(buffer.array(), 0, read);
        }
    }

    private static boolean sameSnapshot(BasicFileAttributes left, BasicFileAttributes right) {
        boolean sameKey = left.fileKey() == null
                || right.fileKey() == null
                || left.fileKey().equals(right.fileKey());
        return sameKey
                && left.size() == right.size()
                && left.lastModifiedTime().equals(right.lastModifiedTime());
    }

    /**
     * Supports both the legacy plain-text secret file and the owner-only onboarding JSON written
     * by tools/qq-official-onboarding. Detection is deliberately narrow so a secret containing
     * braces (or even an unrelated JSON object) is not silently reinterpreted as a credential
     * configuration object.
     */
    private String secretFromFile(String fileText) throws OfficialQqCredentialException {
        if (!looksLikeOnboardingJson(fileText)) {
            return fileText;
        }
        JsonNode root;
        try (JsonParser parser = ONBOARDING_JSON.createParser(fileText)) {
            root = ONBOARDING_JSON.readTree(parser);
            if (root == null || parser.nextToken() != null) {
                throw invalidOnboardingJson("onboarding JSON 必须是单个 JSON 对象");
            }
        } catch (IOException | RuntimeException malformed) {
            // Do not attach the parser exception: Jackson can include raw file excerpts in its
            // message. The fixed diagnostic below is the only thing callers may see.
            throw invalidOnboardingJson("onboarding JSON 解析失败");
        }

        if (!root.isObject() || root.size() != 2
                || !root.has("appId") || !root.has("appSecret")) {
            throw invalidOnboardingJson(
                    "onboarding JSON 必须且只能包含 appId/appSecret 两个字段");
        }
        JsonNode appIdNode = root.get("appId");
        JsonNode secretNode = root.get("appSecret");
        if (!appIdNode.isTextual() || !secretNode.isTextual()) {
            throw invalidOnboardingJson("onboarding JSON appId/appSecret 必须是字符串");
        }
        String fileAppId = appIdNode.textValue();
        if (!appId.equals(fileAppId)) {
            throw invalidOnboardingJson("onboarding JSON appId 与配置不一致");
        }
        String secret = secretNode.textValue();
        if (secret == null || secret.isBlank()) {
            throw invalidOnboardingJson("onboarding JSON appSecret 为空");
        }
        return secret;
    }

    private static boolean looksLikeOnboardingJson(String fileText) {
        if (!fileText.startsWith("{")) {
            return false;
        }
        // A top-level-looking key and colon, not merely a leading brace or a quoted value, selects
        // JSON mode. This preserves ordinary secrets such as "{literal-secret}" and unrelated
        // JSON-shaped secrets as plain text.
        return ONBOARDING_FIELD.matcher(fileText).find();
    }

    private OfficialQqCredentialException invalidOnboardingJson(String detail) {
        return failure(
                OfficialQqCredentialException.Reason.CONFIGURATION,
                "FILE",
                detail,
                null);
    }

    private OfficialQqCredential credential(String secret) {
        char[] chars = secret.toCharArray();
        try {
            return new OfficialQqCredential(appId, chars);
        } finally {
            java.util.Arrays.fill(chars, '\0');
        }
    }

    private OfficialQqCredentialException failure(
            OfficialQqCredentialException.Reason reason,
            String source,
            String detail,
            Throwable cause) {
        return new OfficialQqCredentialException(reason, accountId, source, detail, cause);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
