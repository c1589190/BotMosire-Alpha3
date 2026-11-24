# Linux DSH 单插件实施顺序

> **草案** · 2026-11-24（UTC+8）· [专题设计](../specs/2026-11-24-dsh-unified-plugin-linux-design.md)

基线为 `feat/0.0.9-early` 的 `5dfdd38`。DSH 组件重组和基本启停可复用现有 JAR 握手先行验证。停机编辑 `app.yaml` 的端到端集成依赖 Java 配置入口。首次集成使用隔离的 Linux x86_64 / DSH Web `0.2.2-rc.5` profile。

| 步骤 | 交付检查点 | 验证命令或入口 |
|---|---|---|
| 1. 单入口下组合三个组件 | Linux patch 只装一条入口。将 Inbox 命令与连接生命周期解耦，将原生 MCP 注册移入 `bma3` 的宿主作用域。复用现有 JAR 启动握手与动态回环端口。 | `dsh --profile web --dump-config` 核对一条入口。用真实宿主探针和两个预设验证工具数、提示词、Inbox 与重连。 |
| 2. 托管生命周期 | 整理 `bma3_runtime` 的状态返回和启停动作，加入串行重启。验证停止意图、外部实例所有权、启动失败和多请求串行化。 | 在 Linux 隔离 profile 从普通会话调用 `bma3_runtime`。执行 Node 22 的插件套件。 |
| 3. Java 配置事务与接线 | 抽取在线和离线共用的校验、加锁、写入与脱敏，实现一次性配置命令及 DSH `configure` 接线。 | `./mvnw -B -ntp -o -pl bma3-app -am test`。从普通会话验证停机和启动失败后的编辑、无监听启动、无效输入、畸形 YAML 修复及并发冲突。 |
| 4. Linux 一键包及迁移 | 构建唯一的 Linux DSH tgz，完成旧普通包、旧托管包的三种来源迁移，演练失败恢复和卸载。 | `bash packaging/build-dsh-bundle.sh`、`sha256sum -c <tgz.sha256>`、`dsh plugin --profile web add <tgz> --ignore-scripts`。检查唯一发行产物、内置镜像和入口计数，核对迁移前后的配置、绑定、待确认状态和停止意图。 |
| 5. 整体门禁 | 跑 Maven 全量、插件套件与真实宿主场景，记录成功和失败命令、rc、测试数和日志路径。 | `./mvnw -B -ntp -o test`，以及从 `dsh-plugin/` 用 Node 22 运行 `node --test --experimental-test-isolation=none`。逐条核对专题设计的 Linux 验收判据。 |

迁移验收使用旧包和配置快照，留出干净 profile 用来验证一键安装，任何回滚前先停止候选进程。镜像回退验证数据格式和配置的兼容性，整套插件回退验证旧包及旧 profile 配置能够恢复。验证用合成 Inbox 事件并保留日志中的脱敏状态和计数。

步骤 1、2 已在 Linux 隔离宿主验证。步骤 4 已验证一键包构建、安装、托管旧包替换及运行来源。停机配置事务、旧普通包其余迁移、完整回退和 Java 全量门禁为 `NOT_RUN`。执行证据见[当前状态](../../agents/STATUS.md)，调查基线见[可行性报告](../reports/2026-11-24-dsh-unified-plugin-linux-survey.md#验证记录)。
