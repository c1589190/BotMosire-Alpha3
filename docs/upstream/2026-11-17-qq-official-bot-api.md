# QQ 官方机器人 API 契约摘要

> **类型**：上游契约摘要（2026-11-17 查阅；只作实现依据，不替代官方原文）。
> **适用**：`bma3-qq-official-connect`、QQ 双渠道来源统一与 Inbox 一致性校验。
> **一手来源**：
> - [QQ 机器人官方文档首页](https://bot.q.qq.com/wiki)
> - [启动接入](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/getting-started.html)
> - [获取访问凭证](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/access-token.html)
> - [API 调用指南](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/api-call-guide.html)
> - [WebSocket 方式](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/websocket.html)
> - [通用数据结构 / Intents](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/payload.html)
> - [消息收发概述](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/overview.html)
> - [发送群聊消息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_messages.post.html)
> - [发送单聊消息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_messages.post.html)
> - [获取群基本信息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_info.get.html)
> - [获取机器人详情](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/users_me.get.html)
> - [生成分享链接](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_generate_url_link.post.html)
> - [接口文档索引](https://bot.q.qq.com/wiki/develop/api-v2/autogen/)
> - NPM 官方包：`@tencent-connect/qqbot-connector@1.2.0`（2026-11-17 从 NPM 获取并只读检查发布包）。

## 1. 身份与凭证

官方 QQ 机器人不是 OneBot。接入票据是：

- `AppID`：机器人 ID。
- `AppSecret`：换取 AccessToken 的密钥。

`Token` 的旧鉴权方式已被官方文档标为废弃；本实现只使用 Access Token。

AccessToken：

```http
POST https://api.bot.qq.com/app/getAppAccessToken
Content-Type: application/json

{"appId":"<APPID>","clientSecret":"<APPSECRET>"}
```

成功返回：

```json
{"access_token":"<TOKEN>","expires_in":"7200"}
```

现实约定：

- 有效期约 7200 秒；官方建议服务端自行刷新。
- 到期前 60 秒内重新获取会得到新 token，旧 token 在该窗口仍有效。
- 获取接口的业务失败即使在 HTTP 200 下也通过响应体 `code` 返回；不能只看 HTTP 状态。
- 调用 OpenAPI 使用：

```http
Authorization: QQBot <access_token>
```

- API 业务失败使用响应体 `err_code`；成功时成功结构不保证有 `err_code=0`。
- `message` 只用于人工排查，不应用作稳定错误分类；`trace_id` / `X-Tps-trace-ID` 用于排查。

## 2. WebSocket 事件通道

### 2.1 获取网关

- `GET /gateway`：通用 WSS 接入点。
- `GET /gateway/bot`：带分片建议与 session 限制；响应包含 `url`、`shards`、`session_start_limit`。

首次实现使用 `[0,1]` 单分片。若未来需要多分片，必须按网关建议数分别建连；不能自行假定 `shards=1`。

### 2.2 连接控制

WebSocket opcode：

| op | 方向 | 语义 |
|---|---|---|
| 0 | 接收 | Dispatch 事件 |
| 1 | 双向 | Heartbeat |
| 2 | 发送 | Identify |
| 6 | 发送 | Resume |
| 7 | 接收 | Reconnect |
| 9 | 接收 | Invalid Session |
| 10 | 接收 | Hello，含 `heartbeat_interval` |
| 11 | 接收 | Heartbeat ACK |

Identify 载荷的关键字段：

```json
{
  "op": 2,
  "d": {
    "token": "QQBot <access_token>",
    "intents": 33554432,
    "shard": [0, 1],
    "properties": {"$os":"linux","$browser":"bma3","$device":"bma3"}
  }
}
```

- `GROUP_AND_C2C_EVENT = 1 << 25`，十进制 `33554432`。
- 该 Intent 覆盖 C2C、群 @、机器人进/退群、好友变更、群消息接收开关等。
- Resume 需要保存 `session_id` 和最近收到的 `s`；恢复成功后收到 `RESUMED`。
- 不能把断线一律当新会话。优先 Resume；遇到 4006/4007 等明确不可恢复或协议拒绝时，再重新 Identify。
- 4014 表示 Intent 无权限；4914/4915 表示机器人只能沙箱连接或已被封禁。必须输出可诊断状态，不能无限重连。

### 2.3 事件封套

```json
{"id":"event_id","op":0,"d":{},"s":42,"t":"EVENT_NAME"}
```

- `id`：事件 ID。
- `s`：下行序号，心跳和 Resume 使用。
- `t`：事件类型。
- `d`：事件体。

## 3. 官方 ID 与消息事件

### 3.1 ID 格式

- 群：`group_openid`，字符串。
- 单聊用户：`user_openid`，字符串。
- 群成员：`member_openid`，字符串。
- 消息：`ROBOT1.0_...` 形态的字符串。
- 引用索引：`REFIDX_...` 形态的字符串。

**注意**：不同 AppID 获取到的官方 openid 不同；官方没有提供把 openid 反推成真实 QQ 号的通用能力。不能把官方 openid 当 OneBot QQ 号，也不能用哈希强行转成数字 ID。

### 3.2 支持的首期消息事件

| 事件 | 触发 | 关键限制 |
|---|---|---|
| `C2C_MESSAGE_CREATE` | 用户单聊机器人 | 需要单聊能力 |
| `GROUP_AT_MESSAGE_CREATE` | 群内 @ 机器人 | 默认群聊事件 |
| `GROUP_MESSAGE_CREATE` | 群内全量消息 | 机器人必须开启“接收所有消息”并有相应权限 |
| `GROUP_ADD_ROBOT` / `GROUP_DEL_ROBOT` | 机器人进/退群 | 用于本地目录 |
| `FRIEND_ADD` / `FRIEND_DEL` | 用户添加/删除机器人 | 用于状态与目录 |
| `GROUP_MSG_RECEIVE` / `GROUP_MSG_REJECT` | 群管理员开关机器人消息接收 | 用于能力状态 |

消息事件常见字段：

- `id`：消息 ID，可用于被动回复和撤回。
- `content`：文本内容；群 @ 事件已去掉 @ 机器人前缀。
- `author`：发送者；群聊有 `member_openid`、`member_role`、`member_nickname` 等，单聊有 `user_openid`。
- `group_openid`：群 OpenID。
- `timestamp`：RFC3339 时间。
- `message_type`：`0` 文本、`3` ARK 卡片、`101` 并行消息、`102` 聊天记录、`103` 引用消息。
- `attachments`：图片、视频、语音、文件等附件。
- `mentions`：群聊被 @ 的用户列表；`is_you` 标识是否 @ 当前机器人，`scope=all` 表示 @全体。
- `ark_data`：结构化卡片数据。
- `msg_elements`：嵌套消息元素，可用于引用/合并内容读取。
- `message_scene.ext`：包含 `msg_idx`、`ref_msg_idx`、`auth_token` 等上下文；不要记录 `auth_token`。

官方文档说明相同消息可能重复推送，需去重；具体事件中可依赖的字段可能因事件类型而异。实现应保存原始消息 ID 与事件 ID，禁止仅按正文去重。

## 4. 发送消息

### 4.1 端点

```http
POST /v2/groups/{group_openid}/messages
POST /v2/users/{user_openid}/messages
```

请求体核心字段：

- `msg_type`：`0` 文本、`2` Markdown、`7` 富媒体、`8` 图文卡片；单聊另有 `6` 输入中。
- `content`：文本。
- `msg_id`：被动回复消息 ID，来自入站事件的 `d.id`。
- `event_id`：事件回复 ID，来自事件封套 `id`；与 `msg_id` 二选一，只能用于支持的事件。
- `msg_seq`：与 `msg_id` 联合的回复序号；相同 `msg_id + msg_seq` 重复发送会失败。
- `media.file_info`：富媒体上传后的临时文件信息。
- `message_reference`：引用回复。

### 4.2 被动消息时效

| 场景 | 有效期 | 单条消息最多回复次数 |
|---|---|---|
| 单聊 | 60 分钟 | 4 次 |
| 群聊 | 5 分钟 | 5 次 |

被动回复超时或超过次数会失败；不能自动重试或伪造成功。

### 4.3 主动消息

主动消息没有 `msg_id/event_id`，受平台频控与用户开关限制。当前文档中的大致限制：

- 单聊主动：个人/企业认证 10 QPS，单关系 20 QPM，每日 1000 条/用户；未认证更严。
- 群聊主动：认证机器人 60 QPM，单群 20 QPM，每日 1000 条/群；未认证 30 QPM。
- 用户或群管理员可以关闭主动消息接收。

实际错误必须以响应 `err_code` 为准。发送状态为 `UNKNOWN` 时不得自动重发。

## 5. 读取与管理 API

确认存在的只读能力：

- `GET /users/@me`：机器人自身信息；响应中可能有 `share_url`。
- `GET /v2/users/{user_openid}/info`：已知用户信息；无好友关系可能拒绝。
- `GET /v2/groups/{group_openid}/info`：已知群信息；官方文档注明可能是白名单能力，错误 `11253` 表示无接口访问权限。
- `GET /v2/groups/{group_openid}/bot_state`：机器人在群内状态、主动消息开关、消息接收范围。
- `GET /v2/groups/{group_openid}/members` 等群成员接口：官方文档标注“内邀接入中”，不能作为首版必需能力。

公开接口索引中**没有确认到**“机器人加入的全部 QQ 群列表”或“QQ 群/C2C 服务端历史消息拉取”接口。因此：

- 群列表只能来自本地目录、进群事件、入站消息和手工别名。
- 会话历史只能以 BMA3 消息板为事实源。
- 不可用能力必须明确返回 `CAPABILITY_UNAVAILABLE` / 权限错误，不能返回空列表伪装成“没有数据”。

## 6. 分享二维码

官方接口：

```http
POST /v2/generate_url_link
Authorization: QQBot <access_token>
Content-Type: application/json

{"callback_data":"bma3"}
```

成功返回：

```json
{"data":{"url":"https://bot.q.qq.com/s/..."}}
```

2026-11-17 本机实测：

- AccessToken 获取 HTTP 200。
- `/users/@me` HTTP 200。
- `/gateway/bot` HTTP 200，`shards=1`。
- 实际 WebSocket 收到 `Hello`，Identify 后收到 `READY`。
- `/v2/generate_url_link` HTTP 200，返回可被手机 QQ 识别为分享页面的短链接。
- 未发送任何 QQ 消息。

分享链接只用于邀请/打开机器人分享页，不是登录凭据，也不能绕过群沙箱或平台权限。

## 7. 官方 Connector 扫码绑定契约

`@tencent-connect/qqbot-connector@1.2.0` 是腾讯官方发布的 Node 包，提供扫码连接/创建机器人并返回 `AppID` 与 `AppSecret` 的流程。

从发布包只读观察到的关键行为（**不在公开 API 文档中；属于上游 SDK 行为，必须锁版本并做失效检测**）：

1. 生成随机 32 字节 key，Base64 编码。
2. `POST https://q.qq.com/lite/create_bind_task`，body `{"key":"<BASE64>"}`，返回 `task_id`。
3. 绑定页面：

```text
https://q.qq.com/qqbot/openclaw/connect.html?task_id=<task_id>&source=<source>&_wv=2
```

4. `POST https://q.qq.com/lite/poll_bind_result`，body `{"task_id":"..."}`。
5. 轮询状态：`0=NONE`、`1=PENDING`、`2=COMPLETED`、`3=EXPIRED`。
6. 成功后返回 `bot_appid`、`bot_encrypt_secret`、`user_openid`。
7. `bot_encrypt_secret` 使用 AES-256-GCM 解密；key 为第 1 步随机 key，密文布局为 12 字节 IV、ciphertext、16 字节 tag，明文为 AppSecret。
8. 二维码过期时重新创建 task。

风险：

- 这些端点不是公开 OpenAPI 文档的一部分，未来可能变化。
- NPM 包 license 为 `UNLICENSED`；不作为 Java 运行时代码复制，不把其源码直接搬入本仓。
- 首版 A 方案优先用官方 Node SDK 作为隔离的 onboarding 工具；如果要求主 jar 完全不依赖 Node，再另立 Java port 的裁定与测试。

## 8. 安全与失败边界

- AppSecret、AccessToken、Authorization、`message_scene.ext.auth_token` 永不进入日志、Inbox、MCP 工具结果、配置样例或提交。
- 凭据文件必须 owner-only（POSIX `0600`；Windows 仅当前用户 DACL）。
- 不根据 HTTP 200 判断业务成功；必须解析 `code` / `err_code`。
- WebSocket 断线区分可 Resume、需 Identify、不可重试；不可重试错误不能让 watchdog 无限重连。
- 未识别事件只记录事件名/长度/错误码，不记录正文。
- 官方 openid 与 OneBot QQ 号不能自动互转；跨渠道匹配必须依赖显式身份映射或明确标记未证实。


## 9. Phase 6 增补：图片/文件上传与撤回（2026-11-17 核查）

> 本节只补上游契约，不写 spec/plan/实现。标记说明：
> - **[官方]**：QQ 机器人官方文档明确写出。
> - **[官方 SDK]**：腾讯官方 SDK 源码/文档行为，不等同于公开 OpenAPI 承诺。
> - **[冲突]**：同一批官方页面互相矛盾，不能自行选一个当成确定承诺。
> - **[未核实]**：官方一手材料没有说清，必须 fail-closed 或透传平台错误。
> - **[项目规则]**：BMA3 安全边界，不是腾讯官方能力承诺。

一手来源（除特别注明外，查阅日期均为 2026-11-17）：

- [群聊富媒体上传](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_files.post.html)
- [单聊富媒体上传](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_files.post.html)
- [富媒体消息概述](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/rich-media.html)
- [群聊富媒体预上传](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_id_upload_prepare.post.html) / [单聊富媒体预上传](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_id_upload_prepare.post.html)
- [群聊分片上传完成](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_id_upload_part_finish.post.html) / [单聊分片上传完成](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_id_upload_part_finish.post.html)
- [发送群聊消息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_messages.post.html) / [发送单聊消息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_messages.post.html)
- [撤回群聊消息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_messages_message_id.delete.html) / [撤回单聊消息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_messages_message_id.delete.html)
- [消息收发概述](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/overview.html) / [消息类型](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/type/overview.html)
- [API 调用指南](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/api-call-guide.html)
- [腾讯官方 Node SDK README](https://github.com/tencent-connect/qqbot-nodejs)；[`media.ts`](https://github.com/tencent-connect/qqbot-nodejs/blob/429a0f1b798f3b0fcef3679e207dbc0469a1df2d/src/protocol/api/media.ts)；[`media-chunked.ts`](https://github.com/tencent-connect/qqbot-nodejs/blob/429a0f1b798f3b0fcef3679e207dbc0469a1df2d/src/protocol/api/media-chunked.ts)

### 9.1 上传端点与场景隔离

**[官方]** 单聊和群聊上传接口独立，上传得到的 `file_info` 不能跨场景使用：

| 场景 | 最终上传/合并端点 | 文档频率 |
|---|---|---|
| 群聊 | `POST /v2/groups/{group_openid}/files` | 50 QPS |
| 单聊 | `POST /v2/users/{user_openid}/files` | 50 QPS |
| 群聊预上传 | `POST /v2/groups/{group_id}/upload_prepare` | 10 QPS |
| 单聊预上传 | `POST /v2/users/{user_id}/upload_prepare` | 10 QPS |
| 群聊分片完成 | `POST /v2/groups/{group_id}/upload_part_finish` | 10 QPS |
| 单聊分片完成 | `POST /v2/users/{user_id}/upload_part_finish` | 10 QPS |

- 预上传/分片完成页使用 `{group_id}`/`{user_id}`，但字段说明均指群/用户 OpenID；不要与真实 QQ 号互转。
- 发送媒体仍使用消息端点：群 `/v2/groups/{group_openid}/messages`，单聊 `/v2/users/{user_openid}/messages`。
- 统一 API 基地址 `https://api.bot.qq.com`，鉴权 `Authorization: QQBot <access_token>`。见 [API 调用指南](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/api-call-guide.html)。

### 9.2 上传方式：URL、直接数据、分片

- **[官方] URL**：最终上传请求可传 `url`，必须以 `http` 开头，由平台下载转存；平台下载失败可能有 `850026`。URL 直传不会返回 `raw_url`。
- **[官方] 直接数据**：`file_data` 是 `string`，文档描述为“直接上传二进制数据”，上限 5MB；文档没有逐字写 Base64。
- **[官方 SDK]** 官方 Node SDK 对 `buffer`/`localPath` 调用 `toString("base64")` 后放入 JSON `file_data`；因此本地文件/字节走该路径时按 Base64 实现，不使用 `file://` 或 multipart。SDK 在源文件达到 5MB 时自动转分片。
- **[官方] 分片上传（推荐，本地大文件）**：`upload_prepare` 传 `file_type`、`file_size`、`file_name`、`md5`、`sha1`、`md5_10m`；每片 PUT 到 `parts[].presigned_url`，成功后调 `upload_part_finish`（`upload_id`、`part_index`、`block_size`、分片 `md5`），最后带 `upload_id` 调最终上传接口完成合并。
- **[未核实]** `5MB` 指原始字节还是 Base64 编码后长度、短文件 `md5_10m` 的具体算法，OpenAPI 页面未说清；应做双边界检查并参考官方 SDK 行为。
- **[官方] `srv_send_msg`**：`true` 表示上传接口直接发送消息并占用主动消息频次，返回消息 ID；`false` 表示仅返回 `file_info`，供后续 `/messages` 使用。
- **[项目规则]** Phase 6 正常路径固定 `srv_send_msg=false`，上传与消息发送分离；不使用上传接口直接发送，以保证消息板先行和被动回复字段可控。

### 9.3 `file_type`、`file_info` 与 TTL

**[官方]** 上传接口的 `file_type`：

| `file_type` | 类型 | 接口页列出的格式 | 软限制 | 硬限制 |
|---|---|---|---|---|
| 1 | 图片 | png / jpg | 20 MB | 200 MB |
| 2 | 视频 | mp4 | 30 MB | 200 MB |
| 3 | 语音 | silk（群页另列 mp3/wav） | 20 MB | 200 MB |
| 4 | 文件 | 任意格式 | 200 MB | 200 MB |

- 超过软限制会降级为文件类型上传；超过 200MB 硬限制报错（典型 `850031`）。
- 请求体表把 `file_type` 标为“否”，但没有默认值说明；**[未核实]**，实现必须显式传 1..4，不能依赖默认值。
- **[冲突]** 富媒体概览列图片支持 `jpg/png/gif/webp/bmp`、语音支持 `silk/mp3/wav/ogg`；上传接口页只列较窄格式。不得承诺未列格式在所有场景可用；平台拒绝时透传 `850019` 等错误。

**[官方]** 上传成功响应：

| 字段 | 类型 | 含义 |
|---|---|---|
| `file_uuid` | string | 文件唯一 ID |
| `file_info` | string | 发送消息用；内部为序列化二进制数据，开发者不要解析，直接透传 |
| `ttl` | integer | `file_info` 有效期（秒）；`0` 表示可长期使用 |
| `id` | string | 仅 `srv_send_msg=true` 时返回的消息 ID |
| `raw_url` | string | 仅分片合并且类型为图片/视频/语音时条件返回；有效期与 `ttl` 一致 |

- **`file_info` 是 opaque string，不是 JSON 对象，不应 decode、重编码或打印。**
- **TTL 以服务端响应为准，不能写死 300 秒**；示例 300 只是示例。发送前检查过期，相关发送错误包括 `304080`。
- 单聊/群聊上传隔离；**[未核实]** 同 AppID 跨接收者复用、跨 AppID 复用均无官方保证；按账号与场景绑定，不跨目标复用。

### 9.4 媒体发送：`msg_type`、被动字段与 `event_id` 白名单

**[官方]**

- 图片、视频、语音、文件统一用 `msg_type=7`，内容放 `media.file_info`；图片/文件没有不同的发送 `msg_type`，类型由上传时的 `file_type` 决定。
- 路径目标为群/单聊 OpenID；`msg_type=7` 与 `media.file_info` 是实现语义必需字段。文档表格虽标“否”，但描述明确“`msg_type=7` 时填写”“`file_info` 来自上传接口”，不能据此省略。
- 被动回复二选一：`msg_id`（入站事件 `d.id`）或 `event_id`（事件封套 `id`）；不能同时传。
- `msg_seq` 只与 `msg_id` 联用，不填默认 1；相同 `msg_id + msg_seq` 重复发送会失败。
- 主动发送不带 `msg_id/event_id`；受平台频控和用户/群主动消息开关限制。
- `message_reference` 是可选引用回复，不是媒体发送必需字段。

**[官方] `event_id` 场景白名单：**

| 场景 | 支持 `event_id` 的事件 |
|---|---|
| 群聊 | `INTERACTION_CREATE`、`GROUP_ADD_ROBOT`、`GROUP_MSG_RECEIVE` |
| 单聊 | `INTERACTION_CREATE`、`C2C_MSG_RECEIVE`、`FRIEND_ADD` |

**[冲突] 被动回复窗口：**

- 消息总览：群聊 5 分钟/5 次；单聊 60 分钟/4 次。
- 群/单聊发送页的 `msg_id` 字段描述：均写“5 分钟内有效”。
- 不能把 60 分钟当成绝对保证；应按平台 `304103`、`40034005`、`40034128` 等错误分类，失败不自动重发。

### 9.5 撤回

**[官方] 端点：**

| 场景 | 端点 | 方法 | 频率 |
|---|---|---|---|
| 群聊 | `/v2/groups/{group_openid}/messages/{message_id}` | `DELETE` | 10 QPS |
| 单聊 | `/v2/users/{user_openid}/messages/{message_id}` | `DELETE` | 10 QPS |

- **时限**：发送超过 **2 分钟**不可撤回；超时报 `40064004`。
- **群聊权限**：机器人是群管理员时，可撤回自己的消息和普通群成员的消息；是普通成员时只能撤回自己的消息。成员消息 ID 从群消息事件 `d.id` 获取，自己的消息 ID 从发送响应获取。
- **单聊权限**：只能撤回机器人发送给当前用户的消息；文档未给单聊撤回用户消息的能力。
- **返回 [冲突]**：撤回接口页写成功 HTTP 200、无响应体（示例 `{}`）；API 调用指南说删除操作一般成功为 204 无包体。客户端应按 **2xx 且无业务 `err_code`** 判成功，不能只接受 200 或只接受 204。
- **关键错误**：群 `40061001`/`40062003`/`40064004`/`50065001`；单聊 `306009`/`40061001`/`40061002`/`40064004`。
- **[未核实]** DELETE 是否幂等未说明；重复撤回不得自动重试。`message_id` 必须与同一账号、场景和 `group_openid`/`user_openid` 匹配，不能跨账号/跨场景猜测。

### 9.6 冲突与未核实清单（实现前必须保留）

| 项目 | 标记 | 处理 |
|---|---|---|
| OpenAPI 是否逐字规定 `file_data` 为 Base64 | **[未核实]**；官方 SDK 明确 Base64 | 只按 SDK 行为实现，不发明 raw binary/multipart |
| 5MB 是原始大小还是编码后大小 | **[未核实]** | 两种大小都做保守限制；超过走分片 |
| 图片/语音完整格式 | **[冲突]** | 使用上传接口页保守清单，平台错误透传 |
| 单聊被动窗口 60 分钟 vs 5 分钟 | **[冲突]** | 不本地硬编码 60 分钟；依赖平台错误 |
| 撤回成功 200 vs 204 | **[冲突]** | 接受 2xx 且无 `err_code` |
| `file_info` 固定 TTL/跨目标复用 | **[未核实]** | 只信响应 `ttl`，绑定 account/scene/target |
| `srv_send_msg=true` 的被动字段与返回完整性 | **[未核实]** | Phase 6 不使用该路径 |
| 成功响应是否一定含 `err_code=0` | **[冲突]**：指南文字说 0，成功示例不含 | 非零 `err_code` 即失败；2xx 无 `err_code` 按成功结构解析 |
| 上传接口完整权限模型 | **[未核实]** | 不伪造能力；按平台 `err_code` 分类 |

### 9.7 项目边界（不是腾讯官方承诺）

项目规则依据：[根 `AGENTS.md`](../../AGENTS.md) 第 44-47、53 行；本节只引用，不修改。

- **[项目规则] 消息板先行**：发送前先写 outbound 消息板记录并绑定账号、raw source、目标 OpenID、媒体元数据和上传结果；成功回写平台 `id`/`timestamp`/`ref_idx`。撤回只更新记录和审计，不删除消息事实。
- **[项目规则] `UNKNOWN` 不自动重发**：`/messages` 超时/断连标 `UNKNOWN`；`srv_send_msg=true` 的上传超时等同发送 `UNKNOWN`；撤回超时标 `RECALL_UNKNOWN`。均禁止自动重试。纯 `srv_send_msg=false` 上传超时标 `UPLOAD_UNKNOWN`，不产生消息，也不得自动接着发送。
- **[项目规则] 账号不串号**：上传、发送、撤回必须使用同一 `accountId`、同一场景和同一目标 OpenID；`file_info` 与 `accountId + scene + targetOpenid` 绑定，不匹配 fail-closed。不同 AppID 的 openid 不可互转。
- **[项目规则] 统一文本硬限**：纯媒体发送不走文本字段；若未来支持媒体附带 `content`/caption，必须经现有统一文本出口并受默认 3000 字 `maxTextChars` 硬限。`file_info`、`file_data`、Base64、本地绝对路径不得进入正文、Inbox、日志或 MCP 工具结果。
- 真实媒体上传与撤回仍未在真实平台验收；没有单独授权时保持 `NOT_RUN`。
