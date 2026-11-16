# 0.0.7-hotfix2 诊断、媒体、QQ、xvfb 设计（切片 B）

> **状态**：已确认方向并进入实现（2026-11-16，用户要求持续实现、无需逐项确认）
> **范围**：watchdog HTTP/MCP 诊断包、jar 侧媒体计数、插件媒体流水线、xvfb 高层易用性、QQ friend/provider 动作面。
> **依据**：[hotfix2 调查](../reports/2026-11-16-hotfix2-feature-survey.md)、D-069/D-070。
> **不改变**：Inbox 单一调度器、发送必须经消息板、`UNKNOWN` 不自动重发、凭据/正文不落诊断。

## 1. watchdog 诊断包

### 1.1 HTTP

`HealthServer` 新增：

```text
GET /watchdog/diagnostics
Authorization: Bearer <MCP_TOKEN>
```

- 只绑回环；复用 `/watchdog` 的受保护入口，不新增公网无鉴权端点。
- 返回 content-free JSON bundle，至少包含：
  - `version`、`now`、`security.auth`；
  - watchdog snapshot（组件、facts、最近事件、计数）；
  - Inbox status + plugin heartbeat；
  - `media` counters（见 §2）；
  - DB 文件大小（`bot.db`/`inbox.db`/`schedule.db`/目录/扩展/debug requests）。
- 不返回正文、token、Cookie、URL、绝对路径或完整日志。

### 1.2 MCP

`bma3_watchdog_diagnose` 返回同一 bundle + watchdog snapshot；只读，不执行恢复。

### 1.3 验收

- 无 Authorization → 401；错误 token → 401；正确 token → 200 JSON。
- body 不含 `Bearer`/`Cookie`/消息正文/绝对路径。
- `/watchdog/diagnostics` 不因单个可选组件 unavailable 而 500。

## 2. 媒体诊断与流水线

### 2.1 jar 媒体计数

新增 `QqMediaDiagnostics`（进程内计数，content-free）：

- attempts/successes/failures；
- downloadedBytes；
- splitImages；
- byKind（image/file/record/video）；
- errors（错误码计数）；
- downloads 目录 files/bytes 汇总。

`qq_media_view` 在解析、成功下载、切图、失败时更新计数；watchdog 新增 `media` 探针，facts 只放数字。

### 2.2 插件媒体流水线

插件新增 `bma3_media(filePath, kind=auto, purpose=...)`：

- `auto` 按扩展名路由 image/document → OCR，audio → ASR；
- 调用前校验文件存在；
- 无 `DEEPSEEK_API_KEY` 时返回 `{ok:false, code:"MEDIA_FAILED", error:"...未配置..."}`，不请求外网；
- 不声称纯文本模型能直接“看图”；明确要求 OCR 文本或 provider 视觉模型。

### 2.3 验收

- jar 计数 attempts/success/failure 与 `qq_media_view` 调用一致；
- watchdog `media` probe 只含数字；
- `bma3_media` 对 image 路由 OCR、对 audio 路由 ASR、未知扩展名结构化失败；
- 缺 key 时零外网请求且无密钥回显；
- 真实 LLBot/provider OCR 仍 `NOT_RUN`，不冒充通过。

## 3. xvfb 易用性

新增两个高层工具，保留现有 12 个原语：

- `xvfb_open_browser(workspace?, url?, binary?)`：
  - 默认浏览器取 `webconnect.binary`（默认 `/snap/bin/chromium`）；
  - workspace-local `home/chromium-profile` 保持登录态；
  - URL 仅允许 `http(s)`；
  - 返回 pid/browser/profile/workspace/display。
- `xvfb_wait(workspace?, titleContains?, timeoutMs?)`：
  - 轮询真实窗口，默认 10s、上限 60s；
  - title 为空时忽略 1×1 Openbox 辅助窗口；
  - 超时返回 `WAIT_TIMEOUT`。

验收：工具面从 12 个变为 14 个；默认/自定义 browser binary 可断言；不改变原有截图、输入、workspace 语义。

## 4. QQ friend/provider 动作面

### 4.1 通用 provider action

新增 `qq_provider_action(accountId?, action, params?, confirm?)`：

- `get_*`/`can_*`/`check_*`/`ocr_*`/`download_*` 视为只读；
- 其他 action 必须 `confirm=true`；
- `send_private_msg`/`send_group_msg`/`send_msg`/`send_forward_msg`/`send_group_forward_msg`/`upload_group_file`/`upload_private_file` 永久拒绝，发送必须走消息板 typed 工具；
- 返回 provider 原始 `response`（含 status/retcode）。

### 4.2 friend typed 便捷面

新增 `qq_friend(operation, userId, ...)`：

- `info` → `get_stranger_info`（只读）；
- `remark` → `set_friend_remark`；
- `delete` → `delete_friend`；
- `like` → `send_like`；
- `poke` → `friend_poke`；
- `request_decide` → `set_friend_add_request`。
- 除 `info` 外必须 `confirm=true`；provider 不支持时保留原始错误，不伪造成功。

### 4.3 验收

- 通用 action 读写确认/发送拦截均有测试；
- friend info 只读；delete/remark 未确认被拒，确认后发出正确 action；
- 不改变 `qq_friend_list`、群工具、发送 typed 工具；
- 真实 LLBot friend 写操作 `NOT_RUN`。

## 5. 不变量

1. 诊断只读、content-free、loopback + Bearer。
2. 媒体外部调用只在 Agent 显式调用工具时发生，不批量、不缓存、不回显 key。
3. 无 OCR key / 无 provider 支持时明确失败，不伪造识别结果。
4. 通用 QQ action 不能绕过消息板发送和 `UNKNOWN` 不重发。
5. xvfb URL 只允许 http(s)；不新增公网 viewer/人机接力。
6. 真实 provider/QQ/B 站/DSH 行为未验证时写 `NOT_RUN`。
