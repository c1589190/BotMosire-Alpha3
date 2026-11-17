package io.mosire.bma3.app.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.mosire.bma3.app.channel.QqChannelRegistry.AuditEntry;
import io.mosire.bma3.app.channel.QqChannelRegistry.AuditResult;
import io.mosire.bma3.app.channel.QqChannelRegistry.BindingState;
import io.mosire.bma3.app.channel.QqChannelRegistry.Channel;
import io.mosire.bma3.app.channel.QqChannelRegistry.ChannelBinding;
import io.mosire.bma3.app.channel.QqChannelRegistry.ErrorCode;
import io.mosire.bma3.app.channel.QqChannelRegistry.IdentityScope;
import io.mosire.bma3.app.channel.QqChannelRegistry.MatchingMode;
import io.mosire.bma3.app.channel.QqChannelRegistry.RegistryException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QqChannelRegistryTest {

    private static final Instant NOW = Instant.parse("2026-11-17T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ONEBOT_GROUP = "qq_group/bot-1/955432692";
    private static final String ONEBOT_PRIVATE = "qq_private/bot-1/132843233";
    private static final String OFFICIAL_GROUP = "qq_official_group/app-1/group_openid_A";
    private static final String OFFICIAL_PRIVATE = "qq_official_private/app-1/user_openid_B";

    @TempDir
    Path tempDir;

    private final Set<String> knownAccounts = new HashSet<>();
    private final Set<String> observedSources = new HashSet<>();
    private final Set<String> availableAccounts = new HashSet<>();
    private QqChannelRegistry registry;

    @AfterEach
    void closeRegistry() {
        if (registry != null) {
            registry.close();
        }
    }

    @Test
    void bindCreatesActiveThreadAndRepeatsAreIdempotent() {
        known("onebot", "bot-1");
        observedSources.add(ONEBOT_GROUP);
        registry = open("channels.db");

        var first = registry.bind("thread.one", ONEBOT_GROUP, Channel.ONEBOT, "QQ群 测试群");

        assertThat(first.changed()).isTrue();
        assertThat(first.thread().threadId()).isEqualTo("thread.one");
        assertThat(first.thread().label()).isEqualTo("QQ群 测试群");
        assertThat(first.thread().matchingMode()).isEqualTo(MatchingMode.STRICT);
        assertThat(first.binding().channel()).isEqualTo(Channel.ONEBOT);
        assertThat(first.binding().accountId()).isEqualTo("bot-1");
        assertThat(first.binding().state()).isEqualTo(BindingState.ACTIVE);
        assertThat(first.binding().active()).isTrue();

        var repeated = registry.bind("thread.one", ONEBOT_GROUP, Channel.ONEBOT, "QQ群 测试群");

        assertThat(repeated.changed()).isFalse();
        assertThat(repeated.thread().revision()).isEqualTo(first.thread().revision());
        assertThat(repeated.binding().revision()).isEqualTo(first.binding().revision());
        assertThat(count("SELECT COUNT(*) FROM channel_binding")).isEqualTo(1);
        assertThat(registry.status("thread.one")).get()
                .extracting(status -> status.bindings().size()).isEqualTo(1);
    }

    @Test
    void changedDefaultOnlyAppliesToSubsequentlyCreatedThreads() {
        known("onebot", "bot-1");
        AtomicReference<MatchingMode> configured = new AtomicReference<>(MatchingMode.STRICT);
        registry = new QqChannelRegistry(tempDir.resolve("defaults.db"),
                (channel, account) -> knownAccounts.contains(channel.wireName() + ":" + account),
                observedSources::contains, FIXED_CLOCK, configured::get,
                (channel, account) -> true);

        registry.bind("old", ONEBOT_GROUP, Channel.ONEBOT, "");
        configured.set(MatchingMode.OFF);
        registry.bind("new", ONEBOT_PRIVATE, Channel.ONEBOT, "");

        assertThat(registry.status("old")).get()
                .satisfies(status -> assertThat(status.thread().matchingMode())
                        .isEqualTo(MatchingMode.STRICT));
        assertThat(registry.status("new")).get()
                .satisfies(status -> assertThat(status.thread().matchingMode())
                        .isEqualTo(MatchingMode.OFF));
    }

    @Test
    void prebindingIsPendingUntilObservationIsInjected() {
        known("onebot", "bot-1");
        registry = open("channels.db");

        var pending = registry.bind("thread.pending", ONEBOT_GROUP);
        assertThat(pending.binding().state()).isEqualTo(BindingState.PENDING_OBSERVATION);

        var marked = registry.markSourceObserved(ONEBOT_GROUP).orElseThrow();
        assertThat(marked.changed()).isTrue();
        assertThat(marked.binding().state()).isEqualTo(BindingState.ACTIVE);
        assertThat(registry.markSourceObserved(ONEBOT_GROUP).orElseThrow().changed()).isFalse();
    }

    @Test
    void knownAccountVerifierIsFailClosedAndNoDirectoryAssumptionIsMade() {
        registry = open("channels.db");

        expect(ErrorCode.ACCOUNT_UNKNOWN, () -> registry.bind("thread.unknown", ONEBOT_GROUP));

        assertThat(count("SELECT COUNT(*) FROM logical_thread")).isZero();
        assertThat(count("SELECT COUNT(*) FROM channel_binding")).isZero();
        assertThat(registry.audits(10))
                .anySatisfy(audit -> {
                    assertThat(audit.action()).isEqualTo("qq_thread_bind");
                    assertThat(audit.result()).isEqualTo(AuditResult.REJECTED);
                    assertThat(audit.errorCode()).isEqualTo(ErrorCode.ACCOUNT_UNKNOWN.name());
                });
    }

    @Test
    void invalidThreadRawChannelAccountAndObservedProviderFailClosed() {
        known("onebot", "bot-1");
        known("qq_official", "app-1");
        registry = open("channels.db");

        expect(ErrorCode.INVALID_THREAD_ID, () -> registry.bind("bad thread", ONEBOT_GROUP));
        expect(ErrorCode.INVALID_THREAD_ID,
                () -> registry.bind("x".repeat(129), ONEBOT_GROUP));
        expect(ErrorCode.INVALID_RAW_SOURCE,
                () -> registry.bind("thread.bad", "qq_group/bot-1/not-a-number"));
        expect(ErrorCode.INVALID_RAW_SOURCE,
                () -> registry.bind("thread.bad", "qq_group/bot-1/955432692/extra"));
        expect(ErrorCode.INVALID_RAW_SOURCE,
                () -> registry.bind("thread.bad", "unknown_platform/bot-1/12345"));
        expect(ErrorCode.INVALID_ACCOUNT_ID,
                () -> registry.bind("thread.bad", "qq_group/bad account/955432692"));
        expect(ErrorCode.CHANNEL_MISMATCH,
                () -> registry.bind("thread.bad", OFFICIAL_GROUP, Channel.ONEBOT));

        QqChannelRegistry brokenObservation = new QqChannelRegistry(
                tempDir.resolve("broken.db"),
                (channel, accountId) -> true,
                raw -> {
                    throw new IllegalStateException("observed lookup unavailable");
                },
                FIXED_CLOCK);
        try {
            RegistryException failure = expect(ErrorCode.OBSERVATION_UNAVAILABLE,
                    () -> brokenObservation.bind("thread.broken", ONEBOT_GROUP));
            assertThat(failure.getMessage()).doesNotContain("token", "secret", "正文");
        } finally {
            brokenObservation.close();
        }
    }

    @Test
    void safeSegmentsAndPositiveLongIdsMatchExistingSourceBoundaries() {
        known("onebot", "机器人.账号");
        known("qq_official", "官方-账号");
        observedSources.add("qq_group/机器人.账号/1");
        observedSources.add("qq_official_group/官方-账号/群_openid-一");
        observedSources.add("qq_private/机器人.账号/9223372036854775807");
        registry = open("channels.db");

        var shortNumeric = registry.bind("thread.safe.one", "qq_group/机器人.账号/1");
        assertThat(shortNumeric.binding().accountId()).isEqualTo("机器人.账号");
        assertThat(shortNumeric.binding().state()).isEqualTo(BindingState.ACTIVE);

        var unicodeOfficial = registry.bind("thread.safe.official",
                "qq_official_group/官方-账号/群_openid-一");
        assertThat(unicodeOfficial.binding().channel()).isEqualTo(Channel.QQ_OFFICIAL);

        var maxLong = registry.bind("thread.safe.max",
                "qq_private/机器人.账号/9223372036854775807");
        assertThat(maxLong.binding().state()).isEqualTo(BindingState.ACTIVE);

        expect(ErrorCode.INVALID_RAW_SOURCE,
                () -> registry.bind("thread.safe.bad", "qq_private/机器人.账号/0"));
        expect(ErrorCode.INVALID_RAW_SOURCE,
                () -> registry.bind("thread.safe.bad", "qq_private/机器人.账号/-1"));
        expect(ErrorCode.INVALID_RAW_SOURCE,
                () -> registry.bind("thread.safe.bad",
                        "qq_private/机器人.账号/9223372036854775808"));
        expect(ErrorCode.INVALID_ACCOUNT_ID,
                () -> registry.bind("thread.safe.bad", "qq_private/机 器人/1"));
        expect(ErrorCode.INVALID_ACCOUNT_ID,
                () -> registry.bind("thread.safe.bad", "qq_private/机\u200B器人/1"));
        expect(ErrorCode.INVALID_RAW_SOURCE,
                () -> registry.bind("thread.safe.bad", "qq_official_group/官方-账号/群\\id"));
        RegistryException pipeAccount = expect(ErrorCode.INVALID_ACCOUNT_ID,
                () -> registry.bind("thread.safe.bad", "qq_private/账号|pipe/1"));
        assertThat(pipeAccount.getMessage()).doesNotContain("|");
        expect(ErrorCode.INVALID_RAW_SOURCE,
                () -> registry.bind("thread.safe.bad", "qq_official_group/官方-账号/群|pipe"));
        expect(ErrorCode.ACCOUNT_UNKNOWN,
                () -> registry.bind("thread.safe.unknown", "qq_private/未知账号/1"));

        registry.bindIdentity("thread.safe.one", Channel.ONEBOT, "1", "统一身份",
                IdentityScope.thread("thread.safe.one"));
        registry.bindIdentity("thread.safe.official", Channel.QQ_OFFICIAL, "成员_openid-一",
                "统一身份", IdentityScope.thread("thread.safe.official"));
        assertThat(registry.identities("thread.safe.one")).hasSize(1);
        assertThat(registry.identities("thread.safe.official")).hasSize(1);
    }

    @Test
    void defaultAvailabilityIsFailClosedWhenNoProviderIsInjected() {
        known("onebot", "bot-1");
        observedSources.add(ONEBOT_GROUP);
        registry = new QqChannelRegistry(tempDir.resolve("channels.db"),
                (channel, accountId) -> true,
                observedSources::contains,
                FIXED_CLOCK,
                MatchingMode.STRICT);

        registry.bind("thread.default.availability", ONEBOT_GROUP);

        assertThat(registry.status("thread.default.availability").orElseThrow().display()).isEmpty();
        assertThat(registry.sourceStatus(ONEBOT_GROUP).orElseThrow().display()).isEmpty();
    }

    @Test
    void preferredAvailabilityFallsBackRecoversAndAllowsNoDisplayWhenAllDown() {
        known("onebot", "bot-1");
        known("qq_official", "app-1");
        observedSources.add(ONEBOT_GROUP);
        observedSources.add(OFFICIAL_GROUP);
        availableAccounts.add("onebot:bot-1");
        availableAccounts.add("qq_official:app-1");
        Set<String> seenAccounts = new HashSet<>();
        registry = new QqChannelRegistry(tempDir.resolve("channels.db"),
                (channel, accountId) -> knownAccounts.contains(
                        channel.wireName() + ":" + accountId),
                observedSources::contains,
                FIXED_CLOCK,
                MatchingMode.STRICT,
                (channel, accountId) -> {
                    seenAccounts.add(channel.wireName() + ":" + accountId);
                    return availableAccounts.contains(channel.wireName() + ":" + accountId);
                });
        registry.bind("thread.availability", ONEBOT_GROUP);
        registry.bind("thread.availability", OFFICIAL_GROUP);
        registry.setPreferredChannel("thread.availability", Channel.QQ_OFFICIAL);

        var preferred = registry.status("thread.availability").orElseThrow().display().orElseThrow();
        assertThat(preferred.channel()).isEqualTo(Channel.QQ_OFFICIAL);
        assertThat(preferred.preferred()).isTrue();
        assertThat(preferred.fallback()).isFalse();
        assertThat(seenAccounts).contains("onebot:bot-1", "qq_official:app-1");

        availableAccounts.remove("qq_official:app-1");
        var fallback = registry.status("thread.availability").orElseThrow().display().orElseThrow();
        assertThat(fallback.channel()).isEqualTo(Channel.ONEBOT);
        assertThat(fallback.fallback()).isTrue();
        assertThat(fallback.enabledChannels()).containsExactly(Channel.ONEBOT);

        availableAccounts.add("qq_official:app-1");
        var recovered = registry.status("thread.availability").orElseThrow().display().orElseThrow();
        assertThat(recovered.channel()).isEqualTo(Channel.QQ_OFFICIAL);
        assertThat(recovered.preferred()).isTrue();
        assertThat(recovered.fallback()).isFalse();

        availableAccounts.clear();
        assertThat(registry.status("thread.availability").orElseThrow().display()).isEmpty();
        assertThat(registry.sourceStatus(OFFICIAL_GROUP).orElseThrow().display()).isEmpty();
        assertThat(registry.status("thread.availability").orElseThrow().toString())
                .doesNotContain("token", "secret", "Bearer");
    }

    @Test
    void injectionStatusSeparatesConfiguredGatesFromPendingAndAvailability() {
        known("onebot", "bot-1");
        known("qq_official", "app-1");
        observedSources.add(ONEBOT_GROUP);
        observedSources.add(OFFICIAL_GROUP);
        availableAccounts.add("onebot:bot-1");
        registry = open("channels.db");
        registry.bind("thread.effective", ONEBOT_GROUP);
        registry.bind("thread.effective", OFFICIAL_GROUP);
        registry.setPreferredChannel("thread.effective", Channel.QQ_OFFICIAL);

        var status = registry.status("thread.effective").orElseThrow();
        assertThat(status.configuredInjection()).isTrue();
        assertThat(status.effectiveInjection()).isTrue();
        assertThat(status.display().orElseThrow().channel()).isEqualTo(Channel.ONEBOT);
        assertThat(status.display().orElseThrow().fallback()).isTrue();

        var officialDown = registry.sourceStatus(OFFICIAL_GROUP).orElseThrow();
        assertThat(officialDown.configuredInjection()).isTrue();
        assertThat(officialDown.effectiveInjection()).isFalse();
        assertThat(officialDown.display().orElseThrow().channel()).isEqualTo(Channel.ONEBOT);

        registry.setSourceInjection(OFFICIAL_GROUP, false);
        var officialGateOff = registry.sourceStatus(OFFICIAL_GROUP).orElseThrow();
        assertThat(officialGateOff.configuredInjection()).isFalse();
        assertThat(officialGateOff.effectiveInjection()).isFalse();

        availableAccounts.clear();
        var allDown = registry.status("thread.effective").orElseThrow();
        assertThat(allDown.configuredInjection()).isTrue();
        assertThat(allDown.effectiveInjection()).isFalse();
        assertThat(allDown.display()).isEmpty();

        // 仅 prebind、尚未观察时：gate 配置为 true，但实际注入必须为 false。
        var pending = registry.bind("thread.effective.pending", OFFICIAL_PRIVATE);
        assertThat(pending.binding().state()).isEqualTo(BindingState.PENDING_OBSERVATION);
        var pendingStatus = registry.status("thread.effective.pending").orElseThrow();
        assertThat(pendingStatus.configuredInjection()).isTrue();
        assertThat(pendingStatus.effectiveInjection()).isFalse();
        assertThat(pendingStatus.display()).isEmpty();
        var pendingSource = registry.sourceStatus(OFFICIAL_PRIVATE).orElseThrow();
        assertThat(pendingSource.configuredInjection()).isTrue();
        assertThat(pendingSource.effectiveInjection()).isFalse();
    }

    @Test
    void injectionAuditUsesActualEffectiveStateNotOnlyRequestedGate() {
        known("onebot", "bot-1");
        known("qq_official", "app-1");
        observedSources.add(ONEBOT_GROUP);
        observedSources.add(OFFICIAL_GROUP);
        availableAccounts.add("onebot:bot-1");
        registry = open("channels.db");
        registry.bind("thread.audit.effective", ONEBOT_GROUP);
        registry.bind("thread.audit.effective", OFFICIAL_GROUP);

        availableAccounts.clear();
        registry.setThreadInjection("thread.audit.effective", false);
        registry.setThreadInjection("thread.audit.effective", true);
        AuditEntry allDownAudit = registry.audits(1).getFirst();
        assertThat(allDownAudit.action()).isEqualTo("qq_thread_set_injection");
        assertThat(allDownAudit.enabled()).isTrue();
        assertThat(allDownAudit.effectiveInjection()).isFalse();

        availableAccounts.add("onebot:bot-1");
        registry.setThreadInjection("thread.audit.effective", false);
        registry.setThreadInjection("thread.audit.effective", true);
        AuditEntry availableAudit = registry.audits(1).getFirst();
        assertThat(availableAudit.enabled()).isTrue();
        assertThat(availableAudit.effectiveInjection()).isTrue();

        registry.setSourceInjection(ONEBOT_GROUP, false);
        registry.setSourceInjection(ONEBOT_GROUP, true);
        AuditEntry sourceAudit = registry.audits(1).getFirst();
        assertThat(sourceAudit.enabled()).isTrue();
        assertThat(sourceAudit.effectiveInjection()).isTrue();
    }

    @Test
    void observationNoOpsDoNotGrowAuditLog() {
        known("onebot", "bot-1");
        registry = open("channels.db");
        registry.bind("thread.observe.audit", ONEBOT_GROUP);
        assertThat(registry.markSourceObserved(ONEBOT_GROUP).orElseThrow().changed()).isTrue();
        long afterTransition = count("SELECT COUNT(*) FROM channel_audit");

        for (int i = 0; i < 5; i++) {
            assertThat(registry.markSourceObserved(ONEBOT_GROUP).orElseThrow().changed()).isFalse();
            assertThat(registry.refreshObservation(ONEBOT_GROUP).orElseThrow().changed()).isFalse();
        }
        assertThat(count("SELECT COUNT(*) FROM channel_audit")).isEqualTo(afterTransition);
    }

    @Test
    void rawSourceCannotBelongToTwoActiveThreadsAndFailedBindRollsBack() {
        known("onebot", "bot-1");
        observedSources.add(ONEBOT_GROUP);
        registry = open("channels.db");
        registry.bind("thread.a", ONEBOT_GROUP);

        expect(ErrorCode.SOURCE_ALREADY_BOUND,
                () -> registry.bind("thread.b", ONEBOT_GROUP));

        assertThat(registry.thread("thread.b")).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM channel_binding WHERE active = 1")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM logical_thread")).isEqualTo(1);
        assertThat(registry.audits(20))
                .anySatisfy(audit -> {
                    assertThat(audit.result()).isEqualTo(AuditResult.REJECTED);
                    assertThat(audit.errorCode()).isEqualTo(ErrorCode.SOURCE_ALREADY_BOUND.name());
                });
    }

    @Test
    void oneBindingPerThreadPerChannelAndDifferentChannelsCanCoexist() {
        known("onebot", "bot-1");
        known("qq_official", "app-1");
        observedSources.add(ONEBOT_GROUP);
        observedSources.add(ONEBOT_PRIVATE);
        observedSources.add(OFFICIAL_GROUP);
        registry = open("channels.db");

        registry.bind("thread.multi", ONEBOT_GROUP, Channel.ONEBOT, "");
        expect(ErrorCode.THREAD_CHANNEL_ALREADY_BOUND,
                () -> registry.bind("thread.multi", ONEBOT_PRIVATE, Channel.ONEBOT, ""));

        var secondChannel = registry.bind("thread.multi", OFFICIAL_GROUP, Channel.QQ_OFFICIAL, "");
        assertThat(secondChannel.changed()).isTrue();
        assertThat(registry.bindings("thread.multi"))
                .extracting(ChannelBinding::channel)
                .containsExactly(Channel.ONEBOT, Channel.QQ_OFFICIAL);
        assertThat(count("SELECT COUNT(*) FROM channel_binding WHERE thread_id = 'thread.multi' "
                + "AND active = 1")).isEqualTo(2);
    }

    @Test
    void databaseUniqueIndexRejectsDuplicateThreadChannelBinding() throws Exception {
        known("onebot", "bot-1");
        observedSources.add(ONEBOT_GROUP);
        observedSources.add(ONEBOT_PRIVATE);
        registry = open("channels.db");
        registry.bind("thread.unique", ONEBOT_GROUP);

        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO channel_binding(
                       raw_source, thread_id, channel, account_id, injection_enabled, state,
                       active, revision, created_at, updated_at
                     ) VALUES (?, ?, ?, ?, 1, 'ACTIVE', 1, 1, ?, ?)
                     """)) {
            statement.setString(1, ONEBOT_PRIVATE);
            statement.setString(2, "thread.unique");
            statement.setString(3, "onebot");
            statement.setString(4, "bot-1");
            statement.setString(5, NOW.toString());
            statement.setString(6, NOW.toString());
            assertThat(catchThrowable(statement::executeUpdate)).isNotNull();
        }
        assertThat(count("SELECT COUNT(*) FROM channel_binding WHERE thread_id = 'thread.unique' "
                + "AND channel = 'onebot' AND active = 1")).isEqualTo(1);
    }

    @Test
    void unbindIsIdempotentKeepsRowsAndAllowsRebinding() {
        known("onebot", "bot-1");
        observedSources.add(ONEBOT_GROUP);
        registry = open("channels.db");
        var bound = registry.bind("thread.life", ONEBOT_GROUP);

        var first = registry.unbind("thread.life", ONEBOT_GROUP);
        assertThat(first.changed()).isTrue();
        assertThat(registry.thread("thread.life")).isPresent();
        assertThat(registry.binding(ONEBOT_GROUP)).get()
                .extracting(ChannelBinding::active, ChannelBinding::state)
                .containsExactly(false, BindingState.UNBOUND);
        assertThat(registry.unbind("thread.life", ONEBOT_GROUP).changed()).isFalse();

        var rebound = registry.bind("thread.life", ONEBOT_GROUP);
        assertThat(rebound.changed()).isTrue();
        assertThat(rebound.binding().active()).isTrue();
        assertThat(rebound.thread().revision()).isGreaterThan(bound.thread().revision());
        assertThat(count("SELECT COUNT(*) FROM channel_binding")).isEqualTo(1);
        assertThat(registry.audits(20))
                .extracting(AuditEntry::action)
                .contains("qq_thread_bind", "qq_thread_unbind");
    }

    @Test
    void unboundSourceGateAndThreadGatePersistIndependentlyAcrossRestart() {
        known("onebot", "bot-1");
        observedSources.add(ONEBOT_GROUP);
        registry = open("channels.db");

        var unboundGate = registry.setSourceInjection(ONEBOT_GROUP, false);
        assertThat(unboundGate.changed()).isTrue();
        assertThat(unboundGate.binding()).isEmpty();
        assertThat(unboundGate.thread()).isEmpty();
        assertThat(registry.sourceInjectionEnabled(ONEBOT_GROUP)).isFalse();

        var bound = registry.bind("thread.gates", ONEBOT_GROUP);
        assertThat(bound.binding().injectionEnabled()).isFalse();
        assertThat(registry.sourceStatus(ONEBOT_GROUP).orElseThrow().effectiveInjection()).isFalse();

        registry.setThreadInjection("thread.gates", false);
        registry.setSourceInjection(ONEBOT_GROUP, true);
        var whileThreadOff = registry.sourceStatus(ONEBOT_GROUP).orElseThrow();
        assertThat(whileThreadOff.effectiveInjection()).isFalse();
        assertThat(whileThreadOff.sourceGate()).get()
                .extracting(QqChannelRegistry.SourceGate::injectionEnabled).isEqualTo(true);

        long revision = registry.thread("thread.gates").orElseThrow().revision();
        registry.close();
        registry = open("channels.db");

        assertThat(registry.thread("thread.gates").orElseThrow().injectionEnabled()).isFalse();
        assertThat(registry.thread("thread.gates").orElseThrow().revision())
                .isGreaterThanOrEqualTo(revision);
        assertThat(registry.sourceInjectionEnabled(ONEBOT_GROUP)).isTrue();
        assertThat(registry.sourceStatus(ONEBOT_GROUP).orElseThrow().effectiveInjection())
                .isFalse();
    }

    @Test
    void preferredChannelFallsBackWhenItsSourceInjectionIsOff() {
        known("onebot", "bot-1");
        known("qq_official", "app-1");
        observedSources.add(ONEBOT_GROUP);
        observedSources.add(OFFICIAL_GROUP);
        availableAccounts.add("onebot:bot-1");
        availableAccounts.add("qq_official:app-1");
        registry = open("channels.db");
        registry.bind("thread.display", ONEBOT_GROUP);
        registry.bind("thread.display", OFFICIAL_GROUP);
        registry.setPreferredChannel("thread.display", Channel.QQ_OFFICIAL);

        var preferred = registry.status("thread.display").orElseThrow().display().orElseThrow();
        assertThat(preferred.channel()).isEqualTo(Channel.QQ_OFFICIAL);
        assertThat(preferred.preferred()).isTrue();
        assertThat(preferred.fallback()).isFalse();

        registry.setSourceInjection(OFFICIAL_GROUP, false);
        var fallback = registry.status("thread.display").orElseThrow().display().orElseThrow();
        assertThat(fallback.channel()).isEqualTo(Channel.ONEBOT);
        assertThat(fallback.preferred()).isFalse();
        assertThat(fallback.fallback()).isTrue();
        assertThat(fallback.enabledChannels()).containsExactly(Channel.ONEBOT);

        registry.close();
        registry = open("channels.db");
        assertThat(registry.thread("thread.display").orElseThrow().preferredChannel())
                .isEqualTo(Channel.QQ_OFFICIAL);
    }

    @Test
    void matchingModeDefaultOnlyAppliesToNewThreadsAndPersists() {
        known("onebot", "bot-1");
        observedSources.add(ONEBOT_GROUP);
        observedSources.add(ONEBOT_PRIVATE);
        registry = open("channels.db", MatchingMode.LOOSE);

        registry.bind("thread.a", ONEBOT_GROUP);
        assertThat(registry.thread("thread.a").orElseThrow().matchingMode())
                .isEqualTo(MatchingMode.LOOSE);
        registry.setMatchingMode("thread.a", MatchingMode.OFF);

        registry.close();
        registry = open("channels.db", MatchingMode.STRICT);
        assertThat(registry.thread("thread.a").orElseThrow().matchingMode())
                .isEqualTo(MatchingMode.OFF);
        registry.bind("thread.b", ONEBOT_PRIVATE);
        assertThat(registry.thread("thread.b").orElseThrow().matchingMode())
                .isEqualTo(MatchingMode.STRICT);
        assertThat(registry.setMatchingMode("thread.a", MatchingMode.OFF).changed()).isFalse();
    }

    @Test
    void identityMappingsAreScopedIdempotentAndConflictSafe() {
        known("onebot", "bot-1");
        known("qq_official", "app-1");
        observedSources.add(ONEBOT_GROUP);
        observedSources.add(OFFICIAL_GROUP);
        registry = open("channels.db");
        registry.bind("thread.identity", ONEBOT_GROUP);
        registry.bind("thread.identity", OFFICIAL_GROUP);

        var first = registry.bindIdentity("thread.identity", Channel.ONEBOT, "132843233",
                "person-1", IdentityScope.thread("thread.identity"));
        assertThat(first.changed()).isTrue();
        assertThat(registry.bindIdentity("thread.identity", Channel.ONEBOT, "132843233",
                "person-1", IdentityScope.thread("thread.identity")).changed()).isFalse();
        expect(ErrorCode.IDENTITY_CONFLICT,
                () -> registry.bindIdentity("thread.identity", Channel.ONEBOT, "132843234",
                        "person-1", IdentityScope.thread("thread.identity")));

        var official = registry.bindIdentity("thread.identity", Channel.QQ_OFFICIAL,
                "member_openid_1", "person-1", IdentityScope.thread("thread.identity"));
        assertThat(official.changed()).isTrue();
        assertThat(registry.identities("thread.identity")).hasSize(2);

        var sourceScoped = registry.bindIdentity("thread.identity", Channel.ONEBOT,
                "132843234", "person-2", IdentityScope.source(ONEBOT_GROUP));
        assertThat(sourceScoped.changed()).isTrue();

        assertThat(registry.unbindIdentity("thread.identity", Channel.ONEBOT, "132843233",
                IdentityScope.thread("thread.identity")).changed()).isTrue();
        assertThat(registry.unbindIdentity("thread.identity", Channel.ONEBOT, "132843233",
                IdentityScope.thread("thread.identity")).changed()).isFalse();
        assertThat(registry.identities("thread.identity")).hasSize(2);

        registry.close();
        registry = open("channels.db");
        assertThat(registry.identities("thread.identity")).hasSize(2);
    }

    @Test
    void conflictingIdentityAcrossThreadAndSourceScopesIsRejected() {
        known("onebot", "bot-1");
        observedSources.add(ONEBOT_GROUP);
        registry = open("channels.db");
        registry.bind("thread.cross.scope", ONEBOT_GROUP);
        registry.bindIdentity("thread.cross.scope", Channel.ONEBOT, "132843233", "person-1",
                IdentityScope.thread("thread.cross.scope"));

        expect(ErrorCode.IDENTITY_CONFLICT,
                () -> registry.bindIdentity("thread.cross.scope", Channel.ONEBOT, "132843233",
                        "person-2", IdentityScope.source(ONEBOT_GROUP)));
        // 严格模式：同一 user 不允许在其他 scope 再建 active 映射（即使 canonical 相同）。
        expect(ErrorCode.IDENTITY_CONFLICT,
                () -> registry.bindIdentity("thread.cross.scope", Channel.ONEBOT, "132843233",
                        "person-1", IdentityScope.source(ONEBOT_GROUP)));
        // 同一 canonical 也不允许在同一 thread/channel 跨 scope 映射到另一个 user。
        expect(ErrorCode.IDENTITY_CONFLICT,
                () -> registry.bindIdentity("thread.cross.scope", Channel.ONEBOT, "132843234",
                        "person-1", IdentityScope.source(ONEBOT_GROUP)));
    }

    @Test
    void sourceScopedIdentityIsDeactivatedOnUnbindAndCanRebindWithNewCanonical() {
        known("onebot", "bot-1");
        observedSources.add(ONEBOT_GROUP);
        availableAccounts.add("onebot:bot-1");
        registry = open("channels.db");
        registry.bind("thread.source.identity", ONEBOT_GROUP);
        registry.bindIdentity("thread.source.identity", Channel.ONEBOT, "132843233", "person-old",
                IdentityScope.source(ONEBOT_GROUP));
        assertThat(registry.identities("thread.source.identity")).hasSize(1);

        registry.unbind("thread.source.identity", ONEBOT_GROUP);
        assertThat(registry.identities("thread.source.identity")).isEmpty();

        registry.bind("thread.source.identity", ONEBOT_GROUP);
        var rebound = registry.bindIdentity("thread.source.identity", Channel.ONEBOT,
                "132843233", "person-new", IdentityScope.source(ONEBOT_GROUP));
        assertThat(rebound.changed()).isTrue();
        assertThat(rebound.identity().canonicalUserId()).isEqualTo("person-new");
        assertThat(registry.identities("thread.source.identity")).hasSize(1);
    }

    @Test
    void identityRequiresChannelBindingAndValidScope() {
        known("onebot", "bot-1");
        known("qq_official", "app-1");
        observedSources.add(ONEBOT_GROUP);
        observedSources.add(OFFICIAL_GROUP);
        registry = open("channels.db");
        registry.bind("thread.identity", ONEBOT_GROUP);

        expect(ErrorCode.THREAD_CHANNEL_NOT_BOUND,
                () -> registry.bindIdentity("thread.identity", Channel.QQ_OFFICIAL,
                        "member_openid_1", "person-1", IdentityScope.thread("thread.identity")));
        expect(ErrorCode.IDENTITY_SCOPE_MISMATCH,
                () -> registry.bindIdentity("thread.identity", Channel.ONEBOT, "132843233",
                        "person-1", IdentityScope.source(OFFICIAL_GROUP)));
        expect(ErrorCode.IDENTITY_SCOPE_MISMATCH,
                () -> registry.bindIdentity("thread.identity", Channel.ONEBOT, "132843233",
                        "person-1", IdentityScope.thread("another.thread")));
        expect(ErrorCode.INVALID_IDENTITY,
                () -> registry.bindIdentity("thread.identity", Channel.ONEBOT, "not-a-qq",
                        "person-1", IdentityScope.thread("thread.identity")));
        expect(ErrorCode.INVALID_IDENTITY,
                () -> registry.bindIdentity("thread.identity", Channel.ONEBOT, "132843233",
                        "", IdentityScope.thread("thread.identity")));
        RegistryException pipeIdentity = expect(ErrorCode.INVALID_IDENTITY,
                () -> registry.bindIdentity("thread.identity", Channel.QQ_OFFICIAL,
                        "member|openid", "person-1", IdentityScope.thread("thread.identity")));
        assertThat(pipeIdentity.getMessage()).doesNotContain("|");
        expect(ErrorCode.INVALID_IDENTITY,
                () -> registry.bindIdentity("thread.identity", Channel.ONEBOT, "132843233",
                        "canonical|pipe", IdentityScope.thread("thread.identity")));
    }

    @Test
    void auditHasEnabledEffectiveStateAndNeverStoresInvalidSecretLikeInput() {
        known("onebot", "bot-1");
        observedSources.add(ONEBOT_GROUP);
        registry = open("channels.db");
        registry.bind("thread.audit", ONEBOT_GROUP);
        registry.setThreadInjection("thread.audit", false);
        registry.setSourceInjection(ONEBOT_GROUP, false);

        String secret = "TOKEN_SECRET_SHOULD_NOT_PERSIST";
        expect(ErrorCode.INVALID_RAW_SOURCE,
                () -> registry.setSourceInjection("qq_group/bot-1/" + secret, true));

        List<AuditEntry> audits = registry.audits(50);
        assertThat(audits)
                .anySatisfy(audit -> {
                    assertThat(audit.action()).isEqualTo("qq_thread_set_injection");
                    assertThat(audit.enabled()).isFalse();
                    assertThat(audit.effectiveInjection()).isFalse();
                });
        assertThat(audits.toString()).doesNotContain(secret).doesNotContain("body").doesNotContain(
                "credential");
        assertThat(audits)
                .filteredOn(audit -> audit.result() == AuditResult.REJECTED)
                .allSatisfy(audit -> assertThat(audit.rawSource()).isNull());
    }

    @Test
    void concurrentBindAttemptsAllowExactlyOneActiveRawBinding() throws Exception {
        known("onebot", "bot-1");
        observedSources.add(ONEBOT_GROUP);
        registry = open("channels.db");
        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                String threadId = "thread.concurrent." + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        registry.bind(threadId, ONEBOT_GROUP);
                        return true;
                    } catch (RegistryException failure) {
                        assertThat(failure.code()).isEqualTo(ErrorCode.SOURCE_ALREADY_BOUND);
                        return false;
                    }
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT COUNT(*) FROM channel_binding WHERE raw_source = '"
                + ONEBOT_GROUP + "' AND active = 1")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM logical_thread")).isEqualTo(1);
    }

    @Test
    void activeBindingsKeysetPaginationCovers501RowsAndSkipsUnbound() throws Exception {
        registry = open("channels.db");
        registry.close();
        insertActiveBindingFixtures(502);

        registry = open("channels.db");
        String unboundRaw = rawForPageIndex(0);
        registry.unbind("thread.page.000", unboundRaw);

        var first = registry.activeBindings(null, 500);
        assertThat(first.bindings()).hasSize(500);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.next()).isPresent();
        assertThat(first.items()).isEqualTo(first.bindings());

        var second = registry.activeBindings(first.next().orElseThrow(), 500);
        assertThat(second.bindings()).hasSize(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.next()).isEmpty();

        List<String> paged = new ArrayList<>();
        first.bindings().forEach(binding -> paged.add(binding.rawSource()));
        second.bindings().forEach(binding -> paged.add(binding.rawSource()));
        List<String> expected = new ArrayList<>();
        for (int i = 1; i < 502; i++) {
            expected.add(rawForPageIndex(i));
        }
        expected.sort(String::compareTo);
        assertThat(paged).containsExactlyElementsOf(expected);
        assertThat(paged).doesNotHaveDuplicates().doesNotContain(unboundRaw);
        assertThat(first.toString()).doesNotContain("token", "secret", "正文");
    }

    @Test
    void fullStateSurvivesRestart() {
        known("onebot", "bot-1");
        known("qq_official", "app-1");
        observedSources.add(ONEBOT_GROUP);
        observedSources.add(OFFICIAL_GROUP);
        registry = open("channels.db", MatchingMode.LOOSE);
        registry.bind("thread.restart", ONEBOT_GROUP, Channel.ONEBOT, "跨渠道会话");
        registry.bind("thread.restart", OFFICIAL_GROUP, Channel.QQ_OFFICIAL, "跨渠道会话");
        registry.setPreferredChannel("thread.restart", Channel.QQ_OFFICIAL);
        registry.setSourceInjection(ONEBOT_GROUP, false);
        registry.bindIdentity("thread.restart", Channel.ONEBOT, "132843233", "person-9",
                IdentityScope.thread("thread.restart"));
        long revision = registry.thread("thread.restart").orElseThrow().revision();

        registry.close();
        registry = open("channels.db", MatchingMode.STRICT);

        var thread = registry.thread("thread.restart").orElseThrow();
        assertThat(thread.label()).isEqualTo("跨渠道会话");
        assertThat(thread.preferredChannel()).isEqualTo(Channel.QQ_OFFICIAL);
        assertThat(thread.matchingMode()).isEqualTo(MatchingMode.LOOSE);
        assertThat(thread.revision()).isEqualTo(revision);
        assertThat(registry.bindings("thread.restart")).hasSize(2);
        assertThat(registry.identities("thread.restart")).hasSize(1);
        assertThat(registry.sourceInjectionEnabled(ONEBOT_GROUP)).isFalse();
        assertThat(registry.sourceStatus(ONEBOT_GROUP).orElseThrow().effectiveInjection()).isFalse();
    }

    @Test
    void migratesSpecV1SchemaTransactionallyAndKeepsExistingRows() throws Exception {
        createV1Database();
        known("onebot", "bot-1");
        observedSources.add(ONEBOT_GROUP);
        availableAccounts.add("onebot:bot-1");

        registry = open("channels.db");

        assertThat(userVersion()).isEqualTo(QqChannelRegistry.SCHEMA_VERSION);
        assertThat(columnNames("logical_thread")).contains("injection_enabled");
        assertThat(columnNames("channel_binding"))
                .contains("state", "active", "injection_enabled", "revision");
        assertThat(columnNames("channel_identity_binding")).contains("active", "revision");
        assertThat(columnNames("channel_audit"))
                .contains("channel", "enabled", "effective_injection", "changed", "revision");
        assertThat(columnNames("source_injection_gate"))
                .contains("raw_source", "injection_enabled");

        var migratedBinding = registry.binding(ONEBOT_GROUP).orElseThrow();
        assertThat(migratedBinding.state()).isEqualTo(BindingState.ACTIVE);
        assertThat(migratedBinding.active()).isTrue();
        assertThat(migratedBinding.injectionEnabled()).isTrue();
        assertThat(registry.thread("thread.legacy").orElseThrow().injectionEnabled()).isTrue();
        assertThat(registry.audits(10)).anySatisfy(audit -> {
            assertThat(audit.action()).isEqualTo("legacy_bind");
            assertThat(audit.result()).isEqualTo(AuditResult.OK);
            assertThat(audit.changed()).isFalse();
        });
        assertThat(registry.status("thread.legacy").orElseThrow().effectiveInjection()).isTrue();
    }

    @Test
    void failedV1MigrationRollsBackAllSchemaChangesAndUserVersion() throws Exception {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 1");
            statement.execute("CREATE VIEW channel_audit AS SELECT 1 AS id");
        }

        Throwable failure = catchThrowable(() -> new QqChannelRegistry(
                tempDir.resolve("channels.db"),
                (channel, accountId) -> true,
                observedSources::contains,
                FIXED_CLOCK,
                MatchingMode.STRICT,
                (channel, accountId) -> true));

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(userVersion()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sqlite_master "
                + "WHERE type = 'table' AND name = 'logical_thread'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM sqlite_master "
                + "WHERE type = 'table' AND name = 'source_injection_gate'")).isZero();
    }

    // ------------------------------------------------------------------

    private QqChannelRegistry open(String fileName) {
        return open(fileName, MatchingMode.STRICT);
    }

    private QqChannelRegistry open(String fileName, MatchingMode defaultMode) {
        return new QqChannelRegistry(tempDir.resolve(fileName),
                (channel, accountId) -> knownAccounts.contains(
                        channel.wireName() + ":" + accountId),
                observedSources::contains,
                FIXED_CLOCK,
                defaultMode,
                (channel, accountId) -> availableAccounts.contains(
                        channel.wireName() + ":" + accountId));
    }

    private void known(String channel, String accountId) {
        knownAccounts.add(channel + ":" + accountId);
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("channels.db"));
    }

    private long count(String sql) {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getLong(1) : 0L;
        } catch (Exception failure) {
            throw new AssertionError("SQL failed: " + sql, failure);
        }
    }

    private void insertActiveBindingFixtures(int count) throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement threadInsert = connection.prepareStatement("""
                         INSERT INTO logical_thread(
                           thread_id, label, preferred_channel, matching_mode, injection_enabled,
                           revision, created_at, updated_at
                         ) VALUES (?, '', NULL, 'STRICT', 1, 1, ?, ?)
                         """);
                 PreparedStatement bindingInsert = connection.prepareStatement("""
                         INSERT INTO channel_binding(
                           raw_source, thread_id, channel, account_id, injection_enabled, state,
                           active, revision, created_at, updated_at
                         ) VALUES (?, ?, 'onebot', 'bot-1', 1, 'ACTIVE', 1, 1, ?, ?)
                         """)) {
                for (int i = 0; i < count; i++) {
                    String threadId = String.format("thread.page.%03d", i);
                    threadInsert.setString(1, threadId);
                    threadInsert.setString(2, NOW.toString());
                    threadInsert.setString(3, NOW.toString());
                    threadInsert.addBatch();

                    bindingInsert.setString(1, rawForPageIndex(i));
                    bindingInsert.setString(2, threadId);
                    bindingInsert.setString(3, NOW.toString());
                    bindingInsert.setString(4, NOW.toString());
                    bindingInsert.addBatch();
                }
                threadInsert.executeBatch();
                bindingInsert.executeBatch();
            }
            connection.commit();
        }
    }

    private static String rawForPageIndex(int index) {
        return "qq_private/bot-1/" + (50_000 + index);
    }

    private void createV1Database() throws Exception {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 1");
            statement.execute("""
                    CREATE TABLE logical_thread (
                      thread_id TEXT PRIMARY KEY,
                      label TEXT,
                      preferred_channel TEXT,
                      matching_mode TEXT,
                      revision INTEGER,
                      created_at TEXT,
                      updated_at TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE channel_binding (
                      raw_source TEXT PRIMARY KEY,
                      thread_id TEXT,
                      channel TEXT,
                      account_id TEXT,
                      injection_enabled INTEGER,
                      created_at TEXT,
                      updated_at TEXT,
                      revision INTEGER
                    )
                    """);
            statement.execute("""
                    CREATE TABLE channel_identity_binding (
                      thread_id TEXT,
                      channel TEXT,
                      channel_user_id TEXT,
                      canonical_user_id TEXT,
                      scope TEXT,
                      revision INTEGER,
                      created_at TEXT,
                      updated_at TEXT,
                      PRIMARY KEY (thread_id, channel, channel_user_id, scope)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE channel_audit (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      action TEXT,
                      thread_id TEXT,
                      raw_source TEXT,
                      result TEXT,
                      error_code TEXT,
                      created_at TEXT
                    )
                    """);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO logical_thread(
                      thread_id, label, preferred_channel, matching_mode, revision,
                      created_at, updated_at
                    ) VALUES (?, ?, NULL, 'STRICT', 1, ?, ?)
                    """)) {
                insert.setString(1, "thread.legacy");
                insert.setString(2, "旧库 thread");
                insert.setString(3, NOW.toString());
                insert.setString(4, NOW.toString());
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO channel_binding(
                      raw_source, thread_id, channel, account_id, injection_enabled,
                      created_at, updated_at, revision
                    ) VALUES (?, ?, ?, ?, 1, ?, ?, 1)
                    """)) {
                insert.setString(1, ONEBOT_GROUP);
                insert.setString(2, "thread.legacy");
                insert.setString(3, "onebot");
                insert.setString(4, "bot-1");
                insert.setString(5, NOW.toString());
                insert.setString(6, NOW.toString());
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO channel_audit(
                      action, thread_id, raw_source, result, error_code, created_at
                    ) VALUES ('legacy_bind', ?, ?, 'ok', NULL, ?)
                    """)) {
                insert.setString(1, "thread.legacy");
                insert.setString(2, ONEBOT_GROUP);
                insert.setString(3, NOW.toString());
                insert.executeUpdate();
            }
        }
    }

    private int userVersion() throws Exception {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private Set<String> columnNames(String table) throws Exception {
        Set<String> columns = new HashSet<>();
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                columns.add(result.getString("name"));
            }
        }
        return columns;
    }

    private static RegistryException expect(ErrorCode code, Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).as("expected RegistryException %s", code)
                .isInstanceOf(RegistryException.class);
        RegistryException failure = (RegistryException) thrown;
        assertThat(failure.code()).isEqualTo(code);
        return failure;
    }
}
