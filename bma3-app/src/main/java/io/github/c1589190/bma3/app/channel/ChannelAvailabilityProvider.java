package io.github.c1589190.bma3.app.channel;

/**
 * 渠道账号当前是否可用于展示/注入的注入点。
 *
 * <p>实现必须来自真实连接/账号状态（例如 OneBot connected、官方网关 READY），而不是 registry
 * 中是否存在 binding。provider 只接收 channel 与 accountId，不接收/返回 token 或正文。</p>
 *
 * <p><b>默认安全语义</b>：未注入 provider 时，registry 视为“可用性未知 = 不可用”，因此不会把
 * 未知连接状态的账号选为展示渠道，也不会伪造 fallback 可用性。生产装配必须显式传入真实 provider。</p>
 */
@FunctionalInterface
public interface ChannelAvailabilityProvider {

    /** @return true 仅当该渠道账号当前确实可用于后续展示；false 或异常均按不可用处理 */
    boolean isAvailable(QqChannelRegistry.Channel channel, String accountId);

    /** 默认 fail-closed provider：可用性未知时一律不可用。 */
    static ChannelAvailabilityProvider unknownUnavailable() {
        return (channel, accountId) -> false;
    }
}
