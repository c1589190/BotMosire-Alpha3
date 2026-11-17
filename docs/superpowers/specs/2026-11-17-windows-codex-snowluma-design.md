# 0.0.8 Windows / SnowLuma 纯 MCP 适配设计

> 状态：实现与验证中；2026-11-17（UTC+8）。用户已明确 [D-071](../DESIGN_RULINGS.md) 和 [D-072](../DESIGN_RULINGS.md) 的范围；实际验证与未完成项见 [当前状态](../../agents/STATUS.md)和[调查报告](../reports/2026-11-17-windows-codex-snowluma-survey.md)。

## 需求与范围

用户原话摘录：“现在我想让其更加通用、适用于Windows环境下的Codex，并且接入我在当前系统启动的SnowLuma”；另要求“调查和定位相关问题、做好修改准备”。原始消息包含连接凭据，本文不引用该部分。

补充裁定：“我想创建0.0.8的windows系统/纯MCP适配版本”；“对于开发，我甚至不认为现在应该激进地把Codex插件实现出来，因为对于SnowLuma这种注入和我的需求，我只需要windows下的bma3提供一个易用的MCP给你来读我的QQ即可”。

进一步裁定：“其实你接下来的工作主要是易用性和通用性（Onebot11在各种平台上的表现各有不同，这一点你得注意）改动”；“对于xvfb的等价功能，目前Codex有能力提供ComputerUse吗？如果有的话就不用额外适配了”。见 [D-072](../DESIGN_RULINGS.md)。

目标：核心 Java 服务在 Windows 原生运行并接入 SnowLuma，提供易用、受保护的 MCP，让 Codex 主动读取 QQ 消息与相关信息。读取应经过 BMA3 封装的 QQ 工具，不能依赖 Inbox 注入（[D-073](../DESIGN_RULINGS.md)）。Windows 工作区、GitHub 登录和构建过程与可能独立维护的 WSL 项目分开，不依赖 WSL 中的仓库、凭据、依赖缓存或运行实例（[M-004](../../agents/MAINTENANCE_RULINGS.md)）。

本版本不开发 Codex 专用插件，不实现对 Codex 会话的自动唤醒或注入；Windows 桌面自动化后端不属于本轮需求。读取 QQ 是首期重点；用户没有要求删除或隐藏现有写工具，不擅自作此裁定。保留已有服务的安全约束，是否需要其他安装和常驻方式可在后续明确。

当前 Codex 会话已提供 Windows Computer Use 工具；应用访问可能需要用户逐应用授权，Windows 使用当前前台桌面，不承诺 Xvfb 式的独立虚拟桌面。相关功能由宿主提供，本版本不实现替代后端。

## 运行序列与契约

1. 操作员选择独立运行目录，提供 SnowLuma 正向 WebSocket 地址及仅在进程环境或受保护凭据存储中可用的 token；不把 token 放在仓库示例、日志或工具结果。
2. 服务创建/读取 MCP、Inbox、debug 各自 token，创建消息板及 Inbox 数据，保持原有单实例、仅回环绑定和令牌分离；在 Windows 上检查实际 DACL，在 POSIX 上检查 owner-only 语义。安全权限无法落实时如何向用户暴露应在实现前明确，不能静默宣称成功。
3. QQ provider 握手后，区分基础 OneBot 11 与厂商扩展。`get_group_system_msg` 的分类对象和数组由可测试的标准化边界处理；未知或不完整响应返回可诊断错误。历史按 provider 的真实游标与排序语义组包/解析，不能假设所有服务均使用 LLBot 的 `message_seq`。
4. Codex 使用支持认证的标准 MCP 客户端主动访问服务，读取消息板和 QQ 工具；不要求安装 DSH 插件，也不依赖后台对当前聊天进行注入。
5. 按实际启用情况注册或报告 Xvfb、浏览器和宿主探针；被禁用或不适用于当前平台的能力应与故障区分。不能擅自破坏其他现有能力；其他环境的验证结果应单独记录。

健康探针候选契约：`xvfb.enabled=false` 时报告探针已禁用，不把未启用的 Linux 功能当成 Windows 故障；`watchdog.pluginHeartbeatEnabled` 默认为 `true` 以保留现有 DSH 行为，纯 MCP 配置可设为 `false`，此时 DSH 心跳探针报告已禁用。恢复启用后仍按既有心跳时限检测。配置视图必须如实显示该设置。

`watchdog.pushBacklogEnabled` 独立控制推送积压探针，默认为 `true` 以保留现有行为；没有 SSE 推送消费者的纯 MCP 运行配置设为 `false`，报告该探针已禁用，不把尚未推送的待阅读条目视作故障。此选项可热重载，且不改变 Inbox 内容、游标或 ACK 语义。需要监控 SSE 消费者的其他宿主可独立打开它。

敏感文件候选契约：token 和含诊断正文的文件在写入内容前先对空临时文件设置 owner-only 权限；POSIX 用 `0600`，Windows 用仅文件所有者可访问的 DACL，并在替换后再次验证。读取已有 token 文件前也先收紧权限；不能建立权限或拒绝符号链接时不得在 token 模式下继续启动。应用不擅自更改用户已有整个数据目录的权限；Windows 启动工具创建独立私有运行目录。

Windows 启动入口默认使用 `%LOCALAPPDATA%\BotMosire-Alpha3`：首次创建后先设置仅当前用户的可继承 DACL，再写入配置或数据；已有目录仅验证、不擅自更改权限。首次生成的配置连接指定的 OneBot 正向 WS，禁用 Xvfb 和 DSH 心跳，不覆盖已有配置。OneBot token 从进程环境、交互输入或用户主动选择的 Windows DPAPI 当前用户加密文件取得；无 token 的 provider 可显式选择该模式。Codex 可通过标准 HTTP MCP 的 `http_headers_helper` 从 BMA3 受保护的 token 文件读取 Bearer，不需要自定义插件，也不在 Codex 配置中写入 token。辅助脚本和实际连接均需验证；未获验证的宿主行为不得宣称通过。

单实例锁继续与旧版本的整文件锁互斥；新版本只锁定文件中远离 PID 文本的一个字节，使 Windows 上的其他进程仍可读取锁文件中的进程号。锁文件仍不得删除，锁无法获取时不得继续启动。

历史读取候选契约：按账号/当前连接的 `get_version_info` 识别已知 provider；调用方可明确指定已知方言以处理未实现该标准动作的 provider。LLBot 继续使用原有 `message_seq`、`reverseOrder` 和游标语义；SnowLuma 使用 `message_id` 与 `reverse_order`，以实际消息 ID 作为锚点，翻页时请求额外一条并去除锚点，游标携带方向且不能跨方言误用。未知 provider 只尝试不带游标的通用首页，不猜测翻页语义；分页不可用要明确返回，不能伪称没有更多消息。provider 的非零状态不得统一视为空页；现有 LLBot 专属的 `1200` 处理只用于已识别的 LLBot 方言。

未知 provider 的通用历史首页在每条记录都有有效时间戳时按时间应用 `reverseOrder`；时间戳不完整时保留 provider 原始顺序并报告无法确认排序，不虚构顺序。

## 不变量

- 消息板仍是事实源；文本及附件发送仍经现有统一出口，账号按来源路由；`UNKNOWN` 不自动重发。
- 凭据和消息正文不进入提交、样例和不必要的日志；token 模式和回环绑定不因兼容性被静默降级。
- Token 文件、含正文的诊断数据以及宿主持久状态按平台落实可验证的访问限制；临时文件、替换目标及已存在文件都纳入边界。
- 本版本不新建宿主通知、自动注入或唤醒路径；现有消息板和 ACK 安全语义不因纯 MCP 接入而被削弱。
- provider 未知响应不能伪装成“没有请求”或“历史结束”；provider 不支持的动作必须明确表示，不能触发不安全自动回退。
- 区分 OneBot 11 标准动作与 provider 扩展；扩展的参数、响应及游标按具体实现识别或显式指定，不根据端口或连接地址猜测实现。不同账号的 provider 身份和能力相互隔离。
- 不把 Windows 运行、编译、Git 凭据建立在 WSL 项目或 WSL 环境上；每个平台探针的禁用与失败含义应可区分。

## 验收判据

1. Windows 环境能根据文档获得 Maven、AgentLib 依赖并原生构建，且不依赖 WSL 工作区或缓存；Windows Java 测试有真实结果。遇到无法运行的项必须标 `NOT_RUN`。
2. 敏感文件实际 ACL/POSIX 权限测试覆盖首次创建、已有文件、原子替换和错误路径；`.bma3env` 与本地运行配置不会被意外加入 Git。
3. SnowLuma 的只读群/好友列表、消息读取及分页契约有 fixture 和真实只读验收证据；未知响应不得伪装为空结果。LLBot 或真实 QQ 写操作不以未运行的测试冒充通过。
4. Windows 下 QQ、SQLite、MCP/Inbox 在禁用不支持能力后可以启动；健康状态不因明确禁用的 Xvfb 或未选用的 DSH 宿主产生误导性降级。
5. Codex 实际发现并调用 BMA3 MCP 工具，能够按用户请求读取本地 SnowLuma 接入的 QQ 数据；不以主动唤醒或会话注入作为本版本验收条件。

## 未决问题

- Windows 服务自启动/安装方式及默认本地运行目录。
- SnowLuma 扩展各版本的能力识别、非空群请求 flag 映射及完整媒体契约，均需进一步实测或基于其明确版本的文档。
