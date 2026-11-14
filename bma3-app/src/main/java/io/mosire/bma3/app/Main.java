package io.mosire.bma3.app;

import io.mosire.bma3.app.config.AppConfig;
import io.mosire.bma3.app.config.ConfigLoader;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** CLI entry point used by the jpackage launcher. */
public final class Main {

    public static final String VERSION = "0.0.5-hotfix2";

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
        for (int index = 0; index < args.length; index++) {
            if ("--config".equals(args[index]) && index + 1 < args.length) {
                root = Path.of(args[index + 1]);
            } else if ("--no-auth".equals(args[index])) {
                noAuth = true;
            } else if ("--rotate-token".equals(args[index])) {
                rotateToken = true;
            }
        }
        AppConfig config = ConfigLoader.load(root);
        if (noAuth) {
            // D-064: no-auth is an explicit request, never an implicit fallback.
            config = config.withSecurity(new AppConfig.Security(
                    AppConfig.Security.AUTH_NONE, config.security().envFile()));
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
}
