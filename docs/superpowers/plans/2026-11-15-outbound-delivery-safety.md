# Outbound Delivery Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development or executing-plans. Steps use checkbox (`- [ ]`) syntax. Do not commit without explicit user authorization.

**Goal:** Persist conservative, inspectable QQ outbound delivery outcomes before and after dispatch, and prevent unsafe automatic retries.

**Architecture:** Extend the SQLite board with delivery metadata and transactional terminal updates. Text and attachments use the same classification rules, record PENDING before dispatch, and recover unresolved PENDING only after the app holds its data-directory lock. History exposes status without exposing provider exception contents.

**Tech Stack:** Java 21, SQLite/JDBC, Maven, JUnit 5, AssertJ; OneBot 11 via existing WebSocket client.

**Spec:** `docs/superpowers/specs/2026-11-15-delivery-safety-design.md` §§4, 6, 7.

## Global Constraints

- Message board is the business source of truth; outbounds are persisted before provider side effects.
- UNKNOWN may have been sent; no automatic resend, fallback or dependent message following it.
- Provider nonzero status is not proof of no dispatch; timeouts do not cancel remote work.
- Atomic terminal state and external ID; legacy outbound is LEGACY_UNKNOWN, even with external ID; inbound status is not applicable.
- No body, credential or raw provider exception text in logs; route by account, fail closed.
- Recovery after the app's existing single-instance lock and before accepting traffic; never during a live operation.
- No real QQ, DSH, deployment, commits, or remote changes in this plan; mock/local tests are not real validation.
- Upgrade requires stopping all writers and a coordinated SQLite-consistent backup of bot.db and inbox.db including WAL; never mix old and new writers. Restore the compatible old binary and matching pre-upgrade backup for rollback; new state cannot be represented by old writes. Do not claim unconditional power-loss durability.

## Review Focus

- A legacy outbound with external ID stays uncertain after two opens; a new inbound has no delivery state (Task 1).
- An outcome update failure after a provider success never reports durable success or dispatches again (Task 2).
- A late successful provider response after timeout cannot update UNKNOWN or trigger a second request (Tasks 2–3).
- Attachment UNKNOWN prevents both alternative upload attempts and associated text (Task 3).
- Recovery never marks active work UNKNOWN during a normal store open; only app startup after locking invokes it (Task 4).

---

### Task 1: Board schema, transitions and migration

**Files:**
- Create: `bma3-core/src/main/java/io/mosire/bma3/core/message/DeliveryStatus.java`, `Delivery.java`; `bma3-core/src/test/java/io/mosire/bma3/core/store/SqliteDeliveryMigrationTest.java`.
- Modify: `bma3-core/src/main/java/io/mosire/bma3/core/message/Message.java`, `bma3-core/src/main/java/io/mosire/bma3/core/store/MessageBoardStore.java`, `SqliteMessageBoardStore.java`; relevant core tests.

**Interfaces:**
- Produces: `DeliveryStatus { PENDING, SENT, FAILED_BEFORE_SEND, UNKNOWN, LEGACY_UNKNOWN }`; `Delivery(String operationId, int partIndex, int partTotal, DeliveryStatus status, String errorCode)`; nullable `Message.delivery()` (null for inbound).
- Produces: `AppendResult beginOutbound(InboundMessage message, String operationId, int partIndex, int partTotal)`; `boolean finishOutbound(long localSeq, DeliveryStatus status, String externalMessageId, String errorCode)`; `int recoverPendingOutbound()` on `MessageBoardStore`.
- Preserve existing Message constructors; non-send existing append remains compatible. New QQ send paths MUST use beginOutbound. No global recovery in a store constructor.

- [ ] Write a failing migration test using an old-schema temp SQLite database containing one inbound and one outbound with external id `777`; after open assert outbound LEGACY_UNKNOWN, same seq and external id, inbound null; repeat open and assert unchanged. Check via actual history reads. Verify failure is for missing migration behavior.
- [ ] Run `./mvnw -q -pl bma3-core -am test`, expect the new assertion to fail.
- [ ] Add nullable delivery columns and safe, repeatable legacy backfill. Map fields in every SELECT path and returned Message; never change local_seq. Persist new PENDING and part metadata in the same INSERT transaction. Finish only an existing PENDING outbound via one atomic update; update status and ID together, reject invalid transitions and invalid metadata. Error codes must be bounded categories, never raw errors.
- [ ] Add and run focused red→green cases for PENDING→SENT with/without ID, atomic update failure, duplicate terminal update, PENDING→UNKNOWN recovery, and inbound unaffected; run `./mvnw -q -pl bma3-core -am test` and expect PASS.

### Task 2: Text sends and shared result classification

**Files:**
- Create: `bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/tools/QqDelivery.java`, `bma3-qqconnect/src/test/java/io/mosire/bma3/qqconnect/tools/QqSendSafetyTest.java`.
- Modify: `bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/tools/QqSendSupport.java`, `QqDeployerNotifier.java` and affected QQ tests.

**Interfaces:**
- Consumes: Task 1 board API.
- Produces: package-private shared attempt classification and persistence helpers used by Task 3. Use a 5-second text wait and bounded attachment wait of 60 seconds. Exact helper signatures are implementation-private.

- [ ] Write tests asserting board PENDING exists when a fake provider receives the first request; 3 chunks, second receives failed/unknown result → 2 provider calls, 2 rows, same operation ID, part indices 1 and 2 of total 3; nonzero response → UNKNOWN; success without message ID → SENT. Use real board and fake external provider.
- [ ] Run `./mvnw -q -pl bma3-qqconnect -am test` and verify failures are due to missing behavior.
- [ ] Persist one fresh server-generated operation ID per call, begin each actual chunk before dispatch, apply bounded wait, atomically finalize; on terminal write failure return an explicitly non-durable result and stop, never resend. Provider response errors and post-dispatch exceptions are UNKNOWN. Prove any FAILED_BEFORE_SEND classification before applying it; do not infer from an ambiguous asynchronous failure. Remove raw provider messages from returned/logged diagnostics.
- [ ] Add red→green tests for timeout and late success, initial board write failure preventing provider invocation, terminal write failure, and distinct operations with identical text; run QQ suite and expect PASS.

### Task 3: Attachments and dependency-safe continuation

**Files:**
- Modify: `bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/tools/QqConversationSendFileTool.java`, `QqConversationSendImageTool.java`; QQ tests, adding `QqAttachmentSendSafetyTest.java` if useful.

**Interfaces:** Consumes Tasks 1–2. Existing providerPath-only use remains supported; do not introduce new configuration unless necessary.

- [ ] Write tests: upload base64 response lost or ambiguous nonzero ⇒ UNKNOWN, exactly one provider action and zero fallback paths; optional associated text is not sent; independently selected providerPath succeeds; image never completes ⇒ bounded UNKNOWN and persisted row.
- [ ] Run QQ suite and verify failures arise from current unsafe retry or missing status.
- [ ] Persist PENDING before dispatch, use Task 2 classification and bounded waiting; advance a file fallback only on demonstrable no-side-effect FAILED_BEFORE_SEND; otherwise stop. Send associated text only after durably recorded SENT. Maintain account isolation.
- [ ] Add tests for missing external ID and late response; run `./mvnw -q -pl bma3-qqconnect -am test` and expect PASS.

### Task 4: Read surface and locked-start recovery

**Files:**
- Modify: `bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/tools/QqToolSupport.java`, `bma3-app/src/main/java/io/mosire/bma3/app/Bma3App.java`; appropriate QQ and app tests.

**Interfaces:** Consumes Task 1 delivery metadata and `recoverPendingOutbound()`.

- [ ] Write tests: history/get shows status and chunk association for outbound and no delivery data for inbound; startup of locked app converts pre-existing PENDING to UNKNOWN before any request is served; merely opening a store does not recover active rows.
- [ ] Run `./mvnw -q -pl bma3-app -am test` and observe expected failures.
- [ ] Expose delivery metadata on existing message read surfaces, and invoke recovery after the data-directory lock and before provider/service startup; report count only, not body.
- [ ] Run `./mvnw -q -pl bma3-app -am test` and expect PASS; then run full `./mvnw -q test`, record counts and skips.
