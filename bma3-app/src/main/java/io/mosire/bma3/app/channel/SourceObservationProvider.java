package io.mosire.bma3.app.channel;

/**
 * raw source 观察状态注入点。
 *
 * <p>只有真实 ingest/消息板侧能回答某个 raw source 是否已经观察过。registry 不创建空目录、不扫描
 * 空表冒充“未观察”，也不把预绑定当成观察事实。</p>
 */
@FunctionalInterface
public interface SourceObservationProvider {

    /** @return true 仅当该 canonical raw source 至少已被一个真实渠道观察过 */
    boolean hasObserved(String rawSource);
}
