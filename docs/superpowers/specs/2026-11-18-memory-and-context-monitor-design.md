# 长期记忆与上下文监控设计（备忘录 + 滚动上下文 + 水位探针）

状态：草案（待用户确认后进入实现）。裁定：D-078、D-079。想法记录（不构成实现授权）见 [设计想法记录](../reports/2026-11-18-memory-layer-design-notes.md)，市场调研见 [调查报告](../reports/2026-11-18-long-term-memory-survey.md)。

## 1. 需求原话（摘要）

- D-078 / m00147：「肯定是自主形成的、无预设对象的经验/知识，并且要求Agent显式写入」——记忆对象是**自我型经验**，写入只有 Agent 显式调用一个通道。
- D-078 / m00387：要一组工具——主动录入并打标签；按标签/文本检索，**标签命中优先级最高**、只回极简缩略、要求 Agent 主动查看、返回有限可翻页列表；查看具体记忆；看完标记有用/无用并影响排序权重。
- D-078 / m00613：「R1 不自动」「不设遗忘……直接给记忆权重硬设为-1即可……不进入记忆搜索调用的list」「R3 A」「搞一个最大存储条目限制，允许Agent热修改」「对标的是人用的记事本」「R6 也是可配置的权重增量值，默认10」。
- D-079 / m00616：「1 A 2 B」，且「允许读取和单独导出的最好不只是dsh的原生session event，还要允许读取bma3自己记录的完整上下文」「只有这样watchdog才是watchdog」。
- D-079 / m00433：watchdog 要能监听 dsh 运行；插件把新上下文条目推给 jar；jar 维护滚动上下文；**不消耗 LLM 算力**、自动格式化缩略；Agent 可查条目内容、独立选中、导出 txt；"导入"允许整份文件。

## 2. 目标与非目标

### 2.1 目标

1. 一个**记事本**：Agent 用 5 个工具写入/检索/查看/评分任意经验片段，标签优先、可翻页、零自动注入。
2. 一份**滚动上下文副本**：dsh 插件把绑定会话的新事件推给 jar，jar 按上限滚动保存完整正文，默认只回缩略，可选中导出 txt。
3. 一条**可核对的水位**：watchdog 能回答"插件在线但上下文停更/断层/推的是旧会话"，而不是只知道进程活着。
4. 读取面双份：DSH 原生会话工具（真相层）＋ jar 自己的滚动副本（便利层），互不替代。

### 2.2 非目标

- 不做遗忘/删除（D-078）。不设 `confidence`/`scope` 分级（记事本隐喻）。
- 不做自动注入、不做 pinned 核心记忆、不改 `systemPrompt` 组装。
- 不做后台 LLM 抽取、不做摘要、不上向量（向量留给 P4 eval 之后决定）。
- 不引入 Python 服务（Mem0/Zep/Letta 类），不新增外部模型客户端。
- 不改消息板写入路径，不把记忆表变成第二事实源。

## 3. 术语与三层职责

| 层 | 表 | 所有者 | 语义 |
|---|---|---|---|
| 事实层 | `chat_message` | `bma3-core`（既有，`SqliteMessageBoardStore.java:69-120`） | 业务事实源。不因本设计做任何改变 |
| 便利层 | `context_digest` | 本设计新增 | DSH 会话事件的**副本缓存**：可清空、可重放、可有界淘汰，缺失只损失便利 |
| 记事本 | `memory_item` | 本设计新增 | Agent 显式写入的经验片段。**不是事实源**，必须带 `source_kind`/`source_ref` |

双锚：`source_ref` 指向消息板 `local_seq`（"这话谁说的"）；`context_ref` 指向 DSH 会话 `(session_id, event_seq)`（"当时现场长什么样"）。两者都可缺省。

## 4. 数据模型

```sql
CREATE TABLE memory_item (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  text          TEXT    NOT NULL,
  tags          TEXT    NOT NULL DEFAULT '',     -- 原文，空格/逗号分隔，给人和导出看
  tags_norm     TEXT    NOT NULL DEFAULT '',     -- 归一化 `|例会|站会|`，供精确列匹配
  weight        REAL    NOT NULL DEFAULT 0,      -- 排序权重；-1 = 静音（永久哨兵）
  source_kind   TEXT    NOT NULL,                -- 'agent' | 'message_board' | 'user_said' | 'import'
  source_ref    TEXT,                            -- 消息板 local_seq / 导入文件名
  context_ref   TEXT,                            -- JSON {"session":…,"seq":…,"end":…,"excerpt":…}
  created_at    TEXT    NOT NULL,
  last_used_at  TEXT,
  use_count     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_memory_item_weight ON memory_item(weight, created_at DESC);
CREATE INDEX idx_memory_item_tags   ON memory_item(tags_norm);

CREATE VIRTUAL TABLE memory_fts USING fts5(
  text, content='memory_item', content_rowid='id', tokenize='trigram'
);

CREATE TABLE context_digest (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id    TEXT    NOT NULL,
  event_seq     INTEGER NOT NULL,
  kind          TEXT    NOT NULL,   -- 'user' | 'assistant' | 'tool'
  speaker       TEXT,
  at            TEXT,
  body          TEXT    NOT NULL,   -- 完整正文（已脱敏）
  chars         INTEGER NOT NULL,
  truncated     INTEGER NOT NULL DEFAULT 0,
  pushed_at     TEXT    NOT NULL
);
CREATE UNIQUE INDEX idx_context_digest_event ON context_digest(session_id, event_seq);

CREATE TABLE context_gap (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id    TEXT    NOT NULL,
  from_seq      INTEGER NOT NULL,
  to_seq        INTEGER NOT NULL,
  detected_at   TEXT    NOT NULL
);
```

`memory_fts` 用 `tokenize='trigram'`：已在 `sqlite-jdbc 3.53.4.0` 实测可建表并 `MATCH '长期记忆'` 命中（见调查报告 §7）。**trigram 对 <3 字符子串不建索引，中文二字标签（"例会"）永远命不中**——所以标签走 `tags_norm` 列精确匹配，正文才走 FTS。

## 5. 工具面（5 个，全部为 MCP `AgentTool`）

| 工具 | 入参 | 出参 |
|---|---|---|
| `memory_write` | `text`（≤`memory.write.maxTextChars` 字）**或** `file`（`downloads/` 内的文件名，二选一）、`tags?`、`source_kind?`、`source_ref?`、`context_ref?` | 一段文本 `{id, chars, tags}`；整文件 `{ids, count, chars, file, tags}` |
| `memory_search` | `query`、`limit?`（默认 20，1..50）、`cursor?` | `{items:[{id, digest, tags, weight, at}], next_cursor?}`；**永不返回 `weight=-1`** |
| `memory_view` | `id` | `{id, text, tags, weight, source_*, context_ref, stale?}`；`weight=-1` 的行照样返回 |
| `memory_rate` | `id`、`value` ∈ `useful`/`useless`/`mute` | `{id, weight}` |
| `memory_context` | `from?`/`to?`/`limit?`/`view?`/`export?` | `view`：单条正文；`export='txt'`：写出文件并回路径；无参：当前绑定会话的缩略目录 |

要点：

- `memory_search` 的缩略形如 `#12 · [例会|排期] · 22:15 · 站会固定在周二…（缩略 60 字）`，正文必须 `memory_view` 才给（D-078 "只回极简缩略、要求 Agent 主动查看"）。
- `memory_write(file=…)` 是"导入的记忆是一个完整文件，而不是 Agent 手写的工具参数"（m00433）：文件必须位于有效媒体目录（`<dataDir>/downloads`，或 `memory` 之外配置的 `media.dir`）**之内**——`normalize()` 后必须仍在根下（拒 `../`）、拒绝符号链接本体与符号链接父目录逃逸（`toRealPath()` 再比对）、拒绝超过 `memory.import.maxBytes`（默认 1MB）的文件；读入后去 BOM、`\r\n` → `\n`，按 `memory.write.maxTextChars` **切成多条**写入（`source_kind` 固定为 `import`，`source_ref` 为文件名，`tags` 缺省取文件名主部），因此"完整文件"不会因为单条上限而被截断。空文件、非普通文件、目录一律拒绝。
- `memory_rate` 三态是 D-079 的口径：`mute` 硬设 `weight=-1`；`useful`/`useless` 是配置化增量，**递减在 −0.9 处 clamp**，因此 `weight` 精确等于 `-1` 只可能来自显式 mute，不会被"点几次无用"误触发。
- `memory_context(export='txt')` 由 jar 自己写文件到 `<dataDir>/exports/context-<session>-<UTC 时间戳>.txt`（文件名服务端生成、`CREATE_NEW` 不跟随符号链接、目录为符号链接直接拒写）。原因：BMA3 预设的 `tool-fs` 是 `{read:false,write:false,edit:false}`（`dsh-plugin/lib/preset.js:50-58`），Agent 现在没有写文件的能力。
- `memory_context` 的**会话不是入参**：取当前绑定会话（`InboxService.pluginSessionId()`），没有绑定时回退到副本里最近推送的会话，两者都没有则返回空目录并说明原因。目录每行是一行 60 字缩略（`#seq · 时间 · 说话人 · 前 60 字`），响应带 `chars`（目录总字符数）、`max_entries`、`gaps`，`limit` 默认取 `memory.search.limit`、上限 50。
- `context_ref` 的协议形式固定为 `session=<sessionId>#seq=<eventSeq>`：`memory_view` 用它回指 DSH 会话日志，取不到就报 `stale=true`（"不可验证"不等于"已验证"）。
- 读取面另一半在插件侧：给 BMA3 预设挂 DSH 原生 5 个只读工具（D-079 ②），Agent 需要"真实现场"时用它们，需要"我自己的副本/导出"时用 `memory_context`。

## 6. 检索与排序

0. 取词：query 先过 `bma3-memory/src/main/java/io/github/c1589190/bma3/memory/QueryTerms.java`。按标点/空白切块，每块整体保留（逐字引用的短句因此仍能命中），块内每个 CJK 连续段再展开成**全部二字 gram**（＋段本身），去重后截到 `MAX_TERMS = 16`。原因见 §12 P4：A18 首跑 `recall@8 = 0.30`，根因是"查询是句子、索引只能按字面匹配"——整句在正文里从不出现，只有恰好是正文子串的短词能中（D-081）。
1. 标签：对**同一组取词**逐个在 `tags_norm` 上做 `LIKE '%|term|%'`，命中行按命中词数取 rank_tag（因此"记忆的标签权重"能命中标签 `标签`）。
2. 正文：对每个取词单独检索——不足 3 个码点走 `text LIKE '%term%' ESCAPE '\\'`（`created_at` 降序取池；FTS5 `trigram` 不索引 <3 码点子串，中文最常用的二字词否则永久失效，D-080 ①），否则走 `memory_fts MATCH '"term"'` + `ORDER BY bm25(memory_fts)`；然后按**命中的不同词数降序 → 词内最小排名升序 → id 降序**编号成 rank_text，所以"负责部署"排在只有"负责"的行前面。
3. 合分：RRF `score = tagBoost/(60+rank_tag) + 1/(60+rank_text)`，`tagBoost` 可配置、默认 `10.0`（D-078 ⑥）。
4. 过滤：`weight = -1` 永不入列表；其余按 `score` 降序、`created_at` 降序稳定排序。
5. 分页：`cursor` 是**上一页返回的整数偏移**（`next_cursor` = `offset + count`），默认 20 条（"返回有限可翻页列表"）。不用 `(score,id)` 不透明游标：RRF 分数每次调用重算，相邻页之间若有新写入，偏移翻页可能重复或跳过一条——对"Agent 自己翻自己的记事本"可接受，记为已知取舍（D-080 ②）。
6. 命中即记账：`memory_view` 成功后 `use_count += 1`、`last_used_at = now`（供后续 P4 学式 reranker 取标签，本设计不参与打分）。

## 7. 上下文通道（插件 → jar）

- 路由：`POST /v1/inboxes/{inboxId}/plugin/context`（追加）、`GET /v1/inboxes/{inboxId}/plugin/context?after=<event_seq>&limit=`（补洞）。挂在既有路由表 `bma3-inbox/src/main/java/io/github/c1589190/bma3/inbox/http/InboxHttpServer.java:97-140` 内，除 `/healthz` 外全部 Bearer（与现有一致）。
- 请求体：`{sessionId, capturedThroughSeq, entries:[{eventSeq, kind, at, speaker, text}], sentAt}`。
- 幂等：`(session_id, event_seq)` 唯一索引，重复推送覆盖同一行、不新增。
- 有界：条目数 ≤ `memory.context.maxEntries`（默认 200，**Agent 可用既有 `set_config` 热改**），超限按 `(session_id, event_seq)` 淘汰最旧；单条 `body` ≤ `maxEntryChars`（默认 10240），超出截断并 `truncated=1`。
- 事实流向单向：插件只「推」，jar 不反向命令插件；补洞是插件重启后自己 `GET ... ?after=` 拉回断点，jar 不主动催。
- 心跳不变：`PluginHeartbeat.java:12-14` 已写死 "Only ids/status/counters are carried: never a token, never a message body"——正文**只走 `/plugin/context`**，绝不进心跳（15s 周期见 `dsh-plugin/lib/inbox.js:748`）。

## 8. watchdog 强化

在 `bma3-watchdog/src/main/java/io/github/c1589190/bma3/watchdog/probe/Probes.java` 增加一个 `plugin-context` 探针，判定三件事：

1. **停更**：插件在线（心跳正常）且已绑定会话，但 `max(event_seq)` 在 `staleAfterSeconds`（默认 600s）内没有推进 → `DEGRADED`。
2. **断层**：`context_gap` 有未确认记录 → `DEGRADED` 并报 `from_seq..to_seq`。
3. **换绑遗留**：最近推送的 `session_id` 与当前绑定会话不一致 → `DEGRADED`（换绑后插件还在推旧会话）。

这套判定成立的前提就是 jar 手里有自己那份副本与水位；没有副本时 watchdog 只能看进程存活，那不是 watchdog 而是 `ps`（D-079 ④）。

## 9. 不变量

1. 三层各司其职：`chat_message` 是业务事实源，`context_digest` 是可丢弃的副本缓存，`memory_item` 是记事本。任一层缺失只降低能力，不改变其余层的语义；本设计不改消息板写入路径。
2. 记忆永不成为事实源：每行必须带 `source_kind`；`source_kind='message_board'` 时 `source_ref` 必须指向真实存在的 `local_seq`，否则拒写。
3. **不删行**：没有任何 delete/forget 工具，`memory_item` 只增；"退役"唯一表达是 `weight=-1`。
4. **零自动注入**：记忆与 digest 不进 `systemPrompt`、不进回合位置，只有 Agent 调用工具才出现在上下文里。
5. `memory_search` 的列表永不返回 `weight=-1`；`memory_view` 对 `weight=-1` 照常返回正文。
6. 非静音行的 `weight ≥ memory.rate.floor`（默认 −0.9，恒 > −1）：`useful`/`useless` 的增量递减在该下限处 clamp，`weight` 精确等于 `-1` 只由显式 `mute` 产生。
7. 标签命中优先于正文命中：同一 query 下，标签命中的行排序必须先于仅正文命中的行（`tagBoost` ≥ 1 时）。
8. `context_digest` 有界：条目数受 `maxEntries` 约束、单条受 `maxEntryChars` 约束，截断必须可由 `truncated` 观测；上限可由 Agent 经 `set_config` 热改。
9. 幂等：同一 `(session_id, event_seq)` 重复推送不产生新行、不改变语义。
10. 正文只走 `/plugin/context`：不进心跳、不进日志、不进 MCP 结果以外的任何通道；落库前跑既有脱敏（Bearer/token/长 hex/URL 凭据）。
11. `context_ref` 悬空可观测：被引用事件在 DSH 侧不可达时，`memory_view` 正常返回正文并标 `stale=true`，不报错、不自动清理。
12. 导出不放大权限：`export` 只写 `<dataDir>/exports/`，文件名由服务端生成，不跟随符号链接，不覆盖已有文件。
13. 合成唤醒（scheduler/后台/定时任务）不写记忆：由 agent 来源与调用方身份判定，拒绝 `memory_write`。

## 10. 验收判据

| # | 判据 | 测法 |
|---|---|---|
| A1 | 迁移不伤既有数据 | 旧库打开后新表/索引存在；`chat_message` 行数与内容逐一不变 |
| A2 | 正文检索可用 | 写 3 条含"站会"的记忆，`memory_search('站会')` 命中 |
| A3 | 二字中文标签可用 | 标签 `例会` 的记忆能被 `memory_search('例会')` 命中（证明走 `tags_norm`，不是 FTS） |
| A4 | 标签优先于正文 | 同 query 下标签命中行排序严格先于仅正文命中行 |
| A5 | 静音语义 | `mute` 后 `search` 不含、`view` 含；`useless` 连打 5 次后 `weight == memory.rate.floor`（仍 > −1），改 `floor` 后重跑仍成立 |
| A6 | 无删除面 | 工具清单里不存在 delete/forget（快照断言） |
| A7 | digest 幂等 | 同一批次推两次，`context_digest` 行数不变 |
| A8 | digest 有界 | `maxEntries=5` 推 8 条后只剩最新 5 条，`memory_context()` 可列出缩略 |
| A9 | 截断可观测 | 超 `maxEntryChars` 的条目 `truncated=1`，正文长度=上限 |
| A10 | 断层可见 | 推 event_seq 1,2,5 后 `context_gap` 有 `from=2,to=5` 记录，watchdog 探针 `DEGRADED` |
| A11 | 停更可见 | 停推超过 `staleAfterSeconds` 后 `plugin-context` 探针 `DEGRADED`，心跳仍 `UP` |
| A12 | 换绑可见 | 绑定会话切到 B 后仍推 A 的条目 → 探针 `DEGRADED` 并报会话不一致 |
| A13 | 脱敏 | body 含 `Bearer xxx`／48 位 hex 时，落库与导出均被替换；jar 日志无该串 |
| A14 | 导出 | `memory_context(export='txt')` 生成 `<dataDir>/exports/*.txt`，含正文与 tags |
| A15 | 悬空指针 | 伪造 `context_ref` 指向不存在 seq → `view` 正常、`stale=true` |
| A16 | 零注入 | 不调用工具时，`systemPrompt` 段与回合内容与改动前逐字相同（快照对比） |
| A17 | 真实宿主 | 重启宿主后在 BMA3 会话里 `/压缩` 生效（上一轮修复的复验，同时是 P1 的宿主前置） |
| A18 | eval（P4） | 50 条 fixture 上 `recall@8 ≥ 0.7`；未达标不进 P4 |

A1–A16 为 P0/P1 的合并门禁；A17 依赖宿主重启；A18 只在 P4 前执行。

## 11. 配置键

| 键 | 默认 | 说明 |
|---|---|---|
| `memory.search.tagBoost` | `10.0` | 标签 RRF 权重增量（D-078 ⑥） |
| `memory.search.limit` | `20` | 单页条数，1..50 |
| `memory.rate.usefulDelta` | `+1.0` | 有用增量 |
| `memory.rate.uselessDelta` | `-1.0` | 无用增量；运算后在 `memory.rate.floor` 处 clamp |
| `memory.rate.floor` | `-0.9` | 非静音行的权重下限；必须 > `-1.0`，保证 `-1` 只由 mute 产生 |
| `memory.write.maxTextChars` | `8000` | 单条记忆上限（导入文件按此切分） |
| `memory.import.maxBytes` | `1048576` | 单次文件导入上限，1..67108864 |
| `memory.context.maxEntries` | `200` | 滚动条目上限，**允许 Agent `set_config` 热改** |
| `memory.context.maxEntryChars` | `10240` | 单条正文上限，超出截断（要能装下完整 dsh 响应，m00645 定 10KB 量级） |
| `memory.context.staleAfterSeconds` | `600` | 水位探针停更阈值 |

以上每一项都是**配置键**，实现只允许出现在 `AppConfig.Memory` 记录的 `defaults()` 与 compact constructor 的校验区间里（照 `AppConfig.Watchdog` 现有写法：`bma3-app/src/main/java/io/github/c1589190/bma3/app/config/AppConfig.java:77-102`），由 `set_config` 热改；工具实现里不得出现字面量。代码中唯一真正写死的量是语义哨兵 `weight = -1`（D-078「静音」的唯一表达，可配置就失去意义）与表名/列名/工具名/路由路径这些协议面。

## 12. 分期

| 期 | 内容 | 门禁 |
|---|---|---|
| P0 | `memory_item`/`memory_fts` 建表与存储层 + `memory_write/search/view/rate` | A1–A6、A13、A16 |
| P1 | `context_digest` + `/plugin/context` 路由 + `memory_context` + 老迁移 | A7–A9、A14、A15、A17 |
| P2 | watchdog `plugin-context` 水位探针 + 断层/换绑 | A10–A12 |
| P3 | 导出/导入闭环（`.txt` 导出、`downloads/` 限定导入） | 新增用例 |
| P4 | 50 条 eval fixture + 判分脚本，决定要不要上向量 | A18 |

P0–P4 均已实现并过门禁（P0 `b2e19ed`、P1 `818f9ff`，P2–P4 见 `docs/agents/STATUS.md`）。A18 的实测结果是 `recall@8 = 1.00`（20/20，50 条语料），因此**第一版不引入向量库**；fixture 由本项目自撰，不等同于 LongMemEval 这类外部基准，跌破 0.7 时的正确动作仍是"上一个被测量过的向量库"而不是下调阈值。

## 13. 待确认

1. ~~条数上限~~ 已定（m00645）：滚动上限仍为 `maxEntries=200`，单条正文放到 **10240 字符**（要装下完整 dsh 响应）；总容量 ≈2MB 量级，Agent 可 `set_config` 热改。
2. 第一版是否只支持**单会话**滚动（当前单 inbox 单绑定）；多会话并行滚动留到确有需要再做。
3. ~~预设是否改成"默认不禁用其它命令"~~ 已定（D-082）：维持现状白名单，且**预设层不挂压缩**（压缩归 `billion-context`），另挂 `@deepseek-ai/dsh-tool-session-query` 让 Agent 读真相层。以下为当时的三选项，留档。机制上能实现：shipped 预设就是 `@deepseek-ai/dsh-web-app/presets/{standard,ptc,minimal,cordis}.patch.yml`，`standard` 有 30+ 行（含 `tool-bash`/`tool-pwsh`/`tool-fs`/`tool-schedule`/`tool-web`/`tool-office`/`tool-plugin-manager`、`planning`/`compaction`/`delegation` 三个 group）。可选：（a）照抄 standard 全量；（b）注册时**运行时读 standard 的 patch 文件**做基线 + 显式豁免表 + bma3 追加，并加测试断言"standard 的每个 row id 在 bma3 里要么出现、要么在豁免表里"；（c）覆盖 `id: preset-standard`（影响所有 standard 会话，不做）。注意这不是命令问题而是**权限面**问题：bma3 的工具由 QQ 消息间接驱动。
4. 顺带修正：`tool-result-pruner` 的真实包名是 `@deepseek-ai/dsh-compaction-tool-result-pruner`，上一轮 `sessionRows()` 之外若引用旧名会解析失败退回裸 specifier。
