# 维护裁定（M-xxx）

> 项目维护类裁定的逐字原话 + 落点。设计裁定在 [`../superpowers/DESIGN_RULINGS.md`](../superpowers/DESIGN_RULINGS.md)。
> 本文件只记录用户明确说过的维护规则；Agent 的实施选择写「落点说明」，不冒充裁定。

| ID | 日期 | 来源 | 原话 | 落点 |
|---|---|---|---|---|
| M-001 | 2026-11-09 | 本会话用户消息 | 「从隔壁的DevMosire-iiBot了解隔壁项目基本情况」<br>「这个先把规矩立住了写代码才不烧心，从隔壁抄一下AGENTS.md和文档系统规范先，不要急着写代码，等我描述需求」 | 根 `AGENTS.md`；`docs/AGENTS.md`；`docs/agents/**`；本次初始化提交 |
| M-002 | 2026-11-10 | 本会话用户消息 | 「裁定项不要只列决定本身，要列决定所处的代码环境语义（进AGENTS.md）」<br>「AGENTS.md里是否要求文档中进用户原话？我记得我要求写过，确认一下吧」 | 设计裁定条目在 `DESIGN_RULINGS.md` 保留逐字原话；代码环境语义进专题 spec 与模块 `AGENTS.md`；根 `AGENTS.md` 只放薄红线/路由；见 `docs/AGENTS.md` §二/§九 |

## 落点说明

- 「抄 AGENTS.md 和文档系统规范」= 以 `DevMosire-iiBot/docs/AGENTS.md` 与 `iiBotMosire/docs/AGENTS.md` 为母本，按「单仓项目 + 继承重构」改写；**没有**逐字照搬多仓工作区规则，也没有复制旧项目业务文档、源码或运行期 Skill。
- 「不要急着写代码」= 初始化期不创建 `src/`、`pom.xml`、`package.json`；需求 spec 确认前不进入实现。见根 `AGENTS.md` §1 与 [`WORKFLOW.md`](WORKFLOW.md)。
- 「了解隔壁项目基本情况」= 只读调查，产出 [`../superpowers/reports/2026-11-09-neighbor-DevMosire-iiBot-survey.md`](../superpowers/reports/2026-11-09-neighbor-DevMosire-iiBot-survey.md)；未构建、未运行、未修改继承来源。
- 用户给出的 GitHub 链接记录在根 `AGENTS.md` 与 [`ENVIRONMENT.md`](ENVIRONMENT.md)；本仓初始化时远端为空仓。
- M-002 的落点：`DESIGN_RULINGS.md` 保留逐字原话 + 简短落点；完整代码环境语义（模块/数据结构/状态机/配置/并发/失败模式/验收）进专题 spec 与模块 `AGENTS.md`。根 `AGENTS.md` 只放常驻红线/路由，避免重新变成厚设计文档。
- M-002 对“用户原话”的确认：根 `AGENTS.md:56` 要求设计裁定以「D-xxx，逐字原话」进入 `DESIGN_RULINGS.md`；`docs/AGENTS.md:144` 要求引用用户原话逐字并带日期；`DESIGN_RULINGS.md:3,39` 再次要求逐字原话与日期。
