# BMA3 本体管理 OneBot 凭据

> 状态：已确认，2026-11-23（UTC+8）。

> 额，如果下移到bma3本体会不会更好？
>
> 开始实现吧。哦对了，你能就在当前环境验证这是否在wsl linux上同样可用吗？

Agent 通过 BMA3 MCP 的 `bma3_credentials` 管理 OneBot access token，使用既有的 `set_config` 设置账号、`qq_account_list` 检查实际连接。凭据操作包括 `list`、`set`（含替换）、`remove` 和 `import_windows_onebot`（导入 Windows 启动器保存在当前用户目录的 DPAPI 文件）。账号使用 `tokenRef`；已有 `tokenEnv` 与无鉴权账号沿用各自的连接方式。Windows Desktop、Linux DSH 与独立 JAR 共享这套协议。首期范围为 OneBot access token。

## 不变量

- 凭据按数据目录隔离，使用独立的运行期文件。Linux 权限为 `0600`，Windows 使用仅当前用户可读的 ACL 和 CurrentUser DPAPI。更新采用原子替换。工具结果和应用日志只显示状态、引用或错误码。存储不进入配置导出，`.bma3env` 承载服务端鉴权。
- `tokenRef` 与 `tokenEnv` 互斥。账号的凭据缺失或不可读时，该账号停止连接并在 `qq_account_list` 中报告错误。MCP 和 Inbox 继续服务，Agent 随时可以修复。已有 `tokenEnv` 账号启动时也可诊断缺失变量；配置热重载保留既有的无效 `tokenEnv` 拒绝契约。
- 设置或删除引用只重新协调使用该引用的账号。删除立即撤销相关连接及 watchdog 的重连目标。替换沿用账号 registry 的连接切换和诊断语义。重启恢复保留已写入的凭据。凭据与配置更新按顺序应用。
- Windows 导入读取现有 DPAPI 文件并复制到当前实例。包含明文的调用参数按 D-060 处理。真实 Windows/WSL 运行实例的数据、配置与会话由对应宿主管理。

## 验收判据

- 在 Windows 与 WSL Ubuntu 的隔离实例，通过真实 MCP 和假 OneBot 回环服务验证缺凭据启动、Agent 设置与绑定、同账号热重连、删除和重新补齐、进程重启恢复及输出脱敏。Windows 验证 DPAPI 导入，Linux 验证文件权限。
- 配置兼容性及账号隔离门禁通过。保留原有 Windows 整合包的已验收文件；凭据验证可以使用当次构建的独立 JAR 和隔离配置。

## 未决问题

- 实际 DSH profile 升级及真实 QQ 联调单独验收。
