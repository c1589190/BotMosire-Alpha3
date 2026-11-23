package io.github.c1589190.bma3.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.bma3.app.config.AppConfig;
import io.github.c1589190.bma3.app.config.ConfigLoader;
import io.github.c1589190.bma3.inbox.core.SqliteInboxStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** CLI entry point used by the jpackage launcher. */
public final class Main {

    public static final String VERSION = "0.0.9-fix1";

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--version".equals(args[0])) {
            System.out.println("botmosire-a3 " + VERSION);
            return;
        }
        Path root = Path.of(".");
        boolean noAuth = false;
        boolean rotateToken = false;
        boolean rotateInboxIdentity = false;
        boolean managed = false;
        Path managedState = null;
        for (int index = 0; index < args.length; index++) {
            if ("--config".equals(args[index]) && index + 1 < args.length) {
                root = Path.of(args[index + 1]);
            } else if ("--no-auth".equals(args[index])) {
                noAuth = true;
            } else if ("--rotate-token".equals(args[index])) {
                rotateToken = true;
            } else if ("--rotate-inbox-identity".equals(args[index])) {
                rotateInboxIdentity = true;
            } else if ("--managed-runtime".equals(args[index])) {
                managed = true;
            } else if ("--manager-state".equals(args[index]) && index + 1 < args.length) {
                managedState = Path.of(args[index + 1]);
            }
        }
        AppConfig config = ConfigLoader.load(root);
        if (noAuth) {
            // D-064: no-auth is an explicit request, never an implicit fallback.
            config = config.withSecurity(new AppConfig.Security(
                    AppConfig.Security.AUTH_NONE, config.security().envFile()));
        }
        if (managed) {
            if (managedState == null || !managedState.isAbsolute()
                    || rotateToken || rotateInboxIdentity || !config.security().requiresToken()
                    || !"127.0.0.1".equals(config.server().host())) {
                throw new IllegalArgumentException("托管运行要求绝对状态路径、127.0.0.1 和 token 鉴权");
            }
            try {
                SingleInstanceLock managerLock = SingleInstanceLock.acquire(managedState.resolve("runtime-owner"));
                try {
                    runManaged(config, root);
                } finally {
                    managerLock.close();
                }
            } catch (SingleInstanceLock.Held held) {
                System.out.println("BMA3_MANAGED_ERROR INSTANCE_IN_USE");
                throw held;
            }
            return;
        }
        if (rotateInboxIdentity) {
            // Offline maintenance: rotate the inbox database identity while holding the data
            // directory's single-instance lock, then exit without starting any service, port or
            // QQ connection. A running/new instance would otherwise keep using the old identity.
            String identity = rotateInboxIdentity(config);
            System.out.println("inboxIdentity 已轮换: " + identity
                    + "（插件必须重新读取 /panel；旧身份下的待 ACK 记录会被拒绝）");
            return;
        }
        if (rotateToken) {
            Bma3Env rotated = Bma3Env.ensure(Bma3Env.resolve(config, root), true);
            if (rotated.degraded()) {
                throw new IllegalStateException("无法安全轮换 token 文件: " + rotated.file());
            }
            System.out.println("token 已轮换: " + rotated.file() + "（插件需重连）");
        }
        Bma3App app = Bma3App.start(config, System.getenv(), root);
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.close();
            stopped.countDown();
        }, "bma3-shutdown"));
        System.out.println("botmosire-a3 " + VERSION + " started"
                + " mcp=127.0.0.1:" + app.mcpPort()
                + " health=127.0.0.1:" + app.healthPort()
                + " inboxHttp=127.0.0.1:" + app.inboxHttpPort()
                + " auth=" + config.security().auth());
        stopped.await();
    }

    private static void runManaged(AppConfig config, Path root) throws Exception {
        Bma3App app = Bma3App.start(config, System.getenv(), root);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicBoolean closing = new AtomicBoolean();
        Runnable close = () -> {
            if (closing.compareAndSet(false, true)) {
                try {
                    app.close();
                } finally {
                    stopped.countDown();
                }
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            close.run();
            try {
                stopped.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "bma3-managed-shutdown"));
        Thread.ofVirtual().name("bma3-manager-pipe").start(() -> {
            try {
                while (System.in.read() != -1) {
                    // The manager holds this pipe open for the lifetime of the child.
                }
            } catch (IOException ignored) {
                // A failed pipe also means the manager can no longer own this process.
            } finally {
                close.run();
            }
        });
        String ready = new ObjectMapper().writeValueAsString(Map.of(
                "protocol", 1,
                "version", VERSION,
                "mcpPort", app.mcpPort(),
                "mcpPath", config.server().mcpPath().trim(),
                "mcpDailyPath", McpToolSurfaces.dailyPath(config.server().mcpPath()),
                "mcpOpsPath", McpToolSurfaces.opsPath(config.server().mcpPath()),
                "inboxPort", app.inboxHttpPort(),
                "inboxId", config.inbox().inboxId(),
                "healthPort", app.healthPort(),
                "envFile", Bma3Env.resolve(config, root).toString()));
        System.out.writeBytes(("BMA3_MANAGED_READY " + ready + "\n").getBytes(StandardCharsets.UTF_8));
        System.out.flush();
        stopped.await();
    }

    /**
     * Offline, lock-guarded rotation of the durable inbox identity.
     *
     * <p>Restoring or cloning {@code inbox.db} keeps its original identity, so the plugin cannot
     * tell the copy from the original. An operator must stop every BMA3 process and rotate: this
     * method takes the existing data-directory single-instance lock (fail-closed when another
     * instance holds it), rewrites the identity in {@code inbox.db} and returns the new value. It
     * never starts a server, binds a port or opens a QQ connection.</p>
     *
     * <p>Rotation replaces the identity for that database only; item rows, revisions and pending
     * ACK targets are untouched, which is why the plugin must be told to stop automatic ACK and
     * re-read the panel instead of replaying old targets.</p>
     *
     * @throws IllegalStateException when the lock is held by another instance or the identity write
     *                               cannot be confirmed
     */
    static String rotateInboxIdentity(AppConfig config) {
        Path dataDir = config.dataDir().toAbsolutePath().normalize();
        try (SingleInstanceLock ignored = SingleInstanceLock.acquire(dataDir)) {
            try (SqliteInboxStore store = new SqliteInboxStore(dataDir.resolve("inbox.db"))) {
                return store.rotateIdentity();
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("获取数据目录单实例锁失败: " + failure.getMessage(),
                    failure);
        }
    }
}
