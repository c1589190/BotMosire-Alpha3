# BMA3 Foundation + QQ + Inbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` (recommended) or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 建立一个可运行的 Java 21 常驻服务：OneBot 11 正向 WS 入站 → 统一消息板 → 单 Inbox → 回环 Bearer MCP；带 DSH 薄插件、jlink/jpackage app-image 与一站式安装骨架。

**Architecture:** `bma3-core` 持有领域模型/SQLite；`bma3-agentlib` 包装 AgentLib 的 ToolRegistry/MCP；`bma3-qqconnect` 与 `bma3-inbox` 各自实现 `AgentTool`；`bma3-app` 做唯一组合根，暴露一个 MCP 端点。大量代码从隔壁 `iiBotMosire` / `InboxMosire` 移植，先搬测试再改包名。

**Tech Stack:** Java 21、Maven、SQLite JDBC、Jackson、Log4j2/SLF4J、AgentLib 0.2.4、JDK HttpServer、OneBot 11 正向 WS、Node 22（DSH 插件）。

> **执行状态**：2026-11-09 已按 native 执行；测试与证据见 `docs/agents/STATUS.md`。

**Spec:** [`docs/superpowers/specs/2026-11-09-bma3-foundation-design.md`](../specs/2026-11-09-bma3-foundation-design.md)

## Global Constraints

- 只支持 Java 21；根 `pom.xml` 用 `maven.compiler.release=21`。
- AgentLib 固定 `0.2.4`，不依赖隔壁未发布改动；所有 P0 需求由 `bma3-agentlib` 包装层兜住。
- 服务默认只绑 `127.0.0.1`；MCP 必须 Bearer；非回环 fail-closed。
- 消息板是唯一业务事实源；Inbox 不拥有正文；`UNKNOWN` 不自动重发。
- 不把 Playwright/Chromium 放进主 app-image；webconnect 是后续 Plan。
- 日志/测试/文档不出现 token、cookie、完整消息正文。
- 真实 QQ/DSH 联调未跑时写 `NOT_RUN`；fake WS/回环测试不能冒充真实验收。
- 每个任务先写失败测试，再实现，再跑测试；commit 步骤需用户当次授权，未授权则只留工作树、不 push。

## Reuse Map

| 新位置 | 从隔壁搬什么 | 处理 |
|---|---|---|
| `bma3-core` | `iiBotMosire/iibot-bot/.../store/*`、`chat/*`、`media/*` 的领域/存储 | 改包名 `io.mosire.bma3.core`；保留 SQLite schema 语义 |
| `bma3-core` | `iibot-connect/core/MessageSourceAddress.java`、`ChatMessage*` | 改名为 `SourceAddress` / `Message`；保留 parse/format 测试 |
| `bma3-agentlib` | `iibot-bot/.../mcp/BotMcpServer.java`、`BotToolCallLoggingTool` | 改名 `Bma3McpServer`；保留「内部 loopback + 外部 Bearer 代理」 |
| `bma3-qqconnect` | `iibot-connect/napcat/*`（`OneBot11Codec`、`NapCatAdapter`、`RecentMessageDeduplicator`、`TextSplitter`） | 第一版只保留正向 WS；HTTP action 走同一 WS |
| `bma3-qqconnect` | `iibot-bot/.../mcp` 的 QQ 工具 | 只搬 account/read/send/ack；改名 `qq_*` 保持 Agent 兼容 |
| `bma3-inbox` | `InboxMosire/inbox-core` + `inbox-sqlite` 的 item/delivery/ack | 去掉 claim/release/complete；保留 delivery/ack/occupancy |
| `bma3-inbox` | `InboxMosire/inbox-server` 的 HTTP/SSE 路由 | 只保留 Plan 1 的 status/items/delivery ack/health |
| `bma3-app` | `iibot-app` 的 config/logging/lifecycle | 改为 `config/*.yaml` + 动态调级；保留 redaction |
| `dsh-plugin` | `iiBotMosire-DSH/packages/iibot-dsh` 的 module/preset 模式；`InboxMosire-DSH` 的 host 事件/wake | 一个 npm 包、三个 loader row；只做薄桥 |
| 后续 Plan | `iibot-video-connect`、`BilibiliAdapter`、Playwright 代码 | **Plan 1 不搬**；webconnect/B 站另写计划 |

搬代码规则：先复制对应测试，改包名/依赖，跑通后再删旧逻辑；不允许把旧模块整体复制后保留死代码。

## Review Focus

- 配置热重载只改坏一个账号时，其他账号和在途消息不能受影响；非法配置必须保留 last-known-good。
- 同一 `self_id` 重复连接、账号删除后旧连接仍在发消息，必须 fail-closed。
- WS 重连后的重复/乱序消息不能产生重复 Inbox item 或错误已读游标。
- 发送返回 `UNKNOWN` 时不能自动重发，也不能写成 `SENT`。
- 日志/错误/MCP 结果不能泄露 token、cookie 或正文；debug 打开也不能。

---

## File Structure

```text
pom.xml
mvnw / .mvn/
bma3-core/src/main/java/io/mosire/bma3/core/{account,source,message,store,config,log}/
bma3-agentlib/src/main/java/io/mosire/bma3/agentlib/
bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/{onebot,account,tools}/
bma3-inbox/src/main/java/io/mosire/bma3/inbox/{core,http,tools}/
bma3-app/src/main/java/io/mosire/bma3/app/
bma3-app/src/main/resources/config-template/
dsh-plugin/package.json, index.js, cordis.patch.yml, lib/
packaging/build-app-image.sh, packaging/install.sh
```

---

### Task 1: Maven 骨架 + core 领域类型

**Files:**
- Create: `pom.xml`, `mvnw`, `.mvn/wrapper/*`（从隔壁复制），`bma3-core/pom.xml`（其余模块空壳 pom 可一并建）
- Modify: `.gitignore`（`target/`、`dist/`、`config/local/`、`*.db`、`logs/`）
- Create: `bma3-core/src/main/java/io/mosire/bma3/core/account/AccountId.java`
- Create: `bma3-core/src/main/java/io/mosire/bma3/core/account/ConsumerId.java`
- Create: `bma3-core/src/main/java/io/mosire/bma3/core/source/SourceAddress.java`
- Create: `bma3-core/src/main/java/io/mosire/bma3/core/message/Message.java`
- Test: `bma3-core/src/test/java/io/mosire/bma3/core/source/SourceAddressTest.java`

**Interfaces:**
- Produces: `record AccountId(String value)`、`record ConsumerId(String value)`、`SourceAddress.parse(String)` / `format()`、`record InboundMessage(SourceAddress source, String messageId, Instant at, List<Segment> segments, Direction direction)`、`record Message(long localSeq, InboundMessage inbound)`。

- [x] Step 1: 写 `SourceAddressTest`：parse/format 往返；`qq_group/bot-1/955432692`、`qq_private/bot-1/123`、`qq_system/bot-1/request`、非法输入抛 `IllegalArgumentException`。
- [x] Step 2: 跑 `./mvnw -q -pl bma3-core -Dtest=SourceAddressTest test`，确认失败（类不存在）。
- [x] Step 3: 从 `iibot-connect/core/MessageSourceAddress.java` 移植 parse/format，改包名；实现 `AccountId`/`Message` 最小 record。
- [x] Step 4: 再跑测试，PASS；跑 `./mvnw -q -pl bma3-core test`。
- [x] Step 5: 提交：`git add pom.xml bma3-core && git commit -m "feat(core): add domain ids and source address"`。

### Task 2: core SQLite 消息板

**Files:**
- Create: `bma3-core/src/main/java/io/mosire/bma3/core/store/MessageBoardStore.java`
- Create: `bma3-core/src/main/java/io/mosire/bma3/core/store/SqliteMessageBoardStore.java`
- Create: `bma3-core/src/main/java/io/mosire/bma3/core/store/ConsumerConversationStore.java`
- Test: `bma3-core/src/test/java/io/mosire/bma3/core/store/SqliteMessageBoardStoreTest.java`

**Interfaces:**
- Consumes: Task 1 `Message` / `SourceAddress`.
- Produces: `AppendResult append(InboundMessage)`、`List<Message> read(SourceAddress, long afterSeq, int limit)`、`long readSeq(ConsumerId, SourceAddress)`、`void ack(ConsumerId, SourceAddress, long seq)`。

- [x] Step 1: 复制并改名 `SqliteMessageBoardStoreTest` 的核心用例：入站追加、按 thread 读、consumer readSeq、ack 幂等、重启后保留。
- [x] Step 2: 跑测试，确认失败。
- [x] Step 3: 移植 `SqliteMessageBoardStore`/schema；删除文游/玩家相关字段；用 `jdbc:sqlite:<dataDir>/bot.db`，开启 WAL。
- [x] Step 4: 跑 `./mvnw -q -pl bma3-core test`，PASS。
- [x] Step 5: 提交。

### Task 3: 配置与日志

**Files:**
- Create: `bma3-core/src/main/java/io/mosire/bma3/core/config/{ConfigSource,ConfigException,SecretRef}.java`
- Create: `bma3-app/src/main/java/io/mosire/bma3/app/config/{AppConfig,ConfigLoader,ConfigWatcher}.java`
- Create: `bma3-app/src/main/resources/log4j2.xml`
- Create: `bma3-app/src/main/java/io/mosire/bma3/app/logging/LoggingConfigurer.java`
- Test: `bma3-app/src/test/java/io/mosire/bma3/app/config/ConfigLoaderTest.java`
- Test: `bma3-app/src/test/java/io/mosire/bma3/app/config/ConfigWatcherTest.java`
- Test: `bma3-app/src/test/java/io/mosire/bma3/app/logging/RedactionTest.java`

**Interfaces:**
- Produces: `AppConfig load(Path root)`；`List<ConfigError> validate(AppConfig)`；`ConfigWatcher.watch(Path, Consumer<AppConfig>)`；`void applyLogLevel(String module, Level level)`。

- [x] Step 1: 写测试：缺文件给默认值；非法 `port` 拒绝；`${ENV:TOKEN}` 解析；日志脱敏 `Bearer ...`/`Cookie: ...`；watch 到非法变更时保留 last-known-good，不回调。
- [x] Step 2: 跑测试失败。
- [x] Step 3: 实现 YAML 加载（Jackson YAML）+ JSON Schema 最小校验；`WatchService` debounce；日志用 Log4j2 JSON layout + 模块 logger；`SecretRef` 不落盘。
- [x] Step 4: 跑 `./mvnw -q -pl bma3-app -am test`，PASS。
- [x] Step 5: 提交（需授权）。

### Task 4: AgentLib MCP 包装

**Files:**
- Create: `bma3-agentlib/src/main/java/io/mosire/bma3/agentlib/Bma3McpServer.java`
- Create: `bma3-agentlib/src/main/java/io/mosire/bma3/agentlib/ToolCallLoggingTool.java`
- Test: `bma3-agentlib/src/test/java/io/mosire/bma3/agentlib/Bma3McpServerTest.java`

**Interfaces:**
- Consumes: AgentLib `ToolRegistry` / `AgentTool`。
- Produces: `Bma3McpServer(host, port, path, token, serverName, version, List<AgentTool>)`、`start()`、`boundPort()`、`close()`。

- [x] Step 1: 移植 `BotMcpServerTest`：401 无 token、200 tools/list、streamable POST、路径不匹配 404。
- [x] Step 2: 跑测试失败。
- [x] Step 3: 从 `BotMcpServer.java` 移植内部 `AgentToMcpServer.startHttp` + 外部 Bearer 代理；保留回环校验、虚拟线程 executor。
- [x] Step 4: 跑 `./mvnw -q -pl bma3-agentlib -am test`，PASS。
- [x] Step 5: 提交。

### Task 5: OneBot 11 正向 WS

**Files:**
- Create: `bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/onebot/{OneBot11Codec,OneBot11Client,OneBotEvent}.java`
- Test: `bma3-qqconnect/src/test/java/io/mosire/bma3/qqconnect/onebot/OneBot11CodecTest.java`
- Test: `bma3-qqconnect/src/test/java/io/mosire/bma3/qqconnect/onebot/OneBot11ClientTest.java`

**Interfaces:**
- Produces: `OneBot11Client(URI endpoint, String token, Listener listener)`；`sendAction(String action, Map<String,Object>)`；`close()`；回调 `onEvent(OneBotEvent)`。

- [x] Step 1: 复制 `OneBot11CodecTest` 的消息/事件用例；新增 fake WS server 测试：连接、收到 `message` 事件、action 请求/响应；重连后同一条 `message_id` 只交付一次、乱序不回退游标。
- [x] Step 2: 跑测试失败。
- [x] Step 3: 移植 `OneBot11Codec` 与 `NapCatAdapter` 的 WS 部分；删除 HTTP 双开；token 走 `Authorization` header；重连 backoff；`RecentMessageDeduplicator` 按 `message_id` 去重。
- [x] Step 4: 跑 `./mvnw -q -pl bma3-qqconnect -am test`，PASS。
- [x] Step 5: 提交（需授权）。

### Task 6: 账号 registry 与热插拔

**Files:**
- Create: `bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/account/{AccountConfig,AccountRegistry,ReconcileResult}.java`
- Test: `bma3-qqconnect/src/test/java/io/mosire/bma3/qqconnect/account/AccountRegistryTest.java`

**Interfaces:**
- Produces: `ReconcileResult reconcile(List<AccountConfig>)`；`Optional<OneBot11Client> client(AccountId)`；`void close()`。

- [x] Step 1: 写测试：新增两条账号；删除一条；改 endpoint 触发重连；重复 `self_id` 拒绝；非法账号不影响其他账号。
- [x] Step 2: 跑测试失败。
- [x] Step 3: 移植/实现 registry；每账号一个 client；reconcile 先建新、后关旧；drain 在途；记录 redacted diff。
- [x] Step 4: 跑测试 PASS。
- [x] Step 5: 提交。

### Task 7: QQ AgentTool

**Files:**
- Create: `bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/tools/{QqAccountListTool,QqConversationReadTool,QqConversationSendTool,QqConversationAckTool}.java`
- Test: `bma3-qqconnect/src/test/java/io/mosire/bma3/qqconnect/tools/QqToolsTest.java`

**Interfaces:**
- Consumes: `MessageBoardStore`、`AccountRegistry`。
- Produces: `List<AgentTool> tools(...)`；工具名 `qq_account_list`、`qq_conversation_read/send/ack`；JSON Schema 与旧版兼容。

- [x] Step 1: 复制旧 `BotMcpToolsTest` 中 read/send/ack 用例；新增多账号路由失败测试（账号不匹配 fail-closed）。
- [x] Step 2: 跑测试失败。
- [x] Step 3: 移植工具实现；发送走 outbox 三态；`UNKNOWN` 不重发；正文不进日志。
- [x] Step 4: 跑测试 PASS。
- [x] Step 5: 提交。

### Task 8: Inbox 单状态机

**Files:**
- Create: `bma3-inbox/src/main/java/io/mosire/bma3/inbox/core/{InboxItem,InboxStore,InboxService,DeliveryPolicy}.java`
- Create: `bma3-inbox/src/main/java/io/mosire/bma3/inbox/core/SqliteInboxStore.java`
- Test: `bma3-inbox/src/test/java/io/mosire/bma3/inbox/core/InboxServiceTest.java`

**Interfaces:**
- Produces: `append(AppendItem)`、`InboxStatus status()`、`List<ItemSummary> list()`、`List<Delivery> due(Instant now)`、`void ack(String deliveryId)`。

- [x] Step 1: 移植 `InboxServiceTest`/`SqliteInboxStoreTest` 的 delivery/ack 用例；删除 claim/release/complete 用例。
- [x] Step 2: 跑测试失败。
- [x] Step 3: 移植 `inbox-core`/`inbox-sqlite` 子集；状态 `available→delivered→acked`，超限 `undelivered`；revision/attempts/TTL。
- [x] Step 4: 跑 `./mvnw -q -pl bma3-inbox -am test`，PASS。
- [x] Step 5: 提交。

### Task 9: Inbox HTTP + tools

**Files:**
- Create: `bma3-inbox/src/main/java/io/mosire/bma3/inbox/http/InboxHttpServer.java`
- Create: `bma3-inbox/src/main/java/io/mosire/bma3/inbox/tools/{InboxStatusTool,InboxListTool,InboxAckTool}.java`
- Test: `bma3-inbox/src/test/java/io/mosire/bma3/inbox/http/InboxHttpServerTest.java`
- Test: `bma3-inbox/src/test/java/io/mosire/bma3/inbox/tools/InboxToolsTest.java`

**Interfaces:**
- Produces: `/healthz`、`/v1/inboxes/{id}/status`、`/items`、`/deliveries/{id}/ack`；工具 `inbox_status/list/ack`。

- [x] Step 1: 写测试：status 200、items 不含正文、ack 幂等、无 token 401。
- [x] Step 2: 跑测试失败。
- [x] Step 3: 用 JDK HttpServer + 虚拟线程；token 从 env/file；工具包装 `InboxService`。
- [x] Step 4: 跑测试 PASS。
- [x] Step 5: 提交。

### Task 10: app 组合根 + fake QQ 闭环

**Files:**
- Create: `bma3-app/src/main/java/io/mosire/bma3/app/{Bma3App,AppRuntime,HealthServer}.java`
- Create: `bma3-app/src/main/resources/config-template/app.yaml`
- Test: `bma3-app/src/test/java/io/mosire/bma3/app/Bma3AppFakeQqTest.java`

**Interfaces:**
- Consumes: all modules.
- Produces: `Bma3App.start(AppConfig)`；一个 MCP 端点；`/healthz`；graceful shutdown。

- [x] Step 1: 写集成测试：fake OneBot WS 推入私聊消息 → 消息板 → Inbox item → MCP `qq_conversation_read` → `inbox_ack`；再发一条 → `qq_conversation_send` 三态；改 qqconnect 配置触发 `reconcile`，其他账号不受影响。
- [x] Step 2: 跑测试失败。
- [x] Step 3: 组装 config → stores → qqconnect → inbox → tool registry → Bma3McpServer → start；订阅 `ConfigWatcher` 调 `AccountRegistry.reconcile`；注册 shutdown hook。
- [x] Step 4: 跑 `./mvnw -q test`，PASS。
- [x] Step 5: 提交（需授权）。

### Task 11: DSH 薄插件

**Files:**
- Create: `dsh-plugin/package.json`、`dsh-plugin/index.js`、`dsh-plugin/cordis.patch.yml`
- Create: `dsh-plugin/lib/{inbox,persona,preset}.js`
- Test: `dsh-plugin/test/{manifest,inbox,persona,preset}.test.mjs`

**Interfaces:**
- Consumes: 服务 HTTP API、DSH `agent.followup`/lifecycle/tools。
- Produces: 三个 loader row；`inbox` 消费 wake → followup；`persona` 注入系统提示词；`preset` 工具组。

- [x] Step 1: 复制 `iiBotMosire-DSH` 的 module/preset 模式和 `InboxMosire-DSH` 的 host 事件测试；写 manifest 测试。
- [x] Step 2: 跑 `npm test` 失败。
- [x] Step 3: 实现三个 module，独立配置/失败域；插件只检查服务 `/healthz`，不托管进程。
- [x] Step 4: 跑 `npm test` PASS。
- [x] Step 5: 提交。

### Task 12: jpackage + 安装器骨架

**Files:**
- Create: `packaging/build-app-image.sh`
- Create: `packaging/install.sh`
- Test: `packaging/smoke.sh`

**Interfaces:**
- Produces: `dist/botmosire-a3/bin/botmosire-a3`；`dist/botmosire-a3.tar.gz` + SHA256；`install.sh --version <v>` 解压/软链/systemd unit。

- [x] Step 1: 写 `smoke.sh`：构建 app-image，运行 `--version`，启动 fake 配置后 `curl /healthz` 200。
- [x] Step 2: 跑脚本失败（构建未实现）。
- [x] Step 3: `jlink` + `jpackage --type app-image`；installer 只做用户级目录/软链/systemd unit，不自动 root。
- [x] Step 4: 跑 `packaging/smoke.sh` PASS；真实 systemd 安装标 `NOT_RUN`。
- [x] Step 5: 提交。

### Task 13: 文档与状态

- [x] 更新 `docs/agents/STATUS.md`：测试数、命令、rc、`NOT_RUN`（真实 QQ/DSH）。
- [x] 更新 `docs/upstream/agentlib-change-requests.md`：仅当实际依赖版本变化。
- [x] 提交。

---

## Execution Notes

- 每个任务开始前先读对应旧文件与测试；能复制就不要重写。
- 包名统一 `io.mosire.bma3`；旧包名只出现在移植注释里。
- 所有真实外发/登录/浏览器动作在 Plan 1 不实现；`webconnect` 另写 Selenium 计划。
- 真实 QQ/LLBot、DSH、systemd 安装的验收统一 `NOT_RUN`，直到用户授权真机联调。
