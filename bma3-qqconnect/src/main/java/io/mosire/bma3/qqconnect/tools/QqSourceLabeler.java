package io.mosire.bma3.qqconnect.tools;

import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.source.SourceLabeler;
import io.mosire.bma3.qqconnect.directory.QqDirectoryStore;
import java.util.Set;
import java.util.function.Supplier;

/** Human-readable labels for the QQ source platforms; the Inbox never sees this switch. */
public final class QqSourceLabeler implements SourceLabeler {

    private static final Set<String> PLATFORMS = Set.of("qq_group", "qq_private", "qq_system");

    private final Supplier<QqDirectoryStore> directory;

    public QqSourceLabeler() {
        this(null);
    }

    public QqSourceLabeler(Supplier<QqDirectoryStore> directory) {
        this.directory = directory;
    }

    @Override
    public boolean supports(String platform) {
        return PLATFORMS.contains(platform);
    }

    @Override
    public String label(SourceAddress source) {
        String id = source.lastSegment();
        return switch (source.platform()) {
            case "qq_group" -> {
                String name = groupName(source, id);
                yield name.equals(id) ? "QQ群 " + id : "QQ群 " + name + " (" + id + ")";
            }
            case "qq_private" -> "QQ私聊 " + id;
            case "qq_system" -> "QQ系统 " + id;
            default -> source.displayFallback();
        };
    }

    /** Group name from the local directory; falls back to the group number alone. */
    private String groupName(SourceAddress source, String id) {
        if (directory == null) {
            return id;
        }
        try {
            QqDirectoryStore store = directory.get();
            if (store == null) {
                return id;
            }
            return store.group(source.accountId(), id)
                    .map(QqDirectoryStore.Group::name)
                    .filter(name -> !name.isBlank())
                    .orElse(id);
        } catch (RuntimeException failure) {
            return id;
        }
    }
}
