package io.mosire.bma3.qqofficial.media;

import java.util.Optional;

/** Exact account lookup seam for the media path. */
@FunctionalInterface
public interface OfficialQqMediaAccounts {

    Optional<OfficialQqMediaAccount> resolve(String accountId);
}
