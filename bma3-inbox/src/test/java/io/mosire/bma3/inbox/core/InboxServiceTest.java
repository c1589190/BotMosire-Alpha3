package io.mosire.bma3.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InboxServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void deliversImportantFirstThenNormalAndAcks() {
        Instant t0 = Instant.parse("2026-11-09T11:00:00Z");
        DeliveryPolicy policy = new DeliveryPolicy(200, Duration.ofSeconds(1),
                Duration.ofSeconds(30), Duration.ofMinutes(10), 3);
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("inbox.db"))) {
            InboxService service = new InboxService(store, policy);
            service.append(new AppendItem("qq_private/bot-1/1", "important", 300, t0));
            service.append(new AppendItem("qq_private/bot-1/2", "normal", 0, t0));

            InboxStatus initial = service.status();
            assertThat(initial.available()).isEqualTo(2);
            assertThat(initial.remaining()).isEqualTo(2);

            List<Delivery> important = service.due(t0.plusSeconds(2));
            assertThat(important).singleElement().satisfies(delivery -> {
                assertThat(delivery.text()).isEqualTo("important");
                assertThat(delivery.important()).isTrue();
                assertThat(delivery.attempts()).isEqualTo(1);
            });
            assertThat(service.ack(important.getFirst().id()).acked()).isTrue();
            assertThat(service.status().available()).isEqualTo(1);
            assertThat(service.status().acked()).isEqualTo(1);

            List<Delivery> normal = service.due(t0.plusSeconds(31));
            assertThat(normal).singleElement().satisfies(delivery -> {
                assertThat(delivery.text()).isEqualTo("normal");
                assertThat(delivery.important()).isFalse();
            });
            assertThat(service.ack(normal.getFirst().id()).acked()).isTrue();
            assertThat(service.status().remaining()).isZero();
        }
    }

    @Test
    void retriesUntilUndeliveredAndKeepsItems() {
        Instant t0 = Instant.parse("2026-11-09T11:00:00Z");
        DeliveryPolicy policy = new DeliveryPolicy(200, Duration.ofSeconds(1),
                Duration.ofSeconds(30), Duration.ofMinutes(10), 2);
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("inbox.db"))) {
            InboxService service = new InboxService(store, policy);
            service.append(new AppendItem("qq_group/bot-1/42", "retry", 300, t0));

            Delivery first = service.due(t0.plusSeconds(2)).getFirst();
            assertThat(first.attempts()).isEqualTo(1);
            Delivery second = service.due(t0.plusSeconds(2).plus(Duration.ofMinutes(10)))
                    .getFirst();
            assertThat(second.attempts()).isEqualTo(2);
            service.due(t0.plusSeconds(2).plus(Duration.ofMinutes(20)));

            InboxStatus status = service.status();
            assertThat(status.undelivered()).isEqualTo(1);
            assertThat(status.available()).isZero();
        }
    }
}
