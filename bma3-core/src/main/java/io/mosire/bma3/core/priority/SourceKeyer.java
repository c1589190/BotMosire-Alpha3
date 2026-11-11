package io.mosire.bma3.core.priority;

import io.mosire.bma3.core.source.SourceAddress;

/**
 * Maps an opaque {@link SourceAddress} to the two-level key used by configuration rules.
 *
 * <p>Board addresses deliberately carry the bot account in the middle
 * ({@code qq_private/<accountId>/<peerQq>}) because multi-account routing needs it. Configuration
 * keys must not: users and agents reason about {@code qq_private/<peerQq>} and
 * {@code qq_group/<groupId>}. The adapter owns that projection so neither the core nor the Inbox
 * has to guess a segment position.</p>
 */
public interface SourceKeyer {

    /** Configuration key for a source, for example {@code qq_group/955432692}. */
    String keyFor(SourceAddress source);
}
