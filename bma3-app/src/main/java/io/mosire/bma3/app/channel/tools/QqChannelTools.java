package io.mosire.bma3.app.channel.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.bma3.app.channel.QqChannelRegistry;
import io.mosire.bma3.app.channel.binding.QqBindingCoordinator;
import java.time.Clock;
import java.util.List;

/**
 * {@link ChannelTools} 的 QQ 领域命名入口；行为与返回顺序完全相同。
 *
 * <p>保留一个明确以 {@code Qq} 开头的工厂，便于装配处按现有 {@code QqTools}/{@code InboxTools}
 * 的命名习惯发现工具；本类不会自行装配进 {@code Bma3App}。</p>
 */
public final class QqChannelTools {

    private QqChannelTools() {
    }

    public static List<AgentTool> all(QqChannelRegistry registry,
                                      ChannelSourceCatalog sourceCatalog) {
        return ChannelTools.all(registry, sourceCatalog);
    }

    public static List<AgentTool> all(QqChannelRegistry registry,
                                      ChannelSourceCatalog sourceCatalog,
                                      Clock clock) {
        return ChannelTools.all(registry, sourceCatalog, clock);
    }

    public static List<AgentTool> all(QqChannelRegistry registry,
                                      ChannelSourceCatalog sourceCatalog,
                                      ChannelMutationGate mutationGate) {
        return ChannelTools.all(registry, sourceCatalog, mutationGate);
    }

    public static List<AgentTool> all(QqChannelRegistry registry,
                                      ChannelSourceCatalog sourceCatalog,
                                      ChannelMutationGate mutationGate,
                                      Clock clock) {
        return ChannelTools.all(registry, sourceCatalog, mutationGate, clock);
    }

    public static ChannelToolAssembly assemble(QqChannelRegistry registry,
                                               ChannelSourceCatalog sourceCatalog,
                                               ChannelMutationGate mutationGate,
                                               Clock clock) {
        return ChannelTools.assemble(registry, sourceCatalog, mutationGate, clock);
    }

    public static ChannelToolAssembly assemble(QqChannelRegistry registry,
                                               ChannelSourceCatalog sourceCatalog,
                                               ChannelMutationGate mutationGate) {
        return ChannelTools.assemble(registry, sourceCatalog, mutationGate);
    }

    public static ChannelToolAssembly assemble(QqChannelRegistry registry,
                                               ChannelSourceCatalog sourceCatalog,
                                               Clock clock) {
        return ChannelTools.assemble(registry, sourceCatalog, clock);
    }

    public static ChannelToolAssembly assembleProduction(
            QqChannelRegistry registry,
            ChannelSourceCatalog sourceCatalog,
            ChannelMutationGate mutationGate,
            QqBindingCoordinator coordinator,
            Clock clock) {
        return ChannelTools.assembleProduction(registry, sourceCatalog, mutationGate,
                coordinator, clock);
    }

    public static ChannelToolAssembly assembleProduction(
            QqChannelRegistry registry,
            ChannelSourceCatalog sourceCatalog,
            ChannelMutationGate mutationGate,
            QqBindingCoordinator coordinator) {
        return ChannelTools.assembleProduction(registry, sourceCatalog, mutationGate,
                coordinator);
    }

    public static ChannelToolAssembly assemble(
            QqChannelRegistry registry,
            ChannelSourceCatalog sourceCatalog,
            ChannelMutationGate mutationGate,
            QqBindingCoordinator coordinator,
            Clock clock) {
        return ChannelTools.assemble(registry, sourceCatalog, mutationGate, coordinator, clock);
    }

    public static ChannelToolAssembly assemble(
            QqChannelRegistry registry,
            ChannelSourceCatalog sourceCatalog,
            ChannelMutationGate mutationGate,
            QqBindingCoordinator coordinator) {
        return ChannelTools.assemble(registry, sourceCatalog, mutationGate, coordinator);
    }
}
