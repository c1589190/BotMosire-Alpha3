package io.mosire.bma3.qqofficial.readtools;

import io.mosire.bma3.qqofficial.account.OfficialQqAccountRegistry;
import io.mosire.bma3.qqofficial.account.OfficialQqAccountStatus;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Read-only account snapshot seam used by the official read tools. */
@FunctionalInterface
public interface OfficialQqAccountView {

    /** Returns all configured/known accounts in registry order. */
    List<OfficialQqAccountStatus> statuses();

    /** Exact account lookup; no aliases or fuzzy matching. */
    default Optional<OfficialQqAccountStatus> status(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Optional.empty();
        }
        for (OfficialQqAccountStatus status : statuses()) {
            if (status != null && accountId.equals(status.accountId())) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }

    static OfficialQqAccountView fromRegistry(OfficialQqAccountRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        return registry::accountStatuses;
    }

    static OfficialQqAccountView empty() {
        return List::of;
    }
}
