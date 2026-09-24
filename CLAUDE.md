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

`JAVA_HOME` **must** be set; this machine's default `java` is 11 and the Android Gradle plugin needs
17+, failing during startup with an error that doesn't name the cause:

```sh
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"

./gradlew :app:testDebugUnitTest :core:heos:test :core:avr:test :core:smb:test   # 235 tests
./gradlew ktlintCheck          # or ktlintFormat; rules are in .editorconfig, 140-col Kotlin
./gradlew :app:assembleDebug
```

`:core:heos`, `:core:avr` and `:core:smb` are pure JVM and build with no Android SDK at all;
`settings.gradle.kts` gates `:app` and `:core:data` on an SDK being present. Keep the protocol
modules Android-free - that gating is load-bearing for CI.

## Protocol facts that have already cost time

- **Port 23 (AVR telnet) has no correlation ids.** Replies are matched by prefix, so a prefix can
  catch more than you meant: `MV?` returns both `MV40` *and* `MVMAX 98`. Verified on the real unit.
  Any new prefix match needs the same scepticism.
- **Volume wire format is positional**, so every value must be zero-padded to two digits before the
  optional half-step digit. `MV55` is -25 dB, not -74.5 dB.
- **Check protocol behaviour against the receiver instead of the comments.** There is direct LAN
  access to the real unit at `10.1.10.50`, and `tools/probe.py <ip> tap|sources|avr|heos` is the
  tool for it (standard library only). Several comments in this codebase were written from the spec
  and turned out wrong; at least one is still unverified (see the end of `docs/local-setup.md`).
- **Home Assistant's `denonavr` integration competes for the control ports.** If telnet:23 or
  HEOS:1255 reset instantly from here, that's usually why.

## Conventions

- Connections own a `CoroutineScope`. Use `shutdown()`, not `close()` - `close()` cancels the reader
  job but leaves the scope alive, which leaked two connections per reconnect until 0.1.8.
- For `MutableStateFlow` touched from more than one dispatcher, use `update {}`/`getAndUpdate {}`,
  not `value = value.copy(...)`. Careful with the implicit `it` when converting: inside
  `onFailure { }` it shadows the throwable.
- Comments explain *why*, at the density of the surrounding code. The existing prose is deliberate;
  match it rather than adding a banner comment per function.
- Never commit `release.keystore`, `keystore.properties` or `local.properties`, and don't paste
  credentials into docs. Sweeps under `tools/sweeps/` capture receiver config and are gitignored.

## Releasing

Bump `versionCode`/`versionName` in `app/build.gradle.kts`, add a `CHANGELOG` entry for that exact
`versionName` in `AboutScreen.kt`, then `pwsh ./publish-release.ps1` (or the **Release** workflow).
The changelog entry is the source for both the GitHub release notes and the in-app updater, and it
is parsed by regex in two places that must stay in step - read
[`.github/workflows/README-release.md`](.github/workflows/README-release.md) before touching either.
`-DryRun` builds and prints the notes without publishing; use it.

## Environment hazards

The Bash tool here mangles some command strings: a literal `&` splits the command as if it were
background jobs, and doubled backslashes collapse to one **even inside a quoted heredoc**. Both
survive `printf` in a real shell, so it is the tool wrapper, not POSIX. Author file content with the
Write/Edit tools instead of `cat > file << 'EOF'`, and put anything containing `&` (HEOS query
strings, for instance) in a script file rather than inline.

Most files in this repo are CRLF. Rewriting one with a script that reads with universal newlines and
writes with `newline=''` silently converts it to LF across the whole file; pass `newline=''` on the
read too, or just use Edit.
