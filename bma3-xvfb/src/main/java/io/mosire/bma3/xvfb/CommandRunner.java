package io.mosire.bma3.xvfb;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 子进程执行：带超时、带环境变量、输出有界。 */
public final class CommandRunner {

    private static final int MAX_CAPTURE = 64 * 1024;

    public record Result(int rc, String stdout, String stderr, boolean timedOut) {
        public boolean ok() {
            return !timedOut && rc == 0;
        }
    }

    private CommandRunner() {
    }

    public static Result run(List<String> argv, Duration timeout, Map<String, String> env,
                             Path cwd) {
        return run(argv, timeout, env, cwd, null);
    }

    public static Result run(List<String> argv, Duration timeout, Map<String, String> env,
                             Path cwd, String stdin) {
        ProcessBuilder builder = new ProcessBuilder(argv);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        if (env != null) {
            builder.environment().putAll(env);
        }
        try {
            Process process = builder.start();
            if (stdin != null) {
                try (var out = process.getOutputStream()) {
                    out.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }
            Reader outReader = new Reader(process.getInputStream());
            Reader errReader = new Reader(process.getErrorStream());
            Thread t1 = Thread.ofVirtual().start(outReader);
            Thread t2 = Thread.ofVirtual().start(errReader);
            boolean finished = process.waitFor(
                    timeout == null ? 30 : timeout.toSeconds(),
                    TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                t1.join(1000);
                t2.join(1000);
                return new Result(-1, outReader.text(), errReader.text(), true);
            }
            t1.join(2000);
            t2.join(2000);
            return new Result(process.exitValue(), outReader.text(), errReader.text(), false);
        } catch (IOException failure) {
            return new Result(-1, "", failure.getMessage() == null ? "exec failed"
                    : failure.getMessage(), false);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new Result(-1, "", "interrupted", true);
        }
    }

    /** 起一个长跑进程，输出追加到 {@code log}；返回进程句柄。 */
    public static Process start(List<String> argv, Map<String, String> env, Path cwd, Path log)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(argv);
        if (cwd != null) {
            Files.createDirectories(cwd);
            builder.directory(cwd.toFile());
        }
        if (env != null) {
            builder.environment().putAll(env);
        }
        if (log != null) {
            Files.createDirectories(log.getParent());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        }
        return builder.start();
    }

    /** 追加一行到日志文件（不记正文，只记事件）。 */
    public static void note(Path log, String line) {
        try {
            Files.createDirectories(log.getParent());
            Files.writeString(log, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 日志写不进去不影响主流程
        }
    }

    /** 后台读流，避免管道写满导致子进程卡死。 */
    private static final class Reader implements Runnable {
        private final InputStream stream;
        private final StringBuilder buffer = new StringBuilder();

        Reader(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            try (stream) {
                byte[] chunk = new byte[8192];
                int read;
                while ((read = stream.read(chunk)) >= 0) {
                    synchronized (buffer) {
                        if (buffer.length() < MAX_CAPTURE) {
                            buffer.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                        }
                    }
                }
            } catch (IOException ignored) {
                // 进程被杀时的流关闭，属正常
            }
        }

        String text() {
            synchronized (buffer) {
                return buffer.length() > MAX_CAPTURE ? buffer.substring(0, MAX_CAPTURE) : buffer.toString();
            }
        }
    }
}
