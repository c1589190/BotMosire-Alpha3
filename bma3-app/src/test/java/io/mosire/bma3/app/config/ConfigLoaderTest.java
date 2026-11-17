package io.mosire.bma3.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.bma3.core.config.ConfigException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void usesDefaultsWhenConfigFileIsMissing() {
        AppConfig config = ConfigLoader.load(tempDir);

        assertThat(config.dataDir()).isEqualTo(Path.of("data"));
        assertThat(config.server().host()).isEqualTo("127.0.0.1");
        assertThat(config.server().port()).isEqualTo(8765);
        assertThat(config.server().mcpPath()).isEqualTo("/mcp");
        assertThat(config.inbox().inboxId()).isEqualTo("bma3");
        assertThat(config.inbox().display().maxMessagesPerItem()).isEqualTo(6);
        assertThat(config.inbox().display().maxItemsPerPanel()).isEqualTo(6);
        assertThat(config.inbox().display().mode()).isEqualTo("auto");
        assertThat(config.inbox().display().summaryMaxCharsPerItem()).isEqualTo(200);
        assertThat(config.inbox().display().summaryAboveItems()).isEqualTo(8);
        assertThat(config.inbox().waitPolicy().enabled()).isTrue();
        assertThat(config.inbox().waitPolicy().defaultQuietSeconds()).isZero();
        assertThat(config.inbox().waitPolicy().defaultMaxWaitSeconds()).isEqualTo(20);
        assertThat(config.inbox().waitPolicy().sources()).isEmpty();
        assertThat(config.qqconnect().send().maxTextChars()).isEqualTo(3000);
        assertThat(config.qqconnect().media().dir()).isEmpty();
        assertThat(config.qqconnect().media().maxInlineBytes()).isEqualTo(65536L);
        assertThat(config.qqconnect().media().maxDownloadBytes()).isEqualTo(33_554_432L);
        assertThat(config.qqOfficial().accounts()).isEmpty();
        assertThat(config.qqOfficial().transport()).isEqualTo("websocket");
        assertThat(config.qqOfficial().reconnect().initialMs()).isEqualTo(1000);
        assertThat(config.qqOfficial().reconnect().maxMs()).isEqualTo(60_000);
        assertThat(config.qqOfficial().reconnect().jitter()).isTrue();
        assertThat(config.qqOfficial().http().connectTimeoutSeconds()).isEqualTo(10);
        assertThat(config.qqOfficial().http().requestTimeoutSeconds()).isEqualTo(20);
        assertThat(config.qqOfficial().matching().mode()).isEqualTo("strict");
        assertThat(config.qqOfficial().matching().strictTimeToleranceSeconds()).isEqualTo(5);
        assertThat(config.weighting().sourcePriority()).containsEntry("qq_private", 300);
        assertThat(config.weighting().sourcePriority()).containsEntry("qq_group", 200);
        assertThat(config.weighting().sourcePriority().getOrDefault("bilibili", 0)).isZero();
        assertThat(config.weighting().immediateThreshold()).isEqualTo(300);
        assertThat(config.weighting().dropBelow()).isEqualTo(-300);
        assertThat(config.logging().level()).isEqualTo("INFO");
        // Debug surface defaults: mounted, capped at 32 concurrent questions, priority 350.
        assertThat(config.debug().enabled()).isTrue();
        assertThat(config.debug().maxPending()).isEqualTo(32);
        assertThat(config.debug().priority()).isEqualTo(350);
        assertThat(config.schedule().enabled()).isTrue();
        assertThat(config.schedule().tickSeconds()).isEqualTo(1);
        assertThat(config.schedule().maxSchedules()).isEqualTo(200);
        assertThat(config.schedule().defaultMissedPolicy()).isEqualTo("skip");
    }

    @Test
    void readsTheDebugSection() throws Exception {
        writeConfig("""
                debug:
                  enabled: false
                  maxPending: 0
                  askTimeoutSeconds: 30
                  questionMaxBytes: 512
                  answerMaxBytes: 1024
                  retention: 50
                  priority: 400
                schedule:
                  enabled: false
                  tickSeconds: 5
                  maxSchedules: 7
                  defaultMissedPolicy: once
                """);

        AppConfig config = ConfigLoader.load(tempDir);

        assertThat(config.debug().enabled()).isFalse();
        assertThat(config.debug().maxPending()).isZero();
        assertThat(config.debug().askTimeoutSeconds()).isEqualTo(30);
        assertThat(config.debug().questionMaxBytes()).isEqualTo(512);
        assertThat(config.debug().answerMaxBytes()).isEqualTo(1024);
        assertThat(config.debug().retention()).isEqualTo(50);
        assertThat(config.debug().priority()).isEqualTo(400);
        assertThat(config.schedule().enabled()).isFalse();
        assertThat(config.schedule().tickSeconds()).isEqualTo(5);
        assertThat(config.schedule().maxSchedules()).isEqualTo(7);
        assertThat(config.schedule().defaultMissedPolicy()).isEqualTo("once");
    }

    @Test
    void interpolatesEnvironmentReferences() throws Exception {
        writeConfig("""
                dataDir: ${ENV:BMA3_DATA_DIR}
                server:
                  tokenEnv: ${ENV:BMA3_TOKEN_ENV}
                """);

        AppConfig config = ConfigLoader.load(tempDir, Map.of(
                "BMA3_DATA_DIR", "/srv/bma3",
                "BMA3_TOKEN_ENV", "BMA3_TEST_TOKEN"));

        assertThat(config.dataDir()).isEqualTo(Path.of("/srv/bma3"));
        assertThat(config.server().tokenEnv()).isEqualTo("BMA3_TEST_TOKEN");
    }

    @Test
    void parsesDisplaySendAndPlatformWeights() throws Exception {
        writeConfig("""
                inbox:
                  display:
                    maxMessagesPerItem: 4
                    maxItemsPerPanel: 8
                    includeOmittedCount: false
                qqconnect:
                  send:
                    maxTextChars: 2800
                    overflow: split
                weighting:
                  immediateThreshold: 400
                  sourcePriority:
                    qq_group: 50
                    bilibili: 120
                  attributes:
                    qq_at_bot: 250
                """);

        AppConfig config = ConfigLoader.load(tempDir);
        assertThat(config.inbox().display().maxMessagesPerItem()).isEqualTo(4);
        assertThat(config.inbox().display().maxItemsPerPanel()).isEqualTo(8);
        assertThat(config.inbox().display().includeOmittedCount()).isFalse();
        assertThat(config.qqconnect().send().maxTextChars()).isEqualTo(2800);
        assertThat(config.qqconnect().send().overflow()).isEqualTo("split");
        assertThat(config.weighting().sourcePriority()).containsEntry("qq_group", 50);
        assertThat(config.weighting().sourcePriority()).containsEntry("bilibili", 120);
        assertThat(config.weighting().attribute("qq_at_bot")).isEqualTo(250);
        assertThat(config.weighting().immediateThreshold()).isEqualTo(400);
    }

    @Test
    void parsesInboxWaitRulesAndSummaryDisplay() throws Exception {
        writeConfig("""
                inbox:
                  display:
                    mode: summary
                    summaryMaxCharsPerItem: 160
                    summaryAboveItems: 4
                  wait:
                    enabled: true
                    defaultQuietSeconds: 0
                    defaultMaxWaitSeconds: 25
                    sources:
                      - source: qq_private
                        quietSeconds: 8
                        maxWaitSeconds: 20
                      - source: bma3_debug
                        quietSeconds: 0
                        maxWaitSeconds: 5
                """);

        AppConfig config = ConfigLoader.load(tempDir);

        assertThat(config.inbox().display().mode()).isEqualTo("summary");
        assertThat(config.inbox().display().summaryMaxCharsPerItem()).isEqualTo(160);
        assertThat(config.inbox().display().summaryAboveItems()).isEqualTo(4);
        assertThat(config.inbox().waitPolicy().enabled()).isTrue();
        assertThat(config.inbox().waitPolicy().defaultQuietSeconds()).isZero();
        assertThat(config.inbox().waitPolicy().defaultMaxWaitSeconds()).isEqualTo(25);
        assertThat(config.inbox().waitPolicy().sources()).hasSize(2);
        assertThat(config.inbox().waitPolicy().sources().getFirst().source())
                .isEqualTo("qq_private");
        assertThat(config.inbox().waitPolicy().sources().getFirst().quietSeconds())
                .isEqualTo(8);
    }

    @Test
    void rejectsInvalidInboxWaitRule() throws Exception {
        writeConfig("""
                inbox:
                  wait:
                    sources:
                      - source: qq_private
                        quietSeconds: 9
                        maxWaitSeconds: 8
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("maxWaitSeconds");
    }

    @Test
    void rejectsInvalidDisplayRange() throws Exception {
        writeConfig("""
                inbox:
                  display:
                    maxMessagesPerItem: 0
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("maxMessagesPerItem");
    }

    @Test
    void rejectsInvalidSendLimit() throws Exception {
        writeConfig("""
                qqconnect:
                  send:
                    maxTextChars: 4000
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("maxTextChars");
    }

    @Test
    void rejectsInvalidPort() throws Exception {
        writeConfig("""
                server:
                  port: 70000
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("port");
    }


    @Test
    void defaultsToTokenAuthWithNoEnvFileOverride() {
        AppConfig config = ConfigLoader.load(tempDir);

        assertThat(config.security().auth()).isEqualTo(AppConfig.Security.AUTH_TOKEN);
        assertThat(config.security().requiresToken()).isTrue();
        assertThat(config.security().envFile()).isEmpty();
    }

    @Test
    void readsExplicitNoAuthMode() throws Exception {
        writeConfig("""
                security:
                  auth: none
                  envFile: /srv/bma3/.bma3env
                """);

        AppConfig config = ConfigLoader.load(tempDir);

        assertThat(config.security().auth()).isEqualTo(AppConfig.Security.AUTH_NONE);
        assertThat(config.security().requiresToken()).isFalse();
        assertThat(config.security().envFile()).isEqualTo("/srv/bma3/.bma3env");
    }

    @Test
    void rejectsUnknownAuthMode() throws Exception {
        writeConfig("""
                security:
                  auth: maybe
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(io.mosire.bma3.core.config.ConfigException.class)
                .hasMessageContaining("security.auth");
    }

    @Test
    void readsOfficialAccountAndRuntimeLimits() throws Exception {
        writeConfig("""
                qqofficial:
                  accounts:
                    - accountId: qq-official-main
                      appId: "synthetic-app-id-main"
                      appSecretEnv: BMA3_QQ_OFFICIAL_APP_SECRET
                      enabled: true
                      shard: [0, 1]
                    - accountId: qq-official-disabled
                      appId: "synthetic-app-id-disabled"
                      appSecretFile: /run/secrets/qq-official.secret
                      enabled: false
                      shard: [0, 1]
                  transport: websocket
                  reconnect:
                    initialMs: 1500
                    maxMs: 120000
                    jitter: false
                  http:
                    connectTimeoutSeconds: 7
                    requestTimeoutSeconds: 25
                  matching:
                    mode: loose
                    strictTimeToleranceSeconds: 12
                """);

        AppConfig config = ConfigLoader.load(tempDir);

        assertThat(config.qqOfficial().accounts()).hasSize(2);
        AppConfig.QqOfficial.Account enabled = config.qqOfficial().accounts().getFirst();
        assertThat(enabled.accountId()).isEqualTo("qq-official-main");
        assertThat(enabled.appId()).isEqualTo("synthetic-app-id-main");
        assertThat(enabled.appSecretEnv()).isEqualTo("BMA3_QQ_OFFICIAL_APP_SECRET");
        assertThat(enabled.appSecretFile()).isEmpty();
        assertThat(enabled.secretEnv()).isEqualTo("BMA3_QQ_OFFICIAL_APP_SECRET");
        assertThat(enabled.enabled()).isTrue();
        assertThat(enabled.shard()).containsExactly(0, 1);
        AppConfig.QqOfficial.Account disabled = config.qqOfficial().accounts().get(1);
        assertThat(disabled.appSecretFile()).isEqualTo("/run/secrets/qq-official.secret");
        assertThat(disabled.secretFile()).isEqualTo("/run/secrets/qq-official.secret");
        assertThat(disabled.enabled()).isFalse();
        assertThat(config.qqOfficial().reconnect().initialMs()).isEqualTo(1500);
        assertThat(config.qqOfficial().reconnect().maxMs()).isEqualTo(120000);
        assertThat(config.qqOfficial().reconnect().jitter()).isFalse();
        assertThat(config.qqOfficial().http().connectTimeoutSeconds()).isEqualTo(7);
        assertThat(config.qqOfficial().http().requestTimeoutSeconds()).isEqualTo(25);
        assertThat(config.qqOfficial().matching().mode()).isEqualTo("loose");
        assertThat(config.qqOfficial().matching().strictTimeToleranceSeconds()).isEqualTo(12);
    }

    @Test
    void acceptsDisabledOfficialAccountWithoutCredentialReference() throws Exception {
        writeConfig("""
                qqofficial:
                  accounts:
                    - accountId: qq-official-off
                      enabled: false
                      shard: [0, 1]
                """);

        AppConfig config = ConfigLoader.load(tempDir);

        assertThat(config.qqOfficial().accounts()).singleElement().satisfies(account -> {
            assertThat(account.accountId()).isEqualTo("qq-official-off");
            assertThat(account.enabled()).isFalse();
            assertThat(account.hasSecretReference()).isFalse();
        });
    }

    @Test
    void rejectsInlineOfficialAppSecret() throws Exception {
        writeConfig("""
                qqofficial:
                  accounts:
                    - accountId: qq-official-main
                      appId: "synthetic-app-id-main"
                      appSecret: "do-not-accept"
                      enabled: true
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("qqofficial.accounts[0].appSecret")
                .hasMessageContaining("inline secret");
    }

    @Test
    void rejectsOfficialEnvAndFileTogether() throws Exception {
        writeConfig("""
                qqofficial:
                  accounts:
                    - accountId: qq-official-main
                      appId: "synthetic-app-id-main"
                      appSecretEnv: BMA3_QQ_OFFICIAL_APP_SECRET
                      appSecretFile: /run/secrets/qq-official.secret
                      enabled: true
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("appSecretEnv/appSecretFile")
                .hasMessageContaining("二选一");
    }

    @Test
    void rejectsEnabledOfficialAccountWithoutCredentialReference() throws Exception {
        writeConfig("""
                qqofficial:
                  accounts:
                    - accountId: qq-official-main
                      appId: "synthetic-app-id-main"
                      enabled: true
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("appSecretEnv 或 appSecretFile");
    }

    @Test
    void rejectsUnsupportedOfficialShard() throws Exception {
        writeConfig("""
                qqofficial:
                  accounts:
                    - accountId: qq-official-main
                      appId: "synthetic-app-id-main"
                      appSecretEnv: BMA3_QQ_OFFICIAL_APP_SECRET
                      enabled: true
                      shard: [0, 2]
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("shard")
                .hasMessageContaining("[0, 1]");
    }

    @Test
    void rejectsUnknownOfficialTransportAndDuplicateAccount() throws Exception {
        writeConfig("""
                qqofficial:
                  transport: http
                  accounts:
                    - accountId: duplicate
                      appId: "1"
                      appSecretEnv: BMA3_QQ_OFFICIAL_APP_SECRET
                      enabled: true
                    - accountId: duplicate
                      appId: "2"
                      appSecretEnv: BMA3_QQ_OFFICIAL_APP_SECRET
                      enabled: true
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("transport");
    }

    @Test
    void rejectsDuplicateOfficialAccountIds() throws Exception {
        writeConfig("""
                qqofficial:
                  accounts:
                    - accountId: duplicate
                      appId: "1"
                      appSecretEnv: BMA3_QQ_OFFICIAL_APP_SECRET
                      enabled: true
                    - accountId: duplicate
                      appId: "2"
                      appSecretEnv: BMA3_QQ_OFFICIAL_APP_SECRET
                      enabled: true
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("qqofficial.accounts.accountId")
                .hasMessageContaining("重复");
    }

    @Test
    void rejectsEnvInterpolationInsideOfficialSectionWithoutEchoingTheSecret() throws Exception {
        String sentinelSecret = "phase1-env-secret-sentinel-7f3a9c";
        writeConfig("""
                qqofficial:
                  accounts:
                    - accountId: official-main
                      appId: "synthetic-app-id-main"
                      appSecretEnv: ${ENV:BMA3_QQ_OFFICIAL_APP_SECRET}
                      enabled: false
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir, Map.of(
                "BMA3_QQ_OFFICIAL_APP_SECRET", sentinelSecret)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("qqofficial")
                .hasMessageContaining("插值")
                .satisfies(failure -> assertThat(failure.getMessage())
                        .doesNotContain(sentinelSecret));
    }

    @Test
    void rejectsEnvInterpolationInAnyOfficialValue() throws Exception {
        String sentinelSecret = "phase1-env-secret-sentinel-other-value";
        writeConfig("""
                qqofficial:
                  transport: ${ENV:BMA3_QQ_OFFICIAL_TRANSPORT}
                  accounts: []
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir, Map.of(
                "BMA3_QQ_OFFICIAL_TRANSPORT", sentinelSecret)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("qqofficial")
                .satisfies(failure -> assertThat(failure.getMessage())
                        .doesNotContain(sentinelSecret));
    }

    @Test
    void yamlParseErrorDoesNotEchoInlineSecret() throws Exception {
        String inlineSecret = "phase1-inline-secret-sentinel-91c2";
        writeConfig("""
                qqofficial:
                  accounts:
                    - accountId: official-main
                      appSecret: "%s"
                      broken: [unterminated
                """.formatted(inlineSecret));

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("YAML 解析失败")
                .satisfies(failure -> assertThat(failure.getMessage())
                        .doesNotContain(inlineSecret));
    }

    @Test
    void unknownOfficialFieldDoesNotEchoItsValue() throws Exception {
        String sentinelValue = "phase1-unknown-field-sentinel-b413";
        writeConfig("""
                qqofficial:
                  unexpectedSecretishValue: "%s"
                """.formatted(sentinelValue));

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("qqofficial.unexpectedSecretishValue")
                .hasMessageContaining("不是支持的字段")
                .satisfies(failure -> assertThat(failure.getMessage())
                        .doesNotContain(sentinelValue));
    }

    @Test
    void rejectsUnsafeOfficialAccountIdsWithoutEchoingThem() throws Exception {
        java.util.List<String> yamlAccountIds = java.util.List.of(
                "\"bad/SENTINEL-ACCOUNT\"",
                "\"bad|SENTINEL-ACCOUNT\"",
                "\" leading-SENTINEL-ACCOUNT\"",
                "\"bad\\a_SENTINEL-ACCOUNT\"");

        for (String yamlAccountId : yamlAccountIds) {
            writeConfig("""
                    qqofficial:
                      accounts:
                        - accountId: %s
                          appId: "synthetic-app-id-main"
                          appSecretEnv: BMA3_QQ_OFFICIAL_APP_SECRET
                          enabled: false
                    """.formatted(yamlAccountId));

            assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("qqofficial.accounts.accountId")
                    .hasMessageContaining("安全单段")
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .doesNotContain("SENTINEL-ACCOUNT"));
        }
    }

    @Test
    void rejectsStringOfficialEnabledFlag() throws Exception {
        writeConfig("""
                qqofficial:
                  accounts:
                    - accountId: official-main
                      appId: "synthetic-app-id-main"
                      appSecretEnv: BMA3_QQ_OFFICIAL_APP_SECRET
                      enabled: "true"
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("qqofficial.accounts[0].enabled")
                .hasMessageContaining("布尔值");
    }

    @Test
    void rejectsStringOfficialJitterFlag() throws Exception {
        writeConfig("""
                qqofficial:
                  reconnect:
                    jitter: "false"
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("qqofficial.reconnect.jitter")
                .hasMessageContaining("布尔值");
    }

    @Test
    void rejectsStringOfficialIntegerField() throws Exception {
        writeConfig("""
                qqofficial:
                  reconnect:
                    initialMs: "1000"
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("qqofficial.reconnect.initialMs")
                .hasMessageContaining("整数");
    }

    @Test
    void rejectsFractionalOfficialIntegerField() throws Exception {
        writeConfig("""
                qqofficial:
                  http:
                    requestTimeoutSeconds: 20.5
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("qqofficial.http.requestTimeoutSeconds")
                .hasMessageContaining("整数");
    }

    private void writeConfig(String yaml) throws Exception {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("app.yaml"), yaml);
    }
}
