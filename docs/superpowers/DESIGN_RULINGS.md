# 设计裁定索引（D-xxx）

> 用户设计裁定的**逐字原话**与落点。维护裁定在 [`../agents/MAINTENANCE_RULINGS.md`](../agents/MAINTENANCE_RULINGS.md)。
> 引用格式：`D-xxx · 日期 · 来源 · 原话`。
> 标记 `〔转述〕` 的条目是压缩后的要点，不是逐字引文。

| ID | 日期 | 来源 | 裁定（原话或转述） | 落点 |
|---|---|---|---|---|
| D-001 | 2026-11-09 | 本会话用户消息 | 「当前目录将会作为隔壁项目的继承与重构被开发」 | 根 `AGENTS.md` §2；继承调查报告 |
| D-002 | 2026-11-09 | 本会话用户消息 | 「那QQ协议方面就用单正向WS吧」 | QQ 适配器第一版只开正向 WS：同一连接收事件/调 API，不双开 WS+HTTP |
| D-003 | 2026-11-09 | 本会话用户消息 | 「DSH插件不单单要维护Inbox的实际注入，还要维护一个简单的角色扮演用系统提示词、工具组预设，这两个东西是分开的小组件，但是统一在一个插件中维护」 | 一个 DSH 插件包，内部三个独立 module/row：Inbox 注入、角色提示词、工具组预设；配置/测试/失败域分开 |
| D-004 | 2026-11-09 | 本会话用户消息 | 「inbox肯定是要做成独立常驻服务的」<br>「单论Inbox服务端lib，只管单个Inbox的封装状态，包括有多少需要处理的条目、什么时候推送、什么时候被消费等」 | Inbox 独立常驻服务；服务端 lib 只封装单 Inbox 状态机，不拥有正文、不知道平台、不做传输 |
| D-005（提议） | 2026-11-09 | 本会话用户消息 | 「我的想法是，这种网页端比较成熟的应用，可以让服务端对接Windows/Linux的成熟Chronium，封装一套简单的登录、状态保存机制，方面Agent自己操作，进行登录、服务端linux屏幕控制等服务端本体环境不方便做的事」 | 浏览器 sidecar（Chromium/Playwright）负责网页登录/状态保存/截图操作；Linux 全桌面控制是否纳入，**未定，待评审** |
| D-006 | 2026-11-09 | 本会话用户消息 | 「走jlink+jpackage吧」<br>「类似billion context，首次安装时检测机器上是否有botmosire-a3程序，如果没有就从github上拉安装包安装，哦对了，botmosire的dsh插件也要做到一站式标准安装」 | Java 21 用 `jlink`/`jpackage` 产出自带运行时的 app-image；DSH 插件负责首次检测、从 GitHub Release 下载/校验/安装；服务由 systemd 常驻 |
| D-007 | 2026-11-09 | 本会话用户消息 | 「botmosire-a3的配置文件系统和日志系统必须优秀，允许部分服务热修改配置文件重连（例如qq连接……其实qq模块本来就该允许多账号热插拔吧？反正onebot协议本来就允许多开），日志则是该录入的调试信息都要录入」 | 配置采用分模块 schema + 热重载/对账；QQ 账号 registry 支持热插拔；日志结构化、分级、脱敏、可动态调级 |
| D-008 | 2026-11-09 | 本会话用户消息 | 「首先是底包……agentlib（带标准AgentTool实现，自动把AgentTool注册到MCP并走agentlib本土渠道暴露）、qqconnect……webconnect……是肯定需要的……此外还有新的inbox，inbox也要依赖agentlib，提供标准MCP、HttpAPI，最终全部整合到app，顺便挂WatchDog或者监控之类的」 | 初版模块：`agentlib` / `qqconnect` / `webconnect` / `inbox` / `app`；connect 模块实现标准 `AgentTool`，app 统一注册 MCP；**共享 core/store 是当前缺项，待确认** |

## 待裁定

以下问题部分已定、部分未定；未定项等 spec 确认，不得把提议当成既定设计。

| # | 问题 | 现状 |
|---|---|---|
| Q-A | 重构范围：保留并演进现有 Java 实现，还是重写？ | 未定；倾向重写，但语言未定 |
| Q-B | 仓库形态：单仓单模块、单仓多模块，还是回到多仓？ | 未定；当前远端是单仓 |
| Q-C | 运行时形态：外部宿主、进程内 Agent 循环，还是纯服务端？ | **已定方向**：DSH 为唯一宿主；Inbox 为独立常驻服务（D-004） |
| Q-D | Inbox/唤醒层：保留独立通知板、内嵌，还是取消 IPC 只留本地表？ | **已定**：独立服务；Inbox lib 只管单 inbox 状态（D-004） |
| Q-E | B 站能力与 Playwright：留在主产物、拆独立模块，还是移出？ | 未定；视频工具可复用，私信需适配器，Playwright 不应进主产物 |
| Q-F | 兼容性：旧配置键、`qq_*`/`inbox_*` 工具名、`source` 地址是否保持？ | 未定 |
| Q-G | 数据迁移：旧 `bot.db`、`inbox.db`、媒体与凭据如何处理？ | 未定 |
| Q-H | 发布形态：fat jar、容器、服务端常驻，还是别的？ | **已定方向**：独立常驻服务；Java 用 `jlink`/`jpackage` 自带运行时（D-006） |
| Q-I | 语言：Java/Kotlin、TypeScript、Go？ | **已定**：Java 21 + `jlink`/`jpackage`（D-006） |
| Q-J | 浏览器 sidecar 范围：只做网页登录/状态保存，还是包含 Linux 桌面控制？ | 未定（D-005 是提议） |
| Q-K | 是否增加共享 `core`/`store`（Account/Conversation/Message/Source/Outbox/Media），让 qqconnect/webconnect/inbox 共用消息板？ | 未定；当前模块图缺此项，是本轮最大结构缺口 |
| Q-L | Inbox 是 app 内的模块，还是 app 之外的第二个进程？ | 倾向：app 是独立于 DSH 的常驻服务，Inbox 是 app 内模块；待确认 |

## 记录规则

- 用户给出新设计裁定后，先在本表登记逐字原话与日期，再写专题 spec；索引与 spec 互相链接。
- 旧项目的 D-xxx 不自动继承；只有被本项目 spec 明确吸收的，才以「继承自 D-xxx」标注。
- 被取代的裁定不删除，加状态头与取代者。
