package io.github.c1589190.bma3.app.channel.tools;

import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import java.util.List;
import java.util.Objects;

/**
 * 渠道控制工具的显式装配结果。
 *
 * <p>生产 router 必须把 {@link #mutationGate()} 的同一个实例同时交给本工具集、binding
 * coordinator 和 OneBot/Official ingress；并且必须使用带 {@code QqBindingCoordinator} 的
 * {@code assembleProduction(...)}。{@link #productionReady()} 只有在显式配置 coordinator 的
 * 生产入口才为 true；旧的无 coordinator 工厂只用于独立测试，不能用于生产。</p>
 */
public record ChannelToolAssembly(
        List<AgentTool> tools,
        ChannelMutationGate mutationGate,
        boolean bindingCoordinatorConfigured) {

    public ChannelToolAssembly {
        tools = List.copyOf(tools);
        Objects.requireNonNull(mutationGate, "mutationGate");
    }

    /** 旧独立测试兼容构造：明确标记为未配置 coordinator。 */
    public ChannelToolAssembly(List<AgentTool> tools, ChannelMutationGate mutationGate) {
        this(tools, mutationGate, false);
    }

    /** {@link #mutationGate()} 的短别名，便于 router 显式传递同一锁。 */
    public ChannelMutationGate gate() {
        return mutationGate;
    }

    /** 只有显式绑定 coordinator 的 assembly 才可进入生产 ingress/control 装配。 */
    public boolean productionReady() {
        return bindingCoordinatorConfigured;
    }
}
