/**
 * Crash-consistent coordination between {@code QqChannelRegistry} and
 * {@code QqChannelObservationStore}.
 *
 * <p>The registry and observation ledger use different SQLite connections and therefore cannot
 * participate in one database transaction. This package deliberately implements a durable,
 * replayable ordering around one shared mutation gate instead of pretending the two stores are
 * atomic. It never stores message bodies, credentials, tokens or body-derived hashes; its journal
 * contains only IDs, binding tuples, generation boundaries and stable error/phase codes.</p>
 */
package io.mosire.bma3.app.channel.binding;
