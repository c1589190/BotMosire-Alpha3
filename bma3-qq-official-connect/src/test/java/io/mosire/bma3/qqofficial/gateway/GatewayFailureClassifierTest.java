package io.mosire.bma3.qqofficial.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.bma3.qqofficial.api.OfficialQqApiException;
import io.mosire.bma3.qqofficial.api.OfficialQqErrorKind;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class GatewayFailureClassifierTest {

    private final GatewayFailureClassifier classifier = GatewayFailureClassifier.defaults();

    @Test
    void typedPermanentFailureIsTerminalWithSafeCode() {
        GatewayFailureClassifier.Classification result = classifier.classify(
                new GatewayPermanentException("AUTH_CONFIG_INVALID",
                        new IllegalStateException("raw secret")));

        assertThat(result.disposition())
                .isEqualTo(GatewayFailureClassifier.Disposition.PERMANENT);
        assertThat(result.code()).isEqualTo("AUTH_CONFIG_INVALID");
    }

    @Test
    void officialConfigurationAndAuthenticationArePermanentButNetworkIsRetryable() {
        assertThat(classifier.classify(apiFailure(OfficialQqErrorKind.CONFIGURATION, false))
                .disposition()).isEqualTo(GatewayFailureClassifier.Disposition.PERMANENT);
        assertThat(classifier.classify(apiFailure(OfficialQqErrorKind.AUTHENTICATION, false))
                .disposition()).isEqualTo(GatewayFailureClassifier.Disposition.PERMANENT);
        assertThat(classifier.classify(apiFailure(OfficialQqErrorKind.NETWORK, true))
                .disposition()).isEqualTo(GatewayFailureClassifier.Disposition.RETRYABLE);
        assertThat(classifier.classify(new IOException("network")).disposition())
                .isEqualTo(GatewayFailureClassifier.Disposition.RETRYABLE);
    }

    @Test
    void frameOverflowIsPermanentAndInvalidConfigurationIsPermanent() {
        assertThat(classifier.classify(new GatewayFrameTooLargeException()).code())
                .isEqualTo("FRAME_TOO_LARGE");
        assertThat(classifier.classify(new IllegalArgumentException("bad config")).code())
                .isEqualTo("INVALID_CONFIGURATION");
    }

    private static OfficialQqApiException apiFailure(OfficialQqErrorKind kind, boolean retryable) {
        return new OfficialQqApiException(
                kind, -1, "account", "TOKEN", "", "", retryable, "safe detail", null);
    }
}
