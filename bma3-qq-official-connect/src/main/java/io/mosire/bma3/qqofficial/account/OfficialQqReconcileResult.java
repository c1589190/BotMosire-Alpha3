package io.mosire.bma3.qqofficial.account;

import java.util.List;

/**
 * Per-reconcile diff. Lists contain account ids only and are safe for logs/tool results.
 *
 * <p>{@code failed} is deliberately separate from {@code rejected}: rejected means the desired
 * configuration was invalid (for example duplicate ids), while failed means a valid desired
 * account could not resolve credentials or create/start its runtime. One failed account never
 * aborts reconciliation of the remaining accounts.</p>
 */
public record OfficialQqReconcileResult(
        List<String> added,
        List<String> removed,
        List<String> restarted,
        List<String> disabled,
        List<String> unchanged,
        List<String> rejected,
        List<String> failed) {

    public OfficialQqReconcileResult {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        restarted = List.copyOf(restarted);
        disabled = List.copyOf(disabled);
        unchanged = List.copyOf(unchanged);
        rejected = List.copyOf(rejected);
        failed = List.copyOf(failed);
    }

    public boolean clean() {
        return rejected.isEmpty() && failed.isEmpty();
    }
}
