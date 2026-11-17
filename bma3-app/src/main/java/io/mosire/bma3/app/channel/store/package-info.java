/**
 * Independent SQLite persistence for QQ channel observations and passive evidence.
 *
 * <p>{@link io.mosire.bma3.app.channel.store.QqChannelObservationStore} is intentionally not wired
 * into the composition root, registry or Inbox. It stores only IDs, source/account, board sequence,
 * direction, timestamps, priority, reliability flags, route decisions and matcher-supplied
 * correlation state. It never accepts or writes message bodies, segment URLs/metadata, credentials
 * or body-derived hashes.</p>
 *
 * <p>The package is recovery-oriented: source bindings carry monotonically increasing generations
 * and inclusive board start boundaries, observation inserts and cursor advancement share one
 * transaction, route/item decisions are append-audited and terminal states are never overwritten by
 * replay. A body-free pre-board intent snapshots the account/source, binding generation, effective
 * gate, priority and route/item decision before {@code board.append}; promotion atomically creates
 * the observation with that original snapshot and never re-reads a newer gate or binding.</p>
 *
 * <p>Real QQ integration, Inbox receipt writes and matcher algorithms are outside this package. The
 * router must hold a process-wide routing/binding lock across intent write, board append and intent
 * commit/promote, and must combine the stable route request key with the Inbox receipt for
 * cross-database exactly-once projection.</p>
 */
package io.mosire.bma3.app.channel.store;
