package io.github.c1589190.bma3.memory;

/**
 * Bounds for the rolling context copy. Every value is configuration: the copy is deliberately
 * small and the Agent may hot-edit these through {@code set_config}.
 *
 * @param maxEntries        rows kept per session (oldest evicted by {@code event_seq})
 * @param maxEntryChars     body ceiling; longer bodies are stored truncated with a flag
 * @param staleAfterSeconds how long a silent watermark stays acceptable (watchdog threshold)
 */
public record ContextOptions(int maxEntries, int maxEntryChars, int staleAfterSeconds) {

    public ContextOptions {
        if (maxEntries < 1 || maxEntries > 100_000) {
            throw new IllegalArgumentException("memory.context.maxEntries 必须在 1..100000");
        }
        if (maxEntryChars < 1 || maxEntryChars > 1_048_576) {
            throw new IllegalArgumentException("memory.context.maxEntryChars 必须在 1..1048576");
        }
        if (staleAfterSeconds < 1 || staleAfterSeconds > 86_400) {
            throw new IllegalArgumentException(
                    "memory.context.staleAfterSeconds 必须在 1..86400");
        }
    }

    public static ContextOptions defaults() {
        return new ContextOptions(200, 10240, 600);
    }
}
