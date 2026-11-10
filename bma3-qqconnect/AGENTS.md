# bma3-qqconnect —— QQ adapter

## 职责

- OneBot 11 正向 WS、账号 registry、QQ tools。
- 把 QQ 事件转成 `SourceView` + `InboxEntry`：来源标签、发送者、时间/序号、@/引用/图片/合并转发摘要。
- 提供 `QqSourceLabeler` 与 QQ `PrioritySignals`（首版 mention/command）；不把 QQ 专用字段写进 Inbox。

## 边界

- 发送必须经 `QqSendSupport`；所有文本发送共享 `maxTextChars`（默认 3000，可配置），不静默截断。
- 图片 URL/rkey 不进 `chat_message.text` 或 Inbox；图片只留占位/媒体引用。
- per-source 权重/备注/阈值是 QQ 的 policy 实现，通用 resolver 在 app 层。

## 入口

- 事件/编码：`src/main/java/io/mosire/bma3/qqconnect/onebot/*`
- 发送：`src/main/java/io/mosire/bma3/qqconnect/tools/QqSendSupport.java`
- 来源标签：`src/main/java/io/mosire/bma3/qqconnect/tools/QqSourceLabeler.java`
- 设计：`../docs/superpowers/specs/2026-11-10-inbox-panel-sse-design.md`

## 验证

- `./mvnw -q -pl bma3-qqconnect -am test`
- 覆盖：3000/3001 字、emoji code point、图片占位、@/reply marker。
