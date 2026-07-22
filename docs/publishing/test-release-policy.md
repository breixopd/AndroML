# Public Repository, Owner-Controlled Phone-Test Release Policy

This policy is active until the owner explicitly approves store publication.

## Allowed distribution

Only the signed OSS APKs attached to a GitHub Release may be distributed. A release must be created from a protected `v<semver>` tag and contain:

- `androml-oss-universal-v<semver>.apk`.
- `androml-oss-arm64-v8a-v<semver>.apk` for arm64-only installs.
- SHA-256 and SHA-512 checksum files.
- A JSON manifest containing the source commit, package ID, and signing-certificate digest.
- The release changelog.
- The source tag and commit identifier.
- The expected package ID `dev.androml.app` and signing certificate fingerprint.

## Blocked distribution

The following remain disabled during the test period:

- Google Play.
- Official F-Droid submission.
- The project-owned F-Droid repository.
- Accrescent.
- IzzyOnDroid.
- Any other app store, repository, mirror, or third-party upload service.
- Obtainium metadata or any other third-party update feed.

The app’s `ReleasePolicy.testPeriod()` permits only `ReleaseChannel.GitHubRelease`. The GitHub workflow also checks `STORE_SUBMISSIONS_ENABLED=false`; changing that value requires a reviewed code change and explicit owner approval after phone testing.

## Phone test loop

1. Build the OSS debug APK locally for rapid iteration.
2. Install it with ADB on the test phone.
3. Exercise the current Home screen and policy behavior.
4. For a shareable test build, create a signed `v<semver>` tag and let GitHub Actions publish only the OSS APK.
5. Verify the downloaded APK’s package ID, certificate, checksum, and version before installing it.

Release Please creates the changelog-backed GitHub release and builds its assets in the same workflow run. A manual recovery workflow can rebuild and re-upload assets for an existing release if a runner or signing step fails.

No model, document, prompt, certificate, API key, signing key, or personal diagnostic data may be attached to a GitHub Release.
