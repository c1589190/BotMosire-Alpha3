# bma3-qqconnect —— QQ adapter

## 职责

- OneBot 11 正向 WS、账号 registry、QQ tools。
- 把 QQ 事件转成 `SourceView` + `InboxEntry`：来源标签、发送者、时间/序号、@/引用/图片/合并转发摘要。
- 提供 `QqSourceLabeler`、`QqSourceKeyer`（板地址 → 两级配置键）与 `QqSignalExtractor`（`qq_at_bot`/`qq_at_all`/`qq_at_other`/`qq_reply_to_bot`/`qq_self_name`/`qq_command`）；不把 QQ 专用字段写进 Inbox。
- 发送成功后把平台 message_id 回写到消息板（`external_message_id`），这是 `qq_reply_to_bot` 与 `self` 判定的唯一依据。

## 边界

- 发送必须经 `QqSendSupport`；所有文本发送共享 `maxTextChars`（默认 3000，可配置），不静默截断。
- 图片 URL/rkey 不进 `chat_message.text` 或 Inbox；结构化 payload 只进 `segments_json`，且只在工具 `includeSegments=true` 时返回。
- 分数不在本模块：`qq_source_weight_*` 与 `qq_user_note.priority` 已退休；来源规则/属性加分在 `config/app.yaml` 的 `weighting.*`，由 `set_config` 写入。

## 入口

- 事件/编码：`src/main/java/io/mosire/bma3/qqconnect/onebot/*`
- 发送：`src/main/java/io/mosire/bma3/qqconnect/tools/QqSendSupport.java`
- 媒体：`tools/{QqMediaSupport,QqMediaViewTool,QqMediaPolicy}.java`（fileId → 刷新链接 → 有上限下载）
- 来源标签：`src/main/java/io/mosire/bma3/qqconnect/tools/QqSourceLabeler.java`
- 设计：`../docs/superpowers/specs/2026-11-10-inbox-panel-sse-design.md`

## 验证

- `./mvnw -q -pl bma3-qqconnect -am test`
- 覆盖：3000/3001 字、emoji code point、图片占位 + 结构化 payload、@/reply marker、发送回写平台 id、发送人字段、媒体查看（内联/落盘/超限/坏 id）。
