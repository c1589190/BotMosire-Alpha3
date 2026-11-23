package io.github.c1589190.bma3.app.channel.tools;

import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry;
import io.github.c1589190.bma3.app.channel.binding.QqBindingCoordinator;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * QQ 双渠道控制面工具工厂。
 *
 * <p>生产入口是 {@link #assembleProduction}：必须显式注入 {@link QqBindingCoordinator}，且该
 * coordinator、本工厂和 ingress 必须使用同一个 {@link ChannelMutationGate} 实例。
 * 无 coordinator 的 {@code assemble/all} 入口只为旧独立测试保留，返回
 * {@code productionReady=false}，不得用于生产装配。</p>
 *
 * <p>所有写操作仍通过共享 gate；bind/unbind 委托 {@link BindingOperations}，生产适配器直接调用
 * coordinator，不静默退回 {@code registry.bind/unbind}。registry 继续负责持久化/审计，ledger
 * 的 generation/startBoardSeq 由 coordinator 维护。</p>
 */
public final class ChannelTools {

    private ChannelTools() {
    }

    /**
     * 生产入口：必须注入共享 coordinator。
     *
     * <p>{@code coordinator} 必须与 {@code mutationGate} 使用同一个 gate 实例；工具写路径与
     * coordinator 的 {@code underLock} 依赖该 gate 可重入。本方法不装配 {@code Bma3App}。</p>
     */
    public static ChannelToolAssembly assembleProduction(
            QqChannelRegistry registry,
            ChannelSourceCatalog sourceCatalog,
            ChannelMutationGate mutationGate,
            QqBindingCoordinator coordinator,
            Clock clock) {
        Objects.requireNonNull(coordinator, "coordinator");
        return build(registry, sourceCatalog, mutationGate,
                BindingOperations.from(coordinator), clock, true);
    }

    /** 生产入口；使用系统 UTC 时钟。 */
    public static ChannelToolAssembly assembleProduction(
            QqChannelRegistry registry,
            ChannelSourceCatalog sourceCatalog,
            ChannelMutationGate mutationGate,
            QqBindingCoordinator coordinator) {
        return assembleProduction(registry, sourceCatalog, mutationGate, coordinator,
                Clock.systemUTC());
    }

    /**
     * 带 coordinator 的显式 assemble 重载；等价于生产入口。
     */
    public static ChannelToolAssembly assemble(
            QqChannelRegistry registry,
            ChannelSourceCatalog sourceCatalog,
            ChannelMutationGate mutationGate,
            QqBindingCoordinator coordinator,
            Clock clock) {
        return assembleProduction(registry, sourceCatalog, mutationGate, coordinator, clock);
    }

    /** 带 coordinator 的显式 assemble 重载；使用系统 UTC 时钟。 */
    public static ChannelToolAssembly assemble(
            QqChannelRegistry registry,
            ChannelSourceCatalog sourceCatalog,
            ChannelMutationGate mutationGate,
            QqBindingCoordinator coordinator) {
        return assembleProduction(registry, sourceCatalog, mutationGate, coordinator);
    }

    /**
     * 旧独立测试入口：不要求 coordinator，bind/unbind 使用显式 legacy registry-direct 端口。
     *
     * <p>返回的 assembly {@code productionReady=false}。生产不得使用本入口，避免绕过 ledger
     * epoch/startBoardSeq 协调。</p>
     */
    public static ChannelToolAssembly assemble(QqChannelRegistry registry,
                                               ChannelSourceCatalog sourceCatalog,
                                               ChannelMutationGate mutationGate,
                                               Clock clock) {
        return build(registry, sourceCatalog, mutationGate,
                new RegistryBindingOperations(registry), clock, false);
    }

    /** 旧独立测试入口：显式 gate + 系统 UTC 时钟。 */
    public static ChannelToolAssembly assemble(QqChannelRegistry registry,
                                               ChannelSourceCatalog sourceCatalog,
                                               ChannelMutationGate mutationGate) {
        return assemble(registry, sourceCatalog, mutationGate, Clock.systemUTC());
    }

    /** 旧独立测试入口：默认本地 gate + 可注入时钟。 */
    public static ChannelToolAssembly assemble(QqChannelRegistry registry,
                                               ChannelSourceCatalog sourceCatalog,
                                               Clock clock) {
        return assemble(registry, sourceCatalog, ChannelMutationGate.processLocal(), clock);
    }

    /** 旧独立测试入口：只取工具列表，factory-local gate，无 coordinator。 */
    public static List<AgentTool> all(QqChannelRegistry registry,
                                      ChannelSourceCatalog sourceCatalog) {
        return all(registry, sourceCatalog, ChannelMutationGate.processLocal(),
                Clock.systemUTC());
    }

    /** 旧独立测试入口：只取工具列表，factory-local gate，无 coordinator。 */
    public static List<AgentTool> all(QqChannelRegistry registry,
                                      ChannelSourceCatalog sourceCatalog,
                                      Clock clock) {
        return all(registry, sourceCatalog, ChannelMutationGate.processLocal(), clock);
    }

    /** 旧独立测试入口：显式 gate，只返回工具列表。 */
    public static List<AgentTool> all(QqChannelRegistry registry,
                                      ChannelSourceCatalog sourceCatalog,
                                      ChannelMutationGate mutationGate) {
        return assemble(registry, sourceCatalog, mutationGate, Clock.systemUTC()).tools();
    }

    /** 旧独立测试入口：显式 gate + 时钟，只返回工具列表。 */
    public static List<AgentTool> all(QqChannelRegistry registry,
                                      ChannelSourceCatalog sourceCatalog,
                                      ChannelMutationGate mutationGate,
                                      Clock clock) {
        return assemble(registry, sourceCatalog, mutationGate, clock).tools();
    }

    private static ChannelToolAssembly build(QqChannelRegistry registry,
                                             ChannelSourceCatalog sourceCatalog,
                                             ChannelMutationGate mutationGate,
                                             BindingOperations bindingOperations,
                                             Clock clock,
                                             boolean productionReady) {
        ChannelToolContext context = new ChannelToolContext(registry, sourceCatalog,
                mutationGate, bindingOperations, clock);
        List<AgentTool> tools = List.of(
                new QqChannelSourcesTool(context),
                new QqChannelThreadListTool(context),
                new QqThreadBindTool(context),
                new QqThreadUnbindTool(context),
                new QqThreadIdentityBindTool(context),
                new QqThreadIdentityUnbindTool(context),
                new QqThreadSetInjectionTool(context),
                new QqThreadSetPreferredChannelTool(context),
                new QqThreadSetMatchingTool(context),
                new QqThreadStatusTool(context));
        ensureUniqueNames(tools);
        return new ChannelToolAssembly(tools, mutationGate, productionReady);
    }

    private static void ensureUniqueNames(List<AgentTool> tools) {
        Set<String> names = new HashSet<>();
        for (AgentTool tool : tools) {
            Objects.requireNonNull(tool, "tool");
            if (!names.add(tool.name())) {
                throw new IllegalStateException("渠道控制工具名重复: " + tool.name());
            }
        }
    }
}
