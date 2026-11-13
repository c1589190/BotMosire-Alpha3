package io.mosire.bma3.xvfb;

import java.nio.file.Path;
import java.util.List;

/**
 * 一个 Xvfb 工作区 = 一个 display = 一次任务的全部桌面状态，<b>可丢弃</b>。
 *
 * <p>目录布局见 spec §3；{@code meta.json} 是唯一事实源（不变量 I6）。
 *
 * @param id       工作区 id（{@code ws-xxxx}）
 * @param dir      工作区目录
 * @param display  X display 号（{@code :90}..{@code :199}）
 * @param state    {@link #STARTING} / {@link #RUNNING} / {@link #KEPT} / {@link #FAILED}
 * @param createdAt 创建时间（epoch millis）
 * @param xvfbPid  Xvfb 进程 pid（0 表示未运行）
 * @param wmPid    窗口管理器 pid（0 表示没有）
 * @param appPids  由 xvfb_launch 启动的应用 pid（关闭时一并回收）
 */
public record Workspace(
        String id,
        Path dir,
        int display,
        String state,
        long createdAt,
        long xvfbPid,
        long wmPid,
        List<Long> appPids,
        int width,
        int height,
        int depth,
        String error) {

    public static final String STARTING = "STARTING";
    public static final String RUNNING = "RUNNING";
    public static final String KEPT = "KEPT";
    public static final String FAILED = "FAILED";
    /** 工作区已被整目录删除（{@code close{keep:false}} 之后的事实状态）。 */
    public static final String DELETED = "DELETED";

    public Workspace {
        appPids = appPids == null ? List.of() : List.copyOf(appPids);
        state = state == null ? STARTING : state;
        error = error == null ? "" : error;
    }

    public String displayName() {
        return ":" + display;
    }

    public boolean running() {
        return RUNNING.equals(state);
    }

    public Workspace withState(String nextState) {
        return new Workspace(id, dir, display, nextState, createdAt, xvfbPid, wmPid, appPids,
                width, height, depth, error);
    }

    public Workspace withProcesses(long nextXvfbPid, long nextWmPid, List<Long> nextApps) {
        return new Workspace(id, dir, display, state, createdAt, nextXvfbPid, nextWmPid,
                nextApps, width, height, depth, error);
    }

    public Workspace withError(String nextError) {
        return new Workspace(id, dir, display, state, createdAt, xvfbPid, wmPid, appPids,
                width, height, depth, nextError);
    }

    public Workspace withDisplay(int nextDisplay) {
        return new Workspace(id, dir, nextDisplay, state, createdAt, xvfbPid, wmPid, appPids,
                width, height, depth, error);
    }
}
