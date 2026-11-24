# Linux DSH 单包与三模块可行性调查

> **类型：调查（时间点快照，只作追溯，不作当前事实）**
>
> **日期**：2026-11-24（UTC+8）
>
> **触发**：合并 DSH 一键包与独立插件，独立管理 Inbox，将 JAR 的 MCP 限定于 BMA3 预设，并开放跨预设的运行时控制。
>
> **方法**：阅读 `feat/0.0.9-early` 的 `5dfdd38`、核对宿主契约，在隔离 Linux x86_64 / DSH Web `0.2.2-rc.5` 上构建、安装并运行回环探针。

**可行。** 普通会话与 BMA3 会话可以共用 Inbox 命令和运行时工具。使用宿主原生 MCP 客户端将同一个 JAR 的工具注册到 BMA3 预设作用域后，实测已有 BMA3 会话可看到并调用工具，普通会话及全局工具表各有 0 个该连接的工具。Linux 可沿用现有 Java 镜像、控制管道和单实例锁。

## 已确认事实

| 问题 | 当前分支行为 |
|---|---|
| 为什么有两个包 | 普通包为 `@mosire/bma3-dsh`。构建整合包时，staging 脚本改包名为 `@mosire/bma3-dsh-managed`，内置镜像，并把现有 Inbox 行改成 `source: bundled`。两包复用插件源码，都插入 `bma3-inbox` 和 `bma3-preset`。隔离环境中同时安装两个包，CLI 成功，但 `--dump-config` 已出现各两条同名行。[staging](../../../packaging/stage-dsh-bundle.mjs#L23) · [patch](../../../dsh-plugin/cordis.patch.yml#L1) |
| 为什么普通会话能用 MCP | 托管连线从 profile 上下文挂载原生 MCP 客户端。凭据隔离由 Cordis 的 `isolate('credentials')` 完成，注册工具的作用域仍是 profile。真实 Web 探针中，`standard` 和 `bma3` 会话各看到同一组 30 个日常工具。[托管连线](../../../dsh-plugin/lib/runtime.js#L185) |
| 为什么停 JAR 会失去 Inbox 命令 | 托管模式把 Inbox 客户端和命令一起挂在运行时的子组件，停机时与 MCP 一起卸载。探针里，两个预设的 `/inbox` 和 `bma3_inbox` 都消失，`bma3_runtime` 仍能查询及启动。把既有 Inbox 入口独立挂上后，JAR 停止期间两个预设的 `/inbox status` 与 `bma3_inbox` 均在。[运行时](../../../dsh-plugin/lib/runtime.js#L230) · [Inbox 入口](../../../dsh-plugin/lib/inbox.js#L3833) |
| 预设隔离是否可用 | 两个实际 Agent Session 分别使用 `standard` 与 `bma3`，自定义提示词仅在后者组装。探针在会话创建后，用宿主原生客户端将真实 JAR 的日常入口挂到 BMA3 预设作用域，按 BMA3 / standard / 全局顺序观测到 30 / 0 / 0。调用探针连接的 `inbox_status` 后卸载，BMA3 作用域回到 0。探针通过单独的连接名观测作用域，当前基线的全局连接仍按原行为工作。[预设注册](../../../dsh-plugin/lib/preset.js#L145) · [原生 MCP 作用域](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/mcp.md) |
| 运行时能改什么 | 托管入口支持 `status/start/stop`，包内镜像首次自动启动，显式停止跨 DSH 重启保存。本地 JAR 的 Java、JAR 和配置根来自 profile 配置。JAR 本身的 `set_config` 已支持校验写盘和热重载反馈，位于额外 MCP 工具面，须有在线 JAR。[托管工具](../../../dsh-plugin/lib/runtime.js#L377) · [配置工具](../../../bma3-app/src/main/java/io/github/c1589190/bma3/app/tools/SetConfigTool.java#L20) · [MCP 工具面设计](../specs/2026-11-23-mcp-daily-tools-design.md) |

## 推断与建议

| 模块 | 建议的职责和生命周期 |
|---|---|
| `inbox` | 在 profile 层注册一次 `/inbox` 与 `bma3_inbox`，任何预设均可控制会话绑定。收到后端就绪信息后建立唯一 SSE 连接。JAR 停机时，命令仍可查询离线状态，需后端的操作返回明确状态。保留目前的绑定、ACK 恢复和持久化约束，换端口或重启后重新验证目标与数据库身份。同一时刻仍沿用一个 Inbox 的现有会话路由。 |
| `preset` | 保持已安装会话使用的 `bma3` 预设 ID 和现有提示词入口。加入预设内的一行，订阅后端的就绪与失效信号，在该作用域挂载或撤销 DSH 原生 MCP 客户端。新版优先 `/mcp/daily`，兼容旧 JAR 的全量入口。后端每次重启用新握手重新接线。已有会话继续受宿主的预设版本和切换约束。 |
| `runtime` | 保留 profile 层的 `bma3_runtime`，供本 profile 中任意预设使用。保留 `status/start/stop`，加入串行的 `restart` 和持久化的 `configure`。复用控制管道、实例锁、已验证镜像和回退路径。分别报告后端、Inbox 和 MCP 的状态。启停由持有托管进程句柄的一方执行，并沿用宿主工具权限。 |

建议 Linux x86_64 采用一个内置 Java 镜像的 `@mosire/bma3-dsh` tgz，一次安装三个模块。首次启用走内置镜像。同一包也可托管兼容的本地 JAR 或连接已运行的服务。外部启动器持有的进程由其原有管理入口控制。一键安装后的基本操作为查看 `bma3_runtime status`、在目标会话里执行 `/inbox bind`、需要 BMA3 业务工具时选用 BMA3 预设。[DSH bundle 安装契约](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/user/develop/basic/publish.md)

Inbox 的提示也需要随会话能力调整。当前面板截断时会建议调用 JAR 的 `inbox_list`，独立服务重连时也有全局 MCP 提示。普通会话改为显示预览和可用的 Inbox 控制项，完整消息仍从消息板读取。ACK 继续走现有插件 HTTP 路径，会话收到预览无需依赖 MCP 工具。[预览渲染](../../../dsh-plugin/lib/inbox.js#L576) · [Inbox 命令](../../../dsh-plugin/lib/inbox.js#L3420)

升级时应先停止旧 profile，再对支持的旧托管包执行替换，复用原有运行根、配置、数据库、Inbox 状态和停止意图。旧独立部署的配置继续指向原服务。原先手动挂在 profile 层的 BMA3 MCP 行也要迁入 BMA3 预设或撤销，否则普通会话仍会看到其工具。迁移应识别现有配置来源，并对用户自己的连接给出明确诊断。

适合作为首版的验收是从干净 Linux Web profile 一次安装，再同时打开普通和 BMA3 会话。用普通会话启停、重启和修改运行设置，验证管理入口两边始终可见，MCP 随后端状态在 BMA3 会话内增减。再从旧普通包、旧整合包各升级一次，核对状态保留、只有一套插件行、只有一条 Inbox SSE。预设作用域切换与实际 Agent 回合应继续用目标 DSH 版本验证。

## 待用户拍板

**`configure` 的范围。** 建议先管生命周期配置，包括启动策略、受支持的运行来源和经校验的启动配置。内置镜像作为默认值，已有本地 JAR 路径由 profile 管理员配置。BMA3 业务配置可以沿用现有 Java 校验及热改工具。如果要求从普通会话在 JAR 停机时也能编辑 `app.yaml`，则须定义复用同一 Java 校验逻辑的离线入口，并区分即时生效、重启生效与验证失败。

## 验证记录

证据目录为 `/tmp/bma3-dsh-linux-survey.QjqvcLcr/`，源码来自 `git archive HEAD`。

| 验证 | 结果与证据 |
|---|---|
| `git -c http.proxy= ls-remote --exit-code origin refs/heads/feat/0.0.9-early`、`git -c http.proxy= fetch --no-tags origin feat/0.0.9-early`、`git merge --ff-only --autostash @{u}` | 均 rc=0，`4d41002 → 5dfdd38`，原未提交状态补丁自动还原。 `git rev-list --left-right --count HEAD...@{u}` 为 `0 0`。 |
| `MAVEN_ARGS="-o -Dmaven.repo.local=<隔离缓存>" bash packaging/build-dsh-bundle.sh`，以及对 tgz 执行 `sha256sum -c mosire-bma3-dsh-managed-0.0.9-fix1.tgz.sha256` | 均 rc=0，构建与包内启动器版本校验通过。隔离缓存补齐 Maven Central 的 JNA 5.18.0 依赖，首次在沙箱内的启动器自检遇到 `EPERM`，获准重跑后通过。日志 `build-linux.log`。 |
| `dsh plugin --profile web add <tgz> --offline --ignore-scripts --store-dir <隔离目录>` | 整合包安装 rc=0，证据 `install.log`。另一隔离 profile 加装普通包及 `dsh --profile web --dump-config` 均 rc=0，两套同名行见 `dump-both.log`。 |
| `node <证据目录>/web-probe.mjs <本机 DSH bin.js>` | rc=0。真实 Linux DSH Web、原生工具表、两 Agent Session 和真实回环 JAR 共 6 个场景通过，证据 `web-probe-native.log`。探针直接调用工具与命令处理入口。 |
| `/usr/bin/node --test --experimental-test-isolation=none`（`dsh-plugin/`） | Node `22.23.3` rc=0，190/190，见 `plugin-tests-node22.log`。默认 Node `26.13.1` 同套测试 rc=1，189/190，`host-tools.test.mjs` 的「初次解析失败后重新安装」断言失败，见 `plugin-tests.log`。该 Node 版本也完成了本次真实宿主探针。 |

新单包升级、持久化 `configure`、真实模型回合、QQ、完整 Maven 测试门禁与 Windows 验收均为 `NOT_RUN`。
