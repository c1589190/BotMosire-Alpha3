package io.mosire.bma3.xvfb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DisplayAllocatorTest {

    @Test
    void lockedDisplaysAreSkipped(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve(".X11-unix"));
        Files.createFile(tmp.resolve(".X90-lock"));
        Files.createFile(tmp.resolve(".X11-unix/X91"));

        List<Integer> free = DisplayAllocator.available(tmp, Set.of());

        assertThat(free).doesNotContain(90, 91);
        assertThat(free.get(0)).isEqualTo(92);
    }

    @Test
    void claimedDisplaysAreSkipped(@TempDir Path tmp) {
        List<Integer> free = DisplayAllocator.available(tmp, Set.of(90, 91));

        assertThat(free).doesNotContain(90, 91);
        assertThat(free.get(0)).isEqualTo(92);
    }

    @Test
    void allocateReturnsTheFirstFreeNumber(@TempDir Path tmp) throws Exception {
        Files.createFile(tmp.resolve(".X95-lock"));

        assertThat(DisplayAllocator.allocate(tmp, Set.of())).isEqualTo(90);
        assertThat(DisplayAllocator.allocate(tmp, Set.of(90, 91, 92, 93, 94))).isEqualTo(96);
    }
}
