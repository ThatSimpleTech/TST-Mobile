# TST Mobile

EZER: the family assistant on Android. Open-source, bring-your-own-model, built on the
[TST Desk](https://github.com/ThatSimpleTech/TST-Desk) engine and held to the same promises.
Default brain is the EZER home box (`ezer-chat` on `llm.ezer-server.ts.net`). OpenRouter and
a LAN Ollama stay as fallbacks.

No account, no server of ours, no subscription, no telemetry, the key never touches disk in
plaintext, and the agent cannot rewrite its own rules.

The plan is [`docs/tst-assist-android.md`](docs/tst-assist-android.md). What the code actually
does today, checked against TST Desk's source, is [`docs/reality-check.md`](docs/reality-check.md).
Decisions are in [`docs/decisions.md`](docs/decisions.md).

**Status:** pre-alpha, private use. Head of work is `grok/m1-closeout` (TM-010–031).
The phone app is **EZER**. Settings default to the home box; OpenRouter is a fallback.
Pixel path (type, spatial, Auto, open-any-app) plus M2 (intents, meter chip, spend-cap
pause, family host block, end-state validator, model-profile suite), M3 (share audit CSV),
M4 v1 (on-device Talk, QS/notification/shortcut Talk, assist-gesture listen sheet),
M6 v1 (`screen ask` JPEG to the brain), and TM-028 (whole-job verbs first; Jerry resolves
through Contacts).

## How it drives the phone

1. The accessibility service flattens the screen into numbered controls (hints) with stable
   identities, and renders them as a data block the model reads.
2. The model answers with exactly one line of a closed grammar: `tap 7`, `type 3 "on my way"`,
   `scroll 4 down`, `done "..."`. Nothing else executes. Hints not on screen are refused.
3. The policy pack decides the tier: silent, once per task, every time, or refused. Every
   rule is data in `policy/policy.yaml`, and `policy/cases.yaml` pins every case.
4. Tier 2 actions (send, call, pay, delete, install, share, settings, notification reply,
   leaving the goal's apps) show a card on the phone with the target highlighted, plus a
   notification with Approve and Deny. No answer is a deny.
5. The kill switch is a Quick Settings tile and a Stop action on the task notification.

## Layout

| Path | What | Builds where |
|---|---|---|
| `core/` | Pure-JVM Kotlin: action grammar, observation format, hint table, policy enforcer, task loop, meter, audit schema, redactor, network rules, tstd wire client. No Android imports. | Any JDK 17+: `./gradlew :core:test` |
| `app/` | The Android side: accessibility observer and executor, overlay approval card, notification actions, kill switch tile, assistant role, Keystore secret store, SQLite audit. | Needs the Android SDK: `./gradlew :app:assembleDebug` |
| `policy/` | The policy pack and its conformance cases. Data, refused to the agent. | |
| `tools/androidcheck/` | Type-checks `app/` sources against a framework jar on machines with no SDK. | `TST_ANDROID_CHECK=1 ./gradlew :tools:androidcheck:compileKotlin` |
| `docs/` | Plan, reality check, decisions. | |

`:app` is only part of the build when an Android SDK is found (`ANDROID_HOME`, `ANDROID_SDK_ROOT`,
or `sdk.dir` in `local.properties`). Without one, `:core` still builds and tests.

## The promises, and what proves them today

| Promise | Where it is enforced | Test |
|---|---|---|
| No account | There is no sign-up path; the only credential is a provider key or a tstd token. | onboarding review |
| No server | Nothing of ours is in the loop. Bind rules refuse `0.0.0.0` and accept loopback or Tailscale only. | `core/net` tests |
| No subscription | Prices live in `config.yaml`; Device mode is priced at zero and the meter says zero. | `core/meter` tests |
| No telemetry | Every outbound connection goes through one `Endpoints` object that refuses hosts config did not name. | `core/net` tests, source scan |
| Key never touches disk | Android Keystore wraps the key; only ciphertext is stored. One redactor for logs and audit rows. | `core/redact` tests |
| The agent cannot rewrite its own rules | `ASSISTANT.md`, `CHARTER.md`, `profiles/`, `policy.yaml` are refused before any approval. | `core/steering` tests |
| Screen and notification text is data | Labels are one bounded, escaped token inside the `<<OBS` block; only the grammar executes. | `ObservationTest`, `ActionParserTest`, loop tests |
| The driven phone shows the approval | Overlay card with the highlighted target plus a notification with actions, on the phone. | approval round trip in loop tests; on-device check pending |
| Audit is append-only | SQLite triggers abort UPDATE and DELETE on every audited table. | `core/audit` tests |

## Building

```
./gradlew :core:test                                   # pure JVM, no SDK needed (includes offline suite)
TST_ANDROID_CHECK=1 ./gradlew :tools:androidcheck:compileKotlin   # type-check app sources without an SDK
./gradlew :app:assembleDebug                           # needs the Android SDK

# Live three-brain suite: not CI, spends keys, still through Endpoints.
# Default brains: OpenRouter moonshotai/kimi-k3, OpenRouter z-ai/glm-5.2,
# local loopback if present else "third brain not configured".
OPENROUTER_API_KEY=… ./gradlew :core:test --tests com.thatsimpletech.assist.core.profile.ModelSuiteTest -Dassist.liveSuite=1
```

## License

Apache 2.0. See `LICENSE` and `NOTICE`.
