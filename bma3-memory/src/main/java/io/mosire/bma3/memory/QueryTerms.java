package io.mosire.bma3.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a natural-language query into the terms the lexical index can actually answer.
 *
 * <p>The notebook is searched by sentences ("谁负责部署", "记忆的标签权重是多少"), but a trigram FTS index
 * and a {@code LIKE} scan both match <em>text</em>, not meaning: the whole sentence appears in no
 * entry, so a single-phrase query only ever finds entries that quote the question. Chinese is also
 * not whitespace-separated, which leaves no word boundaries to split on.</p>
 *
 * <p>The compromise is the classic CJK bigram expansion: every maximal CJK run contributes its
 * two-character grams plus the run itself (which the trigram index can answer), the chunk stays whole
 * so a verbatim quote still wins, and latin runs inside a chunk are never split.
 * Matches are then combined by how many distinct terms an entry hits, so "负责部署" beats "负责"
 * alone. This is still pure lexical matching — no model, no embeddings — and it is what makes the
 * A18 recall floor reachable without a vector store.</p>
 */
final class QueryTerms {

    /** Bound on the terms one query expands into: each term is one indexed lookup. */
    static final int MAX_TERMS = 16;

    /** Two-character grams; also the shortest term {@code LIKE} can answer usefully in Chinese. */
    static final int GRAM = 2;

    private static final String SEPARATORS = "[\\s,，、;；。.!！?？:：/\\\\|()（）\\[\\]【】\"'“”‘’]+";

    private QueryTerms() {
    }

    /**
     * @param query raw query text
     * @return deduplicated terms in query order, at most {@link #MAX_TERMS}; empty for a blank query
     */
    static List<String> of(String query) {
        String raw = query == null ? "" : query.trim();
        if (raw.isEmpty()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String chunk : raw.split(SEPARATORS)) {
            String piece = chunk.trim();
            if (piece.isEmpty()) {
                continue;
            }
            terms.add(piece.toLowerCase(java.util.Locale.ROOT));
            for (String run : cjkRuns(piece)) {
                int length = run.codePointCount(0, run.length());
                terms.add(run);
                for (int start = 0; start + GRAM <= length; start += 1) {
                    int from = run.offsetByCodePoints(0, start);
                    int to = run.offsetByCodePoints(from, GRAM);
                    terms.add(run.substring(from, to));
                }
            }
        }
        List<String> ordered = new ArrayList<>(terms);
        return List.copyOf(ordered.subList(0, Math.min(ordered.size(), MAX_TERMS)));
    }

    /**
     * Maximal CJK runs inside one chunk, so "bma3会话" still contributes "会话".
     *
     * <p>Every term is one indexed lookup, so {@link #MAX_TERMS} bounds the work no matter how long
     * the query is.</p>
     */
    private static List<String> cjkRuns(String piece) {
        List<String> runs = new ArrayList<>();
        int start = -1;
        for (int index = 0; index < piece.length(); ) {
            int codePoint = piece.codePointAt(index);
            boolean cjk = isCjk(codePoint);
            if (cjk && start < 0) {
                start = index;
            } else if (!cjk && start >= 0) {
                if (piece.codePointCount(start, index) >= GRAM) {
                    runs.add(piece.substring(start, index));
                }
                start = -1;
            }
            index += Character.charCount(codePoint);
        }
        if (start >= 0 && piece.codePointCount(start, piece.length()) >= GRAM) {
            runs.add(piece.substring(start));
        }
        return runs;
    }

    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x3400 && codePoint <= 0x9FFF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0x20000 && codePoint <= 0x2FA1F);
    }
}
