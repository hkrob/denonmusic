# Working in this repo

Android controller for playing SMB-hosted music through a Denon AVR-X4500H. Released and in use -
treat `main`-line changes as changes to shipping software.

Orientation, in the order worth reading: [`README.md`](README.md) for what the app is and the LAN
control API, [`docs/local-setup.md`](docs/local-setup.md) for current state plus a dated log of
every session on real hardware, [`docs/plan.md`](docs/plan.md) for the original design reasoning.

## The invariant

**The phone is never in the audio path.** The receiver reads the files itself; the app only browses,
queues and controls. This is why there is no decoder in the app, and it is the reason to prefer
HEOS `browse/add_to_queue` (real queue, gapless, DSD survives) over `browse/play_stream` (one
stream, no next track).

The SMB bridge (`app/.../bridge/`) is the deliberate exception: it relays bytes for files HEOS
hasn't indexed, and inherits every drawback above. It is a fallback, not a second implementation of
the main path - don't grow it into one.

## Build

The project lives on Linux (`/workspace/android-denonmusic`); it was moved from Windows, so any
`C:/...` path or `.bat`/`.ps1` reference you meet is a leftover. `JAVA_HOME` **must** point at a JDK
17+ - the Android Gradle plugin fails during startup with an error that doesn't name the cause. Here
it is already set to Temurin 21 (`/usr/lib/jvm/temurin-21-jdk-amd64`), so just check `java -version`:

```sh
./gradlew :app:testDebugUnitTest :core:heos:test :core:avr:test :core:smb:test :core:data:testDebugUnitTest
./gradlew ktlintCheck :app:lintDebug   # both, or CI fails; ktlintFormat fixes the first
./gradlew assembleDebug
```

`:core:heos`, `:core:avr` and `:core:smb` are pure JVM and build with no Android SDK at all;
`settings.gradle.kts` gates `:app` and `:core:data` on an SDK being present. Keep the protocol
modules Android-free - that gating is load-bearing for CI.

The SDK is `/opt/android-sdk` (`ANDROID_HOME` is set). `local.properties` `sdk.dir` is absolute and
gitignored, and Gradle prefers it over `ANDROID_HOME`, so a stale path there breaks the build - it
once held a `C:/Users/...` path. The SDK has platforms 34 and 36 (`compileSdk` is 36) but no
`emulator` or `system-images`; install them with `sdkmanager` before reaching for an AVD.

## Protocol facts that have already cost time

- **Port 23 (AVR telnet) has no correlation ids.** Replies are matched by prefix, so a prefix can
  catch more than you meant: `MV?` returns both `MV40` *and* `MVMAX 98`. Verified on the real unit.
  Any new prefix match needs the same scepticism.
- **Volume wire format is positional**, so every value must be zero-padded to two digits before the
  optional half-step digit. `MV55` is -25 dB, not -74.5 dB.
- **`browse/add_to_queue` returns success long before the receiver has finished with it** (~30ms vs
  ~450ms for a 13-track container, which ends with `event/player_queue_changed`). A second add
  inside that window is refused with `eid=9 Out of range` - the spec's "parameter out of range",
  which is not remotely what went wrong. `HeosClient.addToQueue` retries for this; don't add a
  second queueing path that doesn't.
- **`aid=4` ("replace and play") is lost if another add follows it.** Sent alone it replaces the
  queue and starts playing; with a second add arriving straight after, both the replace *and* the
  play are dropped - the old queue survives and the new parts are merely appended, stopped. So a
  multi-part replace clears the queue, appends each part with `aid=3`, then plays. Same underlying
  asynchrony as the entry above.
- **Check protocol behaviour against the receiver instead of the comments.** There is direct LAN
  access to the real unit at `10.1.10.50`, and `tools/probe.py <subcommand> <ip>` is the
  tool for it (standard library only). Several comments in this codebase were written from the spec
  and turned out wrong, and two were corrected only after being measured - see `docs/local-setup.md`
  for both. Assume a comment about receiver behaviour is a hypothesis until a tap says otherwise.
- **Home Assistant's `denonavr` integration competes for the control ports.** If telnet:23 or
  HEOS:1255 reset instantly from here, that's usually why.

## Android facts specific to this app

- **`@Inject lateinit var` does not compile here.** Field injection fails this project's
  Dagger/Kotlin pairing with "Unable to read Kotlin metadata due to unsupported metadata version".
  Constructor injection where possible; `@EntryPoint` + `EntryPointAccessors` for the Application,
  the Activity and the Service, which is what all three already do.
- **The now-playing notification appears in the system media panel, not the notification list.** On
  Samsung's One UI that is the bottom of the *second* pull-down. Three releases were once spent
  "fixing" a notification that was working and being looked for in the wrong place. Before changing
  anything, check `dumpsys notification`, `dumpsys media_session`, and the app's own
  Settings -> NOTIFICATION row, which reports why it is not showing when it is not.
- **An emulator settles version-specific behaviour in one boot.** The test phone here is old; most
  of the rules that break a modern Android are not reachable on it. An API 36 AVD reproduced correct
  behaviour immediately and reframed a three-release hunt. Reach for it first.

## Conventions

- Connections own a `CoroutineScope`. Use `shutdown()`, not `close()` - `close()` cancels the reader
  job but leaves the scope alive, which leaked two connections per reconnect until 0.1.8.
- For `MutableStateFlow` touched from more than one dispatcher, use `update {}`/`getAndUpdate {}`,
  not `value = value.copy(...)`. Careful with the implicit `it` when converting: inside
  `onFailure { }` it shadows the throwable.
- A test that asserts on a fire-and-forget send must wait for it. `send()` returns once the bytes
  are written, not once the fake server has read them; `AvrClientTest.awaitReceived` exists for
  exactly this, and the two tests that skipped it were flaky until they used it.
- Comments explain *why*, at the density of the surrounding code. The existing prose is deliberate;
  match it rather than adding a banner comment per function.
- Never commit `release.keystore`, `keystore.properties` or `local.properties`, and don't paste
  credentials into docs. Sweeps under `tools/sweeps/` capture receiver config and are gitignored.

## Releasing

Bump `versionCode`/`versionName` in `app/build.gradle.kts`, add a `CHANGELOG` entry for that exact
`versionName` in `AboutScreen.kt`, push to the default branch, then run the **Release** workflow.
The changelog entry is the source for both the GitHub release notes and the in-app updater, and it
is parsed by regex in two places that must stay in step - read
[`.github/workflows/README-release.md`](.github/workflows/README-release.md) before touching either.

`publish-release.ps1` is the Windows-only local path (`gradlew.bat`, backslash paths,
`apksigner.bat`); it cannot run on this machine even with `pwsh`. Use the workflow, dry run first:

```sh
gh workflow run release.yml --ref claude/android-smb-denon-player-9710bx -f dry_run=true
gh run watch <run-id> --exit-status        # then repeat with dry_run=false to publish
```

The workflow refuses to run past its first check if a release for that version already exists, so a
dry run only means something after the version bump is pushed. After publishing, download the APK
and confirm `apksigner verify --print-certs` shows the fingerprint pinned in `release.yml`.

For a local signed build, `keystore.properties` needs an absolute `storeFile`
(`/workspace/android-denonmusic/release.keystore`); its `keytool` SHA-256 must match
`EXPECTED_SIGNER` in `release.yml`, or no build can install over an existing one.

## Environment hazards

**Line endings are LF.** `.editorconfig` says `end_of_line = lf` and the index is LF throughout
(`gradlew.bat` is the one CRLF file). The tree was once copied over from Windows as CRLF, which broke
`./gradlew` (`#!/bin/sh\r` fails with "No such file or directory") and made an edit to a Kotlin file
diff as a whole-file rewrite. If `git ls-files --eol` shows `i/lf w/crlf` again, convert the working
files before editing or committing, not after.

**git needs three things it does not have by default here.** The checkout is owned by another uid, so
git refuses it ("dubious ownership"); there is no `user.name`/`user.email`; and HTTPS pushes have no
credentials even though `gh` is authenticated. Pass them per command instead of editing global config:

```sh
export GIT_CONFIG_COUNT=3 GIT_CONFIG_KEY_0=safe.directory GIT_CONFIG_VALUE_0=/workspace/android-denonmusic \
  GIT_CONFIG_KEY_1=credential.helper GIT_CONFIG_VALUE_1= \
  GIT_CONFIG_KEY_2=credential.helper GIT_CONFIG_VALUE_2='!gh auth git-credential'
export GIT_AUTHOR_NAME=Rob GIT_AUTHOR_EMAIL=robertlempriere@gmail.com   # the existing history's author
export GIT_COMMITTER_NAME=Rob GIT_COMMITTER_EMAIL=robertlempriere@gmail.com
```

`git status` may also list files as modified when `git diff` is empty: stale stat data the filesystem
won't let git refresh. Trust `git diff --quiet`, not the status list.

**`ping` is not installed.** Check the receiver with a TCP connect to 23 and 1255 (Python's
`socket` is enough). A connect succeeding does not rule out Home Assistant resetting the first command.

The Bash tool on the old Windows machine mangled a literal `&` and doubled backslashes, even inside
quoted heredocs. Re-tested on this Linux machine on 2026-09-26: neither happens, and heredocs are
fine. Keep it in mind only if a command string comes out wrong for no reason.
