# bma3-qq-official-connect — QQ 官方机器人适配器

## 职责

- 只承载 QQ 官方机器人通道：AppID/AppSecret 引用、AccessToken、OpenAPI、Gateway、事件解析、官方详情/发送工具与分享二维码。
- 原始官方消息事实进入消息板；不替代、不修改 `bma3-qqconnect` 的 OneBot 路径。
- 跨渠道逻辑来源、绑定、展示选择和一致性判定属于 `bma3-app` / `bma3-inbox`，本模块不 switch QQ 渠道。

## 红线

- 默认关闭、默认不触网；未配置或 `enabled=false` 时不获取 token、不连接 WebSocket。
- 禁止 inline secret：只能通过 `appSecretEnv` 或 `appSecretFile` 二选一引用；二者同时存在或启用账号缺失引用时 fail-closed。
- `qqofficial` 配置段不接受 `${ENV:...}` 插值；`appSecretEnv` 直接填写环境变量名，避免解析前把真实 secret 带入配置树。
- `accountId` 必须是安全单段：拒绝 `/`、空白、控制/格式字符和超长值，错误输出不回显不可信值。
- 官方段布尔/整数字段严格校验类型，写错不得静默回退默认值。
- 凭据解析和消息处理只在运行期按职责读取数据；secret、AccessToken、Authorization 不得进入日志或工具结果，正文不得进入日志。错误与日志只保留安全的 accountId、状态、错误码、trace_id、耗时；详情工具按设计可返回脱敏消息正文。
- 首版 `shard` 仅 `[0, 1]`；不支持的分片配置 fail-closed。
- 不绕过消息板发送；发送超时按 `UNKNOWN` 处理，禁止自动重发。

## 入口与包

- `io.github.c1589190.bma3.qqofficial.credential`：受保护凭据引用解析（Phase 2）。
- `io.github.c1589190.bma3.qqofficial.auth`：AccessToken 获取、缓存、刷新。
- `io.github.c1589190.bma3.qqofficial.api`：官方 HTTP OpenAPI。
- `io.github.c1589190.bma3.qqofficial.gateway`：WebSocket 状态机。
- `io.github.c1589190.bma3.qqofficial.event`：事件解析与幂等键。
- `io.github.c1589190.bma3.qqofficial.account`：账号注册/状态。
- `io.github.c1589190.bma3.qqofficial.tools`：官方 AgentTools。

配置类型位于 `bma3-app` 的 `AppConfig.QqOfficial`；当前实施进度与媒体草稿的安全限制见 [`../docs/agents/STATUS.md`](../docs/agents/STATUS.md)。

## 验证

```bash
./mvnw -B -ntp -pl bma3-qq-official-connect -am test
./mvnw -B -ntp -pl bma3-app -am -DskipTests package
```

真实 QQ 官方网络联调不能由上述本地构建冒充；未执行项写 `NOT_RUN`。
