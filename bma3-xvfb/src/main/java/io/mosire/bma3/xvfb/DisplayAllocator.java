package io.mosire.bma3.xvfb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * display 号分配（不变量 W2）：在 {@code 90..199} 里挑一个没被占用的号。
 *
 * <p>占用判据两条：{@code /tmp/.X<n>-lock} 存在，或 {@code /tmp/.X11-unix/X<n>} 存在。
 * 有些 X 服务器只用 abstract socket、不留文件，此时分配会撞车——{@link XvfbService} 因此
 * 在启动失败时换号重试，而不是相信这里的结果是绝对的。
 */
public final class DisplayAllocator {

    private static final int MIN = 90;
    private static final int MAX = 199;

    private DisplayAllocator() {
    }

    public static int allocate() {
        return allocate(Path.of("/tmp"));
    }

    public static int allocate(Path tmpRoot) {
        return allocate(tmpRoot, Set.of());
    }

    public static int allocate(Path tmpRoot, Set<Integer> alsoInUse) {
        List<Integer> free = available(tmpRoot, alsoInUse);
        if (free.isEmpty()) {
            throw new IllegalStateException("display 号耗尽（" + MIN + ".." + MAX + " 全部占用）");
        }
        return free.get(0);
    }

    public static List<Integer> available(Path tmpRoot, Set<Integer> alsoInUse) {
        List<Integer> free = new ArrayList<>();
        for (int n = MIN; n <= MAX; n++) {
            if (alsoInUse != null && alsoInUse.contains(n)) {
                continue;
            }
            if (inUse(tmpRoot, n)) {
                continue;
            }
            free.add(n);
        }
        return free;
    }

    static boolean inUse(Path tmpRoot, int display) {
        Path lock = tmpRoot.resolve(".X" + display + "-lock");
        Path socket = tmpRoot.resolve(".X11-unix").resolve("X" + display);
        return Files.exists(lock) || Files.exists(socket);
    }
}
