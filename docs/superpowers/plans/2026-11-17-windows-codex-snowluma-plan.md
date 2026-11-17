# 0.0.8 Windows / SnowLuma 纯 MCP 实施准备

> 状态：实施中；对应[设计](../specs/2026-11-17-windows-codex-snowluma-design.md)。实际结果和未完成项以 [STATUS](../../agents/STATUS.md) 为准，下列验证项描述计划，不表示均已通过。

1. **构建基线与依赖**：补齐 Windows Maven wrapper/工具链入口，确定 `agentlib-mosire:0.2.5` 在 Windows 上可复现的来源；独立使用 Windows 文件系统及工具链，不依赖 WSL。产物为原生构建说明及能运行的 `mvnw.cmd`。验证：`mvnw.cmd -version`、`mvnw.cmd test`（`NOT_RUN`）。
2. **敏感文件与 Git**：修正 `.bma3env` 和本地配置忽略规则；为 BMA3 token、调试请求与相关持久数据增加平台化权限设置/检查。验证：`git check-ignore -v .bma3env config/.bma3env` 与分别针对 NTFS ACL、POSIX 模式的创建/覆盖/失败测试（`NOT_RUN`）。不把仅检查 Windows 的 `stat.mode` 当 ACL 验收。
3. **SnowLuma 读取契约**：优先验证好友/群列表、消息板、消息和历史分页；为数组/对象等 provider 响应和真实游标语义加入 fixture。验证：相关 Windows Java 测试及真实 SnowLuma 只读查询/分页（`NOT_RUN`）。不因读取需求擅自修改现有写工具的范围。
4. **可选能力/健康**：处理禁用 Xvfb、未启用 DSH 及 Windows 不支持项的探针语义，保留实际故障告警。验证：Windows 启停、readyz 和 watchdog（`NOT_RUN`）。DSH 插件的 Windows 测试失败已记录，不作为开发 Codex 插件的理由。
5. **纯 MCP 接入**：提供易用的 Windows 启动与连接说明，实测带认证的 MCP 工具发现、QQ 账号状态和按需读取消息。验证：真实 Codex 会话通过 MCP 读取 QQ（`NOT_RUN`）；不实现或验收专用插件、自动唤醒或会话注入。
6. **运行与恢复**：测试独立运行目录、端口冲突、日志脱敏、SnowLuma 断开重连和 BMA3 重启；具体安装及自启动方式留待需求明确。真实消息写操作不纳入读取链路验收（`NOT_RUN`）。

每步都应保持可单独回退，记录命令、退出码、测试数和未运行项；不操作用户原有 WSL 工作树、登录配置或运行实例。
