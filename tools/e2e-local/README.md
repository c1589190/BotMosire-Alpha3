# 本地端到端联调脚本（一次性，不是 CI）

真进程 + 真 MCP/HTTP + **假 OneBot 11 正向 WS**。用来回答"这套东西真跑起来是什么样"，
覆盖：优先级三档时序、合并批次、逐条 ack、`set_config` 热重载、丢弃线、媒体下载、
连接诊断 hint、read/ack 游标、引用回复回写平台 id。

## 前提

- 本机已 `./mvnw -o -q -pl bma3-app -am package -DskipTests` 产出
  `bma3-app/target/bma3-app-0.0.2-hotfix1.jar`；
- 脚本里写死 `/tmp/bma3-e2e`（工作目录）与端口 `45701`（假 OneBot WS）、`46701`（事件注入 HTTP）、`18080`（假 CDN/HTTP 端口）。

## 跑法

```bash
mkdir -p /tmp/bma3-e2e/config /tmp/bma3-e2e/data /tmp/bma3-e2e/www
# 1. 假 OneBot：WS 端口 45701，事件注入走它自己的 POST /inject（46701）
JW=~/.m2/repository/org/java-websocket/Java-WebSocket/1.6.0/Java-WebSocket-1.6.0.jar
SLF=$(ls ~/.m2/repository/org/slf4j/slf4j-api/*/slf4j-api-*.jar | tail -1)
javac -cp "$JW:$SLF" -d /tmp/bma3-e2e tools/e2e-local/FakeOneBot.java
nohup java -cp "/tmp/bma3-e2e:$JW:$SLF" FakeOneBot 45701 46701 \
  < /dev/null > /tmp/bma3-e2e/fake.log 2>&1 &
# 2. 假 CDN（媒体/诊断用）：把一张 pic.png 放进 www 再起 http.server 18080
(cd /tmp/bma3-e2e/www && python3 -m http.server 18080 --bind 127.0.0.1 &)
# 3. 配置 + 启动（端口全 0，从 stdout 读回真实端口）
#    config/app.yaml 见下方"最小配置"；normalDelaySeconds 调成 2 便于观察
env BMA3_MCP_TOKEN=e2e-mcp-token LLBOT_TOKEN=e2e-llbot-token \
  java -jar bma3-app/target/bma3-app-0.0.2-hotfix1.jar --config /tmp/bma3-e2e \
  > /tmp/bma3-e2e/app.log 2>&1 &
# 4. 驱动
python3 tools/e2e-local/drive.py      # 优先级/时序/配置/丢弃/历史（14 项）
python3 tools/e2e-local/drive3.py     # 失败账号诊断 + inbox_wait（4 项，需无 SSE 消费者）
python3 tools/e2e-local/drive4.py     # 合并带走/at/read 游标/出站回写（6 项）
python3 tools/e2e-local/drive5.py     # 引用 bot vs 引用他人（3 项）
```

## 最小配置

```yaml
dataDir: /tmp/bma3-e2e/data
server: {host: 127.0.0.1, port: 0, mcpPath: /mcp, tokenEnv: BMA3_MCP_TOKEN}
qqconnect:
  accounts:
    - {accountId: bot-1, endpoint: "ws://127.0.0.1:45701/", tokenEnv: LLBOT_TOKEN, enabled: true}
    - {accountId: bot-http, endpoint: "ws://127.0.0.1:18080/", tokenEnv: LLBOT_TOKEN, enabled: true}
inbox:
  {inboxId: bma3, httpPort: 0,
   display: {maxMessagesPerItem: 6, maxItemsPerPanel: 6, includeOmittedCount: true},
   push: {minPriority: -1000}, retention: {maxAgeDays: 30, maxItems: 5000}}
weighting:
  {immediateThreshold: 300, dropBelow: -300, maxPriority: 1000,
   importantDelaySeconds: 1, normalDelaySeconds: 2,
   sourcePriority: {"*": 0, qq_private: 300, qq_group: 200},
   attributes: {qq_at_bot: 100, qq_at_all: 20, qq_at_other: 0,
                qq_reply_to_bot: 100, qq_self_name: 60, qq_command: 100}}
```

## 结果（2026-11-11）

四套驱动连续两轮 **27/27 PASS**。真跑抓到并修掉 4 个 bug：批次时钟被合并刷新后推、失败账号不出现在列表、
flush 被旧连接抢走、`reconcile` 持锁卡住入站。

## 注意

- 这些脚本是**本机一次性验证**，不进 CI；`/tmp` 路径与端口是写死的。
- 脚本用时间派生的 message id，可以反复跑；`drive.py` 开头会先 `inbox_ack` 清场。
- 假 OneBot 只回答 `get_login_info/get_status/get_group_list/get_friend_list/get_msg/get_image/send_*`，
  其余动作回空 `data`。
- `drive3.py` 必须在**没有 SSE 消费者**时跑：SSE 连接本身会驱动 flush，`inbox_wait` 就等不到"下一次"。
