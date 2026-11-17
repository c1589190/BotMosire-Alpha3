package io.mosire.bma3.app.channel.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.bma3.app.channel.QqChannelRegistry;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore;
import io.mosire.bma3.core.store.MessageBoardStore;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Phase 10 只读证据工具工厂：logical-thread correlation 视图与统一历史时间线。
 *
 * <p>compare 只读 {@link QqChannelObservationStore}；history 再按 observation 的
 * {@code rawSource + boardLocalSeq} 精确读取注入的 {@link MessageBoardStore}。本工厂不修改
 * Bma3App、registry、observation store 或 router，也不包含任何写入/审批/ACK 逻辑。</p>
 *
 * <p>边界：compare 不读取 board，不返回正文、segments、URL、无盐正文摘要或凭据；history
 * 默认不返回 segments，显式 includeSegments=true 时也只返回脱敏 segment。两者都只代表本地
 * 已经观察/持久化的事实，不宣称 QQ 平台全量历史。</p>
 */
public final class ChannelEvidenceTools {

    private ChannelEvidenceTools() {
    }

    /** 注入 registry（thread/账号格式校验）、observation store 和消息板；后续统一装配。 */
    public static List<AgentTool> all(QqChannelRegistry registry,
                                      QqChannelObservationStore observations,
                                      MessageBoardStore board) {
        ChannelEvidenceToolContext context = new ChannelEvidenceToolContext(
                registry, observations, board);
        List<AgentTool> tools = List.of(
                new QqThreadCompareTool(context),
                new QqThreadHistoryTool(context));
        ensureUniqueNames(tools);
        return tools;
    }

    private static void ensureUniqueNames(List<AgentTool> tools) {
        Set<String> names = new HashSet<>();
        for (AgentTool tool : tools) {
            Objects.requireNonNull(tool, "tool");
            if (!names.add(tool.name())) {
                throw new IllegalStateException("channel evidence 工具名重复: " + tool.name());
            }
        }
    }
}
