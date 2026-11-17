# Windows、Codex 与 SnowLuma 调查

> 类型：调查（时间点快照，只作追溯，不作当前事实）
> 日期：2026-11-17（UTC+8）
> 触发：用户要求克隆项目，调查 Windows/Codex 和本机 SnowLuma 接入问题，做好修改准备。
> 方法：读取当前源码和设计文档；检查本机工具链；只读探测 SnowLuma；在 Windows Java 下短暂启动既有同版本 jar 并验证健康状态和 MCP；运行 Windows Node 测试。未发送 QQ 消息或执行 QQ 写操作。本文不含令牌、QQ 账号或消息正文。
>
> 后续范围更新：用户已按 [D-071](../DESIGN_RULINGS.md) 将 0.0.8 定为 Windows / 纯 MCP；本文关于自动唤醒或宿主适配的建议不属于该版本目标。WSL 项目可能独立维护，Windows 工作区不得依赖其状态（[M-004](../../agents/MAINTENANCE_RULINGS.md)）。

## 基线与边界

- 克隆位置：`C:\Users\cna\Documents\BotMosire-Alpha3`；分支 `main`，调查前 HEAD `96342b0`；Maven 版本 `0.0.7-tolatest`。相对 tag `v0.0.7-tolatest` 的差异只有文档。
- 系统：Windows 11；Microsoft JDK 21.0.10；Node.js 22.23.2；Codex CLI `0.169.0-alpha.11.1`。Windows PATH 中无 `mvn`。
- SnowLuma：本机 `127.0.0.1:3001`，版本 `2.2.0-node`；本次只在进程内通过 WebSocket 请求头或环境变量使用用户提供的 token，没有把该 token 写到配置或仓库。
- 原生运行验证使用 WSL 中已有的 `/home/cna/BotMosire-Alpha3/bma3-app/target/bma3-app-0.0.7-tolatest.jar`，**不是**从本次克隆在 Windows 新构建的 jar；WSL 同仓 HEAD 亦为 `96342b0`。jar 与 tag 之间只有文档差异，但其二进制可复现性未核对。

## 已确认事实

### 1. 基础连接可用，不能据此宣称完整 QQ 联调通过

- .NET `ClientWebSocket` 以 `Authorization: Bearer …` 连接 `ws://127.0.0.1:3001/`，握手成功。`get_version_info`、`get_status`、`get_login_info` 均 `status=ok, retcode=0`；`online=true, good=true`。`get_group_list`、`get_friend_list`、`get_group_system_msg` 均 `status=ok, retcode=0`，`data` 为数组。未记录账号、名单或消息内容。
- Windows Java 启动上述既有 jar，使用隔离的临时运行目录与 SnowLuma 环境变量；`/healthz` HTTP 200，watchdog 的 `qqAccount=UP/OK`。MCP `initialize` HTTP 200，获取 session；`tools/list` HTTP 200，共 61 个工具，含 `inbox_status` 与 `qq_account_status`。测试时显式关闭 debug/schedule，故该工具数不是完整默认配置的数量。
- 入口使用正向 WebSocket 和 Bearer header，见 `bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/onebot/OneBot11Client.java:48`；`bma3-app/src/main/java/io/mosire/bma3/app/Bma3App.java:680` 从配置的 `tokenEnv` 取 OneBot token。模板默认 `enabled: false`，见 `bma3-app/src/main/resources/config-template/app.yaml:18`。
- `NOT_RUN`：真实 QQ 消息入站写入消息板、Inbox 到 Codex 的主动唤醒、真实 QQ 消息发送/文件/图片/历史分页、断线和重启恢复。以上不能由握手和 `qqAccount=UP` 推导为通过。

### 2. SnowLuma 扩展协议存在具体不匹配

1. **群系统请求响应形状**：当前 SnowLuma 的 `get_group_system_msg` 实测返回数组；其本机发行包 `C:\Users\cna\Documents\SnowLuma-v2.2.0-win-x64\index.mjs:9194` 的动作定义也声明返回数组。BMA3 在 `bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/tools/QqGroupRequestListTool.java:50` 只读取 `data.invited_requests`/`data.join_requests`；数组输入会被当成空列表。`QqGroupInviteAcceptTool.java:110` 也只从分类对象查找邀请。当前没有对非空待处理请求做实测；根据代码，数组中的实际记录会被忽略。
2. **历史游标契约**：BMA3 `QqRemoteHistoryTool.java:141`、`:144`、`:150` 使用 `message_seq` 和 `reverseOrder`；本机 SnowLuma 动作定义 `index.mjs:11365` 使用 `message_id` 和 `reverse_order`，说明中要求用真实消息 ID 翻页。两者接口名称相同但参数/游标语义不同；实际分页重复、遗漏或服务端处理未知参数的行为 `NOT_RUN`。
3. **群请求处理**：SnowLuma 发行包 `index.mjs:10455` 的 `set_group_add_request` 需要同一请求事件的 `flag` 与 `sub_type`；省略 subtype 时默认为 `add`。BMA3 `QqGroupRequestDecideTool.java:64` 发送 `requestId` 作为 flag 且不发送 subtype；`QqGroupInviteAcceptTool.java:130` 也未发送 subtype。`OneBot11Codec.java:50` 只接收 `post_type=message`，不保留 request 事件。具体 flag 映射与非空邀请的真实结果 `NOT_RUN`，不能直接假定数字 requestId 等于 SnowLuma 的 flag。
4. BMA3 存在 `llonebot_debug` 回退（`QqGroupInviteAcceptTool.java:157`）；本机 SnowLuma 发行包文本中未找到该动作名。仅为静态检索，不证明完整动作目录或运行时行为。

建议在 provider 边界识别并标准化已知差异；无法识别时返回明确错误或不支持，避免把未知格式伪装成空列表。现有 LLBot 路径及其测试需保留。

### 3. Windows 凭据权限和忽略规则

- `Bma3Env.java:130`、`:151` 对 POSIX 权限不支持时直接跳过；`debug/DebugStore.java:81`、`:95` 亦如此。代码没有主动建立或验证 Windows DACL，因此实际访问权限依赖父目录的继承 ACL；不能等同于已落实 owner-only。此次临时运行目录是在启动前显式设为仅当前用户可访问，生成的 `.bma3env` 继承该 ACL，不能代表普通目录默认安全。
- 在仓库根执行 `git check-ignore -v --no-index .bma3env config/app.yaml config/.bma3env config/local/.bma3env .env`：只有后两项被忽略；前面三个路径没有匹配。`Bma3Env.resolve` 默认把 `.bma3env` 写在 `--config` 指定目录（`Bma3Env.java:60`），若把仓库当运行目录，就有被误加入版本控制的风险。建议同时完善忽略规则和运行目录约束。
- 敏感文件还包括调试请求、消息板与插件状态；需要明确定义 Windows ACL、创建前后权限、现有文件/异常、继承和重启的行为，不能只检查 POSIX mode 字符串。

### 4. Windows 平台能力和健康状态

- `PlatformProbe.java:58` 对非 Linux 返回 `PLATFORM_UNSUPPORTED`。实测即使核心服务可用，默认启用 Xvfb 时 watchdog 报 `xvfb=DEGRADED/PLATFORM_UNSUPPORTED`。
- `Bma3App.java:387` 只在启用时注册 Xvfb 工具，但 `Bma3App.java:415` 无条件注册并运行 Xvfb 探针，推断仅设 `xvfb.enabled=false` 仍会得到平台降级；此配置组合本次 `NOT_RUN`。
- `Bma3App.java:409` 无条件添加 `dshPlugin` 探针；本次未加载 DSH 插件，实测 `dshPlugin=DEGRADED/NO_HEARTBEAT`，`ready=true` 但 `overall=DEGRADED`。需区分未配置的宿主、真的失联和被明确禁用的能力。
- `bma3-app/src/main/resources/config-template/app.yaml:115` 预填 `/snap/bin/chromium`，该节默认关闭；`packaging/` 下的构建/安装/冒烟脚本均为 shell，安装使用 `systemctl --user`。当前发布资产和这些脚本不能作为原生 Windows 安装方案。

### 5. Codex 与 DSH 是不同宿主

- DSH 适配器依赖 `agents.resume` 和 `agent.followup`（`dsh-plugin/lib/inbox.js:956`、`:990`），并消费 Inbox `/events`（`:2585`）、上报插件心跳（`:1175`）。这不是通用 MCP 客户端行为，不能直接在 Codex 中加载该 DSH 插件来得到推送。
- 本机 `codex mcp add --help` 显示支持 streamable HTTP 与 `--bearer-token-env-var`；BMA3 的 MCP 握手和工具列表已被通用 HTTP 客户端验证。Codex 自身实际配置和调用 `NOT_RUN`。可先设计 MCP 主动读取，再单独设计事件驱动的会话适配和 ACK/重放；单有 MCP 不证明消息会自动唤醒 Codex。
- 本机 `codex exec resume --help` 存在按会话 ID 恢复的接口；[OpenAI 的 Codex 平台说明](https://developers.openai.com/blog/codex-as-a-platform)也区分 CLI、SDK 和 app-server。后台 adapter 能否安全续接**用户正在使用的桌面会话**、活动轮次冲突和错误恢复均未实测，不作既定结论。

### 6. 构建与测试

- 仓库只有 `mvnw`，无 `mvnw.cmd`；Windows PATH 中没有独立 Maven。使用 Git Bash 执行 `./mvnw --version` 时 wrapper 下载 Maven 失败，退出码 1；这是本次机器上的获取失败，未判定全部网络环境。
- WSL 有 Maven 3.9.16 和 `io.mosire:agentlib-mosire:0.2.5` 缓存。直接以 Windows Maven 指向 WSL 的 `\\wsl.localhost\...\.m2\repository`、离线执行 `test`，在 Maven 本地仓库 tracking 文件的 `FileChannel.lock` 阶段失败，退出码 1；此结果只说明该 UNC 缓存复用方式失败，**不是** Java 测试失败。Windows 原生源码编译和 Java 测试均 `NOT_RUN`。
- Windows `cd dsh-plugin; npm test`：退出码 1，167 项中 162 pass、5 fail。四项用目录 `chmod(0o500)` 模拟写入失败，Windows 下未产生所预期的写入失败；另一项直接断言 mode `0600`，得到十进制 438 而非 384（`test/inbox-state-safety.test.mjs:325`）。这是确认的测试移植问题；不单凭此推断四项相应业务路径在生产环境失败。应通过可控故障注入验证持久化失败，并在 Windows 用 ACL 验证权限。

## 推断与建议顺序

1. 优先修补 `.bma3env` 忽略及 Windows 敏感数据 DACL，并准备 Windows Maven wrapper、依赖可获取的构建方式和跨平台测试门禁。
2. 建立 provider 特定形状和游标适配：先覆盖上文三个已发现差异，再用脱敏 fixture 和只读 SnowLuma 回归；不要把未知响应当空结果。
3. 将可选模块与 watchdog 状态按实际启用/宿主能力区分；Windows 上的 Xvfb 未实现属于能力边界，是否另做原生桌面后端尚无用户裁定。
4. 第一阶段用标准 MCP 验证 Codex 可主动读 Inbox；第二阶段另行实现并验证可持久恢复的事件驱动适配。要复用现有消息板、ACK 安全及 `UNKNOWN` 不自动重发语义。
5. 单独规划 Windows 打包、安装和自启动；保留 Linux 构建与 systemd 路径。真实 QQ 发送、文件、重连和服务重启需有明确的测试目标与证据。

## 待确定与未验证

- Codex 使用形态：仅在用户打开的会话主动调用，还是希望 QQ 消息在后台自动唤醒指定会话；具体后台宿主、生命周期和冲突处理需以实际 Codex 支持为准。
- Windows 原生桌面操作是否属于首期范围；否则允许报告未支持，并避免影响 QQ/MCP 核心路径。
- WSL 私有 Maven 依赖的可分发来源、缓存复制/安装策略与 Windows CI 环境。
- 未提交产品代码；本报告对应的方案只是建议，设计草案见 `../specs/2026-11-17-windows-codex-snowluma-design.md`，实施准备见 `../plans/2026-11-17-windows-codex-snowluma-plan.md`。
- 临时 Java 测试进程已停止，测试端口已释放；受自动执行策略限制，临时目录 `C:\Users\cna\AppData\Local\Temp\bma3-win-investigation-20261117-8fd38b` 未能删除，目录 ACL 已保护为当前用户访问，里面有临时生成的 BMA3 token 和测试数据库（不含用户提供的 SnowLuma token）。

## 后续实施与验证（2026-11-17）

上文为初始调查快照；之后用户明确采用 Windows 独立构建、纯 MCP 和宿主提供的 Computer Use，不做 Codex 自动唤醒或桌面自动化插件。公开 ProjectMosire 固定版本源码已可在 Windows 本地构建 AgentLib；Windows Maven 全量测试 rc=0，334 项、2 skipped，随后纯 MCP 探针变更的 16 项相关测试 rc=0，最新 jar 已重新打包。详细状态和证据见 [STATUS](../../agents/STATUS.md)。

真实 Windows BMA3 已连接 SnowLuma，MCP 的账号、群和好友列表、非空群请求以及消息板历史读取成功；群历史两页均有记录且无重复。通过 Codex app-server 的临时客户端实际发现 54 个工具并调用账号列表和消息历史成功。SnowLuma 在测试期间重启后 BMA3 自动重连；当前 watchdog 总体及 QQ 为 `UP`。群请求的操作 flag 与其他 OneBot provider 的真实兼容性仍未验证；没有发送 QQ 消息。运行目录和使用说明见 [Windows 运行指南](../../windows-mcp.md)。
