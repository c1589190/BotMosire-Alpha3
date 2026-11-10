package io.mosire.bma3.core.priority;

/** Adapter-neutral signals that can raise a source's notification priority. */
public record PrioritySignals(boolean mention, boolean reply, boolean name, boolean command) {

    public static PrioritySignals none() {
        return new PrioritySignals(false, false, false, false);
    }
}
