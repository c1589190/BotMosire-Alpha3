package io.github.c1589190.bma3.qqofficial.send;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountClient;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountConfig;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountRegistry;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountRuntimeStatus;
import io.github.c1589190.bma3.qqofficial.credential.OfficialQqCredentialPathPolicy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialQqRegistrySendAccountsTest {

    @Test
    void disabledAccountsNeverCreateATransport() throws Exception {
        AtomicInteger transportFactories = new AtomicInteger();
        try (OfficialQqAccountRegistry registry = new OfficialQqAccountRegistry(
                context -> new FakeAccountClient(context.config().accountId()),
                event -> { },
                Map.of("SECRET", "synthetic-secret"),
                OfficialQqCredentialPathPolicy.none())) {
            registry.reconcile(List.of(
                    new OfficialQqAccountConfig("enabled", "app-1", "SECRET", null, true),
                    new OfficialQqAccountConfig("disabled", "app-2", "", null, false)));

            OfficialQqRegistrySendAccounts accounts = new OfficialQqRegistrySendAccounts(
                    registry, accountId -> {
                        transportFactories.incrementAndGet();
                        return new BoundTransport(accountId);
                    });

            assertThat(accounts.resolve("enabled")).get().satisfies(account -> {
                assertThat(account.enabled()).isTrue();
                assertThat(account.accountId()).isEqualTo("enabled");
                assertThat(account.transport().accountId()).isEqualTo("enabled");
            });
            assertThat(transportFactories).hasValue(1);

            assertThat(accounts.resolve("disabled")).get().satisfies(account -> {
                assertThat(account.enabled()).isFalse();
                assertThat(account.transport()).isNull();
            });
            assertThat(transportFactories).as("disabled 账号不得创建 transport").hasValue(1);
            assertThat(accounts.resolve("missing")).isEmpty();
        }
    }

    private record FakeAccountClient(String accountId) implements OfficialQqAccountClient {

        @Override
        public void start() {
        }

        @Override
        public OfficialQqAccountRuntimeStatus status() {
            return new OfficialQqAccountRuntimeStatus(
                    io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountState.READY,
                    true, false, java.time.Instant.now(), java.time.Instant.now(), null,
                    null, 0, io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountErrorKind.NONE,
                    "", "", "");
        }

        @Override
        public void close() {
        }
    }

    private record BoundTransport(String accountId) implements OfficialQqSendTransport {

        @Override
        public String sendGroup(String groupOpenid, OfficialQqSendMessage message) {
            return "";
        }

        @Override
        public String sendUser(String userOpenid, OfficialQqSendMessage message) {
            return "";
        }
    }
}
