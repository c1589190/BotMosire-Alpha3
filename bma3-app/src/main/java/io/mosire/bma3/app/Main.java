package io.mosire.bma3.app;

import io.mosire.bma3.app.config.AppConfig;
import io.mosire.bma3.app.config.ConfigLoader;
import io.mosire.bma3.inbox.core.SqliteInboxStore;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** CLI entry point used by the jpackage launcher. */
public final class Main {

    public static final String VERSION = "0.0.7-test1";

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
        for (int index = 0; index < args.length; index++) {
            if ("--config".equals(args[index]) && index + 1 < args.length) {
                root = Path.of(args[index + 1]);
            } else if ("--no-auth".equals(args[index])) {
                noAuth = true;
            } else if ("--rotate-token".equals(args[index])) {
                rotateToken = true;
            } else if ("--rotate-inbox-identity".equals(args[index])) {
                rotateInboxIdentity = true;
            }
        }
        AppConfig config = ConfigLoader.load(root);
        if (noAuth) {
            // D-064: no-auth is an explicit request, never an implicit fallback.
            config = config.withSecurity(new AppConfig.Security(
                    AppConfig.Security.AUTH_NONE, config.security().envFile()));
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
