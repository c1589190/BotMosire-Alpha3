package io.github.c1589190.bma3.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.c1589190.bma3.app.config.AppConfig;
import io.github.c1589190.bma3.inbox.core.SqliteInboxStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Offline identity rotation for a restored/cloned inbox database: it must run only while holding
 * the data-directory single-instance lock, must persist the new identity, and must never start a
 * service.
 */
class InboxIdentityRotationTest {

    @TempDir
    Path tempDir;

    private AppConfig config(Path dataDir) {
        return new AppConfig(
                dataDir,
                new AppConfig.Server("127.0.0.1", 0, "/mcp", "ROTATION_TEST_TOKEN"),
                new AppConfig.QqConnect(List.of()),
                new AppConfig.Inbox("bma3", 0),
                new AppConfig.Logging("INFO", tempDir.resolve("logs").toString()));
    }

    private static String identity(AppConfig config) {
        try (SqliteInboxStore store =
                     new SqliteInboxStore(config.dataDir().resolve("inbox.db"))) {
            return store.identity();
        }
    }

    @Test
    void rotationPersistsTheNewIdentityAndReleasesTheLock() {
        AppConfig config = config(tempDir.resolve("data"));
        String before = identity(config);

        String rotated = Main.rotateInboxIdentity(config);

        assertThat(rotated).isNotBlank().isNotEqualTo(before);
        assertThat(identity(config)).as("轮换必须落盘").isEqualTo(rotated);
        try (SingleInstanceLock reacquired = SingleInstanceLock.acquire(config.dataDir())) {
            assertThat(reacquired.file()).as("轮换结束必须释放单实例锁").exists();
        } catch (java.io.IOException failure) {
            throw new AssertionError("重新获取锁失败", failure);
        }
    }

    @Test
    void rotationRefusesToRunWhileAnotherInstanceHoldsTheLock() {
        AppConfig config = config(tempDir.resolve("locked"));
        String before = identity(config);

        try (SingleInstanceLock held = SingleInstanceLock.acquire(config.dataDir())) {
            assertThatThrownBy(() -> Main.rotateInboxIdentity(config))
                    .as("在线轮换会让运行中的实例与插件对不上身份")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已有 BMA3 实例");
        } catch (java.io.IOException failure) {
            throw new AssertionError("测试自身获取锁失败", failure);
        }

        assertThat(identity(config)).as("失败时不能悄悄轮换").isEqualTo(before);
    }
}
