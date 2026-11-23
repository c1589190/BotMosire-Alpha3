package io.github.c1589190.bma3.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The lexical side of A18: what a sentence becomes before it reaches the index. */
class QueryTermsTest {

    @Test
    void a_blank_query_yields_no_terms() {
        assertThat(QueryTerms.of("")).isEmpty();
        assertThat(QueryTerms.of("   ")).isEmpty();
        assertThat(QueryTerms.of(null)).isEmpty();
    }

    @Test
    void a_two_character_run_stays_whole() {
        assertThat(QueryTerms.of("站会")).containsExactly("站会");
    }

    @Test
    void a_sentence_expands_into_bigrams_plus_the_run_itself() {
        assertThat(QueryTerms.of("负责部署"))
                .containsExactly("负责部署", "负责", "责部", "部署");
    }

    @Test
    void the_whole_phrase_comes_first_so_an_exact_quote_outranks_its_grams() {
        List<String> terms = QueryTerms.of("站会固定在周二");
        assertThat(terms).first().isEqualTo("站会固定在周二");
        assertThat(terms).contains("站会", "固定", "周二");
    }

    @Test
    void separators_split_runs_and_punctuation_never_becomes_a_term() {
        assertThat(QueryTerms.of("记忆的 标签权重，怎么算？"))
                .contains("记忆的", "标签权重", "标签", "签权", "权重", "标签权重")
                .doesNotContain("，", "？", "");
    }

    @Test
    void latin_runs_stay_whole_and_lowercase() {
        assertThat(QueryTerms.of("FTS5 trigram")).containsExactly("fts5", "trigram");
    }

    @Test
    void a_mixed_chunk_keeps_its_latin_part_whole_and_expands_the_cjk_runs_inside() {
        // "qwen3" is not a CJK run, so it is never cut; the CJK run beside it still expands.
        assertThat(QueryTerms.of("qwen3本地模型"))
                .contains("qwen3本地模型", "本地模型", "本地", "地模", "模型")
                .doesNotContain("qwe", "wen3", "n3本");
    }

    @Test
    void terms_are_deduplicated_in_query_order() {
        List<String> terms = QueryTerms.of("部署 部署");
        assertThat(terms).containsExactly("部署");
    }

    @Test
    void the_expansion_is_bounded() {
        // Forty distinct ideographs: the run plus 39 distinct grams, far past the cap.
        StringBuilder longQuery = new StringBuilder();
        for (int index = 0; index < 40; index += 1) {
            longQuery.append((char) (0x4E00 + index));
        }
        assertThat(QueryTerms.of(longQuery.toString())).hasSize(QueryTerms.MAX_TERMS);
    }

    @Test
    void surrogate_pairs_are_never_cut_in_half() {
        List<String> terms = QueryTerms.of("𠀀𠀁𠀂");
        assertThat(terms).allSatisfy(term -> assertThat(term).isNotEmpty());
        assertThat(terms.stream().noneMatch(term -> Character.isHighSurrogate(term.charAt(term.length() - 1))))
                .isTrue();
    }
}
