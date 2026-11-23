package io.github.c1589190.bma3.qqofficial.tools;

import io.github.c1589190.bma3.core.source.SourceAddress;
import io.github.c1589190.bma3.core.source.SourceLabeler;
import io.github.c1589190.bma3.qqofficial.directory.OfficialQqDirectoryStore;
import java.util.function.Supplier;

/**
 * Human-readable labels for official QQ sources.
 *
 * <p>The directory is always queried by {@code (accountId, openid)}: aliases and names from one
 * official bot account can never leak into another account's label. Operator aliases/names win;
 * otherwise the openid is shortened rather than rendered in full. Openids are opaque strings, not
 * QQ numbers.</p>
 */
public final class QqOfficialSourceLabeler implements SourceLabeler {

    private static final int MAX_LABEL_NAME_CODE_POINTS = 120;
    private static final int SHORT_ID_CODE_POINTS = 8;

    private final Supplier<OfficialQqDirectoryStore> directory;

    public QqOfficialSourceLabeler() {
        this(null);
    }

    public QqOfficialSourceLabeler(Supplier<OfficialQqDirectoryStore> directory) {
        this.directory = directory;
    }

    @Override
    public boolean supports(String platform) {
        return QqOfficialSourceKeyer.supportsPlatform(platform);
    }

    @Override
    public String label(SourceAddress source) {
        String id = source.lastSegment();
        try {
            QqOfficialSourceKeyer.validateOfficialSource(source);
        } catch (RuntimeException invalid) {
            // SourceLabeler must not break a whole panel. Keep even malformed official IDs short
            // and avoid consulting a directory with an invalid key.
            return safeFallback(source.platform(), id);
        }
        return switch (source.platform()) {
            case QqOfficialSourceKeyer.GROUP_PLATFORM -> groupLabel(source, id);
            case QqOfficialSourceKeyer.PRIVATE_PLATFORM -> privateLabel(source, id);
            case QqOfficialSourceKeyer.SYSTEM_PLATFORM ->
                    "QQ官方系统 " + shortId(safeText(id));
            default -> safeFallback(source.platform(), id);
        };
    }

    private String groupLabel(SourceAddress source, String id) {
        String name = groupName(source.accountId(), id);
        return "QQ群 " + (name.isBlank() ? shortId(safeText(id)) : name);
    }

    private String privateLabel(SourceAddress source, String id) {
        String name = userName(source.accountId(), id);
        return "QQ私聊 " + (name.isBlank() ? shortId(safeText(id)) : name);
    }

    private String groupName(String accountId, String groupOpenid) {
        if (directory == null) {
            return "";
        }
        try {
            OfficialQqDirectoryStore store = directory.get();
            if (store == null) {
                return "";
            }
            return store.group(accountId, groupOpenid)
                    .map(group -> firstNonBlank(group.alias(), group.name()))
                    .map(QqOfficialSourceLabeler::safeText)
                    .orElse("");
        } catch (RuntimeException unavailable) {
            return "";
        }
    }

    private String userName(String accountId, String userOpenid) {
        if (directory == null) {
            return "";
        }
        try {
            OfficialQqDirectoryStore store = directory.get();
            if (store == null) {
                return "";
            }
            return store.user(accountId, userOpenid)
                    .map(OfficialQqDirectoryStore.User::name)
                    .map(QqOfficialSourceLabeler::safeText)
                    .orElse("");
        } catch (RuntimeException unavailable) {
            return "";
        }
    }

    private static String firstNonBlank(String first, String second) {
        String left = safeText(first);
        return left.isBlank() ? safeText(second) : left;
    }

    private static String safeFallback(String platform, String id) {
        String shortId = shortId(safeText(id));
        return switch (platform) {
            case QqOfficialSourceKeyer.GROUP_PLATFORM -> "QQ群 " + shortId;
            case QqOfficialSourceKeyer.PRIVATE_PLATFORM -> "QQ私聊 " + shortId;
            case QqOfficialSourceKeyer.SYSTEM_PLATFORM -> "QQ官方系统 " + shortId;
            default -> platform + " · " + shortId;
        };
    }

    /** Removes line/control/format characters and caps display names to a safe length. */
    private static String safeText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int codePoints = 0;
        for (int index = 0; index < value.length() && codePoints < MAX_LABEL_NAME_CODE_POINTS; ) {
            int codePoint = value.codePointAt(index);
            int type = Character.getType(codePoint);
            if (!Character.isISOControl(codePoint)
                    && type != Character.FORMAT
                    && type != Character.LINE_SEPARATOR
                    && type != Character.PARAGRAPH_SEPARATOR
                    && type != Character.SURROGATE) {
                out.appendCodePoint(codePoint);
                codePoints++;
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
                out.append(' ');
            }
            index += Character.charCount(codePoint);
        }
        return out.toString().strip();
    }

    /** Shortens by code point so a surrogate pair is never split. */
    private static String shortId(String id) {
        if (id.codePointCount(0, id.length()) <= SHORT_ID_CODE_POINTS) {
            return id;
        }
        int end = id.offsetByCodePoints(0, SHORT_ID_CODE_POINTS);
        return id.substring(0, end) + "…";
    }
}
