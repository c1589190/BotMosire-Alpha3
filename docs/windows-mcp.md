# Windows 原生运行与 Codex MCP

`0.0.8-early` 使用 JDK 21。在 Windows PowerShell 中运行下列步骤。Maven wrapper 首次使用时会下载 Maven，并从 ProjectMosire 的公开 Maven 仓库解析 AgentLib `0.2.7-early`。

在仓库根目录执行：

```powershell
.\mvnw.cmd -B -ntp package
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\windows\start-bma3.ps1 -SaveToken
```

默认连接 `ws://127.0.0.1:3001`，首次启动时会隐藏输入 OneBot access token。`-SaveToken` 可选：它用 Windows DPAPI 以当前 Windows 用户身份加密保存，以后启动无需重新输入；不指定时只在本次进程中使用，也可预先在当前进程设置 `BMA3_ONEBOT_TOKEN`。不使用 token 的 provider 首次运行时传 `-NoToken`，不要同时传 `-SaveToken`。其他地址在首次运行时传 `-Endpoint 'ws://127.0.0.1:端口'`；对于已生成的配置请直接修改配置文件。

默认运行目录是 `%LOCALAPPDATA%\BotMosire-Alpha3`，可用 `-ConfigRoot` 改为仓库外的独立目录。首次创建时脚本为目录设置仅当前用户可访问的继承 ACL；已有目录必须已经满足要求，脚本不会替用户修改已有目录的 ACL。配置在其中的 `config\app.yaml`，已有配置不会覆盖。数据目录、MCP token 和 DPAPI 密文也位于该运行目录；备份和迁移应妥善保护消息数据。默认监听 `127.0.0.1` 的 MCP `8765`、健康检查 `8766`，Inbox 使用系统分配的空闲端口；关闭 Xvfb、Web 浏览器、DSH 心跳及推送积压探针。启动窗口需要保持打开，按 Ctrl+C 停止；脚本不会安装系统服务或设置自启动。

保持 BMA3 运行，另开一个 PowerShell 窗口注册 Codex 的标准 HTTP MCP：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\windows\configure-codex.ps1
```

如启动时指定了 `-ConfigRoot`，这里也要指定相同的值；如在配置中修改了 MCP 端口，传对应的 `-Port`。脚本在 Codex 配置中追加名为 `bma3` 的服务；同名服务存在时会拒绝覆盖。配置使用 Codex 的 `http_headers_helper` 调用 `mcp-headers.ps1` 从受保护的 `.bma3env` 读取 MCP token，不把 token 写入 Codex 配置。配置引用本仓库中的脚本，移动仓库后需要更新该路径。新建 Codex 会话以加载配置；如仍看不到服务，重启 Codex 再试。当前运行中的会话不保证自动发现新服务。这个连接是标准 MCP，不需要安装 Codex 插件；没有自动唤醒或向会话注入消息的功能。

不同 OneBot 11 实现对扩展动作支持不同。`qq_remote_history` 自动识别已知的 LLBot 和 SnowLuma 历史接口，也可以显式指定 `historyDialect`；未知实现只尝试首页，不会虚构分页游标。群请求数据支持已识别的分类对象和扁平数组；没有可靠 flag 映射时不能直接决定请求。其他扩展操作要以实际 provider 的能力为准。BMA3 的 MCP 仍包含已有写操作，读取 QQ 不要求调用它们。

如需从上游固定源码重建 AgentLib，可运行 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\windows\bootstrap-agentlib.ps1 -Rebuild`。该脚本校验源码 ZIP 的 SHA-256 后安装依赖，可传 `-ProxyUri 'http://127.0.0.1:端口'`。直接运行 Maven 时可在当前 PowerShell 进程设置 Java 的 `MAVEN_OPTS` 代理参数。

Windows 的 Codex Computer Use 操作当前桌面，应用可能需要单独授权；它不等同于隔离的 Xvfb 虚拟桌面。该版本不额外实现 Windows 桌面自动化后端。
