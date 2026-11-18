package io.mosire.bma3.memory.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.bma3.memory.MemoryOptions;
import io.mosire.bma3.memory.MemoryStore;
import java.util.List;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

/** The four model-facing memory tools. Nothing here is injected into a prompt automatically. */
public final class MemoryTools {

    private MemoryTools() {
    }

    /**
     * @param store           notebook store
     * @param options         live options; read per call so {@code set_config} applies immediately
     * @param boardSeqExists  tells whether a message-board {@code local_seq} exists
     */
    public static List<AgentTool> all(MemoryStore store, Supplier<MemoryOptions> options,
            LongPredicate boardSeqExists) {
        return List.of(
                new MemoryWriteTool(store, options, boardSeqExists),
                new MemorySearchTool(store, options),
                new MemoryViewTool(store),
                new MemoryRateTool(store));
    }
}
