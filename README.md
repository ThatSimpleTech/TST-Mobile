# TST Mobile

TST Assist: an open-source, bring-your-own-model assistant for Android, built on the
[TST Desk](https://github.com/ThatSimpleTech/TST-Desk) engine and held to the same promises.
No account, no server, no subscription, no telemetry, the key never touches disk in plaintext,
and the agent cannot rewrite its own rules.

The plan and architecture live in [`docs/tst-assist-android.md`](docs/tst-assist-android.md).

## Layout

| Path | What | Builds where |
|---|---|---|
| `core/` | Pure-JVM Kotlin: action grammar, observation format, hint table, policy enforcer, meter, audit schema, redactor, wire clients. No Android imports. | Any JDK 17+: `./gradlew :core:test` |
| `app/` | The Android side: accessibility observer and executor, overlay approval card, notification actions, kill switch, assistant role. | Needs the Android SDK: `./gradlew :app:assembleDebug` |
| `tools/androidcheck/` | Type-checks `app/` sources against a framework jar on machines with no SDK. | `TST_ANDROID_CHECK=1 ./gradlew :tools:androidcheck:compileKotlin` |
| `docs/` | The plan, and the reality check of it against the TST Desk code. | |

`:app` is only part of the build when an Android SDK is found (`ANDROID_HOME`, `ANDROID_SDK_ROOT`,
or `sdk.dir` in `local.properties`). Without one, `:core` still builds and tests.

## License

Apache 2.0. See `LICENSE` and `NOTICE`.
