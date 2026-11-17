# QQ 官方机器人扫码 onboarding（Phase 7-A）

这是一个**与 BMA3 常驻 Java 运行时隔离**的 Node.js 工具，用腾讯官方
[`@tencent-connect/qqbot-connector@1.2.0`](https://www.npmjs.com/package/@tencent-connect/qqbot-connector)
完成官方机器人扫码创建/绑定。

- Node.js 要求：`>=18`。Node 只是这个 onboarding 工具的可选前置条件，**不是 BMA3
  jar / app-image 的运行依赖**。
- SDK 版本与 `package-lock.json` 已锁定；本仓不复制、不内嵌 SDK 的 `UNLICENSED`
  源码。
- 工具只调用 SDK 的公开 API：`startQrConnect` / `qrConnect`。SDK 流程变化时以固定错误码
  失败，不猜测内部端点，也不静默降级。
- 本工具没有实现 B 分享二维码；B 属于 Java 官方模块的 `qq_official_share_qr`。

## 安装

```bash
cd tools/qq-official-onboarding
npm ci
```

## 使用

先准备一个仓库外、仅当前用户可访问的目录：

```bash
# POSIX
install -d -m 700 "$HOME/.config/bma3"

# Windows PowerShell 示例（目录由当前用户创建，实际 ACL 由工具在文件上校验）
New-Item -ItemType Directory -Force "$env:LOCALAPPDATA\BotMosire-Alpha3" | Out-Null
```

运行：

```bash
node bin/qq-official-onboarding.js \
  --credentials "$HOME/.config/bma3/qq-official-credentials.json"
```

也可以用 npm 包声明的 bin（安装后或 `npm link`）：

```bash
qq-official-onboarding \
  --credentials "$HOME/.config/bma3/qq-official-credentials.json"
```

### 参数

| 参数 | 必填 | 说明 |
|---|---:|---|
| `--credentials <absolute-path>` | 是 | 凭据输出文件，必须是绝对路径且在仓库外。 |
| `--qr-out <absolute-path>` | 否 | PNG 二维码输出，必须在仓库外；目标已存在则拒绝。默认在凭据同目录生成带随机后缀的新文件。 |
| `--timeout-ms <n>` | 否 | 整个扫码流程超时，默认 `180000`。 |
| `--source <text>` | 否 | 传给官方 connector 的来源标识，默认 `bma3`。 |
| `--no-terminal-qr` | 否 | 不向 stdout 输出终端二维码，只输出 JSON 状态行。 |

默认 PNG 路径由工具生成为随机新文件并在 `QR_READY`/`SUCCESS` 状态中返回；也可以用
`--qr-out` 指定一个**尚不存在**的绝对路径。终端二维码会直接显示在 stdout；同时始终生成
PNG（除非流程失败并在清理阶段删除本次文件）。
stdout 的状态行只有 `status` / `accountId` / `credentialPath` / `qrPath` /
`errorCode` 这些字段。`--no-terminal-qr` 时 stdout 可直接按 JSON Lines 解析。

成功时凭据文件的内容形如：

```json
{
  "appId": "<app-id>",
  "appSecret": "<redacted>"
}
```

`appId` 与 `appSecret` 是两个独立字段。CLI 最终只输出成功状态、账号标识
（`accountId`，即 `appId`）和文件路径；不会输出 `appSecret`、SDK 原始错误文本、
access token 或用户 openid。

## 安全与失败语义

- **凭据绝不进 stdout/stderr/异常文本**。SDK 的 `onFailure(error)` 只被归类为固定
  错误码，`error.message` 不复制到任何输出。
- 凭据目标必须在**仓库外**，并先经过路径规范化/真实路径检查，防止用 symlink 指回仓库。
- POSIX：
  - 临时文件以 `O_CREAT|O_EXCL|O_WRONLY|O_NOFOLLOW`（可用时）预创建，随后 `chmod 0600`，
    写入、`fsync`、关闭，再同目录 `rename` 原子替换目标。
  - 校验临时文件和最终文件的 owner/uid 与 `0600`；父目录必须是当前用户所有且不可被
    group/other 写。无法验证即拒绝。
- Windows：
  - 临时文件创建后通过 `icacls` 移除继承 ACE，只授予当前用户 `R,W,D`。
  - 再用 PowerShell `Get-Acl` 读取结构化 DACL，要求 owner 是当前用户、没有继承 ACE、
    只有当前用户的 Allow ACE；无法验证或出现其他 principal 即拒绝。
  - 原子 rename 后会再次验证目标 ACL。
- 已有凭据文件必须本来就是 owner-only；过宽权限会被拒绝，不会被“顺手覆盖成安全”。
- 替换既有凭据时先在**同目录创建旧 inode 的 hard-link 备份**，再原子 rename 新凭据。
  最终 ACL/owner/mode 复核失败时，用原子 rename 恢复旧 inode，而不是删除新文件后把旧文件
  一起丢掉。无法创建备份时 fail-closed，旧目标保持不动。
- 超时、SIGINT/SIGTERM 取消、SDK 失败、QR 生成/写盘失败都会 `stop()` SDK、中止
  `AbortSignal`、清理临时文件和本次已生成的 PNG；不会留下 secret 临时文件。
- 二维码文件也会拒绝写入仓库，避免误提交。
- **二维码不覆盖非本次运行文件**：运行开始前已存在的 `--qr-out` 直接拒绝并保留原文件。
  运行中刷新只允许覆盖本次运行上一张、且 inode/大小/时间/内容 SHA-256 都匹配的 QR；
  超时/取消清理也只删除身份匹配的本次文件。路径被外部替换后，原文件会被保留。
- 真实扫码需要用户手机参与；本工具测试全部使用 fake SDK，不会联网、扫码或发送消息。

### 状态与退出码

| 状态 / errorCode | 退出码 | 含义 |
|---|---:|---|
| `SUCCESS` | 0 | 凭据已原子写入并通过权限复验。 |
| `FAILED` / `CONNECTOR_FLOW_FAILED` | 1 | 官方 connector 流程失败；不输出 SDK 原文。 |
| `FAILED` / `CONNECTOR_API_CHANGED` | 1 | 锁定的公开 API/返回契约发生变化。 |
| `FAILED` / `CONNECTOR_VERSION_MISMATCH` | 1 | 实际安装版本不是 `1.2.0`。 |
| `FAILED` / `CREDENTIAL_*` | 1 | 凭据路径、已有文件、备份或权限校验失败。 |
| `FAILED` / `CREDENTIAL_FINAL_VERIFY_FAILED` | 1 | 替换后最终验证失败，已回滚旧凭据。 |
| `FAILED` / `CREDENTIAL_ROLLBACK_FAILED` | 1 | 回滚无法完成；不会删除仍保留的 owner-only 备份。 |
| `FAILED` / `WINDOWS_ACL_*` | 1 | Windows ACL 无法建立或验证，已 fail-closed。 |
| `FAILED` / `QR_*` | 1 | 二维码生成或原子写入失败；`QR_TARGET_EXISTS` / `QR_TARGET_CHANGED` 表示不会覆盖未知文件。 |
| `TIMEOUT` / `ONBOARDING_TIMEOUT` | 124 | 扫码超时，已清理本次文件。 |
| `CANCELLED` / `ONBOARDING_CANCELLED` | 130 | 用户中断，已清理本次文件。 |
| `FAILED` / `OUTPUT_PATH_*`、`INVALID_ARGUMENTS` | 2 | CLI 参数或输出路径不合法。 |

## 测试

```bash
npm test
```

测试覆盖：

- fake SDK 成功、失败（含假 secret 的错误文本）、过期刷新、超时、外部取消；
- 凭据 `appId`/`appSecret` 分离、POSIX `0600`、已有文件替换、最终验证失败回滚旧凭据、
  备份失败 fail-closed、宽权限拒绝、仓库内路径拒绝、symlink 拒绝、目录权限拒绝；
- Windows ACL 成功、不可验证、命令失败和最终 ACL 失败回滚模拟，失败时无临时文件残留；
- 二维码终端渲染、PNG 写入、PNG 内容用 `jsqr` 实际解码回同一 URL；
- 预存 QR 拒绝覆盖、本轮刷新身份校验、外部替换文件在超时清理时保留；
- CLI 只输出允许字段且不泄露假 secret；
- 锁定版本、Node engine 与 `package-lock.json` 一致性。

测试只使用 fake connector，不进行真实扫码或联网调用。

## 已知未验证

- 真实手机 QQ 扫码、真实腾讯服务端绑定：`NOT_RUN`（需要用户参与）。
- 真实 Windows `icacls` / PowerShell DACL 路径：Linux 测试环境仅以注入命令的 fake
  覆盖解析与失败关闭逻辑，真实 Windows ACL 联调：`NOT_RUN`。
- 官方 connector 内部私有端点属于上游行为，未来可能变化；本工具只通过锁定版本的公开
  API 使用它，并以 `CONNECTOR_API_CHANGED` / `CONNECTOR_FLOW_FAILED` 明确失败。
