# Binding self-heal, tool registration and diagnostics plan

**Spec:** [2026-11-16-binding-tool-diagnostics-design](../specs/2026-11-16-binding-tool-diagnostics-design.md)
**Status:** released as test1/test2 on the dedicated test branch; real DSH validation remains NOT_RUN.
**Release:** not authorized by this task; do not commit/push/release without a separate user instruction.

## Tasks

1. **Plugin binding**: persist auto-selected target before followup, patch legacy `pendingPush.targetSessionId=null`, preserve fail-closed mismatch behavior; add regression tests for first bind, restart, and existing-target isolation.
2. **Plugin tool registration**: extract `bma3_inbox` definition, add bounded retry, register in profile/global and agent scope when available, expose `toolRegistration` diagnostics; test delayed tools service and agent scope.
3. **Heartbeat diagnostics**: add sanitized plugin version/tool-registration/deferred/fresh/ACK summaries to heartbeat; keep payload free of message bodies/credentials.
4. **Java MCP diagnostics/control**: extend `PluginHeartbeat`; add `inbox_diagnose` read-only tool and `inbox_plugin_control` restore/remind fallback using `InboxService.requestControl`.
5. **Docs/tests**: update plugin README, tool catalog tests and HTTP heartbeat tests; run focused + full plugin/Java gates; record real DSH validation as `NOT_RUN`.
