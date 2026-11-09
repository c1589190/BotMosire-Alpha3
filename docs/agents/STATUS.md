# STATUS —— 更新时间：2026-11-09 19:44 (UTC+8)

## 当前目标 / 进行中范围

1. **已完成**：只读调查继承来源 `DevMosire-iiBot`；建立根 `AGENTS.md`、文档系统、裁定索引与 Skill 规范；初始化本仓 Git 与远端；完成第一轮 bot 工作流前置调查（LLBot/Milky、DSH 注入面、B 站视频/私信、iibot/Inbox、语言选项）。
2. **已定方向**：Java 21 + `jlink`/`jpackage`（D-006）；Inbox 独立常驻服务（D-004）；QQ 第一版单正向 WS（D-002）；DSH 一个插件包内维护 Inbox 注入/角色提示词/工具组预设三个独立组件（D-003）；Inbox lib 只封装单 inbox 状态机（D-004）；分模块配置/日志与 QQ 多账号热插拔（D-007）。
3. **进行中**：评审初版模块图 `agentlib / qqconnect / webconnect / inbox / app`（D-008）；确认是否补共享 `core`/`store`，以及 Inbox 是 app 内模块还是第二进程。
4. **未开始**：模块划分定稿、数据模型、适配器 SPI 细节、安装器脚本、任何产品代码。

## 源码基线

- 仓库：`/home/cna/BotMosire-Alpha3`。
- Git：`main`；初始化提交见 `git log -1`；remote `origin` = `https://github.com/c1589190/BotMosire-Alpha3`。
- 工作树：有未提交文档改动（新增调查、STATUS 更新）；产品代码仍为空。
- 产品代码：无。没有 `src/`、`pom.xml`、`package.json`。
- 继承参考：`/home/cna/DevMosire-iiBot`（只读；5 个嵌套 Git 仓，HEAD 见 [`ENVIRONMENT.md`](ENVIRONMENT.md)）。

## 最近门禁

| 检查 | 命令 | 结果 |
|---|---|---|
| 文档相对链接 | 一次性 Python 链接检查（见 `docs/AGENTS.md` §8） | **PASS**：checked=47 / missing=0 / rc=0（2026-11-09 19:44） |
| Git 状态 | `git status --short --branch` | `## main`；有未提交调查/STATUS 改动；`origin` 已配置、未 push |
| 构建 / 单元测试 | — | `NOT_RUN`（无构建文件） |
| 真实 QQ / B 站 / 宿主联调 | — | `NOT_RUN` |
| 邻居项目测试复跑 | — | `NOT_RUN`（只读调查，未构建） |

## 当前有效设计 / 维护入口

- 设计裁定：D-001（继承与重构）、D-002（单正向 WS）、D-003（DSH 插件三组件）、D-004（Inbox 独立服务 + 单 inbox lib）、D-005（浏览器 sidecar，提议）、D-006（Java jlink/jpackage + 一站式安装）、D-007（配置/日志/热插拔）、D-008（初版模块图）[`../superpowers/DESIGN_RULINGS.md`](../superpowers/DESIGN_RULINGS.md)。
- 维护裁定：M-001（[`MAINTENANCE_RULINGS.md`](MAINTENANCE_RULINGS.md)）。
- 文档系统：[`../AGENTS.md`](../AGENTS.md)。
- 继承调查：[`../superpowers/reports/2026-11-09-neighbor-DevMosire-iiBot-survey.md`](../superpowers/reports/2026-11-09-neighbor-DevMosire-iiBot-survey.md)。
- Bot 工作流前置调查：[`../superpowers/reports/2026-11-09-bot-workflow-design-survey.md`](../superpowers/reports/2026-11-09-bot-workflow-design-survey.md)。
- 设计基线：[`../superpowers/specs/2026-11-09-bma3-foundation-design.md`](../superpowers/specs/2026-11-09-bma3-foundation-design.md)。
- 实施计划：[`../superpowers/plans/2026-11-09-bma3-foundation-plan.md`](../superpowers/plans/2026-11-09-bma3-foundation-plan.md)（Task 1..13）。
- AgentLib 变更需求：[`../upstream/agentlib-change-requests.md`](../upstream/agentlib-change-requests.md)（P0 认证/source 批量注册/取消，P1 resync/工具分组）。
- 未决岔路：Q-A..Q-L（共享 core/store、Inbox 进程边界、webconnect 范围、B 站/Playwright、兼容性、数据迁移等）。

## 未验证 / 风险 / blocker

- **未验证**：邻居的 343 tests、架构审查中的 227 MB / 99% Playwright / 22,559 行适配器等数字均为邻居记录，本次未复跑。
- **未验证**：邻居文档的 HEAD/仓库数量与实际不一致（实测 5 个 Git 仓，文档写 4/6 个）；引用旧文档前必须复核。
- **未验证**：B 站私信没有现成 DSH 插件；现有 iibot `BilibiliAdapter` 的登录续期链有已知缺口，复用前必须重修。
- **已确认（本机源码/实测）**：AgentLib 0.2.4 的 `AgentTool`/`ToolRegistry`/`AgentToMcpServer.startHttp` 可用；P0 变更未合并前，用单条 `register(sourceId, tool)`、工具内超时、`BotMcpServer` 风控包装兜住；固定 0.2.4，不依赖 SNAPSHOT。Java 的 `jlink` 最小运行时约 44 MB，`jpackage` app-image 约 45 MB（Hello world）。
- **已确认（本机环境）**：`/snap/bin/chromium`、Playwright 缓存、`Xvfb` 存在；`x11vnc`/`noVNC`/`xdotool` 未安装，Linux 全桌面控制需要另装 sidecar。
- **风险**：模块图缺共享消息板/会话存储；若让 Inbox 兼做存储，跨平台历史/已读/发送状态会碎。
- **风险**：AgentLib 的 HTTP MCP 无鉴权/TLS；必须复用 `BotMcpServer` 的回环 Bearer 代理，不能直接暴露。
- **风险**：DSH 插件管理器没有通用 service 安装钩子；一站式安装应做 first-run bootstrap + 明确 `setup` 命令，不能只依赖 npm `postinstall`。
- **blocker**：无硬阻塞；计划已出，待用户评审并选择执行方式。

## 下一步

1. 用户评审[实施计划](../superpowers/plans/2026-11-09-bma3-foundation-plan.md)。
2. 选择执行方式：subagent-driven 或 native；确认后从 Task 1 开始。
3. 第一条命令：

```bash
git status --short --branch
```
