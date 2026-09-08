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
