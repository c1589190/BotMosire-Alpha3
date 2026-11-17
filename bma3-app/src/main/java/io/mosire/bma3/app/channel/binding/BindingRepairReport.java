package io.mosire.bma3.app.channel.binding;

import java.util.List;
import java.util.Objects;

/**
 * Result of a replayable startup repair pass.
 *
 * <p>{@link #readyForIngress()} is the only safe admission check. A report with any blocker, or a
 * pass whose source enumeration could not be completed, must keep external ingress and control
 * tools closed.</p>
 */
public record BindingRepairReport(
        int sourcesExamined,
        int consistent,
        int ledgerRolledBack,
        int journalFinalized,
        int unboundCompleted,
        boolean enumerationComplete,
        List<Blocker> blockers) {

    public BindingRepairReport {
        if (sourcesExamined < 0 || consistent < 0 || ledgerRolledBack < 0
                || journalFinalized < 0 || unboundCompleted < 0) {
            throw new IllegalArgumentException("repair 计数不能为负");
        }
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }

    public boolean readyForIngress() {
        return enumerationComplete && blockers.isEmpty();
    }

    public int blockerCount() {
        return blockers.size();
    }

    /** One fail-closed reason; detail is a stable tuple/error description, never message content. */
    public record Blocker(String rawSource, Code code, String detail) {
        public Blocker {
            rawSource = rawSource == null ? "" : rawSource;
            Objects.requireNonNull(code, "code");
            detail = detail == null ? "" : detail;
        }
    }

    /** Stable blocker codes exposed to operators. */
    public enum Code {
        REGISTRY_ACTIVE_LEDGER_MISSING,
        REGISTRY_ACTIVE_LEDGER_INACTIVE,
        REGISTRY_LEDGER_TUPLE_CONFLICT,
        LEDGER_HISTORY_UNPROVABLE,
        SOURCE_READ_FAILED,
        SOURCE_ENUMERATION_FAILED,
        REPAIR_WRITE_FAILED,
        INVALID_SOURCE
    }
}
