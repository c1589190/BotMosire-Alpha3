# DeepSeek 全家桶应用 API（2026-11-13 发布）契约摘要

> **类型**：外部契约摘要（一手文档）· **查阅日期**：2026-11-14
> **用途**：评估 bma3 工具面里"业务能力"能从上游直接复用多少，避免自研重复。
> **一手来源**（均为官方文档，2026-11-14 抓取）：
> - 更新日志 <https://api-docs.deepseek.com/zh-cn/updates>（条目「发布全家桶应用 API」）
> - 搜索与抓取 <https://api-docs.deepseek.com/zh-cn/guides/search>
> - 联网工具（模型侧） <https://api-docs.deepseek.com/zh-cn/guides/web_tools>
> - OCR <https://api-docs.deepseek.com/zh-cn/guides/ocr>
> - 语音识别 <https://api-docs.deepseek.com/zh-cn/guides/asr>
> - 语音合成 <https://api-docs.deepseek.com/zh-cn/guides/tts>
> - 文件解析 <https://api-docs.deepseek.com/zh-cn/guides/document_parsing>
> **本文不替代原文**；关键契约保留上面的链接。

---

## 1. 这次发布给了两类东西

### 1.1 模型侧「服务端工具」（声明即用，工具在服务端跑）

| 工具 | 作用 | 关键限制 |
|---|---|---|
| `deepseek_web_search` | 联网搜索（非思考模式）/ 深度思考搜索（思考模式）/ 图片搜索 | 仅 `deepseek-flash`；`tool_choice` **不能**指定它；`none` 关闭全部工具；用过它的回复**不能续写**（400） |
| `deepseek_read_url` | 阅读最后一条用户消息里的链接（最多前 10 个不同 URL） | 同上；网页正文不回传，加密在 `signature` 里，多轮必须**原样回传**该块才拼回上下文 |

- 协议：Messages / Responses / Chat Completions 都支持（`tools: [{"type": "deepseek_web_search"}]`）。
- 计费：与 Search/Fetch 同价（按成功请求次数）；`usage.server_tool_use` 回报 `web_search_requests` / `web_fetch_requests` / `tool_search` / `tool_fetch` / `tool_image_search`。

### 1.2 独立接口（自己调用）

| API | Endpoint | 入参要点 | 返回 | 计费 | 限速 |
|---|---|---|---|---|---|
| Search | `POST /v1/tools/search` | `query`(1–128)、`count`(1–50，默认 10)、`need_content`、`ori_question`、`localization` | `results[]`：`rerank_score`(0–3，≥1.5 优质)、`title`、`url`、`snippet`、`summary`(600–800 字)、`content`(需 `need_content`)、`site_name`、`date`、`authority`(0–4，≥3 极权威) | 5 元 / 1000 次**成功**请求 | QPS 100 |
| Fetch | `POST /v1/tools/fetch` | `url`(≤8192，http/https，不带账号密码) | `title` + `content`（Markdown 正文） | 5 元 / 1000 次成功 | QPS 25（另受目标域名 QPS） |
| OCR | `POST /v1/tools/ocr` | multipart：`file`(带文件名) + `purpose`(`text_recognition` \| `rich_layout_recognition`) | `pages[].text`（逐页原始输出，含 `<｜ref｜>`/`<｜box｜>` 标记与 0–1000 归一化坐标）、`page_count`、`usage` | 1.5 元 / 1000 页 | 并发 15 |
| ASR | `POST /v1/tools/asr` | multipart `file`：**16 位 PCM WAV 或 Ogg Opus**，≤8 MiB，≤120 s | `text`、`duration_ms` | 0.78 元 / 小时（限时 5 折 0.39） | 并发 50 |
| TTS | `POST /v1/tools/tts`（整段）或 `wss://…/v1/tools/tts`（流式） | `text`(≤10 000 计费字符，汉字算 2)、`voice_id`(`stella`/`tide`)、`format`(`wav`/`pcm`)、`filter_markdown` | 音频流（wav，或 24 kHz/16 bit/单声道 PCM） | 3 元 / 10 000 计费字符（限时 5 折 1.5） | 并发 50 |
| 文件解析 | `POST /files` → 对话里引用 `file_id` | `purpose`(`text_recognition` \| `rich_layout_recognition`) 必须写在 `file` **之前** | `file_id`；对话请求里按 `{"type":"document","source":{"type":"file","file_id":…}}` 引用 | 解析按页计费（价格见 `pricing#ocr-pricing`，**本次未读**）；引用时按输入 token 计费 | 并发 15 |

补充要点：

- **Search 语法**：`site:` / `-site:` / `-词` / `-"短语"` / `"精确短语"` / `lang:zh,en` / `after:2026` / `before:2026-06-01`。
- **Fetch 错误语义**：`404` = 没取到可用正文；`403` = 安全策略拒绝；两者都不重试；`408/429/5xx` 才重试。
- **文件解析**：PDF/Word/PPT + 常见图片，≤100 MiB、≤1000 页、上传 120 s、解析 120 s；`user_data`（txt/md/csv/json/代码/xlsx/epub/mobi，≤32 MiB）**不计费**；每个请求最多 20 个文档、文字合计 ≤8 MiB。
- **模型内容安全**：`safety_check` 参数；开启后读链接会被审核，未通过返回 `url_not_allowed`。

---

## 2. 本机宿主（DSH `0.2.2-rc.3`）已经接好哪部分

宿主安装树里已存在（`~/.local/share/pnpm/global/v11/<hash>/node_modules/.pnpm/`）：

| 包 | 角色 |
|---|---|
| `@deepseek-ai/dsh-web` | web seam（`ctx.web.search()/fetch()`，`searchProvider` / `fetchProvider`） |
| `@deepseek-ai/dsh-web-deepseek-api` | 注册 `deepseek-search-api` + `deepseek-fetch-api`（**直连本文 §1.2 的 Search/Fetch 接口，一次操作一次请求，不额外跑模型轮次**） |
| `@deepseek-ai/dsh-web-search-deepseek` | 注册 `deepseek-official`（走 Messages API 的模型侧搜索，**一次搜索 = 一次模型请求**，慢且贵） |
| `@deepseek-ai/dsh-web-fetch-http` | 本地 HTTP 抓取 provider |
| `@deepseek-ai/dsh-tool-web` | 模型面工具：`web_search`、`web_fetch`（配置 `{search:false}` / `{fetch:false}` 可单开） |

**关键：`dsh-tool-web` 用的正是"能力就绪才暴露"的范式**（可作为 bma3 分层门控的直接先例）：

- `src/search.ts:414` — `ctx.tools.register(webSearch, { when: ({ modelSelection }) => ctx.web.searchReady(modelSelection.provider) })`
- `src/fetch.ts:516` — `ctx.tools.register(webFetch, { when: ({ modelSelection }) => ctx.web.fetchReady(modelSelection.provider) })`
- 策略（README 摘要）：**凭据拿不到 → 该请求干脆不提供这个工具**；**provider 运行期不可用 → 工具仍可见，调用返回结构化错误**。

**挂载形状**（本机 `dsh --profile web --dump-config`，2026-11-14）：

- 全局：`web` 行（provider 选择）+ `web-search-deepseek` + `web-deepseek-api` + `web-fetch-http`；全局 `tool-web` 默认 `disabled: true`。
- 每个 preset（`standard` / `ptc` / `cordis`）在自己的 composition 里各挂一个 `tool-web` → **模型面工具属于 preset，provider 属于全局**。

**已知坑**：`@liustack/modsearch` 的 bundle 注释（`#43`）——base 层钉了 `searchProvider: deepseek-official` + `fetchProvider: http`，去掉 fetch 的钉之后，一旦宿主也注册了 `deepseek-fetch-api`，`web_fetch` 的 provider 解析会**歧义**。任何新组合都要显式钉两个 provider。

**当前不支持**：`@deepseek-ai/dsh-llm-deepseek@0.2.2-rc.3` 的 wire 类型里没有 `server_tool_use` / `web_search_tool_result`，也没有 `deepseek_web_search` / `deepseek_read_url` 的声明位置（`src/wire-types.ts` 全文 + `src/` 内 grep 无结果）⇒ **§1.1 的模型侧工具在当前宿主上不可用**，要用得改上游适配器。

---

## 3. 与 bma3 的落点（评估，不是设计）

| 能力 | 对 bma3 的价值 | 建议归属 | 说明 |
|---|---|---|---|
| Search / Fetch | 三层设计里 L2 的"DSH 自己提供的搜索" | **L2，直接复用上游** | 挂 `dsh-web-deepseek-api` + `dsh-tool-web` 即可，零自研；5 元/1000 次，单请求、无额外模型轮次 |
| `x_search` / `read_page` | 现有 `@liustack/modsearch` 已经提供（全局层） | 与上一行**二选一或并存需去重** | 与 `web_search` 语义重叠，需在 spec 里定"谁是正牌" |
| OCR | QQ 收到的 PDF/Word/PPT/图片 → 文本；bma3 **目前没有文档解析** | **L3**（按页计费、单次 180 s） | 可作为"贵且少用"的典型成员 |
| ASR | QQ 语音消息转写；bma3 目前无此能力 | **L2 或 L3** | **前置阻塞**：OneBot 语音多为 silk/amr，API 只收 16 bit PCM WAV 或 Ogg Opus ⇒ 需要先转码 |
| TTS | 语音回复 | L3 | 汉字算 2 个计费字符 |
| 文件解析（Files API） | 只有"对话模型引用 `file_id`"这一条路 | **bma3 Java 侧用不上** | MCP 工具无法把 `file_id` 塞进 DSH 的对话请求；除非 DSH 侧支持附件 |

---

## 4. 需要一起考虑的风险

1. **正文出境**：ASR / OCR / 搜索会把 QQ 收到的内容上传给 DeepSeek。仓库现有红线是"正文不落**本地**日志"，"正文出境"是新问题 → 必须在 spec 里明确开关与告知。
2. **凭据**：`apiKeyEnv` 或 credentials ref；账号级 QPS 与模型并发独立计。
3. **计费单位不直观**：Search/Fetch 按成功次数、OCR 按页、ASR 按秒、TTS 按字符；"失败不计费"但**超时/断连仍可能计费**（OCR 明确写了客户端断开不取消）。
4. **超时**：OCR 单次最长 180 s；Fetch 目标域名有独立 QPS。三层设计里这类工具必须走 L3，否则长尾调用会拖垮回合。
5. **命名去重**：`web_search`（tool-web）vs `read_page`/`x_search`（modsearch）同名语义重叠；同一作用域内同名注册会抛错，跨作用域则是"近者遮蔽"——需要设计层面定案。

---

## 5. 未验证 / NOT_RUN

- 未用真实 API Key 调用过任何接口（**NOT_RUN**）。
- 文件解析的按页单价未读（`pricing#ocr-pricing` 未抓）。
- bma3 侧尚无任何实现或 profile 变更。
