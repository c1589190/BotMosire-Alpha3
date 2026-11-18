# 长期记忆系统：市面方案调查与适配建议

> **类型**：调查（时间点快照，只作追溯，不作当前事实）
> **日期**：2026-11-18 14:30（UTC+8）
> **方法**：只读代码与设计文档；联网检索公开论文/文档/同类项目（引用链接见 §12）；在本机用 `sqlite-jdbc 3.53.4.0` 做一次 FTS5 能力探测。**未改产品代码、未跑构建/测试、未连接真实 QQ 或远端 DSH、未调用任何模型 API。**
> **触发**：用户要求先调研市面 LLM 记忆实现并给看法，暂不写产品代码。
> **当前状态入口**：[`../agents/STATUS.md`](../../agents/STATUS.md)

## 0. 用户需求原话

> 我希望在当前项目上为机器人实现一个长期记忆系统，能不能先行调查一下市面上的各类llm记忆实现方式，告诉我你的看法？

本文只回答"市面怎么做 + 我建议怎么做"，不含设计稿。按项目规则，进入实现前仍需 `specs/` 里的「不变量 + 验收判据」。

## 1. 结论先行

1. **不要把 Mem0 / Zep / Letta 当依赖引入本项目**。它们是 Python 服务，自带向量库和 LLM 客户端，与本项目两条硬约束直接冲突：① 事实源是消息板（`chat_message`），不是外部记忆库；② 本项目 Java 侧**没有任何模型客户端**，唯一的模型出口是 Agent 自己的推理和插件里两个显式 DeepSeek 工具（`dsh-plugin/lib/media.js:28-30`），正文出境边界由 D-068 把守。引入记忆服务 = 新增一个存放密钥的地方 + 把聊天正文批量送出本机。
2. **记忆必须是消息板的派生物，不是平行真相**。每条记忆要能指回 `chat_message.local_seq` 或一个明确的来源（用户直说 / Agent 观察 / 定时合成）。这样"记忆和实际发生的事对不上"永远可以人工复核，也让 `delete`/`update` 不会造成不可逆的信息丢失——这正好是本项目红线 4（消息板是业务事实源）的自然延伸。
3. **写入者应该是 Agent，不是后台抽取管道**。这和我找到的一个外部工程实践完全一致：`The store doesn't act; actors act. The server computes candidates deterministically; an external agent does the synthesis. No LLM enters the server.` 对本项目来说，这句话有字面意义——Java 侧本来就没有 LLM。
4. **检索从 FTS5(trigram) + 结构化过滤开始，先不要向量**。我已实测本项目使用的 `sqlite-jdbc 3.53.4.0` 支持 FTS5 与 `trigram` 分词，中文子串召回 + `bm25()` 排序可用（证据见 §7.2）。BM25 在"人名/群名/项目名/固定说法"这类 QQ 场景的高频查询上不弱于 embedding，而且零依赖、零出境、可解释。
5. **真正的风险不是"记不住"，是"记错且记牢"**。记忆投毒（MPBench、Visual Inception）和长时记忆幻觉（HaluMem 类评测）已经是独立研究方向；对 QQ bot 更现实的是：陌生群友的一句话被写成"事实"永久驻留。所以第一版的重点是**可审计 + 可撤销 + 分置信度**，而不是召回率。

## 2. 本项目的既有约束（决定方案取舍）

| 约束 | 证据 | 对记忆设计的影响 |
|---|---|---|
| 消息板即事实源 | `AGENTS.md` 红线 4；`SqliteMessageBoardStore.java:69-118` 建表 | 记忆表只能是派生索引，必须带 `source` + seq 指针 |
| 检索目前是 LIKE 子串 | `MessageBoardStore.java:100-108`（注释明确 "literal substring ... SQLite LIKE"）；`SqliteMessageBoardStore.java:541` `text LIKE ? OR sender_name LIKE ? OR sender_card LIKE ?` | 无 FTS、无 tf-idf、无排序信号；长会话下召回质量随数据量下降 |
| Java 侧无模型客户端 | 全仓 `grep -rn "deepseek\|apiKey" bma3-*/src` 只命中 Bearer 鉴权与脱敏，无 provider | 抽取/摘要/embedding 若在 Java 侧做，必须新增密钥与出境通道 → 与红线冲突 |
| 唯一模型出口 | `dsh-plugin/lib/media.js:28-30`（`DEFAULT_BASE_URL='https://api.deepseek.com'`、`CREDENTIAL_REF='DEEPSEEK_API_KEY'`），且 D-068 规定只在显式工具调用时单文件出境 | 记忆的"读懂"应该发生在 Agent 回合内，不是后台批处理 |
| 有可挂载的系统提示段 | `dsh-plugin/lib/persona.js:23-28`（`PERSONA_SECTION='deployment:persona-prefix'`）、`:85-99`（`text: () => ...` 每次装配重读、`complete`/`order` 可配、`ctx.effect` 可撤销）、`index.js:13` `inject=['tools','agents','systemPrompt']` | 已有成熟机制把**常驻核心记忆**注入系统提示，不需要新链路 |
| 存在"合成唤醒" | `dsh-plugin/lib/inbox.js:2101` `wake(panel, source)`；另有 scheduler、debug channel | 必须区分"真人消息"与"系统唤醒"，否则定时任务会污染记忆（同类项目踩过：AstrBot LivingMemory 2.9.1 专门修了"阻止定时任务/后台任务等合成唤醒进入真实对话记忆"） |
| 上下文层已有压缩 | billion-context（cacheHitPct 97%，见 `2026-11-09-DSH迁移调查…md`） | 注入位置会影响 prefix cache 命中率；易变记忆不能塞进稳定前缀 |

## 3. 市面方案地图（按"谁在写、写成什么形状"分层）

- **L0 上下文工程**：滑窗、滚动摘要、compaction（Claude Code auto-compact、billion-context）。不产生持久知识，只控制 token。**本项目已有**。
- **L1 文件式记忆**：`AGENTS.md` / `CLAUDE.md` / `MEMORY.md`、Claude 官方 memory tool（`/memories` 目录 + view/create/str_replace/insert/delete，存储由接入方自己提供，Java SDK 有 `BetaMemoryToolHandler`）、SillyTavern lorebook（手写条目 + 关键词扫描 + 显式 token 预算 + 指定插入深度）。本质是"人可读、可 diff、可回滚"的稳定事实。
- **L2 向量 RAG 记忆**：会话切片 → embedding → top-k。AstrBot Mnemosyne、LangChain4j `ChatMemory`/`EmbeddingStore`、Spring AI `ChatMemory`+`ChatMemoryRepository` 都属这一层或更浅的"消息窗口持久化"。
- **L3 结构化 / 图谱**：Zep/Graphiti（episode 子图 + 实体子图 + community 节点，双时态 `t_valid`/`t_invalid`）、Mem0g、Cognee（ECL: extract-cognify-load）、GraphRAG/HippoRAG/RAPTOR。
- **L4 Agent 自主管理**：MemGPT/Letta（core/recall/archival 三层 + Agent 自编辑 memory block + sleep-time 后台整理）、A-MEM（Zettelkasten 卡片 + 动态链接 + memory evolution）、MemOS（MemCube：plaintext/activation/parameter 三种基质 + 调度/治理/版本溯源）。
- **L5 参数化记忆**：LoRA/online-LoRA。对本项目不现实（每次更新要训练，不可审计）。
- **L6 中文 bot 生态（离本项目最近的一层）**：AstrBot 的 Memorix（向量+BM25+图谱 PageRank，RRF 融合，24h 半衰期衰减→冻结→剪枝，来源追溯 + 内嵌 WebUI）、Mnemosyne（Chroma/Milvus/Qdrant + 自动总结 + 会话隔离 + 模型工具写入）、LivingMemory（偏好/关系/项目/约定，关键词+语义+图谱召回，`recall_long_term_memory` / `memorize_long_term_memory` 双工具，归档+衰减+访问强化）；MaiBot 用 Peewee+SQLite 存 messages/画像/学习到的表达/摘要。
- **L7 具身/机器人**：VLA 长期记忆综述（arXiv:2610.23319）从 sliding window 走向 self-evolving lifelong memory；另一份 Always-On Agents 综述把状态分成 memory / state / governance 三层。**本项目不是具身机器人**，但这层的"治理"视角值得抄。

## 4. 逐个方案卡（与项目相关的判据）

| 方案 | 写入路径 | 检索 | 遗忘/更新 | 可审计 | 引入代价 |
|---|---|---|---|---|---|
| Mem0 | 后台 LLM 抽取 fact → ADD/UPDATE/DELETE | 向量 + 多信号 | 激进合并（有损，答不了"以前认为什么"） | 无 confidence/evidence | Python 服务 / 云 $19-249 月 / 需并置自己的 LLM |
| Zep / Graphiti | 抽取实体关系建双时态图 | 图 + 向量 + 社区摘要 | `t_invalid` 取代而非删除 | **最好**（时间点可查） | Python + Neo4j/FalkorDB；延迟高 |
| Letta / MemGPT | Agent 自编辑 core memory block + archival | 向量/关键词按需页入 | 自编辑，靠 Agent 自觉 | 中（可读 block） | 整套 agent runtime，lock-in 高 |
| MemOS | MemCube 统一对象 + 调度器 | 按基质 | 版本溯源 + 生命周期 | 好 | 研究原型，工程化成本高 |
| A-MEM | 每段记忆成卡片 + 自动建链 + 演化 | 链接图 + 向量 | 卡片可改写 | 中 | 需 LLM 做链接，成本随记忆数增长 |
| Claude memory tool | Agent 显式工具写文件 | 由 Agent 自己 view/grep | 直接改文件 | **最好**（纯文本可 diff） | 无（自己实现 handler），但需自建检索 |
| SillyTavern lorebook | 人写 / side prompt 生成 | 关键词扫描（字符串匹配） | 人改 | 好 | 零模型成本；但注入位置破坏 prefix cache |
| AstrBot Memorix / LivingMemory | 轮次触发总结 + Agent 工具写入 | 向量+BM25+图，RRF | 衰减/冻结/剪枝/强化 | 好（来源追溯 + WebUI） | Python 插件 + embedding provider |
| Spring AI `ChatMemory` / LangChain4j `ChatMemory` | 存消息列表 | 条数窗口 | 无（只裁剪） | 弱 | Java 原生，但**只是窗口持久化**，不含事实/图谱/遗忘；Spring AI 2.1 起 ChatMemory 将被 Session API 取代 |

评测口径参考：Mem0 自报 LoCoMo 相对 OpenAI Memory **+26%**、p95 延迟 **-91%**、token **-90%**；但第三方汇总里它的 LongMemEval 只有 **49.0%**（低于 full-context GPT-4o 60.2%、Zep 71.2%）。Zep 自报 DMR **94.8%** vs MemGPT **93.4%**、LongMemEval 准确率最高 **+18.5%**、延迟 **-90%**。**各家自报分数口径不可比**（Zep/Mem0 有过公开争论），不要拿榜单数字做选型依据。

## 5. 这些方案为什么不能直接落地本项目

1. **事实源冲突**：记忆库一旦与消息板并行演进，两边不一致时谁是真相？本项目已有的答案是"消息板"。外部框架默认自己是真相。
2. **出境与密钥**：任何"后台 LLM 抽取"都要把聊天正文批量送到第三方。D-068 的边界是"显式调用才单文件出境"，后台批处理直接违约。
3. **无 LLM 运行时**：Java 侧连 embedding 都没有。加 onnxruntime + 本地 bge 模型可行（约 100-400MB 模型，CPU 尚可），但这是**新增运维负担**，收益要到规模上去才显现。
4. **迁移成本 vs 收益**：LangChain4j/Spring AI 的 `ChatMemory` 只解决"窗口持久化"，本项目的消息板 + inbox 已经覆盖了这件事，引入框架等于重复建设。
5. **可审计性倒退**：Mem0 式激进 UPDATE/DELETE 会丢掉"以前认为什么"，对一个人格化 bot 是灾难（用户说"你以前不是这么说的"时无法自证）。

## 6. 推荐架构

### 6.1 分层（四层，全部落在本项目已有形态上）

```
L-1 事实层   chat_message（已有，不动）
      ↑ local_seq 指针
L0  记忆层   memory_item（新表，同一 bot.db 或独立 memory.db）
      ↑ 检索/写入
L1  工具层   memory_remember / memory_recall / memory_forget / memory_pin / memory_stats（MCP，随 jar 发）
L2  注入层   dsh-plugin 新增 lib/memory.js：注册第二个 systemPrompt 段（core memory），
             复用 persona.js:85-99 的 section 机制
L3  整理层   bma3-scheduler 定时发出"待整理"候选（只算候选，不做综合），由 Agent 在空闲回合消化
```

### 6.2 数据模型草案

```sql
CREATE TABLE memory_item (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  kind          TEXT NOT NULL,        -- fact | preference | relation | episode | commitment | rule
  scope         TEXT NOT NULL,        -- global | contact:<id> | group:<id> | account:<id>
  subject       TEXT,                 -- 关于谁（人物稳定标识，不做昵称匹配）
  text          TEXT NOT NULL,        -- 记忆正文（可读、可 diff）
  confidence    REAL NOT NULL,        -- 0..1，Agent 自评，注入时按阈值过滤
  weight        REAL NOT NULL,        -- 检索/注入的排序权重（访问强化）
  pinned        INTEGER NOT NULL DEFAULT 0,  -- 常驻 core memory
  valid_from    TEXT NOT NULL,
  valid_to      TEXT,                 -- NULL = 仍然有效（双时态，复刻 Graphiti 的核心思想）
  superseded_by INTEGER,              -- 取代链，不删行
  source_kind   TEXT NOT NULL,        -- user_said | agent_observed | synthesized | imported
  source_ref    TEXT,                 -- "qq_group/955432692#14457" 或 local_seq 区间
  created_at    TEXT NOT NULL,
  last_used_at  TEXT,
  use_count     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_memory_scope ON memory_item(scope, kind, valid_to);
CREATE VIRTUAL TABLE memory_fts USING fts5(text, subject, content='memory_item',
                                            content_rowid='id', tokenize='trigram');
```

要点：**不删行，只 `valid_to` + `superseded_by`**；`source_ref` 必填；`content=''` 外部内容表让 FTS 与主表不重复存正文。

### 6.3 三条写入通道（按可信度递减）

1. **确定性事件**（最高可信）：用户显式说"记住…"，或配置里的偏好项变更 → 直接写 `confidence=1.0`。
2. **Agent 显式工具调用**（中）：Agent 判断值得记 → `memory_remember(kind, scope, text, source_ref, confidence)`。**必须带 `source_ref`**，无引用直接拒写。
3. **离线归纳**（最低）：scheduler 只产出"候选包"（某会话最近 N 条 + 已有相关记忆），Agent 在空闲时合并/取代。Java 侧不调用模型。

### 6.4 检索与打分

```
score = bm25(memory_fts) * w_bm25
      + recency(decay, 半衰期 30d) * w_time
      + log1p(use_count) * w_use
      + pinned * BIG
过滤：scope 命中（global + 当前会话 scope）、valid_to IS NULL、confidence >= 阈值
裁剪：按 token 预算 top-k，默认 k=8、预算 ≤800 token
```

理由：QQ 场景查询短、专名多，BM25 的精确性优于稠密向量；时间/权重项解决"最近说过的更重要"。若日后有证据表明召回不足，再加可选向量通道并用 RRF 融合（AstrBot Memorix 就是 RRF 双路）。

### 6.5 注入（cache 友好）

- **核心记忆**（`pinned=1`，≤600 字符）进 `systemPrompt` 段，随人格一起装配。变更频率低 → 不破坏前缀缓存。
- **检索记忆**放在回合位置（工具结果或当前 user 轮附件），不放进稳定前缀。SillyTavern 的教训正是"作者指定深度插入会破坏 prefix cache"。
- 常驻块必须**有长度上限**并支持"压缩成一句"，否则会像 Letta core memory 那样在角色限额上反复折腾。

### 6.6 生命周期与安全

- **衰减**：`weight *= 0.5 ** (Δt / 30d)`，但 `pinned` 与 `source_kind='user_said'` 不衰减。
- **取代**：新事实 → 旧行 `valid_to=now`、`superseded_by=new.id`。
- **遗忘**：`memory_forget` 默认软删（`valid_to` 置为过去），硬删需显式参数 + 审计日志。
- **投毒防护**：① 陌生来源（未建立关系/首次出现）写入的 `confidence` 上限 0.4；② `kind='rule'`（会影响行为的规则类记忆）只接受 `source_kind='user_said'` 且 scope 为 owner；③ 注入时把记忆渲染成"引述"而非"指令"（`[记忆·群友A 2026-11-10 说过] …`），降低间接注入成功率；④ 每周 `memory_stats` 供 Agent 自查异常增长。
- **合成唤醒隔离**：`wake` 带 `synthesized=true` 时，本轮禁止任何写入（AstrBot LivingMemory 2.9.1 的修复就是这条）。

### 6.7 记忆与上下文的绑定（`context_ref`）

用户追问（m00147）：记录记忆时，能否顺带把**真实上下文**一并计入？

能，而且不需要复制正文——DSH 的会话日志本身就是 append-only、每条事件带稳定 `SessionSeq` 的事实源，权威 API 就在同一进程里：

| 能力 | 签名（`@deepseek-ai/dsh-session-query`） |
|---|---|
| 当前 surface + 水位 | `readSurface(sessionId): Promise<SessionSurfaceSnapshot>` → `{ session, inheritedEventCount, capturedThroughSeq, events: SurfaceEvent[] }` |
| 单事件 + 有界邻居窗口 | `readEvent(request: SessionEventReadRequest): Promise<SessionEventWindow>` |
| 原始事件列表 | `listEvents(sessionId): Promise<SessionEventRecord[]>` |
| 关系追踪 | `traceEvent(request)` / `traceSession(sessionId)` |

`SessionEventReadRequest = { sessionId, seq, before?, after? }`；`SessionEventWindow = { session, inheritedEventCount, target, events, startSeq, endSeq }`（`dsh-session-query/lib/types/types.d.ts:111-135`）。`target` 是完整未删节事件，`events` 是 `startSeq..endSeq` 的原始邻居。

于是 `memory_item` 增加一列：

```sql
context_ref TEXT,   -- JSON: {"session":"session-0378…","seq":412,"start":405,"end":418,"excerpt":"≤200字"}
```

设计要点：

- **指针 + 兜底**：`session`/`seq` 是指针，`excerpt` 是脱锚兜底（日志被清理或换机后仍可读，避免悬空引用）。
- **写入零 token**：`memory_remember` 在 `execute(args, exec)` 里可拿到 `exec.agent`（`ToolExecutionInput.agent`，JSDoc "The agent on whose behalf the call runs (set by the agent loop)"），再取 `session.surface.nodes` 尾部若干 seq → 即"模型此刻看到的上下文"。Agent 不需要复述任何东西。
- **召回分两档**：`memory_recall` 只返回正文 + 一行出处（`session#seq`）；要追根时 `memory_expand(ref)` 按 `before/after` 展开窗口，到那时才付 token。
- **经验只能锚在轨迹上**：消息板记住"用户说了什么"；"我试了 X、因为 Y 失败"只存在于日志的 `tool/call`/`tool/result` 里。用户要的"自主形成的经验/知识"正好是后者，所以 `context_ref` 指 **DSH 日志**，`source_ref` 指 **消息板 `local_seq`**，两者并存、语义不同。

## 7. 可行性证据

### 7.1 FTS5 trigram 实测（本机，只读探测）

```
$ java -cp ~/.m2/.../sqlite-jdbc-3.53.4.0.jar T.java
FTS5-OK: 今天下午三点开站会，讨论机器人长期记忆 bm25=-1.0E-6
sqlite=3.53.4
```

即 `CREATE VIRTUAL TABLE m USING fts5(body, tokenize='trigram')` + `MATCH '长期记忆'` 在中文上可召回并给出 `bm25()` 分数。项目版本 `pom.xml:40` `<sqlite.version>3.53.4.0</sqlite.version>`。（推断：trigram 对 ≥3 字的中文词有效；2 字查询需要用 `LIKE` 兜底或改用 `unicode61` + 外部分词，这一点要在 spec 里定。）

### 7.2 现有 LIKE 检索的上限

`SqliteMessageBoardStore.java:541` 是 `text LIKE '%q%'`，无索引可用，全表扫；`limit` 上限 500（`MessageBoardStore.java:105`）。数据量上万后 `qq_search` 会明显变慢，且结果无序。记忆层不解决这个问题，但 FTS5 顺手可以把 `qq_search` 也升级掉（同一张 FTS 表可做 `content='chat_message'`，属于可选收益）。

### 7.3 DSH 上下文管理实测证据（本机只读）

1. **会话日志 append-only 且带稳定 seq**：本会话 `session-0378417f-a66e-46a1-ab1e-be00ec4809a6`，`session_read(mode=raw)` 头部输出 `events 487 · captured through seq 486`，首条用户消息为 `[21] user/message`。落盘 `~/.dsh/sessions/--home-cna-BotMosire-Alpha3--/<id>/session.v4.jsonl.zstd`（`du -sh` = 1.6G）。
2. **本机 DSH 原生 compaction 被关闭**：`~/.dsh/node_modules/billion-context/gate.js` 在 `internal/config` 钩子里把 `@deepseek-ai/dsh-compaction-basic` 与 `@deepseek-ai/dsh-command-compact` 置 `disabled: true`（`isDshCompactionActive()` 为真时）。实测同会话按 `compaction/start|summary|end` 过滤 → `[no renderable events]`。**因此日志完整无遮蔽，seq 指针不会失效。**
3. **压缩发生在代理层，不改日志**：上下文压缩由 billion-context（代理侧）完成；`session_read` 仍能读出已被压掉的原始事件全文（seq 21 的用户原话完整可见）。这是"指针优于拷贝"的前提。
4. **`ctx.sessionQuery` 已挂载，但 FTS 默认关闭**：`@deepseek-ai/dsh-base/cordis.patch.yml:157-161` 挂 `session-query-sqlite`，`path: ':memory:'`、`openAt: never`；该后端 README 说明 `never` = "关闭全文搜索，继承的读取保持可用"。即**精确读可用、全文搜索不可用** → 记忆检索走我们自己的 FTS5，上下文展开走 `readEvent`，两者互补且不抢索引。
5. **不要为记忆挂 `@deepseek-ai/dsh-tool-session-query`**：该包 README 明说"只需要程序化检索时避免使用：`ctx.sessionQuery` 本身服务代码调用方，无需面向模型的 schema、提示词与授权层"，且启用后"每次模型请求都会增加固定指引与五个工具 schema"。我们只要程序化检索 → 直接调 `ctx.sessionQuery`。
6. **bot 目前没有任何读取自己历史的工具**：`dsh-plugin/lib/preset.js:23-87` 的 `toolRows()` 只挂 bash / pwsh / fs(仅 readImage) / subagent(-control) / plugin-manager / media + MCP 面。要"经验"就必须新增（自建 `memory_*`，或让 Agent 用 `tool-plugin-manager` 自己挂 session-query）。
7. **上下文注入有一条已探明的死路**（`dsh-deja` 记录的实测教训）：在 `agent/pre-step` 中间件里往本步消息列表插一条消息，插件能加载、回合能正常结束，但**内容到不了模型**——该 waterfall 中后续监听器会基于 payload 重建返回值，插入消息被静默丢弃。可用注入面只有 `ctx.systemPrompt.section(...)` 与 `ctx.systemPrompt.context`（每次 assembly 求值）。

## 8. 分期路线（建议，未立项）

| 期 | 内容 | 判据 | 风险 |
|---|---|---|---|
| P0 | `memory_item` + FTS5 + `memory_remember/recall/forget` 三个工具；仅 Agent 显式写入 | 写入必须带 `source_ref`，否则拒写；recall 返回带来源 | 低（不动注入） |
| P1 | `dsh-plugin/lib/memory.js` 注入 pinned 核心记忆段 | 关掉插件后行为完全回退；段长度上限生效 | 中（前缀缓存、人格冲突） |
| P2 | scheduler 候选包 + Agent 归纳（取代/合并） | 每条取代链可回溯到原文；无引用不落库 | 中（记忆膨胀） |
| P3 | 可选本地 embedding 通道 + RRF 融合 | 只有评测显示 BM25 召回不足才做 | 高（依赖/体积） |
| P4 | 图谱（仅当出现"多跳关系"真实需求） | 有具体失败案例再立项 | 高 |

## 9. 验收判据草案（可测，供 spec 用）

1. **可追溯**：任取 100 条记忆，100% 能用 `source_ref` 定位到 `chat_message` 行或显式来源标记。
2. **可撤销**：`memory_forget` 后该条不再出现在任何 recall/注入结果；旧行仍在库中（软删）。
3. **不污染**：`synthesized` 唤醒下所有写入被拒；测试覆盖 scheduler/debug 两条合成路径。
4. **注入有界**：核心记忆段 ≤600 字符、检索注入 ≤800 token，超限截断并有日志。
5. **回退干净**：移除 memory 模块后，全部既有测试通过（当前基线：863 tests / 0 failures / 1 skipped，`./mvnw -B -ntp -o test`）。
6. **召回基线**：用本项目真实会话自建 50 题小评测（"某人上次说想做什么""哪个群提过 X"），BM25 版 ≥ 现有 LIKE 版的准确率——这是唯一能自证的"有没有变好"。

## 10. 待用户裁定

1. **范围**：记忆是"bot 对每个联系人的了解"（关系型）还是"bot 自己的知识/经验"（自我型）？还是两者都要？这决定 `scope` 主轴。
2. **是否允许出境的自动摘要**：若允许，会需要一条与 D-068 不同性质的通道（批量、后台）。
3. **是否需要跨实例/多账号共享记忆**（当前单库单进程形态）。
4. **首期是否接受"只靠 Agent 显式写入"**（我建议是）——代价是 Agent 要养成记忆习惯，需要提示词配合。
5. **遗忘策略的产品口径**：用户说"忘掉这件事"时，是软删（可恢复）还是硬删（真删）？
6. **`context_ref` 的保留口径**：DSH 会话日志现在 1.6G 且无 TTL。记忆指向的会话若被清理，指针就悬空——接受"靠 `excerpt` 兜底"，还是要给被记忆引用的会话加钉住/不清理策略？
7. **召回时的展开预算**：每次 `memory_expand` 展开多少条 `before/after`、每回合最多展开几次？这直接决定 QQ 场景的单轮 token 成本。

## 11. 我没做的事

- 没有跑 benchmark（LoCoMo/LongMemEval 都需要外部数据与模型调用）。
- 没有实测 embedding 方案在中文 QQ 语料上的效果，§6.4 的 `w_recency` 权重都是待调参数（推断）。
- 没有检查远端部署的库规模，`run/bma3-live/data/bot.db` 只按规则未读正文。

## 12. 参考

- Mem0 论文 <https://huggingface.co/papers/2504.19413>；第三方批判性汇总 <https://huggingface.co/datasets/quantellence/srb-data/blob/refs%2Fpr%2F1/datasets/public_ai_memory/papers/12_mem0.md>
- Zep 论文 <https://arxiv.org/html/2501.13956v1>
- Letta/MemGPT 四层与 sleep-time <https://raw.githubusercontent.com/MohamedAbdallah-14/prompt-to-asset/refs/heads/main/docs/research/30-agent-memory-state/30a-agent-memory-architectures.md>
- A-MEM（NeurIPS 2025）<https://neurips.cc/virtual/2025/loc/san-diego/poster/119020>
- MemOS 分析 <https://raw.githubusercontent.com/lhl/agentic-memory/refs/heads/main/ANALYSIS-arxiv-2507.03724-memos.md>
- "store 不算，actor 算"工程原则（backlog-mcp ADR 0092.7）<https://raw.githubusercontent.com/gkoreli/backlog-mcp/7d5827e54708c91bc434dd8579a8e1b98e3e924f/docs/adr/0092.7-phase-d-consolidation-engineering-plan.md>
- Claude memory tool <https://platform.claude.com/docs/en/agents-and-tools/tool-use/memory-tool>
- SillyTavern lorebook 代价分析 <https://github.com/neoneye/agent-memory-atlas/blob/f15b224a9f59093797d05411b44ff70a7ee57864/content/systems/sillytavern.md>
- AstrBot Memorix <https://github.com/exynos967/astrbot_plugin_memorix>；LivingMemory <https://github.com/lxfight-s-astrbot-plugins/astrbot_plugin_livingmemory>；Mnemosyne <https://github.com/lxfight/astrbot_plugin_mnemosyne>
- MaiBot 持久层 <https://deepwiki.com/DrSmoothl/MaiBot/12.1-database-schema-and-models>
- 记忆投毒 MPBench <https://scixplorer.org/abs/2026arXiv260604329D/abstract>；Visual Inception <https://aclanthology.org/2026.acl-long.954/>；长期记忆安全综述 <https://ar5iv.labs.arxiv.org/html/2604.16548>
- Spring AI ChatMemory <https://blog.csdn.net/weixin_43128865/article/details/169233533>；LangChain4j ChatMemory <https://howtostartprogramming.in/mastering-langchain4j-memory-and-chat-history-management-2026-tutorial/>
- VLA 长期记忆综述 arXiv:2610.23319；Always-On Agents 综述 <https://ar5iv.labs.arxiv.org/html/2606.30306>
