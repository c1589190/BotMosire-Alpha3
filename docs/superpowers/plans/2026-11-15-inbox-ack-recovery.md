# Inbox ACK Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development or executing-plans. Steps use checkbox (`- [ ]`) syntax. Do not commit without explicit user authorization.

**Goal:** Recover acknowledgement of already-injected Inbox items without silently repeating an injection or acknowledging the wrong database or items.

**Architecture:** The Inbox database owns a stable identity; its API explicitly confirms every requested item and validates the identity in the same serialized operation as ACK. The plugin persists the original target, identity, revision, IDs and push watermark together before ACK, blocks fresh injection until resolution, and fetches fresh state after resolution.

**Tech Stack:** Java 21, SQLite/JDBC, Maven/JUnit, Node.js `node:test`, DSH Inbox plugin.

**Spec:** `docs/superpowers/specs/2026-11-15-delivery-safety-design.md` §§5–7.

## Global Constraints

- Never ACK an empty/missing item list by revision; never widen or replace original IDs, revision or database identity on retry.
- No pending record contains message body, credentials, or token; status and logs do not expose them.
- One pending batch at a time; block new injections until resolved; 409, missing/partial IDs, unsupported or corrupt state fail closed.
- stop/detach must stop auto ACK; binding change must not inject an old panel in a different session.
- Do not claim exactly-once between followup and durable local snapshot; failure after side effect before persistence remains uncertain.
- Accept/migrate existing valid v1 state without fabricating historical IDs; manual handling remains necessary for prior lost ACKs.
- No real QQ/DSH, deployment, commits, or remote operations. Do not call a fake/loopback test real validation.
- Pair deployment: stop the old plugin instance completely first, upgrade/start the server, then load the new plugin; old plugin versions do not honor a new lock or the guarded ACK protocol, so overlapping an old-version writer is unsupported. New plugin must visibly fail closed against an old server. Backups/restores that clone inbox.db identity require offline identity rotation while holding the data-directory single-instance lock before reconnecting the plugin. A restored identity alone does not detect cloning or rollback.

## Review Focus

- A 200 response containing only a subset of requested IDs cannot clear the pending record (Tasks 1–2).
- A different database cannot be ACKed even if an item ID collides, including a rotation between read and ACK (Task 1).
- ACK may succeed remotely but response be lost; retry with original IDs accepts already-ACKED but not a purged/missing ID (Tasks 1–2).
- Two lock-aware plugin instances cannot both write state; old-version instances are uncooperative, so isolate the v2 snapshot and require stopping old versions on upgrade (Task 2).
- Corrupted existing state and state writes after a successful followup fail closed without clearing original bytes or reinjecting in-process (Task 2).

---

### Task 1: Stable database identity and complete per-item ACK proof

**Files:**
- Modify: `bma3-inbox/src/main/java/io/mosire/bma3/inbox/core/InboxService.java`, `InboxStore.java`, `SqliteInboxStore.java`, `InboxPanel.java`, `InboxAck.java`, `InboxRuntimeInfo.java`, `bma3-inbox/src/main/java/io/mosire/bma3/inbox/http/InboxHttpServer.java`; tests in `bma3-inbox/src/test/java/io/mosire/bma3/inbox/core/` and `http/`.
- Modify as needed for offline controlled rotation: `bma3-app/src/main/java/io/mosire/bma3/app/Main.java` and application tests.

**Interfaces:**
- Produces: `inboxIdentity` (opaque, durable per database) in panel/runtime response; plugin's ACK request includes exactly `revision`, `itemIds`, `inboxIdentity`.
- Produces: item ACK response fields `ackedItemIds`, `alreadyAckedItemIds`, `missingItemIds`, each an array; complete only if the disjoint union of the first two equals exactly the requested set, without duplicates or extra IDs, and missing is empty. Identity-guarded ACK rejects any missing item without changing any item. Preserve legacy manual callers where possible.
- Identity, revision, frozen-item eligibility and all IDs are validated before mutation in the same serialized service operation; nonmatching identity gives distinct 409 and makes no changes. All item changes and ACK cursor/revision writes commit together in a JDBC transaction or roll back. A present but malformed/empty `itemIds` field must never fall through to revision-only ACK. Internal identity is not exposed as an Agent-managed argument.

- [ ] Write failing tests with real temporary SQLite: database identity persists across reopen and differs for separately created databases; mixed known/unknown request is not falsely complete; re-ACK returns already-ACKED; identity mismatch rejects before any row changes. HTTP integration checks returned ID arrays and 409.
- [ ] Run `./mvnw -q -pl bma3-inbox -am test` and observe expected failure.
- [ ] Implement durable identity in inbox database metadata and expose it on panel/runtime; implement per-item proof and identity guarded ACK, keeping missing/cleaned distinguishable from confirmed by conservative semantics. Implement a documented offline rotation under the existing data-directory single-instance lock; fail closed on identity write failure. If rotation belongs in app tests, run `./mvnw -q -pl bma3-app -am test` too.
- [ ] Add red→green tests for lost-response re-ACK and a purged ID, mixed known/unknown and injected database-write failure causing zero partial changes, malformed `itemIds` with revision, revision 409, identity mismatch during operation, identity rotation and service reopen; rerun affected suites, expect PASS.
- [ ] Add a `frozenRevision` (or equivalent provable pushed eligibility) field to the panel item representation. Guarded ACK must reject unpushed items. Do not change existing push ordering, freeze policy, or manually requested tool ACK behavior. Test mixed frozen/unfrozen snapshots and the frozen-window starvation diagnostic.

### Task 2: Durable plugin ACK state machine and manual recovery

**Files:**
- Modify: `dsh-plugin/lib/inbox.js`, `dsh-plugin/README.md`; existing `dsh-plugin/test/inbox.test.mjs`, `dsh-plugin/test/inbox-command.test.mjs`.
- Create: `dsh-plugin/test/inbox-ack-recovery.test.mjs` and `dsh-plugin/test/inbox-state-safety.test.mjs` as needed.

**Interfaces:** Consumes Task 1 `inboxIdentity`, per-item result arrays, and guarded ACK. Plugin state v2 contains at most one `pendingAck` with inbox ID, durable identity, original target session, original panel and push revisions, exact displayed IDs, plus minimal retry/diagnostic metadata; no body/token. Expose a read-only pending summary and explicit confirmed discard action through existing command/tool surfaces, warning about possible duplicate injection and/or unacknowledged entries.

- [ ] Write failing tests with temporary state file, real plugin client and fake external HTTP/DSH boundaries: inside ACK callback assert original IDs/revisions/identity and persisted watermark+pending record; fail ACK once then retry same request and verify only one followup; pending ACK blocks later panel injection.
- [ ] Run `cd dsh-plugin && npm test` and confirm expected failures.
- [ ] Implement atomic snapshot writes with `0600`; migrate valid v1 to a separate v2 snapshot path so an old binary writing v1 cannot erase v2 evidence. Respect configured state-file paths with a deterministic versioned sibling; never silently treat corrupt or unsupported files as first use. Disable injection if persistence is disabled. Require a nonempty identity and nonempty, unique IDs for every displayed item before followup; missing identity on an old server causes zero followups and zero ACKs. Remember last-seen database identity even with no pending ACK and stop on identity change until explicit handling.
- [ ] Before followup, persist a body-free injection intent with exact target, identity, revisions and IDs; if a crash or followup error leaves that intent, fail closed as uncertain without automatic injection or ACK. On confirmed followup atomically replace intent with pending ACK and watermark; a failed write never ACKs or repeats in-process. Retry original ACK on network/5xx with bounded backoff; 409, missing/partial IDs, missing proof or identity mismatch retain record. Stop retries on stop/detach; after confirmed ACK persist removal and verify a fresh server panel before injection. Do not inject a live GET snapshot as though it were a push: only inject items demonstrably frozen for a newer push, preserving server batching. If an intervening push cannot be safely reconstructed, retain a diagnostic/manual path rather than silently losing or replaying it.
- [ ] Prevent overlapping writers with an actual mutual-exclusion mechanism, not a check-then-rename generation fence: atomically acquire an exclusive per-state-file lock before the first state read, retain it through writes and in-flight operations, release only when safely disposed; if lock cannot be acquired or its ownership is ambiguous, fail closed. A crash-stale lock must not be stolen automatically; document safe manual cleanup after verifying no old instance remains. Test overlap and disposal while an asynchronous write is in progress.
- [ ] Add red→green cases for followup throwing after a possible side effect (no automatic reinjection or ACK; diagnostic uncertainty), state write failure after followup, partial/missing item proof, old server, 409, server identity change, 500/disconnect/response loss, restart, pending new push, v1 migration, corrupt/unsupported state, binding change, stop/detach, and manual confirmed discard. Assert no body or token in persisted bytes. Run `cd dsh-plugin && npm test` and expect PASS.

### Task 3: Cross-component verification and documentation

**Files:** `docs/agents/STATUS.md`, `dsh-plugin/README.md`, relevant Java/Node integration tests.

- [ ] Test server+plugin contract using real local services/fake DSH (no real provider): exact identity and full item proof; repeat ACK after lost response; stale cache is not injected. Record local nature of evidence.
- [ ] Run `./mvnw -q test` and `cd dsh-plugin && npm test`; record exit code, counts, skips, warnings, evidence paths; `packaging/smoke.sh`, real QQ and real DSH are NOT_RUN unless separately authorized.
- [ ] Document SQLite-consistent offline backups including WAL, restore/rotation, pair upgrade and rollback risks (old plugin cannot interpret v2), stale-lock recovery, disabled persistence, write failure and uncertainty windows; update STATUS without overwriting existing historical/user content.
