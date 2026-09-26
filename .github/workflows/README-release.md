# Releasing

`release.yml` builds, signs and publishes a release entirely on a GitHub runner. The signing
key never leaves encrypted repository secrets except inside the job that uses it.

## One-time setup

### 1. Generate a keystore (skip if you already have one)

```sh
keytool -genkeypair -v -keystore release.keystore -alias denonmusic \
  -keyalg RSA -keysize 2048 -validity 10000
```

Back the keystore up somewhere durable and offline, and never let it into a commit. See "Security
notes" below on why - losing it is effectively unrecoverable on this app's `minSdk`. `.gitignore`
covers `*.keystore`/`*.jks`, so the working copy sitting at the repo root (which is where this
machine keeps it) stays out of git; it is the *backup* that has to live somewhere else.

Create `keystore.properties` alongside it (also gitignored) for local release builds:

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=...
keyAlias=denonmusic
keyPassword=...
```

`storeFile` is an absolute path, so it goes stale if the checkout ever moves - a release build that
fails on a missing keystore file usually means this line, not a broken signing setup. Verify any
keystore before you point at it; the fingerprint has to be the one pinned in step 2, because
Android will not install an update signed with a different key:

```sh
keytool -list -v -keystore release.keystore -alias denonmusic | grep SHA256
```

### 2. Pin the signing certificate fingerprint

Already done for the current keystore: `EXPECTED_SIGNER` in `.github/workflows/release.yml` and
`$ExpectedSigner` in the Windows-only `publish-release.ps1` are both set to
`7c50709e598856a9a80a75afff9dfb60f1b8d4f682e7ee2bef036bafa79ed8ea`. Re-do this step only if the
key is ever rotated.

`release.yml` refuses to trust an APK signed with any key but the one you intend - print the
fingerprint once and hardcode it as `EXPECTED_SIGNER` in `.github/workflows/release.yml`. The
`keytool` command in step 1 prints it as colon-separated hex; `EXPECTED_SIGNER` is the same digits,
lower-case, without the colons. To read it off a built APK instead:

```sh
$ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Or more simply, since the workflow already prints it: run the workflow once with `dry_run` before
pinning it - the "Verify the APK carries the expected signature" step logs the actual fingerprint
as a warning while the placeholder is still in place. Copy that value in.

This fingerprint is not secret - it ships inside every APK anyway - so it's committed directly in
the workflow file, not stored as a secret.

### 3. Set the four keystore secrets

```sh
cd /path/to/denonmusic                 # the folder holding release.keystore
gh auth status                         # must be authenticated

base64 -w0 release.keystore | gh secret set RELEASE_KEYSTORE_B64 --repo hkrob/denonmusic

gh secret set RELEASE_STORE_PASSWORD --repo hkrob/denonmusic
gh secret set RELEASE_KEY_ALIAS      --repo hkrob/denonmusic
gh secret set RELEASE_KEY_PASSWORD   --repo hkrob/denonmusic
```

`-w0` keeps the secret on one line; the workflow's `base64 -d` would cope with wrapped output too.
Setting a secret overwrites the existing one, so do not run these to "check" them.

The last three commands prompt for the value, so it never reaches your shell history. They must
match the `storePassword`, `keyAlias` and `keyPassword` in your local `keystore.properties`.

Check the result with `gh secret list --repo hkrob/denonmusic` — it shows names and update times
only, never values.

## Cutting a release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add a `CHANGELOG` entry for the new `versionName` in
   `app/src/main/kotlin/com/denonmusic/app/about/AboutScreen.kt`. The workflow reads the release
   notes from it and fails if it is missing or empty.

   That list is parsed out of Kotlin source by regex, in **two places that must stay in step**:
   `release.yml` (Python) and `publish-release.ps1` (PowerShell, Windows-only). A bullet too long for one line is
   written as a string concatenation, `"first half " + "second half"`, and both parsers collapse
   those joins before matching literals - without that, every continuation line becomes a bullet of
   its own and the notes ship split mid-sentence, which is exactly how v0.1.6 and v0.1.7 went out.
   If you touch either parser, run the workflow with `dry_run` (or `publish-release.ps1 -DryRun` on
   Windows) and read the notes it prints before publishing.
3. Merge to this repo's default branch (`claude/android-smb-denon-player-9710bx` as of writing -
   `publish-release.ps1` checks it explicitly; update `$ReleaseBranch` there if it's ever renamed).
4. Run the **Release** workflow (`workflow_dispatch`), or push a `v<versionName>` tag:

   ```sh
   gh workflow run release.yml --ref claude/android-smb-denon-player-9710bx -f dry_run=true
   gh run watch <run-id> --exit-status      # then repeat with dry_run=false to publish
   ```

`dry_run` builds, tests and checks the signature without publishing. It only means something once
the version bump is pushed: while a release for the current version exists, the workflow stops at
its first check, even as a dry run.

`publish-release.ps1` does the same thing locally in one command, but it is Windows-only
(`gradlew.bat`, backslash paths, `apksigner.bat`) and cannot run on Linux even with `pwsh`. On Linux
the workflow is the release path.

## What the workflow refuses to do

- Publish when a release for that tag already exists.
- Publish when a pushed tag disagrees with `versionName`.
- Publish when there is no changelog entry for the version.
- Publish when unit tests fail.
- Publish an APK whose signing certificate is not `EXPECTED_SIGNER` (once pinned - see step 2
  above). Android refuses to install an update signed with a different key, so shipping one would
  strand every existing install.

## Security notes

- Secrets are not exposed to workflow runs from forked pull requests, so the public repo is not
  a leak vector by itself.
- Anyone with **write** access can add a workflow that prints a secret. Keep the collaborator
  list tight; that is the real perimeter here.
- For a second gate, put the four secrets in a GitHub **Environment** with a required reviewer
  and add `environment:` to the job — the run then pauses for approval before it can read them.
- Rotating this key is painful and mostly one-way: `minSdk` is 26, and signing-certificate
  rotation only works on Android 9 (API 28) and above. On older devices a new key means an
  uninstall and reinstall, which loses whatever local settings/state the app hasn't backed up
  itself. Treat the key as unrecoverable and back it up offline.
