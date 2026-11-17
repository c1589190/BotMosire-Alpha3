package io.mosire.bma3.qqofficial.media;

import java.util.Objects;

/**
 * Validated upload response. {@code fileInfo} is an opaque string and must be passed through
 * unchanged. It is an internal transport value and must not be serialized into tool results or logs.
 * The receipt is scoped to the exact account, scene and target used for the upload.
 */
public record OfficialQqMediaUpload(String accountId, Scene scene, String targetOpenid,
                                    String fileInfo, long ttlSeconds) {

    public enum Scene { GROUP, PRIVATE }

    private static final int MAX_FILE_INFO_CHARS = 1_048_576;

    public OfficialQqMediaUpload {
        if (accountId == null || accountId.isBlank()
                || targetOpenid == null || targetOpenid.isBlank()) {
            throw new IllegalArgumentException("upload 账号或目标无效");
        }
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(fileInfo, "fileInfo");
        if (fileInfo.isBlank() || fileInfo.length() > MAX_FILE_INFO_CHARS) {
            throw new IllegalArgumentException("file_info 无效");
        }
        for (int offset = 0; offset < fileInfo.length(); ) {
            int codePoint = fileInfo.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException("file_info 含非法字符");
            }
            offset += Character.charCount(codePoint);
        }
        if (ttlSeconds < 0) {
            throw new IllegalArgumentException("ttl 不能为负");
        }
    }

    @Override
    public String toString() {
        return "OfficialQqMediaUpload[ttlSeconds=" + ttlSeconds + "]";
    }
}
