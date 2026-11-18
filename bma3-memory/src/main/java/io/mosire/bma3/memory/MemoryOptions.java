package io.mosire.bma3.memory;

/**
 * Tuning for the memory notebook. Every value is configuration, never a constant in the store or
 * the tools: the defaults here are the single place a number may appear, mirroring
 * {@code AppConfig.Memory}.
 *
 * @param searchLimit   rows per page, 1..50
 * @param tagBoost      RRF weight added to a tag hit, must be &gt; 0
 * @param usefulDelta   weight increment for "useful"
 * @param uselessDelta  weight increment for "useless" (normally negative)
 * @param floor         lower bound for any non-muted row; must be &gt; {@link #MUTED} so that
 *                      {@code -1} stays reachable only through an explicit mute
 * @param maxTextChars  maximum characters accepted by one write
 * @param maxImportBytes maximum size of one imported file; the file is split into
 *                      {@code maxTextChars} pieces, so nothing is lost to the cap
 */
public record MemoryOptions(int searchLimit, double tagBoost, double usefulDelta,
        double uselessDelta, double floor, int maxTextChars, int maxImportBytes) {

    /** Weight sentinel: the only way to retire an item. Rows never leave the table. */
    public static final double MUTED = -1.0;

    public MemoryOptions {
        if (searchLimit < 1 || searchLimit > 50) {
            throw new IllegalArgumentException("memory.search.limit 必须在 1..50");
        }
        if (!(tagBoost > 0)) {
            throw new IllegalArgumentException("memory.search.tagBoost 必须大于 0");
        }
        if (!(floor > MUTED) || floor >= 1_000_000) {
            throw new IllegalArgumentException("memory.rate.floor 必须大于 -1 且小于 1000000");
        }
        if (maxTextChars < 1 || maxTextChars > 1_048_576) {
            throw new IllegalArgumentException("memory.write.maxTextChars 必须在 1..1048576");
        }
        if (maxImportBytes < 1 || maxImportBytes > 67_108_864) {
            throw new IllegalArgumentException("memory.import.maxBytes 必须在 1..67108864");
        }
    }

    public static MemoryOptions defaults() {
        return new MemoryOptions(20, 10.0, 1.0, -1.0, -0.9, 8000, 1_048_576);
    }
}
