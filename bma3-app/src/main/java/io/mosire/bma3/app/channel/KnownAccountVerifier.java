package io.mosire.bma3.app.channel;

/**
 * 账号存在性校验注入点。
 *
 * <p>实现必须来自真实账号来源（OneBot 账号 registry、官方机器人凭据/已配置账号等）。不能把
 * “本地目录为空”解释成账号不存在，也不能用数据库里没有 binding 这种本模块事实反推账号存在。</p>
 */
@FunctionalInterface
public interface KnownAccountVerifier {

    /**
     * @return true 仅当该渠道的该账号确实已知；false 或抛异常都由 registry fail-closed
     */
    boolean isKnown(QqChannelRegistry.Channel channel, String accountId);
}
