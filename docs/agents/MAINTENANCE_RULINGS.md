# 维护裁定（M-xxx）

> 项目维护类裁定的逐字原话 + 落点。设计裁定在 [`../superpowers/DESIGN_RULINGS.md`](../superpowers/DESIGN_RULINGS.md)。
> 本文件只记录用户明确说过的维护规则；Agent 的实施选择写「落点说明」，不冒充裁定。

| ID | 日期 | 来源 | 原话 | 落点 |
|---|---|---|---|---|
| M-001 | 2026-11-09 | 本会话用户消息 | 「从隔壁的DevMosire-iiBot了解隔壁项目基本情况」<br>「这个先把规矩立住了写代码才不烧心，从隔壁抄一下AGENTS.md和文档系统规范先，不要急着写代码，等我描述需求」 | 根 `AGENTS.md`；`docs/AGENTS.md`；`docs/agents/**`；本次初始化提交 |
| M-002 | 2026-11-10 | 本会话用户消息 | 「裁定项不要只列决定本身，要列决定所处的代码环境语义（进AGENTS.md）」<br>「AGENTS.md里是否要求文档中进用户原话？我记得我要求写过，确认一下吧」 | 设计裁定条目在 `DESIGN_RULINGS.md` 保留逐字原话；代码环境语义进专题 spec 与模块 `AGENTS.md`；根 `AGENTS.md` 只放薄红线/路由；见 `docs/AGENTS.md` §二/§九 |
| M-003 | 2026-11-15 | 本会话用户消息 | 「开始吧。哦对了，子Agent默认使用deepseek-flash请计入AGENTS.md谢谢」 | 历史：此前根 `AGENTS.md` §5 将 `deepseek-flash` 设为子 Agent 默认模型；该限制已由 M-005 取代。 |
| M-004 | 2026-11-17 | 本会话用户消息 | 「可以直接在Windows上登录github，而不是要用wsl、维护一堆庞杂的工作状态！」<br>「wsl那里的本项目可能还得独立维护！」 | Windows 工作区使用 Windows 自有的 GitHub 登录、Git 和构建环境；不把 WSL 仓库、登录状态或运行实例当作本工作区依赖。WSL 中的项目可能独立维护，不修改其仓库或配置；见 [`STATUS.md`](STATUS.md) 与 [Windows MCP 草案](../superpowers/specs/2026-11-17-windows-codex-snowluma-design.md)。 |
| M-005 | 2026-11-17 | 本会话用户消息 | 「额，首先，我想说的是，有些bug也许你自己就可以直接改掉<br>其次是，把子Agent只能用v4.1flash的规定去掉，现在你想用子Agent就用子Agent，想用啥模型就用啥模型」 | 历史：取代了 M-003 的默认模型限制，后由 M-006 撤销仓库中的委派规则。 |
| M-006 | 2026-11-24 | 本会话用户消息 | 「若当前目录下项目残留了有关子Agent调用的规则，请先行取缔，你可以使用任意gpt系列模型作为子Agent进行调用，但是我认为你自己可以直接完成大部分工作」 | 撤销根 `AGENTS.md` 的委派规则、文档工作流的专门交接项和旧实施计划的强制执行方式。保留历史裁定原话。 |

## 落点说明

- 「抄 AGENTS.md 和文档系统规范」= 以 `DevMosire-iiBot/docs/AGENTS.md` 与 `iiBotMosire/docs/AGENTS.md` 为母本，按「单仓项目 + 继承重构」改写；**没有**逐字照搬多仓工作区规则，也没有复制旧项目业务文档、源码或运行期 Skill。
- 「不要急着写代码」= 初始化期不创建 `src/`、`pom.xml`、`package.json`；需求 spec 确认前不进入实现。见根 `AGENTS.md` §1 与 [`WORKFLOW.md`](WORKFLOW.md)。
- 「了解隔壁项目基本情况」= 只读调查，产出 [`../superpowers/reports/2026-11-09-neighbor-DevMosire-iiBot-survey.md`](../superpowers/reports/2026-11-09-neighbor-DevMosire-iiBot-survey.md)；未构建、未运行、未修改继承来源。
- 用户给出的 GitHub 链接记录在根 `AGENTS.md` 与 [`ENVIRONMENT.md`](ENVIRONMENT.md)；本仓初始化时远端为空仓。
- M-002 的落点：`DESIGN_RULINGS.md` 保留逐字原话 + 简短落点；完整代码环境语义（模块/数据结构/状态机/配置/并发/失败模式/验收）进专题 spec 与模块 `AGENTS.md`。根 `AGENTS.md` 只放常驻红线/路由，避免重新变成厚设计文档。
- M-002 对“用户原话”的确认：根 `AGENTS.md:56` 要求设计裁定以「D-xxx，逐字原话」进入 `DESIGN_RULINGS.md`；`docs/AGENTS.md:144` 要求引用用户原话逐字并带日期；`DESIGN_RULINGS.md:3,39` 再次要求逐字原话与日期。
