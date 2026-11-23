package io.github.c1589190.bma3.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A18: the retrieval floor that decides whether this design stays lexical.
 *
 * <p>Fifty notebook entries is roughly a busy month of a single operator. The queries are the ones
 * the Agent actually asks ("谁负责部署", "站会几点", "群规是什么") and the assertion is the one the spec
 * fixes: at least 70% of queries must have an expected entry inside the first eight results. If a
 * change to ranking, the tag/body split or the two-character fallback drops below that, the honest
 * next step is a measured vector store, not a quieter threshold.</p>
 */
class MemoryRetrievalEvalTest {

    private static final double RECALL_FLOOR = 0.7;

    @TempDir
    Path root;
    private SqliteMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new SqliteMemoryStore(root.resolve("memory.db"), MemoryOptions::defaults,
                UnaryOperator.identity());
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    /** One seeded entry: body, tags, and the marker a query must be able to find it by. */
    private record Seed(String text, String tags) {
    }

    private static final List<Seed> CORPUS = List.of(
            new Seed("站会固定在每周二 10:00，地点是三楼小会议室，迟到的人在群里发红包", "例会 排期"),
            new Seed("部署由 cna 负责，发布前必须跑全量门禁 flock /tmp/bma3-maven.lock", "部署 负责人"),
            new Seed("群规第一条：不许发广告，第二次违规直接移出群聊", "群规 纪律"),
            new Seed("主人喜欢先看结论再看细节，长回答最后给一句可以执行的下一步", "偏好 沟通"),
            new Seed("bot 的日志默认写到 run/bma3-live/logs，正文永不落盘", "日志 安全"),
            new Seed("密钥只能放 ~/.bma3env，权限 0600，任何工具结果都不许带上 token", "安全 密钥"),
            new Seed("消息板是业务事实源，inbox 只负责通知，重发必须由 Agent 决定", "架构 事实源"),
            new Seed("QQ 官方渠道和 OneBot 渠道可以同时在线，绑定关系按会话走", "渠道 绑定"),
            new Seed("打包用 jpackage 出 app-image，产物 85M，装到 ~/.local/share/botmosire-a3", "打包 发布"),
            new Seed("虚拟桌面 xvfb 只在 headless 机器上开，本地开发默认关掉", "xvfb 环境"),
            new Seed("定时任务只在合成唤醒里跑，合成唤醒禁止写记忆", "定时 记忆"),
            new Seed("主人不喜欢被反问三次同一个问题，拿不到信息就先给默认方案", "偏好 沟通"),
            new Seed("OCR 和 ASR 走插件里的 media 模块，key 从 DSH credentials 取", "媒体 密钥"),
            new Seed("媒体下载目录是 downloads，超过 20MB 的文件只给路径不内联", "媒体 限制"),
            new Seed("watchdog 每 15 秒探一次，连续三次失败才升级成告警", "监控 watchdog"),
            new Seed("探针只能带 id、状态和计数，绝对不能带正文字符串", "监控 安全"),
            new Seed("记忆检索里标签命中的权重是正文命中的十倍，先看标签再看正文", "记忆 检索"),
            new Seed("两字中文查询命不中 trigram 索引，所以走 LIKE 子串回退", "记忆 实现"),
            new Seed("静音用 weight=-1 表示，列表里不再出现但按 id 仍然查得到", "记忆 静音"),
            new Seed("记忆条目上限由 set_config 热改，不需要重启进程", "配置 记忆"),
            new Seed("配置文件改动会触发 watcher，校验失败就拒绝加载并保留旧值", "配置 重载"),
            new Seed("主人晚上十一点以后基本不回消息，除非是线上故障", "偏好 作息"),
            new Seed("线上故障的判定标准是用户能感知到的功能不可用，内部报错不算", "故障 判定"),
            new Seed("回滚只回滚最近一次发布，跨版本回滚要人工确认", "发布 回滚"),
            new Seed("群里的投票用表情回应统计，不额外装插件", "群务 投票"),
            new Seed("每周五下午整理一次待办，没做完的顺延到下周一", "排期 待办"),
            new Seed("主人更相信可复现的命令输出，不接受凭印象的结论", "偏好 工程"),
            new Seed("测试数据一律用 TempDir，不许写进仓库目录", "测试 规范"),
            new Seed("doc 校验脚本目前不存在，别在文档里引用它", "文档 规范"),
            new Seed("提交信息用 feat/fix/docs 前缀，一次提交只做一件事", "提交 规范"),
            new Seed("推送前必须本地跑通全量测试，红灯不许推", "提交 门禁"),
            new Seed("DSH 的会话日志是 append-only 的 zstd jsonl，压掉的事件还能读回来", "dsh 会话"),
            new Seed("上下文指针记录的是会话 id 加事件序号，不复制正文", "dsh 记忆"),
            new Seed("会话换绑以后旧会话的上下文条目算遗留，watchdog 要报出来", "监控 换绑"),
            new Seed("导出的 txt 落在 exports 目录，文件名带会话和 UTC 时间戳", "记忆 导出"),
            new Seed("导入只认 downloads 目录里的普通文件，符号链接一律拒绝", "记忆 导入"),
            new Seed("主人的机器是 12 核 11G 内存，带一块 5070Ti 笔记本显卡", "环境 硬件"),
            new Seed("本项目没有 GPU 推理依赖，不要引入 torch 或 onnx", "环境 依赖"),
            new Seed("Java 版本是 21，构建走 ./mvnw，离线模式加 -o", "环境 构建"),
            new Seed("插件是 Node 22 的 ESM 包，测试用 node --test", "环境 插件"),
            new Seed("bma3test 这个 profile 是从 web 复制出来的，端口 3090", "环境 profile"),
            new Seed("插件侧推送上下文只在心跳成功后触发，失败保留下次重试", "dsh 推送"),
            new Seed("上下文推送只带 user、assistant、tool 三类事件，其余类型不搬", "dsh 推送"),
            new Seed("被压缩遮蔽的事件不再推送，避免把压掉的上下文又吐回来", "dsh 压缩"),
            new Seed("主人要求回复里不要出现表情符号堆砌，一句话说清就行", "偏好 沟通"),
            new Seed("群里的机器人不许主动私聊成员，只在被 @ 时回应", "群规 边界"),
            new Seed("图片识别失败时直接说识别失败，不要编内容", "媒体 诚实"),
            new Seed("任何结论要么给命令行输出，要么标成推断", "规范 证据"),
            new Seed("记忆的价值在于可回放的现场，而不是模型的自我总结", "记忆 理念"));

    /** Query → the phrase an expected entry contains. */
    private record Query(String text, String expected) {
    }

    private static final List<Query> QUERIES = List.of(
            new Query("站会", "站会固定在每周二"),
            new Query("谁负责部署", "部署由 cna 负责"),
            new Query("群规", "群规第一条"),
            new Query("主人偏好", "主人喜欢先看结论"),
            new Query("token 能放哪", "密钥只能放 ~/.bma3env"),
            new Query("重发消息谁决定", "消息板是业务事实源"),
            new Query("打包产物多大", "打包用 jpackage"),
            new Query("合成唤醒能写记忆吗", "合成唤醒禁止写记忆"),
            new Query("标签权重", "标签命中的权重是正文命中的十倍"),
            new Query("静音", "静音用 weight=-1"),
            new Query("会话日志能读回来吗", "append-only 的 zstd jsonl"),
            new Query("导出文件在哪", "导出的 txt 落在 exports"),
            new Query("导入允许符号链接吗", "符号链接一律拒绝"),
            new Query("显卡", "5070Ti"),
            new Query("端口", "端口 3090"),
            new Query("压缩掉的事件还推送吗", "被压缩遮蔽的事件不再推送"),
            new Query("机器人能私聊吗", "不许主动私聊成员"),
            new Query("识别失败怎么办", "直接说识别失败"),
            new Query("回滚", "回滚只回滚最近一次发布"),
            new Query("测试数据放哪", "测试数据一律用 TempDir"));

    @Test
    void a18_recall_at_eight_stays_above_the_floor() {
        Instant now = Instant.parse("2026-11-18T10:00:00Z");
        for (Seed seed : CORPUS) {
            store.write(seed.text(), seed.tags(), "agent", "", "", now);
        }

        List<String> missed = new ArrayList<>();
        int found = 0;
        for (Query query : QUERIES) {
            List<MemoryHit> hits = store.search(query.text(), 8, 0);
            boolean hit = hits.stream().anyMatch(candidate ->
                    body(candidate.id()).contains(query.expected()));
            if (hit) {
                found += 1;
            } else {
                missed.add(query.text() + " → " + query.expected());
            }
        }
        double recall = (double) found / QUERIES.size();

        assertThat(recall)
                .as("recall@8 = %.2f，未命中：%s", recall, missed)
                .isGreaterThanOrEqualTo(RECALL_FLOOR);
    }

    @Test
    void a18_a_two_character_query_still_reaches_its_entry() {
        Instant now = Instant.parse("2026-11-18T10:00:00Z");
        for (Seed seed : CORPUS) {
            store.write(seed.text(), seed.tags(), "agent", "", "", now);
        }

        List<MemoryHit> hits = store.search("站会", 8, 0);

        assertThat(hits).isNotEmpty();
        assertThat(hits).anySatisfy(candidate ->
                assertThat(body(candidate.id())).contains("站会固定在每周二"));
    }

    private String body(long id) {
        return store.view(id, Instant.parse("2026-11-18T10:00:00Z"), false)
                .map(MemoryItem::text)
                .orElse("");
    }
}
