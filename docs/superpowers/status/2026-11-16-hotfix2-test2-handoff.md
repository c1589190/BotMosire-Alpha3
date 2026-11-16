# 0.0.7-hotfix2-test2 交接（历史快照）

> **状态**：历史快照（2026-11-16）· **当前状态入口**：[`../../agents/STATUS.md`](../../agents/STATUS.md) · 勿把本文件当最新事实。
> **用途**：给新 BMA3 测试会话/新 Agent 快速接上项目代码与未完成验证；不包含 token、消息正文或远端凭据。

## 源码基线

- 仓库：`/home/cna/BotMosire-Alpha3`
- 分支：`feat/0.0.7-hotfix2`
- 已发布测试 tag：`v0.0.7-hotfix2-test2` → `4c11c2f`
- 发布后 docs commit：`f611a70`
- 正式基线：`v0.0.7-hotfix1`
- `feat/bma3-plan-1` 与 `main` 未改。

## 当前工作树

当前本地工作树在 test2 之后有**未提交**的后续修复：

- `bma3-inbox/src/main/java/io/mosire/bma3/inbox/tools/InboxWaitQuietTool.java`
  - 捕获非参数运行时异常，返回 `WAIT_QUIET_FAILED`；
  - 输出 `allAckable` / `processable`，区分“来源安静”和“条目可处理”。
- `bma3-inbox/src/test/java/io/mosire/bma3/inbox/tools/InboxToolsTest.java`
  - 覆盖无尾斜杠 sourcePrefix 与 processability。
- `docs/agents/STATUS.md`
  - 记录远端 test2 复测结果与未发布修复。

全量 Java 门禁：**329 tests / 0 failures / 0 errors / 1 skipped**。

## 已确认可用

- jar 与 DSH 插件 heartbeat 均为 `0.0.7-hotfix2-test2`；
- `inbox_wait_quiet` 可调用，返回结构化 JSON；
- `bma3_watchdog_diagnose` 不重复返回 watchdog；
- `media.byKind` 输出 image/file/record/video 四个零计数；
- `xvfb_status` supported=true，windowManager=`/usr/bin/openbox`；
- 发布资产回下载、`sha256sum -c`、app-image/插件版本检查 PASS。

## 尚未确认

- 当前远端会话的 `agentPreset` 名为 `bma3`，但该会话没有挂上最新 preset 的 tool rows（连 `tool-bash` 也没有）；因此 `bma3_media` / `bma3_ocr` / `bma3_asr` 没有出现在工具面。
- 需要新开一个 **BMA3 预设会话**（或让 DSH 重建 agent）验证三个媒体工具是否注册；这不是删除当前会话，是并行开一个测试会话。
- `allAckable` / `processable` 修复尚未发布，远端 test2 还看不到这两个字段。
- summary 多图 markers、真实 LLBot/provider OCR、QQ 好友写操作、B 站 xvfb 登录态、systemd、掉电/升级回滚仍为 `NOT_RUN`。

## 新会话第一步

1. 打开 `/home/cna/BotMosire-Alpha3`，读 `AGENTS.md`、`docs/agents/STATUS.md`、本文件。
2. 使用 BMA3 preset 创建新测试会话；不要复用未挂上新 preset rows 的旧会话。
3. 调 `bma3_ocr`/`bma3_asr`/`bma3_media` 检查是否存在；用 `bma3_media` 对一个无 key/无文件输入做失败路径，确认返回结构化错误。
4. 用 `inbox_wait_quiet(sourcePrefix='bma3_debug', quietMs=2000)` 复测 structured result；若要测 `allAckable/processable`，需先发布包含未提交修复的新 tag（建议 `0.0.7-hotfix2-test3`）。
5. 不覆盖 test2 tag；新修复必须新版本/tag。
