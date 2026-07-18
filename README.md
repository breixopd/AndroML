# AndroML

AndroML is a power-user Android app for downloading, optimizing, and running machine-learning models locally, with authenticated APIs, RAG, tools, agents, and secure multi-device execution.

## Current status

This repository is in the private phone-test period. The current build is a runnable foundation slice:

- Android 17 / API 37 target.
- OSS and Play build flavors exist, but only the OSS flavor is distributed during testing.
- The Home screen shows device/model/runtime readiness placeholders and the active release gate.
- Only GitHub Releases are allowed by the product policy.
- Google Play, F-Droid, Accrescent, IzzyOnDroid, and every other store/repository submission is disabled until the owner explicitly approves it.

The implementation plan is intentionally local and ignored by Git: `docs/superpowers/plans/2026-07-18-androml-v1-implementation-plan.md`.

## Build locally

Use JDK 17 or newer, Android SDK Platform 37.0, Android SDK Build Tools 37.0.0, and ADB.

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
./gradlew :core:model:test :app:testOssDebugUnitTest :app:lintOssDebug :app:assembleOssDebug
```

Install the debug APK on a connected development phone:

```bash
adb install -r app/build/outputs/apk/oss/debug/app-oss-debug.apk
```

The release build is intentionally unsigned unless all four signing environment variables are supplied. Never commit the keystore or its values:

```bash
export ANDROML_RELEASE_KEYSTORE=/secure/path/androml-test-release.jks
export ANDROML_RELEASE_STORE_PASSWORD='provided-by-secret-manager'
export ANDROML_RELEASE_KEY_ALIAS='androml-test'
export ANDROML_RELEASE_KEY_PASSWORD='provided-by-secret-manager'
./gradlew :app:assembleOssRelease
```

## Test-period release rules

Tags matching `v*` build and publish only the OSS APK to a GitHub Release. The workflow has no store upload step and asserts `STORE_SUBMISSIONS_ENABLED=false`. Store publication requires a deliberate code review and a separate owner approval after the phone-testing period.

See [the test-release policy](docs/publishing/test-release-policy.md) for the exact gate and artifact verification rules.

## License

AndroML-owned code is Apache-2.0. Model files, runtime libraries, and third-party assets retain their own licenses, which the app will display before use.

