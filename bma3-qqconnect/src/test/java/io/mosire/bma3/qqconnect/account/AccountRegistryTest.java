package io.mosire.bma3.qqconnect.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.bma3.core.account.AccountId;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import io.mosire.bma3.qqconnect.onebot.OneBotEvent;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AccountRegistryTest {

    private final List<AccountEvent> events = new CopyOnWriteArrayList<>();
    private final List<FakeClient> clients = new ArrayList<>();

    @Test
    void reconcilesAddRemoveRestartAndRejectsDuplicates() {
        AccountRegistry registry = new AccountRegistry(
                (config, listener) -> {
                    if ("broken".equals(config.accountId())) {
                        throw new IllegalStateException("cannot connect");
                    }
                    FakeClient client = new FakeClient(config, listener);
                    clients.add(client);
                    return client;
                },
                events::add);

        AccountConfig first = account("bot-1", 3001);
        AccountConfig second = account("bot-2", 3002);
        AccountConfig broken = account("broken", 3999);

        ReconcileResult added = registry.reconcile(List.of(first, second, broken));
        assertThat(added.added()).containsExactly("bot-1", "bot-2");
        assertThat(added.rejected()).containsExactly("broken");
        assertThat(clients).hasSize(2).allMatch(client -> client.started);
        assertThat(registry.client(new AccountId("bot-1"))).isPresent();

        clients.get(0).emit(event());
        assertThat(events).singleElement()
                .satisfies(event -> assertThat(event.accountId()).isEqualTo("bot-1"));

        ReconcileResult removed = registry.reconcile(List.of(first));
        assertThat(removed.removed()).containsExactly("bot-2");
        assertThat(clients.get(1).closed).isTrue();
        assertThat(registry.client(new AccountId("bot-2"))).isEmpty();

        AccountConfig changed = account("bot-1", 3011);
        ReconcileResult restarted = registry.reconcile(List.of(changed));
        assertThat(restarted.restarted()).containsExactly("bot-1");
        assertThat(clients.get(0).closed).isTrue();
        assertThat(clients.get(2).started).isTrue();

        ReconcileResult duplicate = registry.reconcile(List.of(changed, changed));
        assertThat(duplicate.rejected()).containsExactly("bot-1");
        assertThat(registry.client(new AccountId("bot-1"))).isPresent();

        registry.close();
        assertThat(clients.get(2).closed).isTrue();
    }

    private static AccountConfig account(String id, int port) {
        return new AccountConfig(id, URI.create("ws://127.0.0.1:" + port), "token", true);
    }

    private static OneBotEvent event() {
        return new OneBotEvent("message", "private", "friend", 10001L, 20002L, 0L,
                9001L, Instant.parse("2026-11-09T11:00:00Z"), "Alice",
                List.of(new Segment("text", "ping")), "");
    }

    private static final class FakeClient extends OneBot11Client {

        private final Consumer<OneBotEvent> listener;
        private boolean started;
        private boolean closed;

        FakeClient(AccountConfig config, Consumer<OneBotEvent> listener) {
            super(config.endpoint(), config.token(), listener);
            this.listener = listener;
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void awaitReady(Duration timeout) {
        }

        @Override
        public CompletableFuture<JsonNode> sendAction(String action, Map<String, Object> params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            closed = true;
        }

        void emit(OneBotEvent event) {
            listener.accept(event);
        }
    }
}
