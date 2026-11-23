package io.github.c1589190.bma3.qqofficial.send;

import java.util.Optional;

/**
 * Account lookup seam. Implementations must resolve only exact account IDs and return an
 * account whose {@link OfficialQqSendAccount#enabled()} reflects live configuration.
 */
@FunctionalInterface
public interface OfficialQqSendAccounts {

    Optional<OfficialQqSendAccount> resolve(String accountId);
}
