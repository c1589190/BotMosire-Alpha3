# BMA3 OneBot 凭据实施计划

见[设计](../specs/2026-11-23-bma3-onebot-credentials-design.md)。

1. 实现数据目录内凭据存储、Windows DPAPI 导入和 MCP 管理工具，核对权限和脱敏。
2. 接入 `tokenRef` 和账号 registry，验证缺凭据诊断、串行协调、局部热重连和重启恢复。
3. 在 Windows 和 WSL Ubuntu 隔离环境运行 Maven 与 OneBot 回环验收，记录命令、结果和证据。
