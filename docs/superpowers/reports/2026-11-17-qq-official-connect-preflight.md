# QQ 官方机器人接入预检

> **类型**：调查 / 运行预检（2026-11-17 时间点快照，只作追溯，不作当前事实）。
> **方法**：用户提供的官方机器人 AppID/AppSecret 只写入仓库外 `0600` 凭据文件；本报告不记录凭据值。使用 Python 标准库访问官方 HTTPS API，再用 Python `websockets` 建立真实 WSS 连接；没有发送任何 QQ 消息。
> **触发**：D-074 官方机器人适配与 A+B 二维码实现前的能力确认。

## 已确认事实

| 步骤 | 结果 |
|---|---|
| `POST https://api.bot.qq.com/app/getAppAccessToken` | HTTP 200，成功取得 AccessToken |
| `GET https://api.bot.qq.com/users/@me` | HTTP 200，返回机器人详情 |
| `GET https://api.bot.qq.com/gateway/bot` | HTTP 200，返回 WSS 地址，`shards=1` |
| 连接返回的 WSS 地址 | 收到 `OpCode 10 Hello` |
| 发送 `OpCode 2 Identify`，intents=`1 << 25`，shard=`[0,1]` | 收到 `OpCode 0 READY`，含 session_id，shard 为 `[0,1]` |
| `POST https://api.bot.qq.com/v2/generate_url_link` | HTTP 200，返回 `https://bot.q.qq.com/s/...` 短链接 |
| 打开分享短链接 | 返回手机 QQ 扫码查看页面（“使用手机QQ扫描二维码查看”） |

## 安全边界

- AppSecret 与 AccessToken 未写入本报告、仓库或 MCP 工具结果。
- 凭据文件位于仓库外，权限 `0600`。
- 本次未调用发送消息 API，未发送 QQ 消息。
- 预检只证明账号、AccessToken、Gateway/WebSocket 和分享链接接口可达；不证明群消息权限、单聊/群聊真实入站、主动/被动发送、二维码扫码或双渠道一致性。

## 未验证项

- 真实群 @ 消息入站：`NOT_RUN`
- 真实单聊消息入站：`NOT_RUN`
- 真实群全量消息：`NOT_RUN`
- 官方文本/回复/媒体发送：`NOT_RUN`
- A 扫码创建/绑定：`NOT_RUN`
- B 二维码真实手机扫码：`NOT_RUN`（接口返回短链接已确认）
- 同一测试群内 OneBot 与官方机器人双渠道印证：`NOT_RUN`
