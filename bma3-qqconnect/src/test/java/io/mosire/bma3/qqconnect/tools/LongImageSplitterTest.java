package io.mosire.bma3.qqconnect.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LongImageSplitterTest {

    /** 造一张「上色带」的长图：每 100 行换一个颜色，切错段立刻能看出来。 */
    private static Path longImage(Path dir, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            for (int y = 0; y < height; y += 100) {
                graphics.setColor(y % 200 == 0 ? Color.RED : Color.BLUE);
                graphics.fillRect(0, y, width, 100);
            }
        } finally {
            graphics.dispose();
        }
        Path file = dir.resolve("long.png");
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    @Test
    void shortImageIsNotSplit(@TempDir Path dir) throws Exception {
        Path source = longImage(dir, 400, 800);

        LongImageSplitter.Result result =
                LongImageSplitter.inspectAndSplit(source, dir, "img", 2048, 64);

        assertThat(result.readable()).isTrue();
        assertThat(result.width()).isEqualTo(400);
        assertThat(result.height()).isEqualTo(800);
        assertThat(result.split()).isFalse();
        assertThat(result.reason()).isEqualTo("SHORT_ENOUGH");
    }

    @Test
    void longImageIsSplitIntoBandsThatCoverTheWholeHeight(@TempDir Path dir) throws Exception {
        Path source = longImage(dir, 400, 5000);

        LongImageSplitter.Result result =
                LongImageSplitter.inspectAndSplit(source, dir, "img", 2000, 100);

        assertThat(result.reason()).isEqualTo("SPLIT");
        assertThat(result.parts()).hasSize(3);
        assertThat(result.parts().get(0).y0()).isZero();
        assertThat(result.parts().get(result.parts().size() - 1).y1()).isEqualTo(5000);
        for (LongImageSplitter.Part part : result.parts()) {
            assertThat(part.path()).isRegularFile();
            assertThat(part.height()).isLessThanOrEqualTo(2000);
            assertThat(part.width()).isEqualTo(400);
            assertThat(part.total()).isEqualTo(3);
            assertThat(part.bytes()).isPositive();
        }
    }

    @Test
    void neighbouringPartsOverlapSoTextIsNotCutInHalf(@TempDir Path dir) throws Exception {
        Path source = longImage(dir, 200, 3000);

        LongImageSplitter.Result result =
                LongImageSplitter.inspectAndSplit(source, dir, "over", 1000, 120);

        List<LongImageSplitter.Part> parts = result.parts();
        assertThat(parts.size()).isGreaterThan(1);
        for (int i = 1; i < parts.size(); i++) {
            int previousEnd = parts.get(i - 1).y1();
            int nextStart = parts.get(i).y0();
            assertThat(nextStart).as("第 %d 段必须与上一段重叠", i + 1)
                    .isLessThan(previousEnd);
        }
    }

    @Test
    void partsAreWrittenWhereTheCallerAsked(@TempDir Path dir) throws Exception {
        Path source = longImage(dir, 300, 4000);
        Path target = dir.resolve("downloads");

        LongImageSplitter.Result result =
                LongImageSplitter.inspectAndSplit(source, target, "shot", 1500, 0);

        assertThat(result.split()).isTrue();
        try (var files = Files.list(target)) {
            assertThat(files.toList()).hasSize(result.parts().size());
        }
        assertThat(result.parts().get(0).path().getFileName().toString())
                .isEqualTo("shot-part-01-of-" + String.format("%02d", result.parts().size())
                        + ".png");
    }

    @Test
    void absurdlyTallImageIsRefusedWithTheReason(@TempDir Path dir) throws Exception {
        Path source = longImage(dir, 100, 40_000);  // 4M 像素，可行；用 40 段逼出上限

        LongImageSplitter.Result result =
                LongImageSplitter.inspectAndSplit(source, dir, "huge", 1000, 0);

        assertThat(result.readable()).isTrue();
        assertThat(result.split()).isFalse();
        assertThat(result.reason()).isEqualTo("TOO_MANY_PARTS");
        assertThat(result.height()).isEqualTo(40_000);
    }

    @Test
    void nonImageFileIsReportedAsUnreadable(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("not-an-image.png");
        Files.writeString(source, "这不是图片");

        LongImageSplitter.Result result =
                LongImageSplitter.inspectAndSplit(source, dir, "x", 1000, 0);

        assertThat(result.readable()).isFalse();
        assertThat(result.reason()).isEqualTo("UNREADABLE_FORMAT");
        assertThat(result.split()).isFalse();
    }

    @Test
    void probeReadsSizeWithoutDecoding(@TempDir Path dir) throws Exception {
        Path source = longImage(dir, 640, 12_000);

        int[] size = LongImageSplitter.probe(source);

        assertThat(size).containsExactly(640, 12_000);
    }
}
