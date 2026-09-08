# Reality check

**Branch:** `grok/m2` (M2 code on this branch; M1 closeout was `grok/m1-closeout` @ `461c9cc`)
**Date:** 2026-09-08
**Against:** `docs/tst-assist-android.md` v2.0 (paste ends at §9.4 S1) and the copies of TST Desk that this repo actually contains.

This is the document the previous session named and did not write. It is a claim ledger, not a brochure. A line that says **held** is held by a test in this tree. A line that says **code, untested on a phone** is implemented and CI-green and has never touched a Pixel. A line that says **not built** is a later milestone, even if a class exists.

TST Desk (`ThatSimpleTech/TST-Desk`) is a private repository. This check could not open `tstd` source. What could be checked is what this repo copied in: `config.yaml` (claims to be the desktop file plus `device` and `home`), `tstd-protocol-fixtures.json`, the v1/v2 audit SQL (comments name `audit.py` line ranges), the redactor pattern list (comments name `logging.py`), and the wire client comments that cite `client.ts` line numbers. Those copies are taken as the desktop contract. They are not a substitute for a line-by-line read of live tstd.

---

## 1. The promises

| Promise | Plan | What is actually here | Verdict |
|---|---|---|---|
| No account | Only a provider key | No sign-up path. The only credential UI is a password field that writes to Keystore. | **held** (static) |
| No server of ours | Bind loopback / Tailscale only | `BindRules` refuses unspecified (`0.0.0.0`, `::`). Loopback always; one Tailscale extra. No process in this repo binds a socket for inbound tstd attach. | **held for outbound bind rules**; the device-side observe/act socket is **not built** (TM-008) |
| No subscription | Meter; device mode $0 | Meter prices from `config.yaml`. `device` preset is $0. Device *runtime* is not built, so the $0 path cannot run. | **held as data**; device mode is a preset, not a planner |
| No telemetry | Every outbound host named in config | `Endpoints` is the only `OkHttpClient` factory in `core`. `NoConnectionOutsideEndpointsTest` source-scans core. `AppNoConnectionOutsideEndpointsTest` (TM-023) walks `app/src/main/kotlin` for the same socket markers plus silent Wi-Fi/BT and `ACTION_CALL` / SMS-send APIs. App code goes through `Graph.endpoints`. | **held** |
| Key never touches disk in plaintext | Keystore + redactor | AES-256-GCM in Android Keystore; only IV+ciphertext in prefs. Redactor covers `sk-`, GitHub PATs, `AKIA`, PEM headers. The wrapping key is **not** `setUserAuthenticationRequired`, so the process can decrypt without a biometric. Plaintext exists in RAM and in the EditText until Save clears it. | **held for disk**; not a hardware-auth lock |
| Agent cannot rewrite rules | Boundary refuses rules files | `RulesBoundary` refuses `ASSISTANT.md`, `CHARTER.md`, `policy.yaml`, `AGENTS.md`, `profiles/`, `rules/`, plus `..` and symlinks. Nothing in the task loop offers a write tool, so the boundary is currently unused by the runner. | **held as a gate**; no write tool exists to attack |
| Screen / notification text is data | Delimited block, grammar only | `ObservationFormatter` quotes labels as one escaped token; injection suite proves a forged `OBS>>` / `GOAL:` inside a message does not become structure. Parser: first non-empty line, closed verb set, unknown hint refused. | **held** (254-test suite, including `InjectionSuiteTest`) |
| Approval on the driven phone | Overlay + notification; timeout = deny | `AndroidApprovalSurface` + `OverlayCard` (`TYPE_ACCESSIBILITY_OVERLAY`) + notification actions. Timeout is `withTimeoutOrNull` → false. Not run on a phone. | **code, untested on a phone** |
| Voice stays on device | On-device STT/TTS | `OnDeviceListen` uses `createOnDeviceSpeechRecognizer` only; cloud constructor is source-scanned out. TTS is the phone's engine. `AssistRecognitionService` stays an error stub. sherpa-onnx not built. | **code, M4 v1** |
| Conversations plaintext, owner-only | App-private storage | No conversation store exists yet. Audit rows are app-private SQLite. | **not built** (memory / chat) |

---

## 2. Architecture, against the diagram

The plan's diagram has three planners (Home tstd / Cloud key / Device) and a phone that is observer, hinter, guard, executor, plus voice and memory.

| Box on the diagram | In this tree | Notes |
|---|---|---|
| Observer (a11y) → Hinter → Guard → Executor | Yes | `TreeWalker` / `TreeObserver` / `HintTable` / `PolicyEnforcer` / `NodeExecutor` / `TaskRunner` |
| Policy pack enforcer | Yes | `policy/policy.yaml` + Kotlin walk + `policy/cases.yaml` |
| Meter | Yes | Cache-honesty: unreported cache is billed as uncached, not as a discount |
| Audit (SQLite) | Yes | Desktop v1+v2 SQL copied; v3 adds `actions`, `approvals`, `device_id`, `planner_mode`, BEFORE UPDATE/DELETE triggers |
| Cloud-key planner | Yes | `CloudPlanner` + `ProviderClient` through `Endpoints` |
| Home as vLLM / OpenAI-compat on the box | Wired | Same `CloudPlanner`. Default home host is `llm.ezer-server.ts.net` / `ezer-chat` (TM-016). |
| Home as tstd daemon (classifier, cards, audit upstream) | Client model only | `TstdClient` speaks the fixture protocol. **Not** attached to `TaskController`. Approvals do not round-trip to tstd. |
| Device brain (LiteRT-LM) | Preset only | `Planners` returns a plain sentence: not built |
| Voice (sherpa-onnx) | Partial | On-device SpeechRecognizer + TTS (TM-026). Keyword spotting / Whisper / Kokoro not built |
| Memory / embeddings | No | |
| CHARTER.md schema | Filename reserved | Boundary refuses the name; no schema validator |
| Instruction inspector UI | Partial | Settings is grants + provider (OpenRouter / LAN / custom URL + model) + goal, not a stack/token inspector |
| tst-cu-mcp Android target | No | TM-008 |
| Overlay highlight + card | Yes | Highlight colour is a hard-coded gold stroke; card is a framework LinearLayout |

Default preset is `tst-default` (OpenRouter, Kimi K3). That is Cloud-key, not Home. The plan's "build order" listed Home first. The code built Cloud-key first because it is the path that does not need a daemon. Recorded as TM-010.

---

## 3. Policy pack vs plan §4

| Plan | Code | Verdict |
|---|---|---|
| Tier 0 silent: observe, scroll, back, home, wait, open allowlisted, read notifs | `read-only` + `open-allowlisted` | **held**. `recents` and `more` and `done`/`ask` are silent too (TM-003 for nav). |
| Tier 1 once/task: type, tap non-sensitive, media, alarms | `in-app-control`, `notif-open`, `screen-ask` | **held**. Grant is requested on first Tier 1 action, not at task start. Same card, later. |
| Tier 2 every time: send, call, pay, delete, install, share, settings, notif reply, leaving the goal's apps | `sensitive-control`, `settings-change`, `notif-reply`, `outside-goal-apps` | **held** as rules. Sensitivity is label-word + resource-id substring, EN and ES. |
| Refused: password, secure, un-allowlisted, rules, shell | `password-field`, `secure-screen`, `keyguard-locked`, `app-not-allowlisted` | **held**. There is no shell verb in the grammar. |
| One Tier 2 per turn | `max_tier2_per_turn: 1` and `Gate.TURN_LIMIT` | **vacuous in the runner**. `TaskRunner` resets `tier2ThisTurn` on every new observation, and the grammar is one action per observation. The gate cannot fire. Harmless given one-action-per-turn. |
| Timeout is deny | 45 s in the pack; surface uses it | **held** in code (TM-009). tstd default (wait forever) is not used on the phone. |
| Intent lock | `goalApps` + `outside-goal-apps` → card; confirm admits the app | **held**. `GoalApps.infer` is a word match against allowlist labels; a goal that names no app gets an empty set, so the first action in any app is a card (TM-014). A planner that emits the app set is M2. |

Conformance: `policy/cases.yaml` is loaded and every rule has a case (`PolicyConformanceTest`).

---

## 4. Grammar and observation vs plan §7

Closed verbs match the table. `drag` is implemented (gesture). `screen ask` captures a JPEG and posts it to the active brain (TM-027).

Parser: first non-empty line; one layer of backticks stripped; unknown verb/hint is an error; one repair then stop. Matches the plan.

Observation block markers and quoted labels match the sample. Coverage detector exists; below-threshold does **not** yet fall through to a labeled screenshot (C1 is M6). Fingerprint is hashed identities; stale screen refuses execution (C3). Hint table quarantines a freed number for one step (C3). Page size 60 with `more`.

Password fields: walker never copies `text` when `isPassword`; executor refuses `ACTION_SET_TEXT` on password nodes even if policy were bypassed.

---

## 5. Wire protocol vs the fixtures

`core/src/test/resources/tstd-protocol-fixtures.json` is the contract. `EventGate` drops unknown kinds and counts them; malformed frames are counted; `ClientMessages` covers hello / attach / detach / approve / deny / always-allow / user_message. `SequenceTracker` handles accept / duplicate / gap (gap re-attaches).

`TstdClient` opens the socket through `Endpoints.webSocketClient`, so a host not in config fails before the upgrade (last commit of the previous session).

This is a **client model**. It is not Home mode. Nothing in `TaskController` sends an observation to tstd or waits for an action event.

---

## 6. What the previous session still owed

| Item | Status on this branch |
|---|---|
| `docs/reality-check.md` | This file |
| Plan review / adversarial review | Section 8 below. The reviews never landed on the draft PR. |
| Merge / un-draft PR #1 | Not done. This work is on `grok/m1-closeout`, not the Claude branch. |
| Run on a phone | In progress. Debug APK sideloaded; accessibility restricted-settings unlocked. Provider picker is TM-015. |

Core tests on `grok/m2`: **386 pass, 1 skipped** (`./gradlew :core:test`, JDK 17). The skip is `ModelSuiteTest.liveSuiteIsOptIn` (`-Dassist.liveSuite=1`). One Endpoints redirect test has flaked on MockWebServer timeout and passed on retry.

---

## 7. Copied-from-desktop claims that this repo cannot prove

These comments assert byte-for-byte identity with tstd. They are believed, not re-verified against live tstd:

- `AuditSchema` v1 = `audit.py` `_SCHEMA_V1` lines 45–123
- v2 = `_SCHEMA_V2` lines 131–146 (TD-1412)
- Redactor patterns = `logging.py` `SECRET_PATTERNS` (five shapes; UI `redact.ts` floors are deliberately *not* copied)
- `config.yaml` presets `tst-default` / `budget` / `local` / `vllm` = desktop `config.yaml`
- Wire fixtures = TST Desk `protocol-fixtures.json`
- `ClientMessages.send` comment cites `client.ts:218-236`

If desktop tstd has moved since these were pasted, the phone is a snapshot, not a live twin. Re-copy before calling Home mode done.

---

## 8. Adversarial findings

### Fixed on this branch

1. **Drag hold landed at (0, 0).** `NodeExecutor.gesture(holdFirst = true)` built the hold path from an empty `RectF` (`moveTo(r.left, r.top)` before `computeBounds` filled it). A `drag 2 to 5` would long-press the origin of the screen, then swipe. Hold now uses `PathMeasure` at offset 0. (TM-011)

2. **Typed text stayed on the clipboard.** The paste fallback called `setPrimaryClip` and never cleared it. A later paste in another app could replay the last `type`. After a successful paste the clip is cleared. (TM-012)

3. **Settings copy claimed Device mode.** "no provider key (Device mode only)" — Device mode is not built. Copy now says cloud/home calls will refuse. (TM-013)

4. **TM-014 closeout.** Empty goal-apps; sensitive before outside-goal; password is any act; IME/overlay dropped; cards show payloads; `more`/`wait` skip the loop detector; kill after `planner.next`; no redirect hop; autofill off on the key field. Details in `docs/decisions.md`.

### Open, not silently "fine"

4. **`TstdClient` is orphaned.** Home-as-tstd is a protocol toy. EZER home talks OpenAI-compat to `llm.ezer-server.ts.net` (home-direct, TM-010 / TM-016). That is not tstd.

5. **No-telemetry scan now covers `app/`.** `AppNoConnectionOutsideEndpointsTest` (TM-023) walks `app/src/main/kotlin`. Finding closed on `grok/m2`.

6. **`GoalApps.infer` is fallback only.** Production uses `Planner.planGoalApps` (TM-019). Infer still empty when the goal names no allowlisted label (TM-014).

7. **Keystore wrapping key is unlocked to the process.** Matches "never plaintext on disk". Does not match a mental model of "unlock the phone to use the key" except when the OS invalidates the key. The `SecretStoreLockedException` path is mostly dead.

8. **Assistant role is a stub.** Long-press power opens `MainActivity` and hides. `onHandleAssist` is unused. Recognition errors. That is honest, but Play policy for `BIND_VOICE_INTERACTION` plus an accessibility service will need a real disclosure before any public listing.

9. **Highlight colour is `Color.rgb(255, 196, 0)`.** Fine on a phone; it is the one gold in the product. Leave it — it is the "this will be tapped" mark, not brand chrome.

10. **Clipboard paste still flashes the typed text to Android 13+ clipboard UI** for a moment before clear. Unavoidable without `FLAG_SENSITIVE`. Acceptable; the durable leak is gone.

11. **Plan document is truncated** at §9.4 S1. Injection-suite *code* exists; plan §10–§17 and the appendices were never supplied. Do not invent them.

### Solid

- Closed grammar + observation quoting + injection suite. This is the actual safety core. It does not depend on the model being careful.
- Policy pack as data, first-match-wins, cases for every rule.
- Endpoints as the one outbound door in core, including the wire client.
- Audit v1/v2 replay plus triggers that abort UPDATE/DELETE.
- Kill switch polled before the step and again after the approval wait.
- Stale-fingerprint check after the human has had time to tap around.
- Executor re-finds by identity, refuses gone/invisible nodes, never taps "whatever is there now".
- `open` resolves through the allowlist; the label is not a command string.

---

## 9. Milestone truth

M2, as this repo can claim it on `grok/m2`:

- Closed verbs for calls, texts, alarms, timers, calendar, contacts, navigation, flashlight, DND, brightness, volume, media, WhatsApp/Spotify/Gmail, and `qs`: **written**. Partner miss is an honest error, not tree-driving.
- Spend cap → `Outcome.Paused`; day chip hydrates from `audit.model_calls`; planner-emitted goal apps; family-mode host block; end-state validator; model profiles + offline suite; `app/` socket scan: **written**.
- Live three-brain suite: **opt-in**, not CI. Do not claim it ran.
- Pixel 7 Pro column: **unverified**. CI green is not M2 done for P2/P4/direct APIs.

M1, as this repo can claim it today:

- Grammar, observation, policy, loop, injection, meter, audit, redactor, steering boundary, Endpoints, OpenAI-compat planner, tstd *client model*, Android observer/executor/approval/kill/keystore/settings: **written**.
- Core tests: **green**.
- `assembleDebug`: **green on GitHub's runners** (previous session); not rebuilt here (no SDK).
- On a Pixel: **never**.
- P3 keyguard matrix, P7 banking, P8 FLAG_SECURE, C2 gestures on device, C3 twenty multi-step tasks, C7 login screen: **unverified**. Those are the M1 *verify* column, and they need the 7 Pro.

M2 code is on `grok/m2` (intents and direct APIs, live meter chip with day hydration, spend-cap pause, planner-emitted goal apps, family host block, end-state validator, model-profile suite, app socket scan). Pixel 7 Pro verify is still **unverified**. The three-brain suite in CI is the **offline fixtures**; live calls are opt-in (`-Dassist.liveSuite=1`) and are not claimed as having run. Home as tstd, Device brain, sherpa-onnx voice, memory: **not this branch**. `screen ask` (M6 v1) posts a JPEG to the active brain (TM-027). Do not invent plan §§10–17.

---

## 10. How to take this

The draft PR is a real M1 *foundation*. It is not a drop-in Gemini replacement and it does not say it is, except in the plan's title. The previous session died after CI went green, with this file listed as "still landing" and two reviews in flight. Those reviews are this document. The code fixes above are the ones that were cheap and real. Everything else waits for a phone or for TST Desk to be readable.
