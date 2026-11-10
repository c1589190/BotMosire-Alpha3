package io.mosire.bma3.qqconnect.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.source.SourceLabels;
import org.junit.jupiter.api.Test;

class QqSourceLabelerTest {

    private final SourceLabels labels = SourceLabels.builder()
            .register(new QqSourceLabeler()).build();

    @Test
    void labelsQqSourcesWithoutTheInboxKnowingQq() {
        assertThat(labels.label(SourceAddress.parse("qq_group/bot-1/955432692")))
                .isEqualTo("QQ群 955432692");
        assertThat(labels.label(SourceAddress.parse("qq_private/bot-1/3668539114")))
                .isEqualTo("QQ私聊 3668539114");
    }

    @Test
    void fallsBackForFuturePlatforms() {
        assertThat(labels.label(SourceAddress.parse("bilibili/12345")))
                .isEqualTo("bilibili · 12345");
        assertThat(labels.view(SourceAddress.parse("wechat/acct/chat")).label())
                .isEqualTo("wechat · chat");
    }
}
