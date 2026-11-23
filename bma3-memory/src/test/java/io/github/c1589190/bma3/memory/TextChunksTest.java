package io.github.c1589190.bma3.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The per-entry cap must never decide what survives an import. */
class TextChunksTest {

    @Test
    void aShortTextStaysOnePiece() {
        assertThat(TextChunks.split("周一站会", 100)).containsExactly("周一站会");
    }

    @Test
    void splittingOnLinesLosesNothing() {
        String text = "第一行\n第二行\n第三行\n第四行";

        List<String> pieces = TextChunks.split(text, 8);

        assertThat(pieces).hasSizeGreaterThan(1);
        assertThat(String.join("\n", pieces)).isEqualTo(text);
        assertThat(pieces).allSatisfy(piece ->
                assertThat(piece.codePointCount(0, piece.length())).isLessThanOrEqualTo(8));
    }

    @Test
    void aLineLongerThanTheCapIsCutHard() {
        String text = "x".repeat(25);

        List<String> pieces = TextChunks.split(text, 10);

        assertThat(pieces).containsExactly("x".repeat(10), "x".repeat(10), "x".repeat(5));
        assertThat(String.join("", pieces)).isEqualTo(text);
    }

    @Test
    void aLongLineAfterAShortOneStillFitsTheCap() {
        List<String> pieces = TextChunks.split("开头\n" + "y".repeat(25), 10);

        assertThat(pieces).allSatisfy(piece ->
                assertThat(piece.codePointCount(0, piece.length())).isLessThanOrEqualTo(10));
        assertThat(String.join("", pieces)).isEqualTo("开头" + "y".repeat(25));
    }

    @Test
    void blankPiecesAreDropped() {
        assertThat(TextChunks.split("一\n\n\n二", 100)).containsExactly("一\n\n\n二");
    }

    @Test
    void aBlankTextIsRejected() {
        assertThatThrownBy(() -> TextChunks.split("   \n\t\n", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有可导入");
    }

    @Test
    void aNonPositiveCapIsRejected() {
        assertThatThrownBy(() -> TextChunks.split("abc", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCapIsCountedInCodePointsNotChars() {
        String text = "🙂".repeat(6);

        List<String> pieces = TextChunks.split(text, 4);

        assertThat(pieces).hasSize(2);
        assertThat(pieces.get(0)).isEqualTo("🙂".repeat(4));
        assertThat(pieces.get(1)).isEqualTo("🙂".repeat(2));
    }
}
