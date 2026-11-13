package io.mosire.bma3.qqconnect.tools;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 长图切段：把一张「高得看不清」的图片按水平带切成若干段，每段单独落盘。
 *
 * <p>存在的理由：宿主侧 {@code read_image} 会把超高图片压到固定高度（DSH 现状是 4096），
 * 1080×18000 这样的长图压完字就糊了。给人看的办法是分段，而不是让 Agent 自己拿刀切。
 *
 * <p><b>设计取舍</b>
 * <ul>
 *   <li><b>带重叠</b>：相邻两段重叠 {@code overlap} 像素，避免正好切在一行字中间。默认 64。</li>
 *   <li><b>无损</b>：切出来的段一律写 PNG（原图可能是有损 JPEG，再压一次会二次损失）。</li>
 *   <li><b>有上限</b>：像素总数超过 {@link #MAX_PIXELS} 直接拒绝解码（一张 10000×10000 的图
 *       解码后约 400 MB，足以把 JVM 顶爆）；段数超过 {@link #MAX_PARTS} 也拒绝，并把
 *       「需要多少段」原样告诉调用方，让它决定是缩放还是分区查看。</li>
 *   <li><b>不猜格式</b>：ImageIO 读不了（比如 WebP）就明说读不了，返回原图尺寸而不是装成功。</li>
 * </ul>
 *
 * <p>本类只做纯粹的文件变换，不懂 QQ、不懂工具协议，便于单测。
 */
final class LongImageSplitter {

    /** 单段目标高度上限（像素）：2048 是「read_image 不缩放也能看清」的经验值。 */
    static final int DEFAULT_PART_HEIGHT = 2048;

    /** 相邻段的重叠像素：宁可多花一点，也别把字切两半。 */
    static final int DEFAULT_OVERLAP = 64;

    /** 段数上限：再多就不该用「切段」解决了。 */
    static final int MAX_PARTS = 16;

    /** 解码前的像素总数上限（约 4000 万像素 ≈ 160 MB 的 ARGB 缓冲）。 */
    static final long MAX_PIXELS = 40_000_000L;

    private LongImageSplitter() {
    }

    /** 一段的元数据。{@code y0}/{@code y1} 是相对原图的像素行（{@code y1} 开区间）。 */
    record Part(int index, int total, Path path, long bytes, int width, int height, int y0, int y1) {
    }

    /**
     * 切段结果。
     *
     * @param readable   ImageIO 能不能解码（false 时 {@code width/height} 为 -1）
     * @param width      原图宽
     * @param height     原图高
     * @param parts      切出来的段；未切段时为空
     * @param reason     未切段的原因码：{@code SHORT_ENOUGH} / {@code UNREADABLE_FORMAT} /
     *                   {@code TOO_MANY_PIXELS} / {@code TOO_MANY_PARTS}
     */
    record Result(boolean readable, int width, int height, List<Part> parts, String reason) {

        public Result {
            parts = parts == null ? List.of() : List.copyOf(parts);
            reason = reason == null ? "" : reason;
        }

        boolean split() {
            return !parts.isEmpty();
        }
    }

    /** 只需要尺寸时用它：不解码整张图（ImageIO 的 reader 先读头部）。 */
    static int[] probe(Path source) {
        try (var stream = ImageIO.createImageInputStream(source.toFile())) {
            if (stream == null) {
                return null;
            }
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return null;
            }
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException failure) {
            return null;
        }
    }

    /**
     * 高了就切，不高就只报尺寸。
     *
     * @param targetDir 段文件写到哪（通常是 {@code <dataDir>/downloads}，这样 QQ 发送工具能直接取）
     * @param baseName  段文件前缀；实际文件名是 {@code <baseName>-part-01-of-03.png}
     */
    static Result inspectAndSplit(Path source, Path targetDir, String baseName,
                                  int partHeight, int overlap) {
        int[] size = probe(source);
        if (size == null) {
            return new Result(false, -1, -1, List.of(), "UNREADABLE_FORMAT");
        }
        int width = size[0];
        int height = size[1];
        if (width <= 0 || height <= 0) {
            return new Result(false, -1, -1, List.of(), "UNREADABLE_FORMAT");
        }
        if ((long) width * height > MAX_PIXELS) {
            return new Result(true, width, height, List.of(), "TOO_MANY_PIXELS");
        }
        int slice = partHeight <= 0 ? DEFAULT_PART_HEIGHT : partHeight;
        int gap = Math.max(0, overlap);
        if (height <= slice) {
            return new Result(true, width, height, List.of(), "SHORT_ENOUGH");
        }

        // 1) 先算边界（含重叠），尾巴太窄就并进上一段
        int step = Math.max(1, slice - gap);
        List<int[]> bands = new ArrayList<>();
        for (int y0 = 0; y0 < height; y0 += step) {
            int y1 = Math.min(y0 + slice, height);
            bands.add(new int[] {y0, y1});
            if (y1 >= height) {
                break;
            }
        }
        if (bands.size() >= 2) {
            int[] last = bands.get(bands.size() - 1);
            if (last[1] - last[0] < Math.max(32, slice / 4)) {
                bands.remove(bands.size() - 1);
                bands.get(bands.size() - 1)[1] = height;
            }
        }
        if (bands.size() > MAX_PARTS) {
            return new Result(true, width, height, List.of(), "TOO_MANY_PARTS");
        }

        // 2) 再解码、切、写
        BufferedImage image;
        try {
            image = ImageIO.read(source.toFile());
        } catch (IOException failure) {
            return new Result(false, -1, -1, List.of(), "UNREADABLE_FORMAT");
        }
        if (image == null) {
            return new Result(false, -1, -1, List.of(), "UNREADABLE_FORMAT");
        }
        try {
            Files.createDirectories(targetDir);
            List<Part> parts = new ArrayList<>();
            int total = bands.size();
            for (int i = 0; i < total; i++) {
                int[] band = bands.get(i);
                parts.add(writePart(image, targetDir, baseName, i + 1, total,
                        band[0], band[1], width));
            }
            return new Result(true, width, height, parts, "SPLIT");
        } catch (IOException failure) {
            return new Result(true, width, height, List.of(), "WRITE_FAILED");
        }
    }

    private static Part writePart(BufferedImage image, Path targetDir, String baseName, int index,
                                  int total, int y0, int y1, int width) throws IOException {
        BufferedImage sub = image.getSubimage(0, y0, width, y1 - y0);
        // getSubimage 与原图共享像素缓冲：先复制再写，写完即可释放
        BufferedImage copy = new BufferedImage(width, y1 - y0, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = copy.createGraphics();
        try {
            graphics.drawImage(sub, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        Path path = targetDir.resolve(baseName + "-part-" + String.format("%02d", index)
                + "-of-" + String.format("%02d", total) + ".png");
        ImageIO.write(copy, "png", path.toFile());
        return new Part(index, total, path, Files.size(path), width, y1 - y0, y0, y1);
    }
}
