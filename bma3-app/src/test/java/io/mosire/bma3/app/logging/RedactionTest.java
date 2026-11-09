package io.mosire.bma3.app.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedactionTest {

    @Test
    void redactsTokensCookiesAndPasswords() {
        String redacted = Redactor.redact("""
                Authorization: Bearer abc.def.ghi
                Cookie: SESSDATA=secret; bili_jct=xyz
                password=hunter2
                normal line
                """);

        assertThat(redacted)
                .doesNotContain("abc.def.ghi")
                .doesNotContain("SESSDATA=secret")
                .doesNotContain("hunter2")
                .contains("[REDACTED]")
                .contains("normal line");
    }
}
