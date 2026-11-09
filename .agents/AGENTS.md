# .agents —— 工程 Agent 资产目录

> 本目录属于项目维护，不是产品运行时数据。

| 路径 | 放什么 | 宿主可发现 |
|---|---|---|
| `skills/<name>/SKILL.md` | **active** Skill | 是 |
| `notes/proposed/` | 提议中的决策解释 | 否 |
| `notes/implemented/` | 已落地决策解释 | 否 |
| `notes/archived/` | 已取代/归档决策解释 | 否 |

- Skill 状态机、frontmatter、体量与检查规则见 [`../docs/agents/SKILLS.md`](../docs/agents/SKILLS.md)。
- 候选 Skill 放 `.work/skill-candidates/`，不进本目录。
- 当前没有 active Skill；不要为目录完整造空 Skill。
- 决策解释记录「为什么这样定、被谁取代」，不替代设计/维护正本。
- 不放密钥、运行时数据、数据库、日志或本机绝对路径。
