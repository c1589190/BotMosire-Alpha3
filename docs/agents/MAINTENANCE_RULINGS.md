# 维护裁定（M-xxx）

> 项目维护类裁定的逐字原话 + 落点。设计裁定在 [`../superpowers/DESIGN_RULINGS.md`](../superpowers/DESIGN_RULINGS.md)。
> 本文件只记录用户明确说过的维护规则；Agent 的实施选择写「落点说明」，不冒充裁定。

| ID | 日期 | 来源 | 原话 | 落点 |
|---|---|---|---|---|
| M-001 | 2026-11-09 | 本会话用户消息 | 「从隔壁的DevMosire-iiBot了解隔壁项目基本情况」<br>「这个先把规矩立住了写代码才不烧心，从隔壁抄一下AGENTS.md和文档系统规范先，不要急着写代码，等我描述需求」 | 根 `AGENTS.md`；`docs/AGENTS.md`；`docs/agents/**`；本次初始化提交 |

## 落点说明

- 「抄 AGENTS.md 和文档系统规范」= 以 `DevMosire-iiBot/docs/AGENTS.md` 与 `iiBotMosire/docs/AGENTS.md` 为母本，按「单仓项目 + 继承重构」改写；**没有**逐字照搬多仓工作区规则，也没有复制旧项目业务文档、源码或运行期 Skill。
- 「不要急着写代码」= 初始化期不创建 `src/`、`pom.xml`、`package.json`；需求 spec 确认前不进入实现。见根 `AGENTS.md` §1 与 [`WORKFLOW.md`](WORKFLOW.md)。
- 「了解隔壁项目基本情况」= 只读调查，产出 [`../superpowers/reports/2026-11-09-neighbor-DevMosire-iiBot-survey.md`](../superpowers/reports/2026-11-09-neighbor-DevMosire-iiBot-survey.md)；未构建、未运行、未修改继承来源。
- 用户给出的 GitHub 链接记录在根 `AGENTS.md` 与 [`ENVIRONMENT.md`](ENVIRONMENT.md)；本仓初始化时远端为空仓。
