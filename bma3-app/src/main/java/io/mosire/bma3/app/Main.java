package io.mosire.bma3.app;

import io.mosire.bma3.app.config.AppConfig;
import io.mosire.bma3.app.config.ConfigLoader;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** CLI entry point used by the jpackage launcher. */
public final class Main {

    public static final String VERSION = "0.0.3-hotfix5";

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--version".equals(args[0])) {
            System.out.println("botmosire-a3 " + VERSION);
            return;
        }
        Path root = Path.of(".");
        for (int index = 0; index < args.length; index++) {
            if ("--config".equals(args[index]) && index + 1 < args.length) {
                root = Path.of(args[index + 1]);
            }
        }
        AppConfig config = ConfigLoader.load(root);
        Bma3App app = Bma3App.start(config, System.getenv(), root);
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.close();
            stopped.countDown();
        }, "bma3-shutdown"));
        System.out.println("botmosire-a3 " + VERSION + " started"
                + " mcp=127.0.0.1:" + app.mcpPort()
                + " health=127.0.0.1:" + app.healthPort()
                + " inboxHttp=127.0.0.1:" + app.inboxHttpPort());
        stopped.await();
    }
}
