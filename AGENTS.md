# AGENTS.md — BotMosire-Alpha3

> **更新时间**：2026-11-09（Plan 1 已实现）
> **定位**：单仓项目；DevMosire-iiBot 的继承与重构。
> **当前阶段**：Plan 1（core / agentlib / qqconnect / inbox / app / DSH 插件 / jpackage）与 Plan 2 的 webconnect（Selenium + 系统 Chromium）已实现；B 站已扫码登录成功，视频逐帧查看器、QQ 图片发送已实现并实测；真实 LLBot/DSH/systemd 联调仍 `NOT_RUN`。
> **远端**：<https://github.com/c1589190/BotMosire-Alpha3>
> **继承来源（只读参考）**：`/home/cna/DevMosire-iiBot`

## 0. 冲突顺序

1. 用户当次明确指令。
2. 用户设计裁定：`docs/superpowers/DESIGN_RULINGS.md`（D-001..D-008）。
3. 项目维护裁定：`docs/agents/MAINTENANCE_RULINGS.md`、`docs/AGENTS.md`。
4. 当前有效的专题设计：`docs/superpowers/specs/**`。
5. 本文件。
6. `docs/agents/STATUS.md` 的当前事实（只覆盖事实，不覆盖设计/维护规则）。
7. 旧项目文档、历史 status、调查报告与外部资料（只作证据，不据此开工）。

## 1. 当前阶段（硬约束）

- 已完成：继承调查、文档系统、Java 21 技术选型、Plan 1 实现（core/store、AgentLib MCP、OneBot 11 正向 WS、Inbox、app、DSH 薄插件、jpackage/installer 骨架）。
- 当前门禁：`./mvnw -q test` PASS（16 suites / 29 tests，1 个真实浏览器 smoke 默认跳过）；`dsh-plugin` `npm test` PASS（5 tests）；`packaging/smoke.sh` PASS。
- 未开始：B 站登录/私信/视频工具、Linux 桌面控制、真实 LLBot/DSH/systemd 联调。
- **仍然禁止无 spec 直接写产品代码；新功能按「需求 → spec（不变量 + 验收判据）→ 计划 → 实现」推进。**

## 2. 继承关系

- `DevMosire-iiBot` 是协调工作区，不是 monorepo 根；其内部有 5 个独立 Git 仓，另有运行目录 `bot/`、`bot2/` 和不带 Git 的 `InboxMosire-OpenCode/`。
- 产品主体是 `iiBotMosire`：Java 21 + Maven，4 模块（`iibot-connect` / `iibot-video-connect` / `iibot-bot` / `iibot-app`），做 QQ（OneBot 11）/ B 站会话、消息板、Bot MCP 与 Inbox 唤醒。
- 配套是 `InboxMosire`（通知板）与三套宿主适配器（DSH / OpenCode / Hermes）。
- 上述内容是**继承来源的现状**，不是本项目的既定架构。本项目已定 Java 21 + jlink/jpackage、单仓多模块、DSH 单宿主；旧 4 模块/三宿主形态只作参考。

只读调查见：[邻居项目基本情况调查](docs/superpowers/reports/2026-11-09-neighbor-DevMosire-iiBot-survey.md)。

**停止线**：不修改、不提交、不清理、不重启继承来源目录中的任何仓库或运行实例；需要对照时只读。

## 3. 常驻红线

1. **默认只绑定回环地址**；非回环监听必须 fail-closed。
2. **密钥零泄露**：token、Authorization、QQ/B 站凭据不得进入日志、MCP 结果、配置样例或提交；B 站 Cookie 文件必须 0600。
3. **正文不落日志**：日志只记录 ID、状态、计数、错误码、耗时和参数 key。
4. **消息板是业务事实源**：完整消息、历史、未读和发送状态以消息板为准；唤醒层只做通知与预览。
5. **Agent 不维护内部标记**：revision、snapshot、itemId、内部 seq、幂等键由服务端管理。
6. **发送必须经消息板**；`UNKNOWN` 表示可能已发送，禁止自动重发。
7. **账号不能串号**：出站按 thread 的 accountId 路由，不匹配 fail-closed。
8. **真实联调不冒充通过**：Mock/fake/回环测试不能写成真实 QQ/宿主验收；没跑写 `NOT_RUN`。
9. **只改本项目**：不跨项目 `git add -A`；不把继承来源的文件复制进来冒充本项目成果。
10. **提交/推送按当次指令**：初始化骨架允许一次本地提交；此后 `commit`/`push` 需用户明确授权，不 force push、不改写已推送历史。

## 4. 文档路由

| 要什么 | 去哪 |
|---|---|
| 文档放哪、谁说了算、怎么取代 | [`docs/AGENTS.md`](docs/AGENTS.md) |
| 当前状态、未验证项、下一步 | [`docs/agents/STATUS.md`](docs/agents/STATUS.md) |
| 用户设计裁定（D-xxx，逐字原话） | [`docs/superpowers/DESIGN_RULINGS.md`](docs/superpowers/DESIGN_RULINGS.md) |
| 用户维护裁定（M-xxx） | [`docs/agents/MAINTENANCE_RULINGS.md`](docs/agents/MAINTENANCE_RULINGS.md) |
| 工作流 / 验证 / 本机环境 | [`docs/agents/WORKFLOW.md`](docs/agents/WORKFLOW.md)、[`VERIFICATION.md`](docs/agents/VERIFICATION.md)、[`ENVIRONMENT.md`](docs/agents/ENVIRONMENT.md) |
| Skill 规范与登记 | [`docs/agents/SKILLS.md`](docs/agents/SKILLS.md)、`.agents/skills/` |
| 设计稿 / 计划 / 调查 / 评审 / 历史快照 | [`docs/superpowers/`](docs/superpowers/AGENTS.md) |
| 继承来源调查 | [`docs/superpowers/reports/2026-11-09-neighbor-DevMosire-iiBot-survey.md`](docs/superpowers/reports/2026-11-09-neighbor-DevMosire-iiBot-survey.md) |
| 外部契约摘要 | [`docs/upstream/AGENTS.md`](docs/upstream/AGENTS.md) |

## 5. 工作方式

1. **读**：本文件 → 相关目录 `AGENTS.md` → `STATUS.md` → 对应 spec/裁定。
2. **查**：先 `deja_recall` / `grep` 找既有实现与历史决定，不重复造。
3. **判**：设计改动写 spec；维护改动写 `MAINTENANCE_RULINGS.md`；两者都不沾才直接动手。
4. **改**：一次只做一个可独立验证的责任区。
5. **验**：按 `VERIFICATION.md` 留下命令、rc、测试数与证据路径；没跑写 `NOT_RUN`。
6. **记**：更新 `STATUS.md`；有设计影响时同步 spec 与裁定索引。

## 6. 目录现状

```text
BotMosire-Alpha3/
├── pom.xml / mvnw       # Maven 多模块父工程
├── bma3-core/           # 领域模型 + SQLite 消息板 + 配置/日志原语
├── bma3-agentlib/       # AgentLib ToolRegistry/MCP Bearer 包装
├── bma3-qqconnect/      # OneBot 11 正向 WS、账号 registry、QQ AgentTools
├── bma3-inbox/          # 单 Inbox 状态机 + HTTP + AgentTools
├── bma3-webconnect/     # Selenium + 系统 Chromium 浏览器 AgentTools
├── bma3-app/            # 组合根：config/logging/MCP/health/Main
├── dsh-plugin/          # @mosire/bma3-dsh，三个 loader row
├── packaging/           # jlink/jpackage + 用户级 installer
├── docs/                # 维护/设计/上游契约
└── .agents/             # 工程 Skill 与决策解释（当前无 active Skill）
```

`dist/`、`build/`、`logs/`、`data/`、`target/` 均不提交；`config/app.yaml` 是本地运行配置。
