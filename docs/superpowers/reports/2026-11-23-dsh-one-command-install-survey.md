# BMA3 的 DSH 单命令安装调查

> 类型：调查（时间点快照，只作追溯，不作当前事实）
>
> 日期：2026-11-23（UTC+8）
>
> 方法：核对现有打包入口、DSH 官方契约，在临时目录构建 Linux app-image 并用真实 DSH CLI 验证安装、组合和卸载。
>
> 触发：「其次是，我希望把这个项目整合为一条dsh相关命令就能安装的一键式安装应用，请调查一下这该如何制作」

## 已确认事实

- `dsh plugin --profile web add <包>` 把包交给 pnpm 安装，在 profile 的 `package.json` 中将带 `dsh.bundle` 的包加入 `dsh.profile.bundles`。DSH 启动 profile 时组合这些 bundle 的 patch。运行中的 profile 是否即时生效取决于 HMR，重启后会读取磁盘上的配置。[DSH CLI 参考](https://github.com/deepseek-ai/deepseek-harness/blob/master/apps/cli/reference/README.md) · [Bundle 发布文档](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/user/develop/basic/publish.md)
- 本仓的插件已经满足 bundle 结构。现有 `.tgz` 包含 Inbox 接入和 BMA3 预设。Java 后端则由 `jlink` + `jpackage` 单独生成 Linux x86_64 镜像，内含 Java 运行时。本轮插件包约 60 KiB，应用镜像压缩包约 86 MiB。[npm manifest](../../../dsh-plugin/package.json) · [Bundle patch](../../../dsh-plugin/cordis.patch.yml) · [应用镜像构建脚本](../../../packaging/build-app-image.sh)
- 当前插件默认连接回环上的 `46500`，但 `envFile` 默认为空，token 模式下需要指向后端生成的 `.bma3env`。MCP 默认是 `127.0.0.1:8765/mcp`，由 DSH 原生 Streamable HTTP 客户端接入。Inbox 和 MCP 各读一个 token。现有 Inbox 状态文件名默认固定，一台机器装多个 profile 时必须分配独立路径或明确指定唯一运行者。[Inbox 配置](../../../dsh-plugin/cordis.patch.yml) · [Inbox 凭据及状态读取](../../../dsh-plugin/lib/inbox.js) · [Java 配置模板](../../../bma3-app/src/main/resources/config-template/app.yaml) · [DSH MCP 客户端](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/mcp/mcp-client/README.md)
- Linux 的 `packaging/install.sh` 会解包、创建 `current` 链接并写 systemd 用户单元。单元实际启动版本目录里的二进制，绕过了 `current` 指针。脚本也会忽略 systemd 调用的失败。现成脚本可作为独立服务方案的起点，直接复用会给升级、就绪判定和回滚留下缺口。[用户级安装脚本](../../../packaging/install.sh)

## 实测

环境为 Linux x86_64、DSH `0.2.2-rc.3`、Node `22.23.3`、pnpm `12.6.0`。证据目录为 `/tmp/bma3-agentlib-dsh-1123.8Y39dHk9`。命令中的 `$STATE` 指向该目录，DSH 使用独立的 `DSH_HOME`、缓存和 pnpm store。

| 操作 | 结果与证据 |
|---|---|
| `npm pack --ignore-scripts --cache "$STATE/npm-cache" --pack-destination "$STATE" ./dsh-plugin` | rc=0，现有插件包 `npm-pack.log`。 |
| `dsh plugin --profile web add "$STATE/mosire-bma3-dsh-0.0.8-hotfix1.tgz" --offline --store-dir "$STATE/pnpm-store"` | rc=0，`dsh-add.log`。包被列入 Web profile 的依赖与 bundle 列表。 |
| `dsh --profile web --dump-config` | rc=0，`dsh-composed.yml` 含 `bma3-inbox` 和 `bma3-preset` 两行。命令只组合配置，不启动宿主。 |
| `dsh plugin --profile web remove @mosire/bma3-dsh --offline --store-dir "$STATE/pnpm-store"` | rc=0，`dsh-remove.log`。隔离 profile 中依赖与 bundle 选择均已删除。 |
| 隔离源码副本执行 `MAVEN_ARGS=-o NO_PROXY=127.0.0.1,localhost bash packaging/smoke.sh` | rc=0，`packaging-smoke.log`。AgentLib `0.2.8` 的 Linux app-image 版本输出正确，Inbox `/healthz` 返回 200。systemd 安装步骤只走 dry-run。 |
| 在临时插件包内加入 `runtime/botmosire-a3-linux-x86_64.tar.gz`，用 `npm pack` 打包，再用同一条 `dsh plugin add` 安装 | rc=0，`npm-combined-pack.log` 和 `dsh-combined-add.log`。组合包约 87 MiB，profile 选择了 bundle，安装目录内 90,410,257 字节的 Java 镜像压缩包与原件 SHA-256 一致，均为 `bc661e2c3b9ed6bc0011a1e86cdb9edde483957ed99797bebd6ff498252e712e`。探针只验证包可交付。 |

真实宿主启动后自动拉起 Java、原生 MCP 和 Inbox 协同接通、远程 `.tgz` 下载、HMR、systemd、Windows DSH 及真实 QQ 均为 `NOT_RUN`。

## 推断与实施建议

首版建议用一个带 Linux x86_64 app-image 的 DSH bundle，通过 GitHub Release 发布版本化的 `.tgz`。这沿用本仓现有发布渠道，用户安装时只需 DSH 和 pnpm，无需单独准备 JDK 或安装 Java 镜像。archive 作为 npm 包里的普通文件交付，初始化时解开，保留 app-image 的文件权限与布局。

```bash
dsh plugin --profile web add "$BMA3_BUNDLE_URL"
```

`BMA3_BUNDLE_URL` 对应未来发布的组合包。DSH 下次启动 Web profile 时，bundle 的生命周期组件负责准备并启动 Java。支持 HMR 的宿主能否在安装后立即激活，留给目标 DSH 版本的实测。pnpm 会按依赖构建策略约束安装脚本，交付流程可以完全使用 bundle 加载生命周期。[Cordis 生命周期](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/cordis-tutorial/02-lifecycle-and-effects.md) · [pnpm 构建脚本授权](https://pnpm.io/cli/approve-builds)

1. **构建**。复用现有 app-image 构建入口，生成与插件同版本的 Java 镜像。将平台、版本、SHA-256 和镜像包放进组合包，并在 GitHub Release 附外层校验值。首版确认 Linux x86_64 后，再决定其他平台是独立 `.tgz`，还是由一个轻量入口按平台选择带版本与校验的 Release 资产。
2. **启动**。加入一个 profile 级生命周期组件。按版本验包，在临时目录解包后再切换可执行版本。Java 使用独立于 `node_modules` 的用户数据目录，首次缺省禁用 QQ 账号。用绝对路径和正确的工作目录启动，保留现有配置、数据库及 `.bma3env`。端口冲突需要报告并退出，由本次启动的进程与认证通道共同确认就绪。
3. **接线**。Java 就绪后，为当前 profile 设置 Inbox 地址、`stateFile` 和 `.bma3env` 路径，使用 DSH 原生 `@deepseek-ai/dsh-mcp-client` 连接现有 MCP。两个 token 只在进程内读取，配置导出和日志只携带状态和路径等非凭据字段。保留 Inbox 和 BMA3 预设的现有行为。QQ 账号、系统提示词和 Chromium、Xvfb、ffmpeg 等按启用的功能配置。
4. **升级和卸载**。确认新版可启动后才切换版本。安装失败保留旧可执行版本和用户数据。profile 停止或 bundle 卸载时停止其托管的 Java，保留数据。验收覆盖空 profile 安装、重装、版本升级、端口碰撞、失败恢复和卸载。端到端检查应包括 Java 健康端点、认证后的 MCP `tools/list` 和 Inbox 握手。

## 待用户拍板

- **BMA3 跟随 DSH 还是独立常驻**。跟随 DSH 适合原生 `dsh plugin add`，后端随 profile 启停。若关闭 DSH 后仍要接收 QQ 或运行调度，建议提供一条用户级安装入口，由它安装 Java 服务、联通同一个配置根并调用 `dsh plugin add`。该路径需要独立验证 systemd 用户会话、升级重启和卸载。
- **首版覆盖范围**。建议用已有镜像支持的 Linux x86_64 先跑通。若同版要求 Windows DSH，需额外制作自带 Java 的 Windows 运行时包，并验证进程管理与凭据目录 ACL。现有 Windows 纯 MCP 启动脚本可继续作为独立入口。
