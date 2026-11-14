package io.mosire.bma3.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleInstanceLockTest {

    @TempDir
    Path tempDir;

    @Test
    void secondAcquireFailsWhileTheFirstHoldsTheLock() throws Exception {
        try (SingleInstanceLock first = SingleInstanceLock.acquire(tempDir)) {
            assertThat(first.file()).exists();
            assertThatThrownBy(() -> SingleInstanceLock.acquire(tempDir))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已有 BMA3 实例");
        }
    }

    @Test
    void lockIsReusableAfterRelease() throws Exception {
        Path dataDir = tempDir.resolve("data");
        SingleInstanceLock first = SingleInstanceLock.acquire(dataDir);
        first.close();

        try (SingleInstanceLock second = SingleInstanceLock.acquire(dataDir)) {
            assertThat(second.file()).exists();
        }
        assertThat(dataDir.resolve(SingleInstanceLock.LOCK_FILE)).exists();
    }

    @Test
    void lockFileRecordsTheOwnerPid() throws Exception {
        try (SingleInstanceLock lock = SingleInstanceLock.acquire(tempDir)) {
            String owner = Files.readString(lock.file());
            assertThat(owner).contains("pid=").contains(String.valueOf(ProcessHandle.current().pid()));
        }
    }
}
