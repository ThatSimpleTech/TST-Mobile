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

