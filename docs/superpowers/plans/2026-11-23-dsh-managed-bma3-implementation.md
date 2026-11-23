# 0.0.9-early DSH 托管 BMA3 实施计划

见[设计](../specs/2026-11-23-dsh-managed-bma3-bundle-design.md)。本轮交付 Linux 整合包构建入口和本地 JAR 托管，提交并推送源码。

| 顺序 | 产物 | 验证 |
|---|---|---|
| 1 | Java 托管协议 | Maven 全量、独立 JAR 控制管道与单实例测试 |
| 2 | 进程管理、MCP/Inbox 和启停工具 | DSH 插件测试、真实 Web profile 启停 |
| 3 | Linux 整合包 | 隔离构建、真实 DSH 安装、文件与健康检查 |
| 4 | 升级与提交 | 回退与数据保留、远端分支复核 |
