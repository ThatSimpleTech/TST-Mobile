# Decisions

Numbered, dated, never edited after the fact; a reversal is a new entry. Same habit as
TST Desk's `DECISIONS.md`. TM = TST Mobile.

## TM-001 (2026-09-07) A native Kotlin client of tstd, which TST Desk's TD-3701 rejected

TST Desk decided the phone is a browser client of the same AppShell and that a native phone
app is out of scope (TD-3701, E49). This repo builds a native assistant surface anyway,
because the product is different: a default-assistant-role app that drives the phone through
the accessibility tree cannot be a web page. Home mode still attaches to tstd over its
existing WebSocket protocol as a client; nothing in tstd is forked. This is a scope change
and is recorded as one; TST Desk's decision stands for TST Desk.

## TM-002 (2026-09-07) The policy pack is rules-only data; tstd's classifier is not reused as code

tstd's decision classifier is fifteen Python predicates plus a model call for ambiguous
tool calls, keyed on paths, hosts and mutation flags. None of that maps to phone verbs. The
plan's own hedge ("if the current classifier turns out to be model-assisted anywhere, that
part is rewritten as rules first") applies: `policy/policy.yaml` is an ordered rule table
over facts the phone can compute without a model, and `policy/cases.yaml` is the shared
conformance suite. Mapping to the desktop axis: silent ≈ A/auto, every ≈ B/ask,
refused ≈ never. Tier "once per task" has no desktop equivalent.

## TM-003 (2026-09-07) Global navigation is never app-scoped

`back`, `home` and `recents` are the way out of any app, including one that is not on the
allowlist or outside the goal set. They are refused on the keyguard and on secure screens
like everything else. Without this a task that lands in the wrong app could only `ask`.

## TM-004 (2026-09-07) Append-only audit is enforced by SQLite triggers

TST Desk enforces INSERT-only by a source-scan test; the file itself is writable. The phone
adds BEFORE UPDATE and BEFORE DELETE triggers that abort. This is stronger than the desktop;
a future desktop migration that drops a table would have to drop its triggers first. The
desktop schema (v1, v2) is replayed verbatim; the phone's tables are a v3 migration on top.

## TM-005 (2026-09-07) Framework APIs only in the app for now

No androidx, no Compose in our own sources. The overlay card, notifications, tile and
settings screen use framework views. Reason: the sources can then be type-checked on a
machine with no Android SDK (`tools/androidcheck` against Robolectric's framework jar), and
the dependency surface a person has to audit stays small. OkHttp's Android artifact brings
androidx.annotation and androidx.startup transitively, so `android.useAndroidX=true` is set;
that is the whole AndroidX footprint. Compose can come when the assistant surface grows.

## TM-006 (2026-09-07) Cleartext only to `*.ts.net`

tstd serves plain `ws://`; Tailscale is the transport security. The app's network security
config refuses cleartext everywhere except MagicDNS names, so Home mode is configured with
the box's MagicDNS name, never a bare tailnet IP.

## TM-007 (2026-09-07) `screen ask` is Tier 1 (once per task), not Tier 0

The plan's Tier 0 list does not name it. A screenshot leaving the phone to a cloud vision
model is a privacy event; one approval at task start covers it. In Device mode it is local
and the same tier costs nothing.

## TM-008 (2026-09-07) No tst-cu-mcp Android target in this iteration

The plan's Appendix C was not supplied, and the sidecar's backend contract is pixels and
global coordinates with a single-node `hit_test`; a hint grammar does not ride it. The
phone's own observe/act path is built directly on the accessibility tree. A device-side
socket for tstd is future work and will reuse `core/net` (bind rules, token, handshake).

## TM-009 (2026-09-07) Timeout is deny, on the phone, always

tstd only denies an approval on timeout when the workspace policy sets
`approval_timeout_seconds`; by default it waits forever. The phone's approval surface owns
its own timer (`approval_timeout_seconds` in the policy pack, 45 s) and answers deny itself,
in every mode. In Home mode that means the phone sends an explicit deny to tstd.

## TM-010 (2026-09-07) Cloud-key shipped first; Home-as-tstd is a client, not a planner

The plan's build order is Home, then Cloud-key, then Device. This repo shipped Cloud-key
(`CloudPlanner` + `ProviderClient`) because it does not need a daemon, and it reused that
planner for any OpenAI-compatible `base_url`, including the `home` and `vllm` presets.
`TstdClient` is a fixture-faithful WebSocket client and is not wired into `TaskController`.
A settings toggle that says "Home" and then posts `/chat/completions` at the box is the
vLLM path, not tstd. Do not blur them. Device mode stays an honest refusal (already in
`Planners`).

## TM-011 (2026-09-07) Drag hold is the path start, not the screen origin

`NodeExecutor`'s `holdFirst` stroke constructed an empty `RectF` and `moveTo`'d its (0, 0)
before `computeBounds` ran. `drag` would long-press the origin. The hold point is the
`PathMeasure` position at offset 0 of the drag path.

## TM-012 (2026-09-07) Paste fallback must not leave the typed text on the clipboard

The `ACTION_SET_TEXT` fallback put the model's `type` string on the primary clip and left
it there. A later paste in another app would replay it. After a successful paste the clip
is cleared. The Android 13 clipboard toasts for a moment; the durable leak does not.

## TM-013 (2026-09-07) Status copy must not claim a planner that is not built

The settings screen said "no provider key (Device mode only)". Device mode is a preset and
a refusal. The line now says cloud/home calls will refuse. The same rule applies to any
future Home-as-tstd toggle.

## TM-014 (2026-09-07) Remaining M1 review closeout

A second pass after TM-010–013. None of these loosen a promise; they close holes the first pass named or missed.

- **Empty goal-apps.** `GoalApps.infer` returning the whole allowlist for "reply to Maria" made the intent lock a no-op at the allowlist boundary. An unnamed goal now gets an empty set: the first app-scoped action is a card, and confirming admits that app. A planner that emits the set is M2.
- **Sensitive before outside-goal.** First-match used to treat Send in Gmail during a WhatsApp task as "admit this app", not as Send. `sensitive-control` and `settings-change` now sit above `outside-goal-apps`. `archive`/`archivar` join the sensitive labels.
- **Password is an act, not a pair of verbs.** The rule is `target_password + acts`. The walker never copies `contentDescription` into the label (always `"Password"`). IME and overlay windows are not observed.
- **Cards show the payload.** `type`, `notif reply` and `screen ask` put the text on the card. A person approving a reply must see what will be sent.
- **`more`/`wait` are not loops.** `loop_repeat_limit: 3` would stop a 150-row list on the third `more`. Paging and waiting are skipped. Identical `tap`/`scroll` still stop.
- **Kill after think.** The switch is polled immediately after `planner.next`, not only after the approval wait. `CancellationException` is rethrown from the foreground service.
- **No redirect hop.** `Endpoints` sets `followRedirects(false)` / `followSslRedirects(false)`, so a 302 to a stranger never opens a socket. A network interceptor cannot refuse before `ConnectInterceptor` has already connected; not following is the actual door.
- **Autofill off on the key field.** The Keystore EditText is `IMPORTANT_FOR_AUTOFILL_NO`.

## TM-015 (2026-09-07) Settings names the model and the endpoint

The settings screen only stored a Keystore key. The brain slug and `base_url` lived in shipped `config.yaml` (`moonshotai/kimi-k3` on OpenRouter), so a person could not pick a model, a LAN Ollama, or their own OpenAI-compatible server.

The screen now has three modes — OpenRouter, Local/LAN, Custom server — plus a model id and a base URL. Save writes those to app prefs and overlays a `user` preset onto the shipped YAML: same prices as the template (`tst-default` / `local` / `home`), one slug and one host for all three router tiers. `Endpoints` is rebuilt from that host, so the allowlist is exactly what they typed. Local and custom do not require a key; OpenRouter still does.

Cleartext at the OS layer is permitted so `http://192.168.x:11434/v1` reaches a box on the LAN. That is a BYOM exception to TM-006, which still stands for Home/tstd (prefer a MagicDNS name). The real door remains `Endpoints`: a host that is not on the active preset is refused before a socket opens. On-device LiteRT is still an honest refusal.

## TM-016 (2026-09-07) The phone is EZER

The working name was TST Assist. The product on the phone is EZER: default brain is the home box (`https://llm.ezer-server.ts.net/v1`, slug `ezer-chat`, credential `ezer`). OpenRouter and LAN Ollama remain BYOM fallbacks. The Android package stays `com.thatsimpletech.assist` so a sideloaded install does not drop Accessibility.

This is the home-direct path (OpenAI-compat to LiteLLM/vLLM on the tailnet), not tstd's WebSocket (TM-010 still holds). Personas, Mem0, and tstd attach stay unbuilt.

## TM-016b (2026-09-08) Closed verbs for non-tree actions

M2 non-tree work (calls, texts, alarms, timers, calendar, contacts, navigation, flashlight, DND, brightness, volume, media, WhatsApp/Spotify/Gmail, Quick Settings) enters the same closed grammar as tree driving: one new `Verb` / `Action` per line, quoted payloads, `render()` round-trips through `ActionParser`, empty `hints` so a node is not required. A generic `do "…"` is refused; unknown words stay `unknown verb`.

Adam accepted this table and the §15 defaults that constrain it:

- **Dial-only.** `call` prepares `ACTION_DIAL`. No `ACTION_CALL` in M2.
- **SMS draft.** `text` prepares `ACTION_SENDTO`. No `SmsManager` in M2.
- **Calendar UI insert.** `event` opens the insert UI. No silent `ContentResolver.insert`.
- **Wi-Fi / Bluetooth.** `qs` then tap a visible tile (Tier 2 via `on_qs`), or `open "Settings"` and tap the row. No `wifi` / `bluetooth` verb and no silent adapter APIs. SystemUI is not allowlisted.
- **Contacts** join the allowlist (`com.google.android.contacts`). `contact lookup` / `contact add` are the two forms of one verb, same pattern as `notif`.
- **No media seek.** `media` / `spotify` are play, pause, next, prev.

Partner misses are honest errors, not a fall-through to `tap`/`type` (that path is M6). Cards still show `plainWords()` payloads (TM-014). Intent, device, partner, and `qs` verbs are not app-scoped; a WhatsApp tree-`tap` still is. `qs` acts (keyguard refuses it) and is silent when unlocked via the `read-only` verb list, like `back` / `home` / `recents`.

## TM-021 (2026-09-08) Visible composers only (dial, SMS draft, calendar UI insert)

Calls, texts, and calendar events open a system composer. They do not complete the write themselves. Core holds the shapes as `IntentSpec` data (no `android.*`); app executors copy the fields and do not invent URIs.

- **Dial-only.** `PhoneIntents.dial` is `ACTION_DIAL` + `tel:`. No `ACTION_CALL`, no `CALL_PHONE`, no `EXTRA_SKIP_UI`. The person taps Call on the driven phone.
- **SMS draft.** `PhoneIntents.smsDraft` is `ACTION_SENDTO` + `smsto:` + `sms_body`. No `SmsManager`, no `SEND_SMS`, no `vnd.android-dir/mms-sms`. The person taps Send.
- **Calendar UI insert.** `PhoneIntents.insertEvent` is `ACTION_INSERT` on `content://com.android.calendar/events`. No silent `ContentResolver.insert`.
- Alarms and timers set `EXTRA_SKIP_UI=false` so Clock still shows its confirmation UI. Partner specs (WhatsApp / Spotify / Gmail) never carry `EXTRA_SKIP_UI`.

These are the §15 Q2 / Q3 / Q4 defaults (and Q12: no second-card `ACTION_CALL` / `SmsManager` in M2).

## TM-017 (2026-09-08) Spend cap is Outcome.Paused, loop-owned, session-scoped

Hitting `AssistConfig.spendCapUsd` pauses the session: no further provider call, `Outcome.Paused`, chip and notification say so. It is not an `ask` line from the model. `CloudPlanner` used to synthesize `ask "The spend cap…"` which became `Outcome.Ask` and lied — the person did not get a brain question.

The loop owns the pause. `SpendGuard` reads `CostTracker.sessionCost()` against the cap at the top of each step, before `planner.next`. Day spend is display-only. Classifier / validator-as-classifier calls already sit off `sessionCost()` (`CostTracker.record(..., isClassifier = true)`), so a validator ping cannot itself trip the cap. Raising the cap and running again is a new `TaskController.run`; there is no in-session resume in M2.

## TM-019 (2026-09-08) Planner-emitted goal apps; infer is fallback only

Production no longer uses `GoalApps.infer` as the only source of the intent lock. `Planner.planGoalApps` is one metered brain call before `TaskRunner.run` (not a loop step). The reply is one `apps` line; labels and packages are intersected with the allowlist; invented names are dropped, never launched. Empty remains valid (TM-014: the first app-scoped tree action is a card). The whole allowlist is never the default.

When the planner returns empty (`apps none`, prose, HTTP failure), `GoalApps.infer` is the fallback (Q8). Infer itself stays empty when the goal names no allowlisted label.

## TM-018 (2026-09-08) End-state validator; fail is not Done

After `done`, a validator compares the last observation to the goal. Deterministic gates always run (wrong app, secure screen; empty no-a11y observations pass). A model check on the validator tier is optional, fail-closed on garbage, and recorded `isClassifier = true` so it cannot trip the spend cap. Per-step calls stay brain-only; there is no three-tier-per-step router.

A failed validator is not `Outcome.Done`. Q6 maps fail to `Outcome.Ask` with the reason so the person can confirm or re-run. This overrides D4 (`Outcome.Stopped`). A null validator on the runner keeps today's `Outcome.Done`.

## TM-025 (2026-09-08) Day meter hydrates from audit.model_calls

A new `CostTracker` per `TaskController.run` used to make `dayCost == sessionCost`. Day spend is display-only (Q7: the pause cap is session). Before the first `record` of a session, `CostTracker.seedDayCost` takes `AuditStore.daySpend`: the sum of non-classifier `model_calls` whose local calendar day matches now. This session is not in the query yet, so it is not double-counted. Overlay and the persistent task notification both show `MeterText.chip` (turn, session, day, by tier, remaining). On `Outcome.Paused` the last chip includes `PAUSED`. The gold tap highlight stays on `OverlayCard`; the meter is a separate untouchable overlay.

## TM-020 (2026-09-08) Chinese cloud hosts blocked in family mode; OpenRouter slugs are not endpoints

“Chinese cloud endpoints” (plan §9.3 M4) are **hosts**, not OpenRouter slugs. The shipped `tst-default` brain is `moonshotai/kimi-k3` on `openrouter.ai`; treating the slug as Chinese would empty the default list and desync TST Desk.

`ProviderPolicy` holds a closed host blocklist (`api.deepseek.com`, `api.moonshot.cn`, `api.moonshot.ai`, DashScope, BigModel, MiniMax, Qianfan, Hunyuan, StepFun, Lingyi, Spark, SenseNova). `openrouter.ai` is not on it. Classification is `Endpoint.host` (a URI parse); nothing resolves DNS.

Family mode **on**: a blocked host is refused even if Custom names it. Settings Save and `Planners.forConfig` say `"that host is blocked in family mode"`. `Graph.reloadProvider` will not put that host on `Endpoints`. Family mode **off** (BYOM): any `base_url` may be typed; `Endpoints` still allowlists only that host (TM-015). Device and loopback stay allowed. The default picker is OpenRouter / Local / LAN / Custom — no blocked-host chip, and Device stays an honest refusal (TM-013).

## TM-022 (2026-09-08) QS-visible Wi-Fi/Bluetooth only; Settings panel is the fallback

There is no silent Wi-Fi or Bluetooth toggle in M2. No `WifiManager.setWifiEnabled`, no `BluetoothAdapter.enable/disable`, no `CHANGE_WIFI_STATE` / `BLUETOOTH_*` permissions, no `wifi` / `bluetooth` verb.

The path is `qs` (global Quick Settings, silent like `recents`) then `tap` a visible tile. That tap is Tier 2 via the `on_qs` fact (`qs-tile`), even though SystemUI is not allowlisted. Shade detection prefers false negatives: a miss is `app-not-allowlisted`. If the tile is not in the tree, the fallback is `open "Settings"` (or `open "Wi-Fi"` / `open "Bluetooth"`, which fire `ACTION_WIFI_SETTINGS` / `ACTION_BLUETOOTH_SETTINGS`) and tap the row — already `settings-change`. Stage 2/3 shell toggles stay out.

## TM-024 (2026-09-08) Intent and direct-API goals may run with accessibility off

`TaskController` no longer bails when the accessibility service is off. An `EmptyObserver` supplies a blank tree (`app=""`, no nodes, fingerprint `"empty"`). Intent, device, partner, and media verbs parse without hints and execute. Hint verbs (`tap`/`type`/…) parse-fail against the empty hint set and stop after one repair. `qs` and tree gestures still need the screen driver and return `"screen driver is off; cannot open Quick Settings"` (or the same for taps). Overlay meter and gold highlight stay a11y-only; the notification chip still updates. Kill switch, approvals, Endpoints, and the planner are unchanged.

## TM-023 (2026-09-08) `app/` source scan for sockets

Reality-check §8 finding 5: `NoConnectionOutsideEndpointsTest` only walked `core/`. An `HttpURLConnection` in a future `app/` file would not fail CI.

`AppNoConnectionOutsideEndpointsTest` walks `app/src/main/kotlin` for `OkHttpClient`, `java.net.Socket`, `HttpURLConnection`, and `URL.openConnection`. None are allowed in `app/`. App Functions, `startActivity`, and `CameraManager` are not sockets. Outbound HTTP still goes through `Graph.endpoints` (`core.net.Endpoints`).

The same scan refuses silent radios and skipped composers: `WifiManager.setWifiEnabled`, `BluetoothAdapter.enable`, `BluetoothAdapter.disable`, `SmsManager`, `Intent.ACTION_CALL`, `EXTRA_SKIP_UI`. Q2/Q3 stayed dial-only / SMS draft, so those strings must not appear in `app/`. Alarms and timers set `EXTRA_SKIP_UI=false` in core `PhoneIntents`, not in `app/`.

