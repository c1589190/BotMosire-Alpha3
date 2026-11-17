package io.mosire.bma3.qqofficial.gateway;

import io.mosire.bma3.qqofficial.api.OfficialQqApiException;
import java.util.Objects;

/**
 * Classifies token/URI/transport setup failures as retryable or permanent.
 *
 * <p>The app can inject this seam to map account/auth/config failures from its credential stack.
 * The default mapping understands this package's typed {@link GatewayPermanentException} and the
 * Phase-2 {@link OfficialQqApiException} category, without logging exception messages.</p>
 */
@FunctionalInterface
public interface GatewayFailureClassifier {

    Classification classify(Throwable failure);

    enum Disposition {
        RETRYABLE,
        PERMANENT
    }

    record Classification(Disposition disposition, String code) {
        public Classification {
            disposition = disposition == null ? Disposition.RETRYABLE : disposition;
            code = safeCode(code);
        }

        public boolean permanent() {
            return disposition == Disposition.PERMANENT;
        }

        private static String safeCode(String raw) {
            String value = Objects.requireNonNullElse(raw, "").strip();
            if (value.isEmpty()) {
                return "GATEWAY_FAILURE";
            }
            StringBuilder safe = new StringBuilder(Math.min(value.length(), 96));
            for (int index = 0; index < value.length() && safe.length() < 96; index++) {
                char ch = value.charAt(index);
                if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')
                        || ch == '_' || ch == '-' || ch == '.' || ch == ':') {
                    safe.append(ch);
                } else {
                    safe.append('_');
                }
            }
            return safe.toString();
        }
    }

    static GatewayFailureClassifier defaults() {
        return failure -> {
            if (failure instanceof GatewayPermanentException permanent) {
                return new Classification(Disposition.PERMANENT, permanent.code());
            }
            if (failure instanceof OfficialQqApiException api) {
                String code = "API_" + api.kind().name();
                return api.retryable() || api.kind().potentiallyRetryable()
                        ? new Classification(Disposition.RETRYABLE, code)
                        : new Classification(Disposition.PERMANENT, code);
            }
            if (failure instanceof IllegalArgumentException
                    || failure instanceof SecurityException
                    || failure instanceof java.net.URISyntaxException) {
                return new Classification(Disposition.PERMANENT, "INVALID_CONFIGURATION");
            }
            if (failure instanceof java.io.IOException
                    || failure instanceof java.util.concurrent.TimeoutException
                    || failure instanceof java.net.http.WebSocketHandshakeException) {
                return new Classification(Disposition.RETRYABLE, "NETWORK");
            }
            return new Classification(Disposition.RETRYABLE, "TRANSIENT");
        };
    }
}
