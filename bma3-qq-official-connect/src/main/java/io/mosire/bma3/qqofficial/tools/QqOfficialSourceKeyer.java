package io.mosire.bma3.qqofficial.tools;

import io.mosire.bma3.core.priority.SourceKeyer;
import io.mosire.bma3.core.source.SourceAddress;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Projects official QQ raw sources onto the two-level priority configuration key.
 *
 * <p>Official sources are {@code qq_official_group/<accountId>/<group_openid>},
 * {@code qq_official_private/<accountId>/<user_openid>} and
 * {@code qq_official_system/<accountId>/<event>}. The configuration key drops only the bot
 * account: {@code qq_official_group/<group_openid>}. Openids remain opaque strings and are never
 * parsed as QQ numbers.</p>
 *
 * <p>Official source shapes are validated strictly. A malformed account/openid segment must not
 * be allowed to collide with another channel or with a more-specific configuration rule.</p>
 */
public final class QqOfficialSourceKeyer implements SourceKeyer {

    public static final String GROUP_PLATFORM = "qq_official_group";
    public static final String PRIVATE_PLATFORM = "qq_official_private";
    public static final String SYSTEM_PLATFORM = "qq_official_system";

    private static final Set<String> PLATFORMS =
            Set.of(GROUP_PLATFORM, PRIVATE_PLATFORM, SYSTEM_PLATFORM);
    private static final int MAX_SEGMENT_CHARS = 512;

    /** Whether this adapter owns the platform namespace. */
    public static boolean supportsPlatform(String platform) {
        return platform != null && PLATFORMS.contains(platform);
    }

    @Override
    public String keyFor(SourceAddress source) {
        Objects.requireNonNull(source, "source");
        if (!supportsPlatform(source.platform())) {
            // Keep the same generic fallback as QqSourceKeyer so this adapter can coexist with
            // non-official platforms in a single keyer without turning them into official keys.
            String target = source.lastSegment();
            return target.isBlank() ? source.platform() : source.platform() + "/" + target;
        }
        validateOfficialSource(source);
        return source.platform() + "/" + source.lastSegment();
    }

    /**
     * Rejects malformed official sources without echoing the untrusted value.
     *
     * <p>Package-visible so the labeler applies the exact same shape rules before consulting the
     * directory.</p>
     */
    public static void validateOfficialSource(SourceAddress source) {
        Objects.requireNonNull(source, "source");
        String platform = source.platform();
        if (!supportsPlatform(platform)) {
            throw new IllegalArgumentException("不是 QQ 官方 source platform");
        }
        List<String> route = source.segments();
        if (route.size() != 2) {
            throw new IllegalArgumentException(
                    platform + " source 必须严格为 platform/accountId/target");
        }
        requireSafeSegment(source.accountId(), "accountId");
        requireSafeSegment(source.lastSegment(), "target");
    }

    private static void requireSafeSegment(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_SEGMENT_CHARS
                || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " 必须是安全单段");
        }
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            int type = Character.getType(codePoint);
            if (codePoint == '/' || codePoint == '\\'
                    || Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)
                    || Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException(field + " 必须是安全单段");
            }
            index += Character.charCount(codePoint);
        }
    }
}
