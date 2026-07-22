# AndroML

AndroML is a power-user Android app for downloading, optimizing, and running machine-learning models locally, with authenticated APIs, RAG, tools, agents, and secure multi-device execution.

## Current status

This repository is public and in the owner-controlled phone-test period. The current build is a usable v1 test candidate:

- Android 17 / API 37 target.
- OSS and Play build flavors exist, but only the OSS flavor is distributed during testing.
- The Home, Settings, Discover, Library, Playground, RAG, Workflows, API, and Cluster screens expose the complete local control surface.
- Hugging Face search and pinned-commit imports use bounded, resumable, SHA-256 verified downloads. Local RAG accepts text, HTML, PDF, EPUB, DOCX, XLSX, and PPTX sources with citations.
- LiteRT is bundled for verified `.tflite` text embeddings, LiteRT-LM is bundled for text generation, ONNX Runtime Mobile is bundled for verified `.onnx`/`.ort` text embeddings, and ExecuTorch is bundled for `.pte` tensor embeddings. Other planned engine packs are shown as unavailable until their signed native implementations are shipped; the app never substitutes a fake model result in production.
- The API includes scoped bearer authentication, loopback/LAN mTLS gates, OpenAI chat/responses/embeddings routes, RAG, tools, workflows, agents, cluster status, and an OpenAPI document.
- Only GitHub Releases are allowed by the product policy.
- Google Play, F-Droid, Accrescent, IzzyOnDroid, Obtainium manifests, and every other store/repository submission is disabled until the owner explicitly approves it.

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

Merges to `main` run Release Please. It opens or updates a release PR from Conventional Commits; merging that PR creates the GitHub tag/release and attaches the verified OSS APK plus SHA-256/SHA-512 checksums. A signing key must be supplied through protected GitHub secrets, and the workflow remains hard-blocked from store publication.

See [the test-release policy](docs/publishing/test-release-policy.md) for the exact gate and artifact verification rules.

## License

AndroML-owned code is Apache-2.0. Model files, runtime libraries, and third-party assets retain their own licenses, which the app will display before use.
