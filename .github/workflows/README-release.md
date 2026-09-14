# Releasing

`release.yml` builds, signs and publishes a release entirely on a GitHub runner. The signing
key never leaves encrypted repository secrets except inside the job that uses it.

## One-time setup

### 1. Generate a keystore (skip if you already have one)

```powershell
keytool -genkeypair -v -keystore release.keystore -alias denonmusic `
  -keyalg RSA -keysize 2048 -validity 10000
```

Keep `release.keystore` **outside the repo** (it's already covered by `.gitignore` as `*.keystore`)
and back it up somewhere durable and offline. See "Security notes" below on why - losing it is
effectively unrecoverable on this app's `minSdk`.

Create `keystore.properties` next to it (also gitignored) for local release builds:

```properties
storeFile=C:/path/to/release.keystore
storePassword=...
keyAlias=denonmusic
keyPassword=...
```

### 2. Pin the signing certificate fingerprint

Already done for the current keystore: `EXPECTED_SIGNER` in `.github/workflows/release.yml` and
`$ExpectedSigner` in `publish-release.ps1` are both set to
`7c50709e598856a9a80a75afff9dfb60f1b8d4f682e7ee2bef036bafa79ed8ea`. Re-do this step only if the
key is ever rotated.

`release.yml` refuses to trust an APK signed with any key but the one you intend - print the
fingerprint once and hardcode it as `EXPECTED_SIGNER` in `.github/workflows/release.yml`:

```powershell
keytool -exportcert -alias denonmusic -keystore release.keystore -storepass <storePassword> |
  & "$env:ANDROID_HOME\build-tools\<version>\apksigner.bat" verify --print-certs -
```

Or more simply, since the workflow already prints it: run the workflow once with `dry_run` before
pinning it - the "Verify the APK carries the expected signature" step logs the actual fingerprint
as a warning while the placeholder is still in place. Copy that value in.

This fingerprint is not secret - it ships inside every APK anyway - so it's committed directly in
the workflow file, not stored as a secret.

### 3. Set the four keystore secrets

```powershell
cd C:\path\to\denonmusic              # the folder holding release.keystore
gh auth status                         # must be authenticated

gh secret set RELEASE_KEYSTORE_B64 --repo hkrob/denonmusic `
  --body ([Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path .\release.keystore))))

gh secret set RELEASE_STORE_PASSWORD --repo hkrob/denonmusic
gh secret set RELEASE_KEY_ALIAS      --repo hkrob/denonmusic
gh secret set RELEASE_KEY_PASSWORD   --repo hkrob/denonmusic
```

`Resolve-Path` matters: .NET methods such as `ReadAllBytes` resolve a relative path against the
process working directory, which is not necessarily the directory PowerShell has `cd`-ed to.
Passing a bare `"release.keystore"` can silently read from the wrong place or throw.

The last three commands prompt for the value, so it never reaches your shell history. They must
match the `storePassword`, `keyAlias` and `keyPassword` in your local `keystore.properties`.

Check the result with `gh secret list --repo hkrob/denonmusic` — it shows names and update times
only, never values.

## Cutting a release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add a `CHANGELOG` entry for the new `versionName` in
   `app/src/main/kotlin/com/denonmusic/app/about/AboutScreen.kt`. The workflow reads the release
   notes from it and fails if it is missing or empty.
3. Merge to this repo's default branch (`claude/android-smb-denon-player-9710bx` as of writing -
   `publish-release.ps1` checks it explicitly; update `$ReleaseBranch` there if it's ever renamed).
4. Run the **Release** workflow (`workflow_dispatch`), or push a `v<versionName>` tag.

Tick **dry_run** to build, test and check the signature without publishing.

Or from a machine with `gh` and a JDK, `publish-release.ps1` does the same thing locally in one
command (see its own header comment).

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
