# docs/AGENTS.md —— 文档系统规则

> **本文件属于项目维护，不属于产品设计。**
> 分层方式改写自 `DevMosire-iiBot/docs/AGENTS.md`（2026-11-05，其母本为 `iiBotMosire/docs/AGENTS.md` 2026-10-23），按「单仓项目」重写，不逐字照搬。
> **当前状态唯一入口**：[`agents/STATUS.md`](agents/STATUS.md)。
> 本文件只规定「文档放哪、谁说了算、什么时候写、怎么被检查」。

## 一、两条轴：项目设计 ≠ 项目维护

| 轴 | 收什么 | 正本位置 | 不放在哪里 |
|---|---|---|---|
| **项目设计** | 代码结构、模块边界、数据所有权、事件/API/载荷、运行时阶段、QQ/Agent/MCP 交互、提示词与可见性语义、数值公式 | `docs/superpowers/specs/**`；索引 `docs/superpowers/DESIGN_RULINGS.md` | 不写进维护裁定；不把维护流程塞进设计稿 |
| **项目维护** | 文档系统、Skill、门禁、验证、工作流、协作、环境、提交/归档 | `docs/AGENTS.md`、`docs/agents/**` | 不写进设计原稿；不冒充用户设计裁定 |

**分账规则**

1. 一条规则只能有一个主正本；另一侧只放链接，不复制全文。
2. 同一条用户原话同时影响两边时拆开记录：设计影响进 `DESIGN_RULINGS.md`，维护影响进 `MAINTENANCE_RULINGS.md`。
3. 代码「为什么这样」属于设计；「怎么验证/派单/提交」属于维护。
4. 分不清时先停手补分类，不默认塞进根 `AGENTS.md`。
5. 继承来源的旧设计不是本项目的设计；只有在被本项目 spec 明确吸收后才算数。

## 二、文档分层

| 层 | 路径 | 放什么 | 何时读 |
|---|---|---|---|
| 常驻路由 | `AGENTS.md` | 项目简介、冲突顺序、常驻红线、目录与文档索引 | 总是 |
| 文档系统 | `docs/AGENTS.md` | 本文件：分层、分账、取代、检查 | 写文档前 |
| 目录局部 | `docs/agents/AGENTS.md`、`docs/superpowers/AGENTS.md`、未来各模块 `AGENTS.md` | 本目录特有规则与局部索引 | 触达该目录后 |
| 实时状态 | `docs/agents/STATUS.md` | 当前目标、源码基线、最近门禁、未验证、下一步 | 每次开工/收工、压缩前 |
| 维护裁定 | `docs/agents/MAINTENANCE_RULINGS.md` | 文档、Skill、门禁、协作、环境的逐字原话 + 落点 | 改维护机制前 |
| 维护手册 | `docs/agents/WORKFLOW.md`、`VERIFICATION.md`、`ENVIRONMENT.md` | 可执行流程、命令、证据规则、本机事实 | 对应任务开始时 |
| 工程 Skill | `.agents/skills/<name>/SKILL.md`；目录见 `docs/agents/SKILLS.md` | 宿主可发现的可复用判断流程 | 任务匹配时 |
| 项目设计 | `docs/superpowers/specs/**` | 需求原话、专题设计、目标运行序列、契约、不变量、验收判据 | 设计/实现前 |
| 设计索引 | `docs/superpowers/DESIGN_RULINGS.md` | D-xxx 与专题入口 | 找设计依据时 |
| 计划 | `docs/superpowers/plans/**` | 按设计拆出的实施顺序；不是设计本身 | 派单/执行前 |
| 调查/报告 | `docs/superpowers/reports/**` | 调查、审计、复现与证据摘要；**只作追溯** | 追溯事实时 |
| 评审 | `docs/superpowers/reviews/**` | 独立评审与裁决 | 评审/关账时 |
| 历史快照 | `docs/superpowers/status/**` | 某时点状态、交接、证据摘要；**不是当前状态入口** | 只作追溯 |
| 上游契约 | `docs/upstream/**` | 外部系统的契约摘要与出处（含继承来源） | 接线/对照前 |
| 决策解释 | `.agents/notes/{proposed,implemented,archived}/**` | 为什么这样定、被谁取代；不替代正本 | 需要背景时 |
| 机械强制 | `tools/check-docs.py`、`tools/skills-lint.py`、`tools/gate.sh`、CI | 能退出码判定的检查 | 直接运行 |

## 三、权威顺序与取代

### 3.1 冲突顺序

1. 用户当次明确指令；
2. 用户项目设计裁定（需求原话 / 当前有效设计）；
3. 项目维护裁定（本文件 / `MAINTENANCE_RULINGS.md`）；
4. 当前有效的专题设计或契约；
5. 根 `AGENTS.md` 的路由与常驻红线；
6. `STATUS.md` 的当前事实（只覆盖事实，不覆盖设计/维护规则）；
7. 旧文档、旧项目文档、历史 status、调查报告与外部资料。

> 旧的不变量文档不构成对用户当次裁定的否决权。发现冲突时先记录双方路径/日期/原话，再按本顺序判定。

### 3.2 取代怎么写

- 被取代的文档不删，在文件头加：
  `> **状态**：历史（YYYY-MM-DD）· **取代者**：<路径> · **勿据此开工**。`
- 取代者一侧在标题下写：`> **本文取代**：<旧路径>（YYYY-MM-DD）。`
- 局部修正只标范围与取代者，不把整篇历史文档改写成「当前事实」。
- **不得按文件名日期推断有效期**；以正文状态头、用户原话日期和实际代码/测试为准。

## 四、记录频率

| 触发 | 必须记录 |
|---|---|
| 用户给出新裁定 | 先分类：设计写 `DESIGN_RULINGS.md` / 专题 spec，维护写 `MAINTENANCE_RULINGS.md`；保留原话与日期 |
| 开始一个责任区 | `STATUS.md` 写当前目标、范围、预期产物；计划落 `plans/` |
| 完成一个可验证步骤 | 更新 `STATUS.md`：改动、命令、rc、测试数、证据路径、未验证项 |
| 门禁/真模型/长跑完成 | 在报告或 dated status 留真实输出；`STATUS.md` 只留摘要和链接 |
| 发现文档矛盾 | 记录双方路径/日期/事实；能判定的加取代头；不能判定的列入待裁定 |
| 子 Agent 交接 / 上下文压缩前 | 更新 `STATUS.md` 的下一步第一条命令、dirty 项、未验证项 |
| 发现可复用判断流程 | 先登记 Skill 候选；经真实任务验证后再进 `.agents/skills/`，不批量造空 Skill |
| 变更维护机制 | 先写 `MAINTENANCE_RULINGS.md` 条目，再改规则/目录/脚本 |

## 五、STATUS 与交接模板

`docs/agents/STATUS.md` 至少包含：

```text
更新时间（绝对日期 + 时区）
当前目标 / 进行中范围
源码基线（分支、HEAD、remote、工作树状态；无 Git 时写「未初始化」）
最近门禁（命令、rc、测试数、证据路径、是否 full；没跑写 NOT_RUN）
当前有效设计/维护入口
未验证 / 风险 / blocker
下一步（可执行的第一条命令）
```

交接时也可另写 dated 文件，但必须：

- 放 `docs/superpowers/status/`；
- 文件头标 `历史快照` + 指向当前 `docs/agents/STATUS.md`；
- 不叫「最新/唯一入口」。

## 六、目录级 AGENTS 规则

- 每个有独立规则的目录可放 `AGENTS.md`；宿主按祖先链加载。
- 局部文件必须短：目录职责、局部边界、关键入口、验证命令、当前文档入口。
- 局部文件不得复制根常驻红线、不得另立用户裁定、不得把历史状态写成当前事实。
- 未来落地源码模块时，必须同批补该模块 `AGENTS.md`。
- 局部规则只在本地目录生效；与设计/维护正本冲突 ⇒ 停手上报。

## 七、Skill 与规则的边界

| 规则形态 | 去处 |
|---|---|
| 项目设计硬约束（模块边界、数据所有权、事件/工具/公式） | `docs/superpowers/specs/**` |
| 项目维护硬约束（文档格式、门禁、提交、工作树） | `MAINTENANCE_RULINGS.md` / `docs/agents/**` |
| 能机械判定的内容 | `tools/*.py`、`tools/gate.sh`、测试 |
| 只在某类重复任务里用的判断流程 | `.agents/skills/<name>/SKILL.md` |
| 当前事实与一次性证据 | `STATUS.md` / `docs/superpowers/status/**` / 报告 |

Skill 的目录、状态机与契约见 [`agents/SKILLS.md`](agents/SKILLS.md)。

## 八、机械检查

当前**尚未迁入**任何文档检查脚本，以下命令状态均为 **NOT_RUN**：

```bash
python3 tools/check-docs.py
python3 tools/skills-lint.py
tools/gate.sh docs
```

在脚本落地前，人工按下列清单核对：

- 根 `AGENTS.md` 是薄路由，不承载长设计；
- 所有相对 Markdown 链接存在；
- `STATUS.md` 含模板关键字段，且「最近门禁」不是编造；
- 设计/维护分账标志存在，D-xxx / M-xxx 不混放；
- dated status 标注历史快照；
- Skill 目录与登记表一致；
- 无 `@` 导入长文；
- 旧项目文档没有被当作本项目当前事实。

引入脚本属于维护改动：先写 `MAINTENANCE_RULINGS.md`，再改目录/脚本，并用真实输出更新 `STATUS.md`。

## 九、引用与日期

- 引用用户原话必须逐字，日期取用户实际说出的日期；不可考时写「用户此前口头说明（转述）」。
- 设计裁定条目除逐字原话外，必须附「代码环境语义」：模块、数据结构、状态机、配置、并发/失败模式、验收判据，并链接专题 spec；根 `AGENTS.md` 只放薄红线/路由，不复制长语义（M-002）。
- 现状事实给 `file:line` 或 URL；跑过的结论给命令、rc、关键输出与证据路径。
- 没跑过的结论写 `NOT_RUN` / `未验证` / `BLOCKED`；不写「通过」。
- 外部资料分「一手文档」与「二次转述」；NapCat/OneBot/MCP 等 API 结论优先给官方 URL 或本地源码行号。
- 长文用普通 Markdown 链接，不用 `@` 导入。

## 十、本仓特例

1. **单 Git 仓**：当前只有 `/home/cna/BotMosire-Alpha3` 一个仓库；不再按多仓工作区组织。
2. **继承来源是外部证据**：`/home/cna/DevMosire-iiBot` 的文档、STATUS、报告只能作为调查材料；复制任何设计前必须先在本仓写 spec 并标注来源。
3. **初始化期没有构建文件**：`src/`、`pom.xml`、`package.json` 在需求 spec 确认前不出现；不要用「先搭架子」绕过。
4. **报告不作当前事实**：`docs/superpowers/reports/**` 只是某时点的证据，引用前先复核代码或重新验证。
5. **不复制旧 Skill**：继承来源的运行期 Skill（`qq-persona` 等）绑定旧架构，是否吸收由后续设计决定，不直接搬入 `.agents/skills/`。
