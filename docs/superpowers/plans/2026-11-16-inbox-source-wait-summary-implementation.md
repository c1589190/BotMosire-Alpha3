# Inbox Source Wait & Summary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add source-scoped hard quiet waiting, an Agent-invoked `inbox_wait_quiet` tool, and hot-reloadable panel summary mode to BMA3 Inbox, with the existing single injection scheduler preserved.

**Architecture:** `InboxService` remains the only push scheduler. A new `InboxWaitPolicy` resolves source-prefix rules; `InboxRuntime` carries the hot-reloadable wait and summary settings; `nextDue()` adds the quiet deadline while `flushPending()` excludes items still held by quiet. `inbox_wait_quiet` is a separate read-only wait loop over the same item state. Panel summary is a rendering choice in `panel()` (one latest entry), so the DSH plugin keeps its existing `entries` renderer and `inbox_get` remains the full expansion path.

**Tech Stack:** Java 21, Maven/JUnit 5/AssertJ, existing SQLite-backed `InboxService`, DSH plugin Node ESM tests, YAML config via `AppConfig`/`ConfigLoader`.

**Spec:** [`../specs/2026-11-16-inbox-source-wait-summary-design.md`](../specs/2026-11-16-inbox-source-wait-summary-design.md)

## Global Constraints

- Java 21; no new dependencies.
- Do not commit or push; the user has not authorized a commit. Leave changes in the working tree and report the exact diff.
- Preserve the existing single injection scheduler and existing batch semantics except for quiet-held items.
- Hard wait factory default: `defaultQuietSeconds=0`, `defaultMaxWaitSeconds=20`; any source can be configured.
- `inbox_wait_quiet` default: `quietMs=2000`, `maxWaitMs=20000`; supported on any source/sourcePrefix; read-only.
- Panel summary must not remove entries from `InboxItem` or `inbox_get`; only the panel rendering may show one latest entry.
- No total character cap; keep `maxCharsPerItem` per item and existing D-044 behavior.
- No content/token/path leakage in diagnostic output.
- Focused tests first; no full suite unless code changes require it.

## Review Focus

- A quiet-held item must remain pending when another source triggers a flush; it must not be frozen or injected early.
- A continuous sender must still be released at `maxWaitSeconds`, even when quiet never elapses.
- `quietSeconds=0` and `enabled=false` must remain behaviorally equivalent to the pre-change scheduler.
- `inbox_wait_quiet` must not ACK, freeze, or mutate revisions; repeated calls must be safe.
- Summary mode must still produce non-empty DSH injection lines and `inbox_get` must remain full.

---

### Task 1: Add wait policy and runtime configuration

**Files:**
- Create: `bma3-inbox/src/main/java/io/mosire/bma3/inbox/core/InboxWaitPolicy.java`
- Modify: `bma3-inbox/src/main/java/io/mosire/bma3/inbox/core/InboxRuntime.java`
- Modify: `bma3-app/src/main/java/io/mosire/bma3/app/config/AppConfig.java:558-678`
- Modify: `bma3-app/src/main/java/io/mosire/bma3/app/config/RuntimePolicy.java`
- Modify: `bma3-app/src/main/java/io/mosire/bma3/app/config/ConfigLoader.java:145-175`
- Modify: `bma3-app/src/main/java/io/mosire/bma3/app/Bma3App.java:205-225`
- Modify: `bma3-app/src/main/resources/config-template/app.yaml:35-55`
- Test: `bma3-app/src/test/java/io/mosire/bma3/app/config/ConfigLoaderTest.java`

**Interfaces:**
- Produces: `InboxWaitPolicy(boolean enabled, int defaultQuietSeconds, int defaultMaxWaitSeconds, List<InboxWaitPolicy.Rule> rules)`.
- Produces: `InboxWaitPolicy.resolve(String source) -> Resolution(int quietSeconds, int maxWaitSeconds, String ruleSource)`.
- Produces: `InboxRuntime.waitPolicy()`.
- Produces: `AppConfig.Inbox.Wait` and `AppConfig.Inbox.Display` summary fields.

- [x] **Step 1: Write failing config tests**

Add two tests to `ConfigLoaderTest`:

```java
@Test
void parsesInboxWaitRulesAndSummaryDisplay() throws Exception {
    // write app.yaml with defaultQuietSeconds=0, defaultMaxWaitSeconds=20,
    // qq_private quietSeconds=8/maxWaitSeconds=20 and mode=summary/summaryMaxCharsPerItem=200
    // assert exact values on config.inbox().wait() and display()
}

@Test
void rejectsInvalidInboxWaitRule() throws Exception {
    // quietSeconds=9, maxWaitSeconds=8 must throw ConfigException
}
```

- [x] **Step 2: Run the config tests to verify failure**

Run: `./mvnw -q -o -pl bma3-app -am -Dtest=ConfigLoaderTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: compile/test failure because `wait()`/summary fields do not exist.

- [x] **Step 3: Implement `InboxWaitPolicy` and config records**

`InboxWaitPolicy` must validate `quietSeconds=0..300`, `maxWaitSeconds=1..600`, reject `quietSeconds > maxWaitSeconds` when quiet is positive, reject duplicate rule sources, and resolve longest matching source prefix with `/` boundary. Defaults: enabled=true, quiet=0, max=20, empty rules.

`AppConfig.Inbox.Wait` should expose:

```java
public record Wait(boolean enabled, int defaultQuietSeconds,
                   int defaultMaxWaitSeconds, List<SourceRule> sources) {
    public record SourceRule(String source, int quietSeconds, int maxWaitSeconds) {}
}
```

Add display fields in this exact order: `mode`, `summaryMaxCharsPerItem`, `summaryAboveItems`; keep compatibility constructors and defaults `auto`, `200`, `8`.

- [x] **Step 4: Parse YAML and build runtime policy**

`ConfigLoader` must parse `inbox.wait.sources` as an array of `{source, quietSeconds, maxWaitSeconds}`; missing section uses `AppConfig.Inbox.Wait.defaults()`. `RuntimePolicy` must carry `AppConfig.Inbox.Wait`; `Bma3App` must construct `InboxWaitPolicy` in `InboxRuntime`.

- [x] **Step 5: Update `ConfigView` and template**

Show `inbox.display.mode`, `summaryMaxCharsPerItem`, `summaryAboveItems`, and all wait rules in `ConfigView`. Add the YAML block from the spec to `config-template/app.yaml`.

- [x] **Step 6: Run config tests**

Run: `./mvnw -q -o -pl bma3-app -am -Dtest=ConfigLoaderTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

### Task 2: Wire quiet into the single injection scheduler

**Files:**
- Modify: `bma3-inbox/src/main/java/io/mosire/bma3/inbox/core/InboxService.java:645-712`
- Modify: `bma3-inbox/src/main/java/io/mosire/bma3/inbox/core/InboxStatus.java`
- Test: `bma3-inbox/src/test/java/io/mosire/bma3/inbox/core/InboxSourceWaitTest.java`

**Interfaces:**
- Consumes: `InboxRuntime.waitPolicy()` from Task 1.
- Produces: `InboxService.waitPolicyView() -> Map<String,Object>`.
- Modifies: `flushPending()` to compute held items from `Instant now`.

- [x] **Step 1: Write failing scheduler tests**

Use short durations (e.g. quiet 250ms, important 40ms, max 1200ms) and existing `InboxService`/`AppendItem` helpers. Tests:

```java
@Test void sameSourceMessagesWaitForQuietAndMerge() { ... }
@Test void quietItemIsNotFlushedByAnotherSource() { ... }
@Test void hardWaitReleasesAContinuouslyActiveSource() { ... }
@Test void quietZeroPreservesExistingBatchBehavior() { ... }
@Test void waitPolicyViewExposesEffectiveRule() { ... }
```

- [x] **Step 2: Run scheduler tests to verify failure**

Run: `./mvnw -q -o -pl bma3-inbox -am -Dtest=InboxSourceWaitTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL on missing behavior/API.

- [x] **Step 3: Implement due/held helpers and flush exclusion**

Add:

```java
private Instant itemFirstSeen(InboxItem item);
private Instant itemLastActivity(InboxItem item);
private InboxWaitPolicy.Resolution waitFor(InboxItem item);
private Instant dueFor(InboxItem item);
private boolean heldForQuiet(InboxItem item, Instant now);
```

`dueFor` uses the formula in spec §3.2. `flushPending(Instant now)` freezes all visible non-frozen pending items **except** `heldForQuiet(item, now)`; keep existing behavior when wait is disabled or quiet is zero. Update `awaitPush` call site.

- [x] **Step 4: Add status/diagnostic fields**

Extend `InboxStatus` at the end with `int waitingForQuiet` and `String nextQuietAt`; add a compatibility constructor and populate them in `status()`. Add `waitPolicyView()` returning enabled/defaults/rules/current held count.

- [x] **Step 5: Run scheduler tests**

Run: `./mvnw -q -o -pl bma3-inbox -am -Dtest=InboxSourceWaitTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

### Task 3: Add read-only `inbox_wait_quiet`

**Files:**
- Create: `bma3-inbox/src/main/java/io/mosire/bma3/inbox/core/WaitQuietResult.java`
- Modify: `bma3-inbox/src/main/java/io/mosire/bma3/inbox/core/InboxService.java`
- Create: `bma3-inbox/src/main/java/io/mosire/bma3/inbox/tools/InboxWaitQuietTool.java`
- Modify: `bma3-inbox/src/main/java/io/mosire/bma3/inbox/tools/InboxTools.java`
- Modify: `bma3-inbox/src/main/java/io/mosire/bma3/inbox/tools/InboxDiagnoseTool.java`
- Test: `bma3-inbox/src/test/java/io/mosire/bma3/inbox/tools/InboxToolsTest.java`

**Interfaces:**
- Consumes: `InboxService` item snapshots and `notifyAll()` from Task 2.
- Produces: `InboxService.awaitQuiet(String source, String sourcePrefix, Duration quiet, Duration maxWait) -> WaitQuietResult`.
- Produces: `WaitQuietResult(boolean settled, String reason, String source, String matchedBy, long waitedMs, Instant lastActivityAt, List<InboxItem> items)`.
- Produces: MCP tool `inbox_wait_quiet` with `source`, `sourcePrefix`, `quietMs`, `maxWaitMs`, `includeEntries`.

- [x] **Step 1: Write failing tool tests**

Add to `InboxToolsTest`:

```java
@Test void exposesInboxWaitQuietWithSourceArguments() { ... }
@Test void waitQuietReturnsEarlyOnceTheSourceIsQuiet() { ... }
@Test void waitQuietReportsStillActiveAtMaxWait() { ... }
@Test void waitQuietDoesNotAckOrFreeze() { ... }
```

- [x] **Step 2: Run tool tests to verify failure**

Run: `./mvnw -q -o -pl bma3-inbox -am -Dtest=InboxToolsTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL on tool count/unknown tool.

- [x] **Step 3: Implement `awaitQuiet` and the tool**

`awaitQuiet` must:

- reject when neither source nor sourcePrefix is present;
- compute `lastActivity` as max item `updatedAt()` across matching source items, or `Instant.now()` if none;
- loop until `lastActivity + quiet <= now` or `start + maxWait <= now`;
- call `wait()` with bounded sleeps and re-check after every notification;
- return matching items (cap 20) and never mutate the store.

Tool defaults: quiet 2000ms, max 20000ms, `quietMs=0` immediate. `includeEntries=true` returns the latest item's bounded entries; false returns item summaries. Error codes: `INVALID_ARGUMENT`, `SOURCE_REQUIRED`, `INVALID_WAIT_PARAMETERS`.

- [x] **Step 4: Register and diagnose**

Add to `InboxTools.all()`. Extend `inbox_diagnose` with `waitPolicy` from `waitPolicyView()` and `waitQuiet` capability metadata (`defaultQuietMs=2000`).

- [x] **Step 5: Run tool tests**

Run: `./mvnw -q -o -pl bma3-inbox -am -Dtest=InboxToolsTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

### Task 4: Panel summary mode

**Files:**
- Modify: `bma3-inbox/src/main/java/io/mosire/bma3/inbox/core/InboxRuntime.java`
- Modify: `bma3-inbox/src/main/java/io/mosire/bma3/inbox/core/InboxService.java:170-180`
- Modify: `bma3-app/src/main/java/io/mosire/bma3/app/Bma3App.java:213-225`
- Test: `bma3-inbox/src/test/java/io/mosire/bma3/inbox/core/InboxPanelTest.java`

**Interfaces:**
- Consumes: `AppConfig.Inbox.Display` summary fields and `InboxRuntime` summary accessors.
- Produces: `InboxRuntime.displayMode()`, `summaryMaxCharsPerItem()`, `summaryAboveItems()`.
- Produces: panel rendering honors `full|summary|auto`.

- [x] **Step 1: Write failing panel tests**

Add tests for:

- full mode unchanged;
- summary mode returns exactly one entry per item and non-zero `omittedMessages`;
- auto mode switches when visible items exceed threshold;
- summary still yields a non-empty rendered line;
- `inbox_get` still returns full entries.

- [x] **Step 2: Run panel tests to verify failure**

Run: `./mvnw -q -o -pl bma3-inbox -am -Dtest=InboxPanelTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL on missing fields/behavior.

- [x] **Step 3: Implement effective panel rendering**

In `panel()`, compute:

```java
boolean summarized = switch (mode) {
    case "summary" -> true;
    case "full" -> false;
    default -> eligible.size() > summaryAboveItems;
};
int maxEntries = summarized ? 1 : maxMessagesPerItem;
int maxChars = summarized ? summaryMaxCharsPerItem : maxCharsPerItem;
```

Pass these to `panelItem(...)`. Keep `PanelItem.entries` non-empty for any non-empty item.

- [x] **Step 4: Update diagnostics and config view**

Expose effective mode and summary thresholds in `inbox_diagnose`/`ConfigView`. Ensure DSH plugin tests use the existing `entries` shape; add a plugin test only if a production JS change is needed (none expected).

- [x] **Step 5: Run panel tests**

Run: `./mvnw -q -o -pl bma3-inbox -am -Dtest=InboxPanelTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

### Task 5: Focused integration and docs

**Files:**
- Modify: `docs/agents/STATUS.md`
- Modify: `docs/superpowers/reports/2026-11-16-hotfix2-feature-survey.md`
- Modify: `dsh-plugin/README.md` only if tool/config documentation needs it

- [x] **Step 1: Run focused combined tests**

Run:

```bash
./mvnw -q -o -pl bma3-inbox -am \
  -Dtest=InboxPushScheduleTest,InboxSourceWaitTest,InboxPanelTest,InboxToolsTest,InboxHttpServerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: rc=0; report test counts.

- [x] **Step 2: Run smoke**

Run: `bash packaging/smoke.sh`
Expected: PASS; record app-image version and `/healthz`.

- [x] **Step 3: Update STATUS**

Record changed files, focused commands/rc/test counts, new defaults (`hard=0s`, agent tool `2000ms`), and `NOT_RUN` for real DSH/LLBot and full suite.

- [x] **Step 4: Do not commit**

Leave changes unstaged and report `git status --short`. User authorization is required before commit/push.

### Task 6: Watchdog diagnostics bundle and media counters

**Files:**
- Create: `bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/tools/QqMediaDiagnostics.java`
- Modify: `bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/tools/QqToolContext.java`
- Modify: `bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/tools/QqMediaViewTool.java`
- Modify: `bma3-watchdog/src/main/java/io/mosire/bma3/watchdog/probe/Probes.java`
- Modify: `bma3-watchdog/src/main/java/io/mosire/bma3/watchdog/tool/WatchdogTools.java`
- Modify: `bma3-app/src/main/java/io/mosire/bma3/app/HealthServer.java`
- Modify: `bma3-app/src/main/java/io/mosire/bma3/app/Bma3App.java`
- Test: `bma3-qqconnect/src/test/java/io/mosire/bma3/qqconnect/tools/QqMediaDiagnosticsTest.java`
- Test: `bma3-watchdog/src/test/java/io/mosire/bma3/watchdog/WatchdogToolsTest.java`
- Test: `bma3-app/src/test/java/io/mosire/bma3/app/HealthServerDiagnosticsTest.java`

**Interfaces:**
- `QqMediaDiagnostics.attempt/success/failure/snapshot/facts`
- `GET /watchdog/diagnostics` returns the supplier map
- `bma3_watchdog_diagnose` returns `{watchdog, bundle}`

- [x] Add content-free counters and wire `qq_media_view`.
- [x] Add `media` probe and diagnostics tool.
- [x] Add HTTP endpoint with Bearer auth.
- [x] Add focused tests for counters, endpoint auth, and tool output.

### Task 7: Media pipeline tool

**Files:**
- Modify: `dsh-plugin/lib/media.js`
- Modify: `dsh-plugin/test/media.test.mjs`
- Modify: `bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/tools/QqMediaViewTool.java`

- [x] Add `classifyMedia()` and `bma3_media`.
- [x] Route image/document to OCR and audio to ASR.
- [x] Return structured `MEDIA_FAILED`/`MEDIA_KIND_UNAVAILABLE` without a key.
- [x] Update hints on `qq_media_view`.
- [x] Add plugin tests; full plugin suite passes.

### Task 8: xvfb high-level tools

**Files:**
- Modify: `bma3-xvfb/src/main/java/io/mosire/bma3/xvfb/XvfbService.java`
- Modify: `bma3-xvfb/src/main/java/io/mosire/bma3/xvfb/XvfbTools.java`
- Modify: `bma3-app/src/main/java/io/mosire/bma3/app/Bma3App.java`
- Test: `bma3-xvfb/src/test/java/io/mosire/bma3/xvfb/XvfbServiceTest.java`
- Test: `bma3-app/src/test/java/io/mosire/bma3/app/XvfbWiringTest.java`

- [x] Add `launchBrowser` with workspace-local profile and http(s) URL validation.
- [x] Add `waitForWindow` with title/any-window and timeout.
- [x] Expose `xvfb_open_browser` and `xvfb_wait`; surface becomes 14 tools.
- [x] Wire configured browser binary from `webconnect.binary`.

### Task 9: QQ friend and generic provider action

**Files:**
- Create: `bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/tools/QqProviderActionTool.java`
- Create: `bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/tools/QqFriendTool.java`
- Modify: `bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/tools/QqToolSupport.java`
- Test: `bma3-qqconnect/src/test/java/io/mosire/bma3/qqconnect/tools/QqToolsTest.java`

- [x] Generic action with read/write confirm and permanent send/upload bypass rejection.
- [x] Friend typed operations for info/remark/delete/like/poke/request_decide.
- [x] Add focused tests for confirmation, blocked send action, and action routing.
