package io.mosire.bma3.qqconnect.account;

import java.util.List;

/** Diff produced by one account reconciliation. */
public record ReconcileResult(
        List<String> added,
        List<String> removed,
        List<String> restarted,
        List<String> rejected) {

    public ReconcileResult {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        restarted = List.copyOf(restarted);
        rejected = List.copyOf(rejected);
    }
}
