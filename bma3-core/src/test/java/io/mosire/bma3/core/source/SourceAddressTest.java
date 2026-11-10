package io.mosire.bma3.core.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SourceAddressTest {

    @Test
    void roundTripsQqGroup() {
        SourceAddress address = SourceAddress.parse("qq_group/bot-1/955432692");
        assertThat(address.format()).isEqualTo("qq_group/bot-1/955432692");
        assertThat(address.platform()).isEqualTo("qq_group");
        assertThat(address.accountId()).isEqualTo("bot-1");
    }

    @Test
    void roundTripsQqPrivate() {
        SourceAddress address = SourceAddress.parse("qq_private/bot-1/123");
        assertThat(address.format()).isEqualTo("qq_private/bot-1/123");
        assertThat(address.segments()).containsExactly("bot-1", "123");
    }

    @Test
    void roundTripsQqSystem() {
        SourceAddress address = SourceAddress.parse("qq_system/bot-1/request");
        assertThat(address.format()).isEqualTo("qq_system/bot-1/request");
        assertThat(address.accountId()).isEqualTo("bot-1");
    }

    @Test
    void acceptsGenericPlatformRoutes() {
        SourceAddress bili = SourceAddress.parse("bilibili/12345");
        assertThat(bili.platform()).isEqualTo("bilibili");
        assertThat(bili.accountId()).isEmpty();
        assertThat(bili.route()).containsExactly("12345");
        assertThat(bili.lastSegment()).isEqualTo("12345");

        SourceAddress wechat = SourceAddress.parse("wechat/acct-1/chat-9");
        assertThat(wechat.platform()).isEqualTo("wechat");
        assertThat(wechat.accountId()).isEqualTo("acct-1");
        assertThat(wechat.route()).containsExactly("acct-1", "chat-9");
    }

    @Test
    void sourceLabelsFallBackForUnknownPlatforms() {
        SourceLabels labels = SourceLabels.empty();
        SourceAddress bili = SourceAddress.parse("bilibili/12345");
        assertThat(labels.label(bili)).isEqualTo("bilibili · 12345");
        assertThat(labels.view(bili).label()).isEqualTo("bilibili · 12345");
    }

    @Test
    void rejectsMalformedAddresses() {
        assertThatThrownBy(() -> SourceAddress.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.parse("qq"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.parse("qq_group//123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.parse("qq_group/bot-1/"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
