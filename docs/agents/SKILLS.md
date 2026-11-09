# 工程 Skill 目录与维护规则

> **本文件属于项目维护。** 工程 Skill 面向维护本项目的编码 Agent。
> 目录：`.agents/skills/`。候选放 `.work/skill-candidates/`（未出现前不创建）。

## 一、什么该进 Skill

1. **项目设计硬约束** → `docs/superpowers/specs/**`；
2. **项目维护硬约束** → `docs/agents/MAINTENANCE_RULINGS.md`、`docs/AGENTS.md`；
3. **能机械判定的内容** → `tools/*.py`、测试；
4. **只在某类重复任务中使用的判断流程** → `.agents/skills/<name>/SKILL.md`；
5. **当前状态/一次性证据** → `STATUS.md`、`docs/superpowers/status/**`、报告。

## 二、当前登记

**没有 active Skill。**

初始化期不复制继承来源的 Skill：

| 旧 Skill | 为什么暂不继承 |
|---|---|
| `qq-persona` | 绑定旧 Bot 运行实例与人设注入机制；人设是否保留未定。 |
| `iibot-qq-operations` | 绑定旧 `qq_*` 工具面与 Hermes 运行环境；工具面是否兼容未定。 |
| `inbox-wake-turns` | 绑定旧 Inbox 唤醒回合；新运行时形态未定。 |

这些 Skill 只能作为调查材料；若未来要吸收，先由设计 spec 明确职责，再按状态机从 pilot 走到 active。

## 三、状态机

```text
proposed -> drafted -> pilot -> active -> deprecated -> retired
                         |          |
                         +----------+--(回归/宿主失配/越界)--> drafted / blocked
```

| 状态 | 位置 | 能否被发现 | 进入条件 |
|---|---|---|---|
| proposed | `.work/skill-candidates/<name>/` | 否 | 有重复任务/返工迹象，先登记候选 |
| drafted | `.work/skill-candidates/<name>/` | 否 | 已写草案，契约自检通过 |
| pilot | `.work/skill-candidates/<name>/` | 否 | 在真实任务中对照验证，仍只显式使用 |
| **active** | **`.agents/skills/<name>/SKILL.md`** | **是** | pilot 通过；有 owner、复核日、退役条件；登记到本表 |
| deprecated | `docs/archive/skills/<name>/` | 否 | 失真、被替代、无 owner、维护成本大于收益 |
| retired | `docs/archive/skills/<name>/` | 否 | 归档/删除实现，只留历史说明 |

硬规则：

- 不跳过 pilot 直接 active；没有真实任务证据的 Skill 不进入 `.agents/skills/`。
- 任何导致误导执行的 Skill 先停用再修。
- 候选/归档 Skill 不得放进 `.agents/skills/`，否则宿主会误发现。
- 不为「目录完整」创建空 Skill。

## 四、Skill 契约

`SKILL.md` 最小 frontmatter：

```yaml
---
name: <目录名>
description: <一句话说明何时使用>
status: active
owner: <owner>
last_reviewed: YYYY-MM-DD
---
```

必备章节：

1. 何时使用 / 何时不使用；
2. 前置条件（目录、工具、权限、是否联网/跑构建）；
3. 步骤（可执行、带工作目录）；
4. Gate（命令、退出码、必须核对的输出/证据）；
5. Report（任务、基线、实际执行、改动、未做/未验证、证据边界）；
6. 失败与停止条件；
7. 维护（owner、复核周期、退役条件）。

体量与安全：

- `SKILL.md` 建议 ≤ 200 行，单文件 ≤ 32 KiB；
- 不写本机临时目录、密钥、token、真实配置；
- 不用 `@` 导入长文；
- 不复制根规则；需要时放链接。

## 五、机械检查

目标命令（脚本尚未迁入，**NOT_RUN**）：

```bash
python3 tools/skills-lint.py
```

检查内容：只允许一层 active 目录、frontmatter 与目录名一致、`status=active`、必备元数据、大小上限、相对链接存在、目录与本表登记一致。
