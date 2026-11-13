# 远端 LLBot 整机冻结事故 —— 调查（2026-11-13）

> **类型**：调查（时间点快照，只作追溯，不作当前事实）
> **日期**：2026-11-13
> **触发**：真实 LLBot 只读实测进行到一半，远端 `ws://47.100.101.156:3001/` 突然不再接受连接（TCP 可达、WS 握手超时）；用户随后重启机器并要求排查「为何突然挂掉」。
> **方法**：SSH 到该机（Aliyun ECS `iZuf6hxs0gs5h4y4m0ytd4Z`）只读取证 —— `journalctl -b -1`、`/var/log/syslog`、`/var/log/sysstat/sa13`、`docker logs`、`docker inspect`、容器内 `llbot.js` 源码。**取证阶段未改动远端任何配置**。
> **限制**：无 pstore、无 OOM-kill 记录、无内核 panic —— 崩溃瞬间的栈无法取得；下文区分「得证」与「推断」。

---

## 0. 结论

**不是 LLBot 挂了，是整台 ECS 内存耗尽后 livelock（假死）约 16 分钟**，用户硬重启。

- 两个容器、QQ 进程、node 进程**自始至终都没崩**（`RestartCount=0`，无 `CRITICAL: QQ main process has died`）。
- 得证：`journald` 在 17:25:08 记录内核内存压力（见 §2.3）。
- 得证：17:21:06 之后整机停止执行 —— 17:25/17:30/17:35 三次 5 分钟 cron **一次都没跑**（§2.4）。
- 未确认：具体是哪一次内存分配把它推过线。

**导火索**：`get_group_msg_history` 传了过大的 `count`。该 action 内部固定 30 条一窗、5 路并发，`count=1000` 会在一瞬间发出 34 个 `SsoGetGroupMsg`，把 QQ 客户端的 CMD-PIPE 打堵，之后**连无关的 OIDB 命令**也开始超时（§3）。

**附带推翻两个既有结论**（§4）：之前写的「这台 provider 没实现 `get_group_msg_history`」和「这台 LLBot 只容忍单连接」**都是错的**。

---

## 1. 现场事实

| 项 | 值 |
|---|---|
| 机器 | Aliyun ECS `iZuf6hxs0gs5h4y4m0ytd4Z`，2 vCPU / **1613 MB RAM** / 2047 MB swap / 40 GB 盘 |
| 上一轮开机 | 2026-11-06 12:02:24 → 2026-11-13 ~17:21（7 天） |
| 本轮开机 | 2026-11-13 17:37:06（用户重启） |
| 部署 | `/root/llbot/docker-compose.yml`：`linyuchen/llbot:8.3.2` + `linyuchen/pmhq:9.0.6`（Xvfb + Electron 版 NTQQ），`restart: unless-stopped` |
| 内存基线 | kbmemused **835 MB / 1613 MB**（51.8%），`kbmemfree` 仅 80–136 MB，available ~530 MB |
| 容器内存上限 | **无**（`HostConfig.Memory=0`），`systemd-oomd`/`earlyoom` 均不存在 |
| swap 使用 | 全天 `pswpin/s = pswpout/s = 0` —— **2 GB swap 一次没用过** |

---

## 2. 证据链

### 2.1 崩溃前机器是健康的

`sar -u` / `sar -q` / `sar -r` 最后一个采样点 **17:20:14**：

```
05:20:14 PM  all  2.30 usr  0.00 sys  1.00 iowait  96.67 idle
05:20:14 PM  runq-sz 0  ldavg-1 0.00  ldavg-5 0.07  ldavg-15 0.08
05:20:14 PM  kbmemused 855248  %memused 51.78   kbcommit 30252512  %commit 806.96
```

CPU 96.7% 空闲、load 0.07、swap I/O 为 0。**没有任何渐进式劣化。**

### 2.2 冻结时刻 ≈ 17:21:06

容器日志（docker json-file，不经 journald）最后一条：

```
2026-11-13T09:21:06.602Z  收到正向 Websocket 消息 { action: 'get_group_msg_history',
  group_id: 150112205, count: 300, echo: 'bma3-17' }
```

此前同一台机器每分钟有 20+ 条 `[收-群]` 消息日志；此后**彻底静默**。pmhq 侧最后一条是 17:21:04。

### 2.3 内核亲口说了内存压力 —— **得证**

`/var/log/syslog` 在 17:20:14 与 17:37:23 之间**只有一条**：

```
2026-11-13T17:25:08.808097+08:00 iZuf6hxs0gs5h4y4m0ytd4Z kernel: systemd-journald[301]: Under memory pressure, flushing caches.
```

`journald` 通过 `/dev/kmsg` 把自身消息写进内核日志，所以表现为 `kernel:` 前缀。这说明 **17:25:08 时内核已处于内存压力状态，且系统还活着**。

### 2.4 之后整机不再执行 —— **得证**

期望每 5 分钟一次的 cron（16:55:01 / 17:00:14 / 17:05:01 / 17:10:14 / 17:15:01 / 17:20:14）在 **17:25:01、17:30:14、17:35:01 全部缺席**。直到 17:37:23 `Startup finished`。

### 2.5 不干净关机

`last -x reboot shutdown`：11-06 与 11-13 之间**没有 shutdown 记录**（正常关机会写一条 `shutdown system down`）。`/sys/fs/pstore/` 为空；`journalctl -b -1` 里没有 panic / soft lockup / hung task / I/O error。

### 2.6 没有 OOM-kill 记录

`journalctl -b -1 | grep -i "oom-kill\|killed process\|out of memory"` → 空。

---

## 3. 导火索：`count=1000` 会打死 pmhq 的 CMD-PIPE

### 3.1 实证：347 次协议超时

`docker logs llbot-pmhq-1` 在 17:20:34–17:21:04 之间刷出 **347 条**：

```
[CMD-PIPE] active_send(cmd=trpc.msg.register_proxy.RegisterProxy.SsoGetGroupMsg)
           failed: active_send: no reply within timeout
```

同时 llbot 侧对应 **347 条** `[E] onebot11-adapter 解析 reply 失败 Error: pmhq ws send: wait result timeout` —— 注意「解析 reply 失败」，说明**连入站消息的解析都被堵住了**。17:21:01 起连 `OidbSvcTrpcTcp.0x88d_0` 也开始超时。

### 3.2 根因在 action 实现里（`/app/llbot/llbot.js`）

```js
async _handle(payload, config) {
    const endSeq = payload.message_seq > 0 ? +payload.message_seq
                                           : await this.ctx.ntMsgApi.getLatestMsgSeq(peer);
    const overallStartSeq = Math.max(1, endSeq - count + 1);
    const windows = [];
    for (let cur = endSeq; cur >= overallStartSeq; cur -= 30) windows.push({...});
    const rawBatches = await mapWithConcurrency(windows, 5,
        (w) => this.fetchRangeRaw(peer, w.start, w.end), (r) => r.length === 0);
```

**内部固定 30 条一个窗口、5 路并发。** 于是 `count` 直接被翻译成 RPC 数量：

| 传入 count | 实际 `SsoGetGroupMsg` 数 |
|---|---|
| ≤30 | 1 |
| 100 | 4 |
| 300 | 10 |
| **1000** | **34** |

### 3.3 实测复现（2026-11-13 17:5x，重启后 + 内存上限生效后）

| count | 耗时 | 消息数 | 体积 |
|---|---|---|---|
| 10 | 0.47 s | 10 | 7.5 KB |
| 30 | 0.64 s | 30 | 20 KB |
| 60 | 0.73 s | 60 | 39 KB |
| 100 | 1.37 s | 100 | 64 KB |
| 300 | 3.58 s | 300 | 198 KB |

`count ≤ 300` **全部 retcode=0 正常返回**。`count=1000` **有意未复现**（避免再次打死远端机器人）。

### 3.4 为什么 17:20 那批「300 和 100 也都超时」

因为**第一个 `count=1000` 已经把 CMD-PIPE 打死了**，后续请求全部排在坏管道后面。bma3 原来的「1000→300→100 降级」因此救不了任何东西 —— 降级发生在已经打死之后。

### 3.5 为什么此前更大的风暴没打死（诚实记录）

| 时段 | `SsoGetGroupMsg` 超时数 | 结果 |
|---|---|---|
| 11-12 11:xx | 1707 | 活着 |
| 11-12 09:xx | 1282 | 活着 |
| 11-13 09:xx（本次） | 374 | 假死 |

所以**不能证明「超时数 > 某阈值必然致死」**。合理解释是：那两次之后机器人被闲置，而本次之后紧接着的分配叠加在已经吃紧的 530 MB 余量上。**内存耗尽导致 livelock 得证；具体致死的分配未确认。**

---

## 4. 推翻的两个既有结论

### 4.1 ❌「这台 provider 根本没实现 `get_group_msg_history`」

`reports/2026-11-13-remote-usage-report-triage.md` §P1-1 与 `STATUS.md` 据此写的 hint 都是错的。
**它实现得好好的**，`count ≤ 300` 秒回。错在 bma3 传了 `count=1000`。

### 4.2 ❌「这台 LLBot 只容忍单连接 / 旧会话不立刻释放」

当时那两次重连失败（+90s、+210s）**正好落在 17:22–17:25 的冻结窗口内**。TCP 能连是因为内核 SYN 队列还在，WS 握手超时是因为用户态已经不调度。与连接数无关。

---

## 5. 采取的处置（用户授权）

**不用重启即可生效：**

| 项 | 处置 |
|---|---|
| swap | 新增 `/swapfile2` 4G，写入 `/etc/fstab` → 总 **6G** |
| `vm.swappiness` | `0` → **10**。`/etc/sysctl.conf` 第 1 行 `vm.swappiness = 0` 经 `/etc/sysctl.d/99-sysctl.conf` 符号链接**最后加载**，覆盖了 9 月 8 日写的 `99-swappiness.conf = 10` —— 那次修改从未生效过 |

**需重建容器：**

| 项 | 处置 |
|---|---|
| pmhq | `mem_limit: 1g` + `memswap_limit: 2g` |
| llbot | `mem_limit: 448m` + `memswap_limit: 1g` |
| node 堆 | `NODE_OPTIONS=--max-old-space-size=320`（原来裸跑，V8 上限 902 MB） |

备份：`/etc/fstab.bak-20261113-174531`、`/etc/sysctl.conf.bak-20261113-174531`、`/root/llbot/docker-compose.yml.bak-20261113-174543`。

**重启后实测**：两容器 `healthy`；pmhq `memory.current` 911 MB 中 **anon 仅 266 MB**、file 582 MB（页缓存，可回收）、`oom_kill=0`；llbot anon 64 MB。整机 available 849 MB（事故前 530 MB）。

**顺带排除**：`AliYunDun` 的 `bin info size limit:/usr/local/bin/node,...` 警告当天 8 次、每天都有，与事故无关。`--cmd-whitelist` 白名单是排障开关、默认关闭（`ENABLED = process.argv.includes("--cmd-whitelist")`），也未参与。

---

## 6. 对 bma3 的直接影响

1. `qq_remote_history` 的 **`count` 上限 1000 必须降下来**（计划降到 100，默认 30）。
2. **降级阶梯 1000→300→100 应删除** —— 超时说明管道已被打死，再降无效。
3. **`reverse_order` 参数名错误**：LLBot schema 认 `reverseOrder`（camelCase），bma3 发的是 `reverse_order` → **一直被忽略**，实际永远拿到默认的旧→新顺序。
4. `parse_mult_msg` / `disable_get_url` 属于 NapCat/LLOneBot，**不是 LLBot 参数**，发了被 schema 丢弃。
5. `message_seq` 是有效游标，语义为「取到这条为止向过去数 count 条」；翻页传 `本页最旧 seq - 1`，实测零重叠、严格连续。

分页设计见 [`../specs/2026-11-13-remote-history-paging-design.md`](../specs/2026-11-13-remote-history-paging-design.md)。
