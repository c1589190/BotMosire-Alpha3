package io.mosire.bma3.qqofficial.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GatewayCloseCodeTest {

    @Test
    void documentedCloseCodesHaveExplicitRecoveryPolicy() {
        assertThat(GatewayCloseCode.classify(4006).action())
                .isEqualTo(GatewayCloseCode.Action.RECONNECT_IDENTIFY);
        assertThat(GatewayCloseCode.classify(4007).clearSession()).isTrue();
        assertThat(GatewayCloseCode.classify(4008).action())
                .isEqualTo(GatewayCloseCode.Action.RECONNECT_RESUME);
        assertThat(GatewayCloseCode.classify(4009).action())
                .isEqualTo(GatewayCloseCode.Action.RECONNECT_RESUME);
        assertThat(GatewayCloseCode.classify(4014).terminal()).isTrue();
        assertThat(GatewayCloseCode.classify(4914).terminalReason()).isEqualTo("BOT_OFFLINE");
        assertThat(GatewayCloseCode.classify(4915).terminalReason()).isEqualTo("BOT_BANNED");
        assertThat(GatewayCloseCode.classify(1000).action())
                .isEqualTo(GatewayCloseCode.Action.UNKNOWN);
    }
}
