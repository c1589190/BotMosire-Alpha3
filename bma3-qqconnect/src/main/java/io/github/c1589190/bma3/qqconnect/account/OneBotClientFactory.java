package io.github.c1589190.bma3.qqconnect.account;

import io.github.c1589190.bma3.qqconnect.onebot.OneBot11Client;
import io.github.c1589190.bma3.qqconnect.onebot.OneBotEvent;
import java.util.function.Consumer;

/** Test seam for account reconciliation; production uses {@link OneBot11Client}. */
@FunctionalInterface
public interface OneBotClientFactory {

    OneBot11Client create(AccountConfig config, Consumer<OneBotEvent> listener);
}
