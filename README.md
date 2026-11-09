# BotMosire-Alpha3

DevMosire-iiBot 的继承与重构。**Plan 1 已实现：Java 21 独立服务 + QQ OneBot 11 + Inbox + MCP + DSH 薄插件 + jpackage 安装骨架。**

- 当前阶段：Plan 1 已实现并通过本地门禁；真实 LLBot/DSH/systemd 联调仍 `NOT_RUN`；Plan 2 做 webconnect/Selenium。
- 继承来源（只读）：`/home/cna/DevMosire-iiBot`
- 远端：<https://github.com/c1589190/BotMosire-Alpha3>

## 从这里开始

| 要什么 | 去哪 |
|---|---|
| 工作规则、红线、继承关系 | [`AGENTS.md`](AGENTS.md) |
| 当前状态与下一步 | [`docs/agents/STATUS.md`](docs/agents/STATUS.md) |
| 文档系统规范 | [`docs/AGENTS.md`](docs/AGENTS.md) |
| 邻居项目调查 | [`docs/superpowers/reports/2026-11-09-neighbor-DevMosire-iiBot-survey.md`](docs/superpowers/reports/2026-11-09-neighbor-DevMosire-iiBot-survey.md) |
| 设计与维护裁定 | [`docs/superpowers/DESIGN_RULINGS.md`](docs/superpowers/DESIGN_RULINGS.md)、[`docs/agents/MAINTENANCE_RULINGS.md`](docs/agents/MAINTENANCE_RULINGS.md) |

## 构建与测试

```bash
./mvnw -q test                 # Java 14 suites / 25 tests
(cd dsh-plugin && npm test)    # 5 tests
bash packaging/smoke.sh        # jlink/jpackage app-image + /healthz smoke
```

## 边界

新功能必须先有设计稿与验收判据；未跑过的验证一律写 `NOT_RUN`。真实 QQ/DSH/systemd 联调不得用 fake/回环测试冒充。
