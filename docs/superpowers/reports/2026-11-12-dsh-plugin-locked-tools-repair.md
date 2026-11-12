# DSH 插件 lockedTools 修复（0.0.4-hotfix1）

> **类型**：维护/回归修复（2026-11-12）
> **触发**：用户报告「每次重新安装插件，bash 都会神秘消失」；`bma3_self_status` 显示 `presetLockedTools` 有 `bash/pwsh/read_image`，但 `lockedTools=[]`，`bma3_self_reload` 后仍为空。
> **范围**：只改 `dsh-plugin`；Java jar 不变。

## 1. 根因

1. **负缓存**：`dsh-plugin/lib/host-tools.js` 的 `hostToolEntry()` 把 `undefined` 也写入 `cache`。`dsh plugin` 重装期间宿主安装树短暂不可解析 → 缓存 `undefined` → 同进程内 `presetDefinition()` 一直得到裸包名 → `tool-bash`/`tool-fs` 行挂不上 → `bash`/`read_image` 从会话消失。只有重启 DSH 才会重新解析。
2. **诊断误导**：`bma3_self_status.lockedTools` 之前用 **global** 工具视图计算；bash/read_image 是 agent-scope 工具，即使存在也不在 `lockedTools` 里。
3. **重载范围**：`bma3_self_reload` 只重应用 `agentconfig` 的工具组（`tools.restrict`），不会重新挂 preset 子行；所以无法把消失的 bash 拉回来。

## 2. 修复

| 文件 | 改动 |
|---|---|
| `lib/host-tools.js` | 不再缓存 miss；新增 `invalidateHostToolCache()` 与 `hostToolCacheSize()` |
| `lib/preset.js` | `presetDefinition()` 每次注册前 `invalidateHostToolCache()`，重新解析宿主工具入口 |
| `lib/self.js` | `bma3_self_status` 用 `definitions(agent)` + `get(name, agent)` 计算 `lockedTools/missingLockedTools`；新增 `bma3_self_repair`；`agent/created` 后 500ms、`tools/change` 后 250ms 自动 repair；`bma3_self_reload` 也先 repair |
| `test/self.test.mjs` | 覆盖 scoped 工具状态、repair 挂载、`restrict` 不被 scope-local 名字污染 |
| `test/host-tools.test.mjs` | 覆盖「miss 不进缓存」「invalidate 清正缓存」 |

`bma3_self_repair` 的行为：

1. 用 `hostToolEntry()` 重新解析 `@deepseek-ai/dsh-tool-bash` / `@deepseek-ai/dsh-tool-fs`；
2. 动态 `import()` 包入口，调用包导出的 `Config()` 得到默认配置；
3. 用 `agentCtx.plugin({ name, inject, apply }, config)` 挂到当前 agent scope；
4. 3s 内未完成注入返回 `MOUNT_PENDING_INJECT`，不阻塞 Agent；
5. 再次读取 `lockedTools`，返回 `{changed, before, after, mounted, errors}`。

## 3. 验证

```bash
cd dsh-plugin && npm test
# 28 tests / 0 fail
```

新增用例：

- `status reports scoped bash/read_image as lockedTools without poisoning restrict()`
- `bma3_self_repair force-mounts missing bash/read_image`
- `a host-tool miss is not cached forever (reinstall regression)`
- `invalidateHostToolCache drops a positive lookup`

未验证：真实 DSH 宿主里的 `ctx.plugin()` 挂载（本机没有可重启的 BMA3 DSH 会话）；需要用户安装 `0.0.4-hotfix1` 后跑：

```text
bma3_self_status      # 看 missingLockedTools
bma3_self_repair      # 强制挂回
bma3_self_status      # 确认 missingLockedTools=[]
bash(...) / read_image(...)
```

## 4. 发布状态

- 版本：`0.0.4-hotfix1`（Java jar 仅版本号变化；功能修复在 DSH 插件侧）。
- GitHub Pre-release：<https://github.com/c1589190/BotMosire-Alpha3/releases/tag/v0.0.4-hotfix1>；tag `v0.0.4-hotfix1` → commit `f450608`。
- 发布产物：
  - `bma3-app-0.0.4-hotfix1.jar` — sha256 `96463fb5275e797c5870d336278b2d896edb1b6fad675061f0a8b9a55020e8d9`
  - `mosire-bma3-dsh-0.0.4-hotfix1.tgz` — sha256 `8eeddeb3fbdf82263adcd646c7229cce8e1bb5f952ec3ce0f3c31aab21da7f1f`
  - `SHA256SUMS`
- 回下载校验：`sha256sum -c SHA256SUMS` OK；jar `--version` = `0.0.4-hotfix1`；tgz `package.json` version = `0.0.4-hotfix1`，内含 `lib/host-tools.js`、`lib/self.js`、`lib/preset.js` 等 10 个文件。
- 安装后验证顺序：`bma3_self_status` → `bma3_self_repair` → `bma3_self_status` → `bash`/`read_image`。
- 若 `bma3_self_repair` 返回 `MOUNT_PENDING_INJECT`，说明当前 agent scope 缺少 `shell`/`fs`/`attachments` 之一，需要把 `errors` 原文发回来。
