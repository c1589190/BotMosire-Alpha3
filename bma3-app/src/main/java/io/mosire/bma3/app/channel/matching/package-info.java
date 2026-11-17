/**
 * Pure, deterministic cross-channel corroboration kernel for Plan Phase 10.
 *
 * <p>This package has no database, Inbox, network, scheduler or composition-root dependency.
 * Callers supply active channel bindings plus a complete recomputable
 * {@link io.mosire.bma3.app.channel.matching.MatchInput} snapshot and persist the returned
 * states/pairs/counts themselves. Results expose only source/account, opaque observation/message
 * IDs, timestamps, states and counts; input segment text, URLs, metadata, credentials and
 * body-derived hashes never appear in result output.</p>
 */
package io.mosire.bma3.app.channel.matching;
