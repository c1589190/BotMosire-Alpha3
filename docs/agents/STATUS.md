# STATUS —— 更新时间：2026-11-09 20:24 (UTC+8)

## 当前目标 / 进行中范围

1. **Plan 1 + webconnect 已实现**：Java 21 独立服务、core SQLite 消息板、AgentLib MCP、OneBot 11 正向 WS、账号热插拔、QQ AgentTools、Inbox、app、DSH 薄插件、jpackage；另加 Selenium/系统 Chromium 的 `browser_*` 工具。
2. **进行中**：webconnect 实测收尾；真实 LLBot/DSH/systemd 联调仍待授权。
3. **未开始（后续 Plan）**：B 站登录/私信/视频工具、Linux 桌面控制、Web 管理面。

## 源码基线

- 仓库：`/home/cna/BotMosire-Alpha3`。
- 分支：`feat/bma3-plan-1`（从 `main` 的 `2ecc94a` 起）。
- 产品代码：`bma3-core` / `bma3-agentlib` / `bma3-qqconnect` / `bma3-inbox` / `bma3-webconnect` / `bma3-app`；DSH 插件在 `dsh-plugin/`；打包脚本在 `packaging/`。Plan 1 + webconnect 在 `feat/bma3-plan-1`。
- 远端：`origin` = `https://github.com/c1589190/BotMosire-Alpha3`；未 push。
- 继承参考：`/home/cna/DevMosire-iiBot`（只读）。

## 最近门禁

| 检查 | 命令 | 结果 |
|---|---|---|
| Maven 全量测试 | `./mvnw -q test` | **PASS**：16 suites / 28 tests / 0 failures / 0 errors / 1 skipped（真实浏览器 smoke 默认跳过，2026-11-09 20:24） |
| DSH 插件测试 | `cd dsh-plugin && npm test` | **PASS**：5 tests / 0 fail（2026-11-09 20:05） |
| jpackage smoke | `bash packaging/smoke.sh` | **PASS**：app-image 启动，`/healthz` 200（2026-11-09 20:24，含 Selenium 依赖） |
| 文档相对链接 | 一次性 Python 链接检查 | **PASS**：checked=47 / missing=0 / rc=0（2026-11-09 20:05） |
| 真实 QQ / LLBot 联调 | — | `NOT_RUN` |
| 真实 DSH 宿主加载 | — | `NOT_RUN` |
| systemd 实际安装/开机常驻 | — | `NOT_RUN`（只跑 `install.sh --dry-run`） |
| 邻居项目测试复跑 | — | `NOT_RUN`（只读调查，未构建） |

## 当前有效设计 / 维护入口

- 设计裁定：D-001..D-008（[`../superpowers/DESIGN_RULINGS.md`](../superpowers/DESIGN_RULINGS.md)）。
- 设计基线：[`../superpowers/specs/2026-11-09-bma3-foundation-design.md`](../superpowers/specs/2026-11-09-bma3-foundation-design.md)。
- 实施计划：[`../superpowers/plans/2026-11-09-bma3-foundation-plan.md`](../superpowers/plans/2026-11-09-bma3-foundation-plan.md)（Task 1..13 已执行）。
- 文档系统：[`../AGENTS.md`](../AGENTS.md)。
- 继承调查：[`../superpowers/reports/2026-11-09-neighbor-DevMosire-iiBot-survey.md`](../superpowers/reports/2026-11-09-neighbor-DevMosire-iiBot-survey.md)。
- Bot 工作流前置调查：[`../superpowers/reports/2026-11-09-bot-workflow-design-survey.md`](../superpowers/reports/2026-11-09-bot-workflow-design-survey.md)。
- AgentLib 变更需求：[`../upstream/agentlib-change-requests.md`](../upstream/agentlib-change-requests.md)。

## 已实现的关键行为

- `qqconnect`：单正向 WS、token header、事件/动作同连接、账号 registry 热插拔（增删/换 endpoint 只影响对应账号）、QQ read/send/ack 工具；发送返回 `SENT/FAILED_BEFORE_SEND/UNKNOWN`。
- `inbox`：单 inbox `available → delivered → acked`、重试/`undelivered`、delivery ack、HTTP status/items/due/ack、`inbox_status/list/ack` 工具。
- `app`：加载 `config/app.yaml`、校验、Bearer MCP、health、Inbox HTTP、ConfigWatcher 对 QQ 账号做 reconcile；fake OneBot → 消息板 → Inbox → MCP read/ack/send 集成测试通过。
- `webconnect`：Selenium + 系统 Chromium，`browser_open/snapshot/click/type/text/screenshot`；B 站首页/热门页已通过 BMA3 MCP 打开、snapshot 并截图（2026-11-09 live smoke）。
- DSH：一个 npm 包三个 loader row（inbox/persona/preset），独立配置/失败域；未接真实 DSH。
- 打包：jlink + jpackage app-image、tar + SHA256、用户级 systemd unit 安装骨架；未实际安装 systemd。

## 未验证 / 风险 / blocker

- **未验证**：真实 LLBot 连接、真实 QQ 收发、真实 DSH 插件加载/followup、systemd 开机常驻均 `NOT_RUN`。
- **未验证**：AgentLib P0（HTTP MCP 认证钩子、source 批量注册、取消）未合并；当前用单条 `register`、`Bma3McpServer` Bearer 包装、工具内超时兜住。
- **限制**：`ConfigWatcher` 当前只对 QQ 账号做 reconcile；server 端口/路径、MCP/Inbox token、日志级别不会热切换。
- **限制**：`OneBot11Client` 暂无自动重连；断线后需要下一次 `reconcile`/配置变更触发重连。
- **限制**：DSH 插件未接真实宿主；`lib/inbox.js` 以轮询 due 方式工作，未做 SSE/occupancy。
- **限制**：Selenium 需要匹配 ChromeDriver；本机通过 `BMA3_CHROMEDRIVER` 指定，Selenium Manager 自动下载曾非常慢。
- **限制**：Snap Chromium 无法读取 `/tmp` 页面/配置；浏览器 profile 必须放 home 下（本机已验证）。
- **限制**：B 站目前只做公开页浏览/截图，未做登录、私信、视频播放工具。
- **风险**：加入 Selenium 后 `bma3-app` app-image 约 109 MB（tar 85 MB）；JVM + jlink + Selenium 比 Go/Node 重。
- **限制**：webconnect 只做公开页浏览/点击/截图；B 站登录、私信、视频播放工具未做。
- **限制**：Chromium 点击新标签页需要 300ms 切换；本机已加，但只做了首页/热门页实测。
- **blocker**：无硬阻塞；下一步是真实 LLBot/DSH 验收，或继续做 B 站登录/私信工具。

## 下一步

1. 在授权后启动真实 LLBot，验证连接/收发/热插拔；在 DSH 测试 profile 安装 `dsh-plugin` 验证三 row。
2. 真实 systemd 安装/重启验收。
3. 之后做 B 站登录/私信/视频工具。

```bash
git status --short --branch
```
