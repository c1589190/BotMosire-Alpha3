# DSH Inbox 内容审核失败恢复实施计划

对应[设计](../specs/2026-11-23-dsh-inbox-content-risk-recovery-design.md)。

1. 在 Inbox 生命周期内接入失败钩子。验证绑定归属、第一步投递和失败类型。复用宿主的单节点替换与重试、Inbox 原有暂停和 ACK。产物为 Inbox 插件实现。可回滚点为移除恢复钩子及其内部实现。
2. 增加覆盖命中、拒绝重入、暂停恢复和拒绝归因冲突的回归测试。WSL 插件全量套件与 Windows 三项定向回归通过。
3. 用本机 DSH 的原生 Session 和 Agent Loop 加模拟模型请求验证单步重试，通过成功和再次拒绝两条路径。命令、证据与未验证项记入 [STATUS](../../agents/STATUS.md)。
