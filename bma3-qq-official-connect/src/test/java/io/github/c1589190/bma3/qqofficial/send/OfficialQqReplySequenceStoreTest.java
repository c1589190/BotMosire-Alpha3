package io.github.c1589190.bma3.qqofficial.send;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialQqReplySequenceStoreTest {

    private static final Instant T0 = Instant.parse("2026-11-17T08:00:00Z");
    private static final String SOURCE = "qq_official_group/acc-1/group-1";
    private static final String MSG = "ROBOT1.0_MSG";

    @TempDir
    Path temp;

    @Test
    void allocatesAtomicallyUnderConcurrencyWithoutDuplicates() throws Exception {
        try (OfficialQqReplySequenceStore store = store(temp.resolve("seq.db"))) {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Integer>> tasks = IntStream.range(0, 24)
                        .mapToObj(index -> (Callable<Integer>) () -> store.allocate(
                                index + 1L, "op-" + index, 1, SOURCE, MSG, "").msgSeq())
                        .toList();
                List<Future<Integer>> futures = pool.invokeAll(tasks);
                List<Integer> seqs = new ArrayList<>();
                for (Future<Integer> future : futures) {
                    seqs.add(future.get());
                }
                assertThat(seqs).containsExactlyInAnyOrderElementsOf(
                        IntStream.rangeClosed(1, 24).boxed().toList());
                Set<Integer> unique = Set.copyOf(seqs);
                assertThat(unique).hasSize(24);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void sequenceAndAssociationSurviveRestart() throws Exception {
        Path db = temp.resolve("restart.db");
        try (OfficialQqReplySequenceStore store = store(db)) {
            OfficialQqReplySequenceStore.ReplyAttempt first =
                    store.allocate(11, "op-1", 1, SOURCE, MSG, "event-1");
            assertThat(first.msgSeq()).isEqualTo(1);
            assertThat(store.attempt(11)).contains(first);
        }
        try (OfficialQqReplySequenceStore reopened = store(db)) {
            OfficialQqReplySequenceStore.ReplyAttempt second =
                    reopened.allocate(12, "op-2", 1, SOURCE, MSG, "event-1");
            assertThat(second.msgSeq()).isEqualTo(2);
            assertThat(reopened.attempt(11)).get()
                    .satisfies(attempt -> assertThat(attempt.msgSeq()).isEqualTo(1));
        }
    }

    @Test
    void differentEventIdForSameMessageRejectsWithoutConsumingASequence() throws Exception {
        try (OfficialQqReplySequenceStore store = store(temp.resolve("conflict.db"))) {
            store.allocate(1, "op-1", 1, SOURCE, MSG, "event-1");

            assertThatThrownBy(() -> store.allocate(2, "op-2", 1, SOURCE, MSG, "event-2"))
                    .isInstanceOfSatisfying(OfficialQqSendException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("REPLY_REFERENCE_CONFLICT"));
            assertThat(store.attempt(2)).isEmpty();

            assertThat(store.allocate(3, "op-3", 1, SOURCE, MSG, "event-1").msgSeq())
                    .isEqualTo(2);
        }
    }

    private static OfficialQqReplySequenceStore store(Path db) {
        return new OfficialQqReplySequenceStore(db, Clock.fixed(T0, ZoneOffset.UTC));
    }
}
