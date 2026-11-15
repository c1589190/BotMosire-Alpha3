package io.mosire.bma3.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Transaction safety of {@link SqliteInboxStore#inTransaction(Runnable)} against a driver that
 * fails to roll back.
 *
 * <p>SQLite's own connection cannot be made to fail {@code rollback()} while staying usable, so
 * these tests use a proxy {@link Connection} over a <b>real</b> temporary SQLite database: only
 * {@code rollback()}/{@code commit()}/{@code setAutoCommit(true)} are contaminated, everything else
 * (schema, rows, WAL) is the real driver.</p>
 *
 * <p>The hazard under test is real: per JDBC, restoring auto-commit on a connection whose
 * transaction state is unknown commits it. After a failed rollback the ACL rows must therefore
 * never be committed by a "cleanup" {@code setAutoCommit(true)}; the connection has to be
 * quarantined instead.</p>
 */
class SqliteInboxStoreTransactionTest {

    @TempDir
    Path tempDir;

    private static InboxItem item(String id, String source) {
        Instant now = Instant.now();
        return new InboxItem(id, source, source, "hello", List.of(), 1, 1L, 300,
                ItemStatus.AVAILABLE, now, now, now, 1, 0L, false);
    }

    private static Connection realConnection(Path database) throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }

    /** Knobs and observations for the injected driver faults. */
    private static final class Faults {
        private volatile boolean failRollback;
        private volatile boolean failCommit;
        private final AtomicInteger autoCommitRestoredAfterFailedRollback = new AtomicInteger();
        private volatile boolean rollbackFailed;
    }

    private static Connection faulty(Connection real, Faults faults) {
        return (Connection) Proxy.newProxyInstance(
                SqliteInboxStoreTransactionTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "rollback" -> {
                            if (faults.failRollback) {
                                faults.rollbackFailed = true;
                                throw new SQLException("INJECTED_ROLLBACK_FAILURE");
                            }
                            return method.invoke(real, args);
                        }
                        case "commit" -> {
                            if (faults.failCommit) {
                                throw new SQLException("INJECTED_COMMIT_FAILURE");
                            }
                            return method.invoke(real, args);
                        }
                        case "setAutoCommit" -> {
                            boolean value = args != null && args.length == 1
                                    && Boolean.TRUE.equals(args[0]);
                            if (value && faults.rollbackFailed) {
                                faults.autoCommitRestoredAfterFailedRollback.incrementAndGet();
                                throw new IllegalStateException("WOULD_COMMIT_PARTIAL_ACK");
                            }
                            return method.invoke(real, args);
                        }
                        default -> {
                            return method.invoke(real, args);
                        }
                    }
                });
    }

    @Test
    void aFailedRollbackQuarantinesTheConnectionAndNeverCommitsPartialWork() throws Exception {
        Path database = tempDir.resolve("rollback-failure.db");
        Faults faults = new Faults();
        faults.failRollback = true;
        SqliteInboxStore store = new SqliteInboxStore(faulty(realConnection(database), faults));
        store.append(item("itm-1", "qq_private/1"));
        store.freeze(List.of("itm-1"), 1L);

        assertThatThrownBy(() -> store.inTransaction(() -> {
            store.update(item("itm-1", "qq_private/1").withStatus(ItemStatus.ACKED));
            throw new IllegalStateException("WORK_FAILED");
        }))
                .as("原始失败必须原样抛出，而不是被清理动作替换")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WORK_FAILED")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .as("回滚失败必须作为被抑制异常保留")
                        .anySatisfy(suppressed -> assertThat(suppressed)
                                .hasMessageContaining("INJECTED_ROLLBACK_FAILURE")));

        assertThat(faults.autoCommitRestoredAfterFailedRollback)
                .as("回滚失败后绝不能 setAutoCommit(true)：驱动会提交部分 ACK")
                .hasValue(0);
        assertThat(store.ping()).as("事务状态未知的连接必须隔离").isFalse();
        assertThatThrownBy(() -> store.update(item("itm-1", "qq_private/1")))
                .as("隔离后任何后续写都必须 fail-closed")
                .isInstanceOf(IllegalStateException.class);

        try (SqliteInboxStore reopened = new SqliteInboxStore(database)) {
            assertThat(reopened.item("itm-1").orElseThrow().status())
                    .as("不能有部分 ACK 落盘")
                    .isEqualTo(ItemStatus.AVAILABLE);
            assertThat(reopened.ackedRevision()).isZero();
        }
    }

    @Test
    void aRollbackThatSucceedsLeavesTheStoreUsable() throws Exception {
        Path database = tempDir.resolve("rollback-ok.db");
        try (SqliteInboxStore store = new SqliteInboxStore(database)) {
            store.append(item("itm-1", "qq_private/1"));

            assertThatThrownBy(() -> store.inTransaction(() -> {
                store.update(item("itm-1", "qq_private/1").withStatus(ItemStatus.ACKED));
                throw new IllegalStateException("WORK_FAILED");
            })).hasMessageContaining("WORK_FAILED");
            assertThat(store.item("itm-1").orElseThrow().status())
                    .isEqualTo(ItemStatus.AVAILABLE);

            // Normal rollback must restore auto-commit, otherwise this second unit of work would
            // silently accumulate in an open transaction and never commit.
            store.inTransaction(() -> {
                store.update(item("itm-1", "qq_private/1").withStatus(ItemStatus.ACKED));
                store.saveAckedRevision(7L);
            });
            assertThat(store.item("itm-1").orElseThrow().status()).isEqualTo(ItemStatus.ACKED);
            assertThat(store.ackedRevision()).isEqualTo(7L);
        }
        try (SqliteInboxStore reopened = new SqliteInboxStore(database)) {
            assertThat(reopened.item("itm-1").orElseThrow().status()).isEqualTo(ItemStatus.ACKED);
            assertThat(reopened.ackedRevision()).isEqualTo(7L);
        }
    }

    @Test
    void aFailedCommitRollsBackAndReportsTheCommitCause() throws Exception {
        Path database = tempDir.resolve("commit-failure.db");
        Faults faults = new Faults();
        faults.failCommit = true;
        SqliteInboxStore store = new SqliteInboxStore(faulty(realConnection(database), faults));
        store.append(item("itm-1", "qq_private/1"));
        try {
            assertThatThrownBy(() -> store.inTransaction(
                    () -> store.update(item("itm-1", "qq_private/1")
                            .withStatus(ItemStatus.ACKED))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("提交失败")
                    .satisfies(failure -> assertThat(failure.getCause())
                            .hasMessageContaining("INJECTED_COMMIT_FAILURE"));
            assertThat(store.ping()).as("提交失败但回滚成功后连接仍可用").isTrue();
        } finally {
            store.close();
        }
        try (SqliteInboxStore reopened = new SqliteInboxStore(database)) {
            assertThat(reopened.item("itm-1").orElseThrow().status())
                    .as("提交失败的整批必须回滚")
                    .isEqualTo(ItemStatus.AVAILABLE);
        }
    }
}
