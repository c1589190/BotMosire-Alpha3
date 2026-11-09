package io.mosire.bma3.webconnect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Browser-based video frame viewer: open a page, seek its HTML5 video, save element screenshots. */
public final class BrowserVideoFramesTool extends BrowserTools.Base {

    private static final ObjectMapper JSON = new ObjectMapper();

    public BrowserVideoFramesTool(BrowserToolContext context) {
        super(context);
    }

    @Override
    public String name() {
        return "browser_video_frames";
    }

    @Override
    public String description() {
        return "打开 B 站等含 HTML5 video 的页面，按 times/count 逐帧 seek 并保存 PNG；适合看动作、界面和画面。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "url", Map.of("type", "string"),
                "times", Map.of("type", "array", "maxItems", 9,
                        "items", Map.of("oneOf", List.of(
                                Map.of("type", "number"), Map.of("type", "string")))),
                "count", Map.of("type", "integer", "minimum", 1, "maximum", 9)),
                "required", List.of("url"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            String url = string(toolContext, "url");
            BrowserSession session = session();
            session.open(url);
            double duration = session.videoDuration();
            if (duration <= 0) {
                return ToolResult.error("VIDEO_NOT_FOUND", "页面没有可用的 HTML5 video");
            }
            List<Double> times = times(toolContext, duration);
            Path dir = context.screenshotDir().resolve("video-" + Instant.now().toEpochMilli());
            List<Map<String, Object>> frames = new ArrayList<>();
            for (int index = 0; index < times.size(); index++) {
                double seconds = times.get(index);
                try {
                    Path path = session.frameAt(seconds, dir);
                    frames.add(Map.of(
                            "index", index,
                            "seconds", Math.round(seconds * 1000.0) / 1000.0,
                            "path", path.toAbsolutePath().toString()));
                } catch (RuntimeException failure) {
                    frames.add(Map.of(
                            "index", index,
                            "seconds", Math.round(seconds * 1000.0) / 1000.0,
                            "error", "FRAME_UNAVAILABLE"));
                }
            }
            if (frames.stream().noneMatch(frame -> frame.containsKey("path"))) {
                return ToolResult.error("VIDEO_FRAMES_UNAVAILABLE", "所有帧都抓取失败");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", session.currentUrl());
            result.put("title", session.title());
            result.put("durationSeconds", Math.round(duration * 1000.0) / 1000.0);
            result.put("frames", frames);
            return ToolResult.ok(JSON.writeValueAsString(result));
        } catch (IllegalArgumentException failure) {
            return fail(failure);
        } catch (Exception failure) {
            return fail(new IllegalStateException(failure.getMessage(), failure));
        }
    }

    private List<Double> times(ToolContext toolContext, double duration) {
        Object raw = toolContext.arguments().get("times");
        List<Double> times = new ArrayList<>();
        if (raw instanceof List<?> values) {
            if (values.size() > 9) {
                throw new IllegalArgumentException("INVALID_ARGUMENT: times 最多 9 个");
            }
            for (Object value : values) {
                double parsed = value instanceof Number number
                        ? number.doubleValue()
                        : parseTime(String.valueOf(value));
                if (parsed < 0 || parsed > duration) {
                    throw new IllegalArgumentException("INVALID_ARGUMENT: time 超出视频范围");
                }
                times.add(parsed);
            }
            return times;
        }
        int count = 6;
        Object countArg = toolContext.arguments().get("count");
        if (countArg instanceof Number number) {
            count = Math.max(1, Math.min(9, number.intValue()));
        }
        for (int index = 0; index < count; index++) {
            times.add(duration * (index + 1) / (count + 1));
        }
        return times;
    }

    private static double parseTime(String raw) {
        String[] parts = raw.trim().split(":");
        try {
            double seconds = 0;
            for (String part : parts) {
                seconds = seconds * 60 + Double.parseDouble(part);
            }
            return seconds;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("INVALID_ARGUMENT: 时间格式应为 SS/MM:SS/HH:MM:SS");
        }
    }
}
