# BMA3 MCP 日常工具入口实施计划

对应[设计](../specs/2026-11-23-mcp-daily-tools-design.md)。

1. 在现有 MCP 代理和应用组合根落地三个入口。Windows 定向 Maven 测试、WSL 全量 Maven 测试及打包通过。
2. 扩展托管就绪消息和 DSH 路径选择，保留旧握手。Windows 握手定向测试与 WSL 插件全量测试通过。
3. 在 WSL 隔离实例列举三个 MCP 入口，在 Windows DSH 隔离宿主验证默认注册 30 个工具。命令、结果和未验证项见 [STATUS](../../agents/STATUS.md)。回滚点为撤销新增入口与握手扩展，保留原完整路径。
