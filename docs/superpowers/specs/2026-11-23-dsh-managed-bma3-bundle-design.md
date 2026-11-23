# 0.0.9-early DSH 托管 BMA3 整合包

> 状态：已确认，2026-11-23（UTC+8）。实现基线为 `feat/0.0.9-early` 的 `99ca23a`。本轮提交并推送源码，发行和 Windows 整合包另行处理。

## 需求

> 首先是提交并推送到远程，作为0.0.9-early，然后发布release，其中应当包含一个新的整合性tgz安装包，其应当允许在dsh的指定目录下自动安装bma3-jar，不需要在外部额外维护bma3连接，并在dsh插件中暴露管理dsh中bma3jar启动和关闭的工具，这是否可行？告诉我你准备怎么设计这个整合包

> 3 这里有一点需要确认，那就dsh在非整合安装情况下，应当也允许管理设置了外部来源的bma3jar，这是否可行？

> 那么开始实现，注意，只提交并推送到远程，不要发布，因为我待会还要补windows上的整合发行包

首发目标为 Linux x86_64 和已实测安装流程的 DSH `0.2.2-rc.3` Web profile。此前已将含 Java 镜像的 tgz 安装进隔离 profile，校验交付内容并成功卸载。自动启动及自动接线进入本版真实宿主验收。[既有安装实测](../reports/2026-11-23-dsh-one-command-install-survey.md)

## 包与安装位置

整合 tgz 使用独立 bundle 名 `@mosire/bma3-dsh-managed`，内容为本版插件、托管模块、含 BMA3 JAR 与 Java 21 运行时的 Linux app-image，以及平台、版本和 SHA-256 清单。安装目标由 `dsh plugin --profile <name> add <tgz>` 选择。DSH 初始化该 profile 时，托管模块按收到的 `profileContext` 定位目录，校验镜像并将可执行文件安装到 `$DSH_HOME/bma3/instances/<profile>/releases/<version>-<sha256>/`。管理员可在该 profile 的插件配置中指定绝对运行根目录。配置、SQLite、Inbox 状态和日志位于运行根的独立子目录，可跨版本保留。首次生成的配置禁用 QQ 账号，管理功能收在选定 profile 中。[DSH bundle 安装契约](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/user/develop/basic/publish.md)

普通插件包也提供同一托管模块。管理员可以启用 `local-jar` 模式，在 profile 配置中指定绝对 `javaPath`、`jarPath` 和 `configRoot`。DSH 使用固定参数启动这份本地 JAR，复用同一组 `bma3_runtime` 工具、MCP 和 Inbox 接线。外部 JAR 默认由 `start` 启动，管理员可启用 `autoStart`。JAR 本体的更新由提供该文件的一方负责，DSH 继续使用指定的配置和数据目录。首版完整生命周期以支持托管握手及控制管道的独立 JAR 为准。

普通插件包配合已经运行的 MCP/Inbox 服务时沿用现有连接配置。若目标进程由外部启动器所有，连接状态可见，启停仍由该启动器负责。外部控制接口需要按其所有权单独定义。

## 进程与连接

- Profile 生命周期组件持有该实例的启动权。整合包首次激活自动启动，本地 JAR 按 profile 的 `autoStart` 配置启动。`start` 与 `stop` 串行且幂等。显式停止记入本 profile 的期望状态，重启 DSH 后仍可通过工具启动。正常退出或卸载时释放当前子进程，保留期望状态和用户数据。
- Java 绑定动态分配的回环端口，并通过本次子进程的启动握手报告实际 MCP、Inbox 和健康端口。组件确认子进程存活、MCP 完成鉴权与工具加载、Inbox 握手成功后标记就绪。关停时先卸载 Inbox 会话注入和 MCP 子组件，再等待 Java 正常退出。托管进程监听继承的控制管道，宿主异常退出导致 EOF 时也可回收。
- MCP 使用 DSH 自带的 `@deepseek-ai/dsh-mcp-client`，在托管组件的子作用域中按实例接入。MCP 的 Bearer 通过作用域内的凭据引用从 Java 生成的 `.bma3env` 解析，Inbox 单独读取其 token 和自身状态路径。托管组件在每次成功启动时更新这一组绑定，停止时撤销绑定。该模式自动完成 D-066 原来由 Agent 配置的连接步骤，继续走原生 MCP 工具发现与调用。[DSH MCP 客户端](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/mcp/mcp-client/README.md) · [Cordis 生命周期](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/cordis-tutorial/02-lifecycle-and-effects.md)
- 在 DSH 的工具注册表提供 `bma3_runtime`，动作 `status`、`start`、`stop`。工具在 Java 停止或启动失败时仍可调用，返回状态、版本、连接就绪情况和错误码。启停服从宿主控制权限，仅作用于当前 profile 拥有的实例。

## 不变量

- 同一运行根最多一个托管进程。控制权绑定到本次启动取得的子进程句柄，同 profile 的两个宿主实例无法同时启动，其他 profile 可以独立运行。外部进程持有数据目录锁或端口时，启动返回占用状态。工具参数只接收动作，Java、JAR 和配置路径来自管理员的 profile 配置。
- 端口仅绑定回环。token 文件维持 `0600`，配置导出、日志和工具结果不含 token。安装清单与控制工具均不接受执行命令。
- 整合包升级先在临时目录校验与解包，再切换就绪版本。升级失败可恢复上一可用运行版本。存在数据格式变化时，恢复须使用升级前的一致性备份。
- 消息板、发送和 Inbox ACK 沿用现行契约。一个 profile 的整合模式只有一个 Inbox 客户端实例。

- 端口变化不改变托管 Inbox 的来源指纹。该指纹绑定运行配置和凭据文件路径，恢复时还须验证服务端数据库身份。Java 持有 profile 状态目录的 OS 锁，宿主异常退出后可回收同主机已退出宿主遗留的托管 Inbox 锁。
- 同一数据格式内允许回退到已经验证的镜像。不同数据格式须具备单独的迁移与一致性备份方案后才可升级。

## 验收判据

1. 干净的 Linux DSH Web profile 安装候选 tgz，首次启动能看到正确版本、MCP 工具和一次合成 Inbox 唤醒，全程无需手配连接。真实 Web 宿主验证通过后标记支持版本。
2. 同 profile 连续或并发执行两次启动，只出现一个 Java。两个 profile 同时运行时端口和状态独立。手动停止后进程退出、业务工具撤销且管理工具仍可启动。正常退出、异常退出和卸载均验证进程回收。
3. 只装普通插件包的 profile 配置独立 JAR 后，用同一工具启动、查询、停止并重启本地 JAR。确认已有配置与数据保留，外部进程占用时收到明确诊断且原进程仍可访问。
4. 重装、升级、候选启动失败与回退保留原有数据。配置导出与失败诊断通过凭据检查。
5. Maven 和 DSH 插件测试、两种托管来源的回环验收、发行包校验全部通过后，从同一提交打 `v0.0.9-early` 并发布 GitHub 预发布。资产包含新增整合 tgz、独立 JAR、Linux app-image、原插件包与 SHA-256 校验文件。发布后从 Release URL 复核下载、校验和及远程安装。

## Windows x64 整合包

> 现制作适用于WindowsDSH的整合包，然后给我文件地址，我会让隔壁dsh进行验证

Windows x64 候选包沿用 `@mosire/bma3-dsh-managed`、profile 级托管和已有数据格式。安装文件以 `-windows-x64.tgz` 区分，内含通过 Windows JDK 21 生成的控制台启动器、BMA3 JAR 与 Java 运行时。DSH 直接启动原生可执行文件并持有 stdin 控制管道，窗口隐藏。镜像校验、按摘要安装、启动握手与回退共用原有流程。

运行中的 Desktop 通过当前 profile 的 `plugin_manager install_bundle` 安装本地 tgz，实测可热应用。Windows Web profile 使用 `dsh.cmd plugin --profile web add` 安装。[Windows 验证记录](../../agents/STATUS.md#2026-11-23windows-x64-dsh-整合候选包)

### 不变量

- Windows 继续用选定 profile 的数据根和动态回环端口。凭据仍由 Java 创建。插件在读取前验证文件是当前 Windows 用户独享的普通文件，并检查 Windows ACL。
- Windows 包与 Linux 包分别按运行平台和架构校验，只能启动适配本平台的镜像。

### 验收判据

- 生成 Windows x64 tgz 和 SHA-256。解包核对清单与镜像摘要，并用内置 Java 完成托管启动、鉴权、同实例拒绝及控制管道停止检查。
- 下游 Windows DSH 验证目标 profile 中的首次自动启动、MCP/Inbox 接线与 `bma3_runtime` 状态、停止和重启。

## 未决问题

- Windows Desktop 的预设选择器和托管运行时停止、重启待验证。
- DSH `0.2.2-rc.3` 中原生 MCP 客户端的动态挂载、凭据作用域和管理工具权限须以真实 Web profile 的验收结果为准。
