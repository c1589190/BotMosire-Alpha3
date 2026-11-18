package io.mosire.bma3.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits an imported file into notebook-sized pieces.
 *
 * <p>The whole point of "import a file" is that nothing is dropped, so the per-entry cap must not
 * decide what survives. Splitting on line boundaries keeps each piece readable; a single line longer
 * than the cap is cut hard, because there is no better place to cut it.</p>
 */
public final class TextChunks {

    private TextChunks() {
    }

    /**
     * @param text          text to split; must not be blank
     * @param maxCodePoints hard cap per piece, in code points
     * @return one or more non-blank pieces, in order, each at most {@code maxCodePoints}
     */
    public static List<String> split(String text, int maxCodePoints) {
        if (maxCodePoints < 1) {
            throw new IllegalArgumentException("maxCodePoints 必须大于 0");
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        int pieceCodePoints = 0;
        for (String line : text.split("\n", -1)) {
            int lineCodePoints = line.codePointCount(0, line.length());
            int cost = pieceCodePoints == 0 ? lineCodePoints : lineCodePoints + 1;
            if (pieceCodePoints > 0 && pieceCodePoints + cost > maxCodePoints) {
                pieces.add(piece.toString());
                piece.setLength(0);
                pieceCodePoints = 0;
            }
            if (lineCodePoints <= maxCodePoints) {
                if (pieceCodePoints > 0) {
                    piece.append('\n');
                    pieceCodePoints += 1;
                }
                piece.append(line);
                pieceCodePoints += lineCodePoints;
                continue;
            }
            if (pieceCodePoints > 0) {
                pieces.add(piece.toString());
                piece.setLength(0);
                pieceCodePoints = 0;
            }
            pieces.addAll(hardCut(line, maxCodePoints));
        }
        if (pieceCodePoints > 0) {
            pieces.add(piece.toString());
        }
        List<String> kept = new ArrayList<>(pieces.size());
        for (String candidate : pieces) {
            if (!candidate.isBlank()) {
                kept.add(candidate);
            }
        }
        if (kept.isEmpty()) {
            throw new IllegalArgumentException("文本没有可导入的内容");
        }
        return List.copyOf(kept);
    }

    private static List<String> hardCut(String line, int maxCodePoints) {
        List<String> cuts = new ArrayList<>();
        int start = 0;
        while (start < line.length()) {
            int end = line.offsetByCodePoints(start,
                    Math.min(maxCodePoints, line.codePointCount(start, line.length())));
            cuts.add(line.substring(start, end));
            start = end;
        }
        return cuts;
    }
}
