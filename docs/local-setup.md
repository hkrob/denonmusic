# Picking this up in a local session

This project was started in a cloud container with no route to the LAN, so nothing has ever talked
to the real receiver or a real phone. A local session can. This file is the handoff.

## Where the work stands

Done and verified (27/27 tests passing):

- `core/heos` - the full HEOS CLI protocol layer. Pure JVM, no Android dependencies, so
  `./gradlew :core:heos:test` runs anywhere with a JDK 17+.
- `tools/probe.py` - receiver probe. Standard library only, nothing to install.
- `.github/workflows` - CI runs the protocol tests; the Android job self-skips until an `:app`
  module exists. A `v*` tag builds a signed APK once the four keystore secrets are set.

Not started: every Android module. `:app`, `:core:avr`, `:core:smb`, `:core:data`, `:feature:probe`
are commented out in `settings.gradle.kts` and get uncommented as they land.

The full design is in [`plan.md`](plan.md). Read it first - it explains why the app is a pure
controller that never touches the audio, which is the decision the rest of the code follows from.

## Prerequisites on the local machine

| Need | Why | Check |
|---|---|---|
| JDK 17+ | Gradle build | `java -version` |
| Android Studio or cmdline-tools | Building the app; sets `ANDROID_HOME` | `echo $ANDROID_HOME` |
| `adb` | Installing to the phone, reading logs | `adb devices` |
| Phone with USB debugging on | Sideloading and `logcat` | `adb devices` lists it |
| Receiver reachable | Probing and playing | `ping <avr-ip>` |

The phone and the receiver must be on the same network segment as the machine running the session.

## First thing to run

The four open questions in the plan are all answered by the probe, and none of them can be settled
from documentation. Do this before writing any Android code, because the answers change what gets
written.

```sh
# 1. Is the share visible to the receiver, and can it be queued?
./tools/probe.py <avr-ip> sources

# 2. Which endpoint backs the speaker OUTPUT map?
./tools/probe.py <avr-ip> sweep -o /tmp/before.json
#    ...now change the sound mode on the receiver: Stereo -> Multi Ch Stereo...
./tools/probe.py <avr-ip> sweep -o /tmp/after.json
./tools/probe.py diff /tmp/before.json /tmp/after.json

# 3. Which mnemonic selects the HEOS input, and how does signal info encode?
./tools/probe.py <avr-ip> avr 'SI?' 'MS?' 'SSINFAISSIG ?' 'SSINFAISFSV ?'

# 4. What does the receiver emit unprompted? Operate it with the remote while this runs.
./tools/probe.py <avr-ip> tap
```

What to do with the answers:

- `sources` marks a row queueable when it supports `browse/add_to_queue`. That is the source the app
  should latch onto - it is what makes gapless playback and DSD possible. If your share is missing,
  fix that before anything else (see README).
- The `diff` output names the endpoint whose value tracks the speaker tiles. Wire that into the
  OUTPUT map instead of the placeholder ranking in the plan.
- `SI?` returns the current input mnemonic. Select the HEOS/network input on the receiver by hand,
  re-run it, and whatever comes back is the value the auto-power-on path should send.

Sweeps capture receiver configuration and network details, so `tools/sweeps/` is gitignored. Write
them to `/tmp` or that directory, and read one before attaching it to an issue.

## Verifying against real hardware

The protocol layer has never met a real receiver. Before trusting it, point it at one - a short
Kotlin scratch main against `HeosConnection` is enough to confirm `get_music_sources` and a
`browse` round-trip. Mismatches between the fake server and the real thing belong in
`tools/transcripts/` as fixtures so they stay fixed.

The acceptance test for the whole architecture is still the one in the plan: queue an album with
`ReplaceAndPlay`, confirm the track transition is genuinely gapless, then force-stop the app and
confirm the music keeps playing.

## Continuing the branch

Work continues on `claude/android-smb-denon-player-9710bx`. Next up is phase 2: source
auto-detection, the browse screen, browse memory, and the four queue actions.
