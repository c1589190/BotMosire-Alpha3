# Inbox live recovery hotfix implementation plan

**Spec:** [2026-11-16-inbox-live-recovery-hotfix](../specs/2026-11-16-inbox-live-recovery-hotfix.md)
**Target:** `0.0.7-hotfix1`
**Status:** implemented and release-gated in this session.

## Scope

1. Plugin: make fresh-verification proof failure retry with bounded backoff instead of terminal `done + nextAt=null`.
2. Plugin: allow a coherent live `/panel` snapshot to recover when it carries mixed positive `frozenRevision` values or uniformly omits the field; reject explicit unpushed/partial metadata.
3. Plugin: keep SSE push and `pendingPush` recovery on the strict same-frozen-revision proof.
4. Server tool: include `frozenRevision` in `inbox_get` diagnostics.
5. Docs/tests: add regression coverage for legacy/mixed live snapshots, retry behavior, strict push isolation, and tool output.

## Verification

- Focused plugin tests: `node --test --test-reporter=spec test/inbox-state-safety.test.mjs` → 17/17 PASS.
- Focused Java test: `./mvnw -q -o -pl bma3-inbox -am -Dtest=InboxToolsTest -Dsurefire.failIfNoSpecifiedTests=false test` → rc=0.
- Full plugin: `npm test` → required 157/157 PASS.
- Full Java: `./mvnw -q -o test` → required all tests PASS.
- Packaging: `packaging/smoke.sh` → required PASS.
- Version/release: 0.0.7-hotfix1 tag, assets, checksums, and release download verification.
- Real DSH Agent/QQ/remote deployment: `NOT_RUN` unless separately executed.
