package io.mosire.bma3.app.channel.tools;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 渠道控制写操作与后续 ingest/router 共享的进程内互斥门。
 *
 * <p>工具只通过 {@link #underLock(Supplier)} 调用 registry 的写方法，确保“Agent 暂停注入/改绑定”
 * 与“ingest 根据旧状态路由”不会在同一进程内交错。生产装配必须创建 <b>一个</b> 实例并同时注入
 * 工具工厂和 ingest/router；默认的 {@link #processLocal()} 仅用于保持独立工具可用，不表示
 * {@code Bma3App} 已完成装配。</p>
 *
 * <p>实现必须在 {@code finally} 中释放锁，异常路径也不能泄漏锁。锁只覆盖写协调；registry 仍
 * 自己负责 SQLite 事务与审计。读取工具不经过此门。</p>
 */
public interface ChannelMutationGate {

    /**
     * 在同一个进程锁下执行动作。
     *
     * @param action 不得为 null；异常会原样抛出，但锁必须在 finally 中释放
     * @return action 的返回值
     */
    <T> T underLock(Supplier<T> action);

    /**
     * 工厂默认的进程内可重入锁。独立工具会共享同一个工厂实例；生产 ingest/router 必须注入与
     * 工具相同的实例，不能各自调用本方法制造第二把锁。
     */
    static ChannelMutationGate processLocal() {
        return new ProcessLocalMutationGate();
    }
}

/** 默认实现：公平、可重入的进程内锁。 */
final class ProcessLocalMutationGate implements ChannelMutationGate {

    private final ReentrantLock lock = new ReentrantLock(true);

    @Override
    public <T> T underLock(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
