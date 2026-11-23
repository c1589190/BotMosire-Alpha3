package io.github.c1589190.bma3.memory.tools;

import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.bma3.memory.ContextDigestStore;
import io.github.c1589190.bma3.memory.ContextOptions;
import io.github.c1589190.bma3.memory.MemoryOptions;
import io.github.c1589190.bma3.memory.MemoryStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

/**
 * The model-facing memory tools. Nothing here is injected into a prompt automatically: the Agent
 * calls them or the memory does not exist (D-078 ①).
 */
public final class MemoryTools {

    private MemoryTools() {
    }

    /**
     * Notebook tools only.
     *
     * @param store          notebook store
     * @param options        live options; read per call so {@code set_config} applies immediately
     * @param boardSeqExists tells whether a message-board {@code local_seq} exists
     */
    public static List<AgentTool> all(MemoryStore store, Supplier<MemoryOptions> options,
            LongPredicate boardSeqExists) {
        return all(store, options, boardSeqExists, null);
    }

    /**
     * Notebook tools with file imports enabled.
     *
     * @param importDir directory {@code memory_write(file=…)} may read from
     */
    public static List<AgentTool> all(MemoryStore store, Supplier<MemoryOptions> options,
            LongPredicate boardSeqExists, Supplier<Path> importDir) {
        return List.of(
                new MemoryWriteTool(store, options, boardSeqExists, importDir),
                new MemorySearchTool(store, options),
                new MemoryViewTool(store),
                new MemoryRateTool(store));
    }

    /**
     * Notebook plus the context copy.
     *
     * @param digest         rolling copy of the DSH session context
     * @param contextOptions live bounds of that copy
     * @param boundSessionId session the panel is bound to; {@code memory_context} never takes one
     *                       from the model
     * @param exportDir      directory the jar writes {@code export=txt} files into
     * @param importDir      directory {@code memory_write(file=…)} may read from
     */
    public static List<AgentTool> all(MemoryStore store, Supplier<MemoryOptions> options,
            LongPredicate boardSeqExists, ContextDigestStore digest,
            Supplier<ContextOptions> contextOptions, Supplier<String> boundSessionId,
            Path exportDir, Supplier<Path> importDir) {
        List<AgentTool> tools = new ArrayList<>(all(store, options, boardSeqExists, importDir));
        tools.set(2, new MemoryViewTool(store, digest::exists));
        tools.add(new MemoryContextTool(digest, contextOptions, options, boundSessionId,
                exportDir));
        return List.copyOf(tools);
    }
}
