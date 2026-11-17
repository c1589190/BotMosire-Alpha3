package io.mosire.bma3.qqofficial.shareqr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class OfficialQqShareUrlValidatorTest {

    @Test
    void acceptsOnlyCanonicalOfficialHttpsShortLinks() throws Exception {
        assertThat(OfficialQqShareUrlValidator.validate("https://bot.q.qq.com/s/AbC_123-x.y~z"))
                .isEqualTo(URI.create("https://bot.q.qq.com/s/AbC_123-x.y~z"));
        assertThat(OfficialQqShareUrlValidator.validate("HTTPS://BOT.Q.QQ.COM:443/s/abc"))
                .isEqualTo(URI.create("https://bot.q.qq.com/s/abc"));
    }

    @Test
    void rejectsUserinfoOtherHostsSchemesPortsQueriesFragmentsAndMalformedPaths() {
        List<String> rejected = List.of(
                "http://bot.q.qq.com/s/abc",
                "https://user:password@bot.q.qq.com/s/abc",
                "https://user@bot.q.qq.com/s/abc",
                "https://bot.q.qq.com.evil.invalid/s/abc",
                "https://evil.invalid/s/abc",
                "https://bot.q.qq.com:8443/s/abc",
                "https://bot.q.qq.com/s/abc?token=SECRET_QUERY",
                "https://bot.q.qq.com/s/abc#SECRET_FRAGMENT",
                "https://bot.q.qq.com/not-share/abc",
                "https://bot.q.qq.com/s/",
                "https://bot.q.qq.com/s/.",
                "https://bot.q.qq.com/s/..",
                "https://bot.q.qq.com/s/%2e%2e%2fsecret",
                "https://bot.q.qq.com/s/abc\\evil",
                " https://bot.q.qq.com/s/abc",
                "https://bot.q.qq.com/s/abc\n",
                "not-a-uri-SECRET",
                "");
        for (String raw : rejected) {
            assertThatThrownBy(() -> OfficialQqShareUrlValidator.validate(raw))
                    .as(raw)
                    .isInstanceOfSatisfying(
                            OfficialQqShareQrException.class,
                            failure -> {
                                assertThat(failure.code()).isEqualTo(
                                        OfficialQqShareQrException.INVALID_SHARE_URL);
                                assertThat(failure.getMessage())
                                        .doesNotContain("SECRET")
                                        .doesNotContain("password");
                            });
        }
    }
}
