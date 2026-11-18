# 记忆层设计想法记录（未裁定、未实现）

- 日期：2026-11-18
- 状态：**想法记录**。未写 spec、未写代码、未做任何实现。上一份《长期记忆调查报告》见 `docs/superpowers/reports/2026-11-18-long-term-memory-survey.md`（市场地图与推荐架构），本文件只记后续四轮设计讨论的结论与本机核实过的事实。
- 规则约束：按 `AGENTS.md` 红线，新功能须走「需求 → spec（不变量 + 验收判据）→ 计划 → 实现」。本文件不是 spec，任何内容在 spec 落地前都不构成承诺。

## 0. 需求演进（用户原话摘要）

| 轮次 | 用户消息 | 要求 |
|---|---|---|
| 1 | m00001 | 「我希望在当前项目上为机器人实现一个长期记忆系统，能不能先行调查一下市面上的各类llm记忆实现方式，告诉我你的看法？」 |
| 2 | m00147 | 「肯定是自主形成的、无预设对象的经验/知识，并且要求Agent显式写入」+「是否能和dsh的上下文管理结合，在Agent记录相关记忆的时候，顺带把真实的上下文一并计入进去？调查一下」 |
| 3 | m00362 | 「很显然bili是我装的插件，我要实现的是原生的薄记忆层」+「有没有更加llm式的方法？例如活用emb模型，乃至更激进的，即时训练本地emb模型之类的」 |
| 4 | m00387 | L0+L1 结合：多个记忆工具（录入带标签、搜索带 10× 标签权重、缩略列表可翻页、查看、标记有用/无用影响权重）+ 由 dsh 插件提供"格式化上下文"能力 |
| 5 | m00433 | 「先完善并拓展watchdog的功能，让dsh插件端被允许推送绑定会话的新上下文条目到bma3的jar」，jar 维护滚动上下文，无 LLM 算力自动缩略，Agent 可查/选/导出 txt，记忆导入接受**完整文件**而非工具参数 |

用户已裁定：记忆对象 = **自我型经验/知识**（无预设对象）；写入通道 = **Agent 显式工具调用**。

## 1. 已核实的硬约束（本机实测 / 源码行号）

### 1.1 模型侧

- **DeepSeek 没有 embeddings 端点**：`gomodel` 的 DeepSeek provider 注释 "DeepSeek does not expose an embeddings endpoint"，`Embeddings()` 直接返回错误（https://pkg.go.dev/github.com/enterpilot/gomodel@v0.1.91/internal/providers/deepseek ）。本项目唯一模型凭据是 `DEEPSEEK_API_KEY`（`dsh-plugin/lib/media.js:28-29`）。→ 用向量必须新增供应商或本地模型，没有"顺手就有"的选项。
- 架构级"记忆即权重"（Titans / TTT / Engram）需改模型权重，API 模型上不可用；唯一可行类比是 context distillation（把记忆蒸馏进常驻提示段）。综述：https://eu.36kr.com/en/p/3926253551679881 ，Titans 分析：https://raw.githubusercontent.com/lhl/agentic-memory/refs/heads/main/ANALYSIS-arxiv-2501.00663-titans.md 。
- 微调 embedder 的现实门槛：10k+ 带硬负例的标注对换 5–15% nDCG（https://lobehub.com/skills/claude-dev-suite-claude-dev-suite-embedding-fine-tuning ）。本机 Python 3.12.3 仅 numpy 2.5.5，无 torch/sentence-transformers/onnx；bot 目标机更不会假设有 GPU。

### 1.2 打包与依赖

- `packaging/build-app-image.sh`：`jlink --strip-debug --compress=2` + `jpackage --type app-image` → `dist/botmosire-a3-linux-x86_64.tar.gz` 现 **85M**（jar 53M）。`packaging/install.sh` 装到 `~/.local/share/botmosire-a3` 并由 **systemd user unit** 运行。
- 全仓 `pom.xml` 无 onnx/djl/tensorflow/pytorch 依赖；`pom.xml:40` `<sqlite.version>3.53.4.0</sqlite.version>`，实测该版本 `fts5(..., tokenize='trigram')` 可用且中文 `MATCH` 命中。
- 既有外部付费接口先例 **D-068**（`docs/superpowers/DESIGN_RULINGS.md:97`）：OCR/ASR 在插件 `lib/media.js`，"不批量、不缓存、不写回消息板"，正文出境仅限被显式指定的单个文件。

### 1.3 DSH 侧（版本 0.2.2-rc.3）

- `ctx.sessionQuery: SessionQueryEngine` 由 `declare module '@deepseek-ai/cordis'` 暴露（`dsh-session-query/lib/types/index.d.ts:26-31`），插件 `inject: ['sessionQuery']` 即可使用。
- `listEvents(sessionId)` 返回 `SessionEventRecord[]`，其形状为 `{ sessionId, seq, type, time, surface }`（`dsh-session-query/lib/types/types.d.ts:45-56`）——**不含 body**，是天然的"上下文结构目录"。
- `readEvent({sessionId, seq, before, after})` → `SessionEventWindow { target, events[], startSeq, endSeq }`（`index.d.ts:159`）；窗口硬上限 `SESSION_QUERY_READ_WINDOW_MAX = 50`（`dsh-session-query/lib/index.js:9`）。
- `readSurface(sessionId)` → `SessionSurfaceSnapshot`（`index.d.ts:136`）；官方导出 `extractSessionEventText`（`index.d.ts:19`），格式化文本不必自写提取器。
- `ToolExecutionInput.agent?: Agent`（`dsh-tools/lib/types/contracts.d.ts:174`），`Agent.sessionId`（`dsh-agent/lib/types/index.d.ts:51`）→ 工具能知道自己属于哪个会话，无需模型传 session_id。
- 插件可订阅 `'session/event'(session: Session, event: SessionEvent)`（`dsh-session/lib/types/index.d.ts:66`）拿增量事件。
- `session-query-sqlite` 挂在 `@deepseek-ai/dsh-base/cordis.patch.yml:157-161`，配置 `{ path: ':memory:', openAt: never }`：`openAt: never` 关闭全文搜索，**精确读保持可用**。bma3 profile 的 `dsh.profile.bundles` 第一项就是 `@deepseek-ai/dsh-base`，故该服务在运行时存在。
- 实测本会话：`session_read(mode=raw)` 报 `events 487 · captured through seq 486`，按 `compaction/start|summary|end` 过滤返回空 → **日志零 compaction 事件，seq 指针不会失效**。

### 1.4 现有项目侧通道

- 插件 → jar 的唯一既有通道是 inbox 面板心跳：`dsh-plugin/lib/inbox.js:1175` `requestUrl(config.inboxId, '/plugin/heartbeat')`，间隔 `dsh-plugin/lib/inbox.js:748` `heartbeatMs ?? 15000`。
- jar 侧路由表在 `bma3-inbox/src/main/java/io/mosire/bma3/inbox/http/InboxHttpServer.java:97-140`，全部挂 `/v1/inboxes/{inboxId}/…`，除 `/healthz` 外一律 Bearer 保护（`:91-95` 返回 401）。
- **`PluginHeartbeat` 的既有不变量**（`bma3-inbox/src/main/java/io/mosire/bma3/inbox/core/PluginHeartbeat.java:12-14`）：
  > "Only ids/status/counters are carried: never a token, **never a message body**."
- watchdog 参数区间见 `bma3-app/src/main/java/io/mosire/bma3/app/config/AppConfig.java:79-100`（`intervalSeconds 1..3600`、`failureThreshold 1..100`、`pluginHeartbeatTimeoutSeconds 5..3600`）。探针实现集中在 `bma3-watchdog/src/main/java/io/mosire/bma3/watchdog/probe/Probes.java`。
- bma3 预设的工具面 `dsh-plugin/lib/preset.js:23-87`：`tool-fs` 配的是 `{read:false, readImage:true, write:false, edit:false}` → **Agent 目前没有读/写文件的能力**。
- 消息板检索是 `LIKE` 子串而非 FTS：`SqliteMessageBoardStore.java:541` `WHERE (text LIKE ? OR sender_name LIKE ? OR sender_card LIKE ?)`。

## 2. 方案骨架（三层，均为想法）

### 2.1 A 层：上下文影子副本 `context_digest`

用户主张（m00433）：插件推送绑定会话的新上下文条目 → jar 滚动维护 → 不消耗 LLM 算力自动缩略 → Agent 查询、选中、导出 txt。

- **通道**：不塞进 watchdog，也不改 `PluginHeartbeat`。在现有 panel 路由表同层新增：
  - `POST /v1/inboxes/{inboxId}/plugin/context` 推送增量（幂等）
  - `GET  /v1/inboxes/{inboxId}/plugin/context?after_seq=N` 插件重启后补洞
- **表**：`context_digest(inbox_id, session_id, seq, type, time, surface, preview, body, body_bytes, hash, received_at)`，主键 `(session_id, seq)` → 重推即覆盖，天然幂等。
- **同步语义**：最终一致、可丢、可重建。插件侧记 `lastPushedSeq`，jar 侧给 `after_seq` 让插件补洞。**永不承诺强一致**，docs 里不写"同步"二字。
- **缩略**：纯机械规则（type 徽标 + 压空白 + 前 80 字），零 token。缺点是无语义 → 只能当索引用，真值靠 `context_ref` 回源。
- **正文落库**：建议存正文（否则"导出 txt"没内容可导），但必须先过 redaction、单条 ≤2KB、总量滚动淘汰（30 天 / 50MB）。

### 2.2 watchdog 的定位（明确反对项）

`PluginHeartbeat` 已写死 "never a message body"，且心跳 15s 一次、带 `failureThreshold` / `recoveryCooldown` 状态机 —— 业务正文走这条路会让数据可靠性与健康判定纠缠。

**正确分工**：数据走 2.1 的路由，watchdog 只**观察水位**——在 `Probes.java` 增加一个探针，判定条件 = `插件在线 && now - contextWatermark.at < N`，报告"插件在线但上下文停更"。这同时满足用户"watchdog 反正要监听 dsh 运行"的诉求：现有 watchdog 只能判断"插件死了"，判断不了"插件活着但不干活"。

### 2.3 B 层：记忆条目 `memory_item`

沿用调查报告 §6 的 DDL 草案，增补本轮讨论的字段语义：

`memory_item(id, kind, scope, subject, text, tags_norm, confidence, weight, bias, pinned, valid_from, valid_to, superseded_by, source_kind, source_ref, context_ref, created_at, last_used_at, use_count)`

- `source_ref` → 消息板 `local_seq`；`context_ref` → `{"session","seq","start","end"}`（调查报告 §6.7）。
- 正文可被模型自由编辑（用户明确"作为通用文本自然允许修改"）→ **记忆正文不可信**，只能靠双锚点事后核对；`memory_view` 固定输出出处。

### 2.4 工具清单（记忆 4 + 上下文 4，均为想法）

记忆侧：
```
memory_write(text, tags?, source?)          录入
memory_search(query, cursor?)               缩略列表：id|time|tags|前40字，8/页，最多3页
memory_view(id)                             正文 + source_ref / context_ref 出处
memory_rate(id, verdict: useful|useless)    调 bias，不删行
```
上下文侧：
```
memory_context_search(query, cursor?)       digest 检索
memory_context_view(from_seq, to_seq)       取正文（服务端按 seq 边界裁剪，窗口 ≤50）
memory_context_export(from_seq, to_seq)     写 run/bma3-live/downloads/context-<session>-<from>-<to>.txt
memory_import(path, tags?)                  从 downloads/ 读文件存为记忆
```
`memory_import` 的关键约束：bma3 预设 `tool-fs` 的 `write:false`（`dsh-plugin/lib/preset.js:23-87`）意味着 Agent 无法自己造文件 → 真正成立的两条闭环是 ①我们导出、我们导入；②QQ 收到的文件（`qq_media_view` 已下到 `downloads/`）。路径必须解析后仍在 `downloads/` 内、拒绝符号链接、大小上限 1MB（与 `OfficialQqLocalMediaPolicy` 的 TOCTOU 阻塞同一套要求）。

## 3. 检索与排序（L0+L1）

- **L0 目录即索引**：`memory_toc`/`listEvents` 类接口返回"一行一条"，模型自己挑；上限约 150–300 条，超出分片两跳。
- **L1 写入时让模型自写检索键**：`memory_write` 强制同时产出 `keys/tags`（3–5 个"将来会被什么问题召回"的说法 + 别名）。生成成本为零（写记忆的本来就是模型）。
- **标签 10× 权重不能靠 FTS**：FTS5 `trigram` 只对 **≥3 字符**子串建索引，中文二字标签（"站会"）`MATCH` 永不命中。故：
  - 标签：规范化列 `tags_norm` 存 `|例会|站会|`，走精确列匹配 `LIKE '%|例会|%'`（分隔符避免"例会"误命中"开例会"）；
  - 正文：`memory_fts(body)` trigram + BM25；查询 <3 字符回退 `LIKE`（与 `SqliteMessageBoardStore.java:541` 现有口径一致）；
  - 合分用 **RRF**（`10/(60+rank_tag) + 1/(60+rank_text)`），不做手工归一化。
- **`bias` 反馈**：`memory_rate` 只改 `bias`（clamp 到 ±3），带 30 天半衰期回落，避免一次误标永久埋没真记忆；**不改 `valid_to`**（不删行红线）。
- 若将来要上向量：优先"纯 Java 伪 embedding"（增量 LSA/SVD 到 k=64，或裁剪静态词向量表），不破坏 85M 发行物；本地 ONNX（bge-small-zh-v1.5 int8 约 24MB + runtime 每平台 20–30MB，推断）只在 eval 证明漏召回时再议。

## 4. 不变量草案（待 spec 正式化）

1. `context_digest` 是**缓存**：任何时刻可清空并由插件全量重放重建；记忆真值只认 `source_ref` / `context_ref`，永不认 `preview`。
2. 入 digest 前 redaction **不可绕过**，且有测试（`sk-[A-Za-z0-9]{16,}`、`Bearer\s+\S+`、`DEEPSEEK_API_KEY` 字面量、token 文件路径）。
3. sessionId 只认 inbox 绑定关系，**工具 schema 中不出现 session_id 字段**。
4. 记忆不删行：只写 `valid_to` / `superseded_by`。
5. `memory_rate` 只影响排序权重，不影响真实性判定。
6. 合成唤醒（定时任务、后台任务、watchdog 自愈）**禁止写入记忆**（参考 AstrBot LivingMemory 2.9.1 的同类修复）。
7. `memory_import` 只接受 `downloads/` 内、非符号链接、≤1MB 的文件。
8. 单次上下文打包有硬 token 预算，返回中带"已用/剩余"。

## 5. 风险

1. **密钥/私密内容入 digest**：DSH 上下文含工具结果与路径。redaction 缺失即是泄露通道（违反红线 2）。
2. **第二事实源**：jar 侧持有上下文副本，与"消息板是事实源"精神冲突 → 靠不变量 1（cache 语义）化解。
3. **换绑会话**：一个 inbox 只绑一个会话，换绑后旧 digest 需标 `orphaned`，否则记忆指向不可再读的 seq。
4. **反馈自污染**：`bias` 信号来自模型自身使用，会把一次投毒固化 → 需半衰期 + 冻结期 + holdout。
5. **体积**：约 500 事件/天 × 1KB ≈ 0.5MB/天，30 天约 15MB；digest 需与消息板分表并单独淘汰策略。

## 6. 未决问题（等用户裁定）

1. digest 存不存正文？（不存 → `memory_context_export` 无内容；存 → 需 redaction + 配额）
2. 记忆默认是否注入上下文？注入位置（`systemPrompt` 段 vs 回合位置）与 token 预算。
3. "忘掉"语义：软删（`valid_to`）还是硬删？用户数据主权口径。
4. read 方向要不要插件补洞（`after_seq`），还是允许丢失只做尽力同步。
5. 50 条 eval fixture 的构造来源与判分方式（没有 eval 就无法判断"更好"）。

## 7. 工作量粗估（未排期）

- jar 侧：`context_digest` 表 + 两条路由 + 2 个工具 + redaction + 测试 ≈ 3 天
- 插件侧：订阅 `session/event` 增量推送 + 水位补洞 ≈ 1.5 天
- watchdog 水位探针 ≈ 0.5 天
- 记忆侧（`memory_item` + FTS + 4 个工具）未估

## 8. 下一步

先写 spec（`docs/superpowers/specs/2026-11-18-*`，含「不变量」「验收判据」两节），用户审完再决定实现顺序。**本文件不构成实现授权。**
