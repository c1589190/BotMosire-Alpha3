package io.mosire.bma3.qqofficial.account;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * One desired official QQ bot account.
 *
 * <p>This type contains credential <em>references</em> only. The AppSecret value is resolved by
 * the registry from either the named environment variable or the owner-only file and is never
 * stored in this record. Account ids become raw-source segments, so they must be one safe source
 * segment; configs with duplicate ids are rejected by {@link OfficialQqAccountRegistry}.</p>
 *
 * <p>The first release supports the configured shard {@code [0, 1]} only. For clarity this record
 * stores that pair as {@code shardIndex=0, shardCount=1}.</p>
 */
public record OfficialQqAccountConfig(
        String accountId,
        String appId,
        String appSecretEnv,
        Path appSecretFile,
        boolean enabled,
        int shardIndex,
        int shardCount,
        OfficialQqAccountRuntimeOptions runtimeOptions) {

    public static final int SUPPORTED_SHARD_INDEX = 0;
    public static final int SUPPORTED_SHARD_COUNT = 1;
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public OfficialQqAccountConfig {
        accountId = requireSafeAccountId(accountId);
        appId = normalize(appId);
        appSecretEnv = normalize(appSecretEnv);
        appSecretFile = normalizeFile(appSecretFile);
        if (enabled && appId.isEmpty()) {
            throw new IllegalArgumentException("enabled=true 时 appId 不能为空");
        }
        boolean envRef = !appSecretEnv.isEmpty();
        boolean fileRef = appSecretFile != null;
        if (envRef && fileRef) {
            throw new IllegalArgumentException("appSecretEnv 与 appSecretFile 只能配置一个");
        }
        if (enabled && !envRef && !fileRef) {
            throw new IllegalArgumentException(
                    "enabled=true 时必须配置 appSecretEnv 或 appSecretFile");
        }
        if (envRef && !ENV_NAME.matcher(appSecretEnv).matches()) {
            throw new IllegalArgumentException("appSecretEnv 必须是合法环境变量名");
        }
        if (shardIndex != SUPPORTED_SHARD_INDEX || shardCount != SUPPORTED_SHARD_COUNT) {
            throw new IllegalArgumentException("首版只支持 shard [0, 1]");
        }
        runtimeOptions = runtimeOptions == null
                ? OfficialQqAccountRuntimeOptions.defaults() : runtimeOptions;
    }

    /** Backward-compatible constructor using credential-free runtime defaults. */
    public OfficialQqAccountConfig(
            String accountId,
            String appId,
            String appSecretEnv,
            Path appSecretFile,
            boolean enabled,
            int shardIndex,
            int shardCount) {
        this(accountId, appId, appSecretEnv, appSecretFile, enabled, shardIndex, shardCount,
                OfficialQqAccountRuntimeOptions.defaults());
    }

    /** Convenience constructor for callers that carry the file reference as config text. */
    public OfficialQqAccountConfig(
            String accountId,
            String appId,
            String appSecretEnv,
            String appSecretFile,
            boolean enabled,
            int shardIndex,
            int shardCount) {
        this(accountId, appId, appSecretEnv, fileOrNull(appSecretFile), enabled,
                shardIndex, shardCount, OfficialQqAccountRuntimeOptions.defaults());
    }

    /** Convenience constructor using a string file reference and explicit runtime options. */
    public OfficialQqAccountConfig(
            String accountId,
            String appId,
            String appSecretEnv,
            String appSecretFile,
            boolean enabled,
            int shardIndex,
            int shardCount,
            OfficialQqAccountRuntimeOptions runtimeOptions) {
        this(accountId, appId, appSecretEnv, fileOrNull(appSecretFile), enabled,
                shardIndex, shardCount, runtimeOptions);
    }

    public OfficialQqAccountConfig(
            String accountId,
            String appId,
            String appSecretEnv,
            Path appSecretFile,
            boolean enabled) {
        this(accountId, appId, appSecretEnv, appSecretFile, enabled,
                SUPPORTED_SHARD_INDEX, SUPPORTED_SHARD_COUNT,
                OfficialQqAccountRuntimeOptions.defaults());
    }

    public OfficialQqAccountConfig(
            String accountId,
            String appId,
            String appSecretEnv,
            Path appSecretFile,
            boolean enabled,
            OfficialQqAccountRuntimeOptions runtimeOptions) {
        this(accountId, appId, appSecretEnv, appSecretFile, enabled,
                SUPPORTED_SHARD_INDEX, SUPPORTED_SHARD_COUNT, runtimeOptions);
    }

    public boolean hasSecretReference() {
        return !appSecretEnv.isEmpty() || appSecretFile != null;
    }

    /** Human-readable source kind for diagnostics; never contains a secret value. */
    public String secretReferenceKind() {
        if (!appSecretEnv.isEmpty()) {
            return "ENV";
        }
        return appSecretFile == null ? "NONE" : "FILE";
    }

    private static String requireSafeAccountId(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || !value.equals(value.strip())) {
            throw new IllegalArgumentException("accountId 必须是 1..128 字符的安全单段");
        }
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            int type = Character.getType(codePoint);
            if (codePoint == '/'
                    || codePoint == '\\'
                    || Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)
                    || Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException("accountId 必须是安全单段");
            }
            index += Character.charCount(codePoint);
        }
        return value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static Path normalizeFile(Path file) {
        return file == null ? null : file.toAbsolutePath().normalize();
    }

    private static Path fileOrNull(String file) {
        String value = normalize(file);
        return value.isEmpty() ? null : Path.of(value);
    }

    @Override
    public String toString() {
        return "OfficialQqAccountConfig[accountId=" + accountId
                + ", appId=" + appId
                + ", enabled=" + enabled
                + ", secretSource=" + secretReferenceKind()
                + ", shardIndex=" + shardIndex
                + ", shardCount=" + shardCount
                + ", runtimeOptions=" + runtimeOptions + "]";
    }
}
