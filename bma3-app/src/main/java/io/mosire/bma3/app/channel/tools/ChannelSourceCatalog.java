package io.mosire.bma3.app.channel.tools;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 注入给 {@code qq_channel_sources} 的本地来源目录。
 *
 * <p>它不是 QQ 平台枚举器，也不是“平台上全部群/好友”的事实源。实现只能返回本进程已经通过
 * 真实 ingest、消息板或装配层观察到的 raw source。空列表只表示本目录没有条目，绝不表示平台
 * 上不存在来源。渠道/账号的 known 校验仍由 {@link io.mosire.bma3.app.channel.QqChannelRegistry}
 * 注入的校验器完成，目录不参与账号认证。</p>
 */
@FunctionalInterface
public interface ChannelSourceCatalog {

    /**
     * @param limit 调用方希望的返回上限；实现可以少返回，但不应借此声称平台全量
     * @return 本地已观察来源；允许为空，但空列表不表示平台为空
     */
    List<ObservedSource> listObservedSources(int limit);

    /** 一个本地已观察 raw source；{@code lastActivity} 未知时可为 {@code null}。 */
    record ObservedSource(String rawSource, Instant lastActivity) {
        public ObservedSource {
            Objects.requireNonNull(rawSource, "rawSource");
        }
    }

    /** 明确表达“本地没有来源事实”的安全目录；不代表平台全量。 */
    static ChannelSourceCatalog empty() {
        return limit -> List.of();
    }
}
