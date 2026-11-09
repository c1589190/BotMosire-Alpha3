package io.mosire.bma3.qqconnect.account;

import io.mosire.bma3.qqconnect.onebot.OneBotEvent;

/** One event tagged with the configured account it arrived on. */
public record AccountEvent(String accountId, OneBotEvent event) {
}
