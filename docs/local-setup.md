# Picking this up in a local session

The handoff file for local sessions - the ones with a route to the LAN, and therefore to the real
receiver at `10.1.10.50` and a real phone. (The project was started in a cloud container that had
neither, which is why so much below is phrased as "verified against real hardware": for the first
few phases that could not be taken for granted.)

Everything from "Probe findings" down is append-only history in date order. Read this top section
for the current state, and the last section for what happened most recently.

## Where the work stands

**Shipping.** v0.1.18 (`versionCode` 19) is published to GitHub Releases and the in-app updater
offers it. (The version moves often; `app/build.gradle.kts` is the authority, not this line.) All six phases in [`plan.md`](plan.md) are done and verified end-to-end against the real
AVR-X4500H. (A later "improvement plan", referenced by track letter further down this file, was
never committed here - its Tracks A-D are all done, so nothing is lost by its absence; the sections
below record what each one produced.)

- Every module in `settings.gradle.kts` is live: `:core:heos`, `:core:avr` and `:core:smb` are pure
  JVM, plus `:app` and `:core:data` when an Android SDK is present. Only `:feature:probe` is still
  commented out, and nothing wants it - `tools/probe.py` covers that ground.
- **244 unit tests, 0 failures, 0 skipped.** ktlint and `:app:lintDebug` clean.
- Verified on real hardware: browse, all four `aid` queue actions, gapless album playback,
  force-stop-and-keep-playing, browse memory across a reinstall, the AVR panel, the SMB bridge
  fallback, the Now Playing technical panel with chain integrity, LAN control, and the
  now-playing notification (including after the app's task is swiped away).

### Building here

The project now lives on Linux (`/workspace/android-denonmusic`); it was developed on Windows up to
v0.1.18, which is why the session log below mentions Windows paths and tools. The Android Gradle
plugin needs JDK 17+, so `JAVA_HOME` has to point at one or Gradle fails before it does anything.
Here it is already Temurin 21, and the SDK is `/opt/android-sdk`:

```sh
java -version                          # 17 or newer
./gradlew :app:testDebugUnitTest :core:heos:test :core:avr:test :core:smb:test :core:data:testDebugUnitTest
./gradlew ktlintCheck :app:lintDebug
./gradlew :app:assembleDebug
```

### Local-machine gotchas

- **`keystore.properties` has pointed at a path that no longer exists** (`C:/...` on the Windows
  machine), which fails `assembleRelease` with a missing-file error. Both it and `release.keystore`
  live at the repo root and are gitignored; `storeFile` should be
  `/workspace/android-denonmusic/release.keystore`. Before pointing it anywhere new, confirm the
  fingerprint with `keytool` - it has to match `EXPECTED_SIGNER` in `.github/workflows/release.yml`,
  or the APK will not install over an existing copy.
- **Home Assistant's `denonavr` integration (`connaught`) competes for the receiver's control
  ports.** While it holds its connection, telnet:23 and HEOS:1255 from anywhere else get reset
  instantly. See the probe findings below for how to free it.

### Picking this up on a different machine

Git carries the project; it deliberately does not carry what makes a build signable or a release
publishable. Before trusting the environment, check each of these:

| Thing | Why it needs checking |
|---|---|
| `release.keystore` | Irreplaceable. Without the same key, no new build can install over an existing one - Android refuses it. Confirm the file is present before planning a release. |
| `keystore.properties` | Holds an **absolute** `storeFile` path, so it is valid only where it was written. A release build failing on a missing keystore is almost always this line; it has happened here before. Check the `keytool` SHA-256 against `EXPECTED_SIGNER` in `.github/workflows/release.yml`. |
| `local.properties` | Same problem: `sdk.dir` is absolute. Regenerate rather than trust. |
| `JAVA_HOME` | Never carried. Must point at a JDK 17+; see "Building here" above. |
| `gh` authentication | Never carried. The Release workflow is dispatched with `gh`, and `gh` is also what git's HTTPS pushes borrow credentials from here. |
| An emulator image | Never carried, and the SDK at `/opt/android-sdk` has no `emulator` or `system-images`; install them with `sdkmanager`. It is the tool for anything version-specific, and the physical test phone may be too old to reach the behaviour in question. |

Verify with: `JAVA_HOME`, `gh auth status`, a TCP connect to `10.1.10.50` on 23 and 1255 (`ping` may
not be installed), `adb devices`, the keystore fingerprint, and a full
`./gradlew ktlintCheck :app:lintDebug` plus the unit tests.

Anything beyond that is specific to how a particular machine is set up, and is recorded outside
this repo - see `docs/handoff-prompt.md`.


## Probe findings (2026-09-13, against 10.1.10.50)

- **Home Assistant contention.** This receiver already has HA's `denonavr` integration
  (`connaught`) permanently attached to it. While that integration holds its connection, raw
  telnet:23 and HEOS:1255 connections from anywhere else get reset instantly (`ECONNRESET`), and
  `:10443/ajax/*` hangs on the TLS handshake. Reloading the HA config entry did **not** free the
  socket — only power-cycling the receiver did. If probing needs the receiver again, expect to
  need both: the HA integration disabled (it currently is - re-enable it in HA when the app is
  what should own the connection instead) and, if telnet/HEOS still won't respond after that, a
  receiver restart.
- **HEOS input mnemonic: confirmed `SINET`.** `SI?` returns `SICD`/`SINET`/etc.; sending `SINET`
  selects the network/HEOS input and the receiver echoes `SINET` back as an event. This is the
  command the auto-power-on path should send.
- **Speaker OUTPUT map: it's telnet `CV?`, not the `:10443/ajax` family.** Sweep/diff (Stereo →
  Multi Ch Stereo) showed the ajax speaker/audio endpoints are static setup-menu metadata
  (`display="1"` flags), not live channel state. `CV?` is: it returns one `CV<channel> <level>`
  line per **currently active output channel** - `CVFL/CVFR/CVSW/CVSW2` only in Stereo, plus
  `CVC/CVSL/CVSR/CVSBL/CVSBR` once Multi Ch Stereo is selected. The channels present in the
  reply are the green tiles; everything else is grey. This is simpler and more reliable than the
  ajax API, which timed out entirely before the receiver was rebooted.
- **`SSINFAISSIG ?` / `SSINFAISFSV ?` confirmed**, and self-documenting: e.g.
  `SSINFAISSIG 02` / `SYSDA PCM`, `SSINFAISFSV 441` (44.1 kHz). No separate lookup table needed -
  the receiver sends the human-readable label alongside the code.
- **`sid 1024` ("Local Music") is queueable but currently empty.** `browse/get_source_info`
  reports it `available: true`, but `browse/browse?sid=1024` returns zero items. No DLNA server or
  HEOS SMB network share is registered yet on this HEOS system - per the plan's own note, that
  needs fixing on the Unraid side (Gerbera/Jellyfin, or add the share directly in the HEOS app)
  before browse-into-folder and the queue actions can be exercised against real content. The app
  already detects and latches onto sid 1024 correctly; it just has nothing to show yet.

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
./tools/probe.py sources <avr-ip>

# 2. Which endpoint backs the speaker OUTPUT map?
./tools/probe.py sweep <avr-ip> -o /tmp/before.json
#    ...now change the sound mode on the receiver: Stereo -> Multi Ch Stereo...
./tools/probe.py sweep <avr-ip> -o /tmp/after.json
./tools/probe.py diff /tmp/before.json /tmp/after.json

# 3. Which mnemonic selects the HEOS input, and how does signal info encode?
./tools/probe.py avr <avr-ip> 'SI?' 'MS?' 'SSINFAISSIG ?' 'SSINFAISFSV ?'

# 4. What does the receiver emit unprompted? Operate it with the remote while this runs.
./tools/probe.py tap <avr-ip>
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

## Playback verified end-to-end (2026-09-13), plus a real protocol bug found and fixed

With no HEOS-indexed source populated yet (DLNA still pending, below), playback was verified via
the plan's own phase-6 bridge path (`browse/play_stream`), which was added early and gated behind
a "DEGRADED BRIDGE TEST" field in the empty-state UI specifically for this. A throwaway
`nginx:alpine` container (`denonmusic-test-http`, removed after) served
`/mnt/user/arr/media/music` read-only over HTTP so the receiver could fetch files directly - no
production container touched.

**Formats confirmed against the real AVR-X4500H, receiver-side (`SSINFAISSIG`/`SSINFAISFSV`),
volume held at 0 throughout:**

| Format | Result |
|---|---|
| FLAC 16/44.1 | `SYSDA PCM`, 44.1 kHz - confirmed via the app itself |
| MP3 | `SYSDA MP3` (the receiver reports MP3 as its own distinct signal type, not generic PCM) |
| DSF (DSD64, 2.8 MHz) | `SYSDA DSD`, `56M` - confirmed via the app itself, native, not downconverted |
| DFF (DSD64, 2.8 MHz) | `SYSDA DSD`, `28M` |
| M4A (Atmos source, E-AC-3 JOC) | `SYSDA PCM`, 48 kHz - expected: HEOS network zone downmixes, doesn't decode Atmos objects |

**Bug found and fixed:** `HeosProtocol.buildCommand` applied `escape()` (`%`→`%25` etc.) to
*every* attribute, including `url` - even though `url` is pinned last specifically so the receiver
takes it verbatim (its own doc comment said as much). A real URL's own percent-encoding (`%20`,
`%5B`, ...) got double-escaped into garbage the receiver's HTTP fetch couldn't resolve, so
`play_stream` silently kept whatever was already playing instead of erroring. Fixed in
`core/heos/.../HeosProtocol.kt` (`url`'s value is no longer escaped) with a regression test
(`HeosProtocolTest`, "does not escape the url attribute's own percent-encoding"). This would have
hit `add_to_queue` too the moment any `cid`/`mid` contained one of those three characters.

**Also observed:** `SSINFAISSIG`/`SSINFAISFSV` can read stale or transiently inconsistent
immediately (within ~3s) after a stream change - querying at ~6s+ settle consistently gives a
clean, matching pair. Worth a short settle delay wherever the app reads these after a play/queue
action, not just for probing.

## UI: Winamp-style theme

`app/src/main/kotlin/com/denonmusic/app/ui/WinampTheme.kt` - dark LCD-green palette, monospace
type, hairline Win9x-style bevels instead of Material elevation, and a decorative (non-audio-driven)
equalizer-bar flourish in the now-playing bar. Applied via `WinampTheme` in `MainActivity`. The
Browse screen now also has a compact now-playing bar (play/pause, volume slider, track info) wired
live off the HEOS event socket (`player_now_playing_changed`/`player_state_changed`/
`player_volume_changed`), not polling.

## DLNA setup - still pending on your end

Jellyfin's template is edited (1900/7359 udp port mappings removed) but **not yet applied** - the
running container still holds those ports (`ss` on sugar still shows `docker-proxy` owning them).
Plex's DLNA server is also still off (nothing on port 32469). Until one of these is done, `sid 1024`
stays empty and the primary HEOS-browse path has nothing to show - the bridge-mode field is a
stopgap for testing, not a replacement.

## Phase 3 done (2026-09-13): Now Playing + Queue, verified end-to-end

`PlayerViewModel` (`app/.../player/PlayerViewModel.kt`) is now the single source of truth for "the
currently selected HEOS player": it resolves the player id once, subscribes to the event socket
once, and exposes now-playing/play-state/volume/repeat/shuffle/progress/queue as one `StateFlow`.
It's hoisted at `MainScreen` (`app/.../nav/MainScreen.kt`), outside the nav graph, so Browse, Queue
and Now Playing all observe the one instance instead of each independently resolving the player and
re-subscribing - `BrowseViewModel` was trimmed back to browse-only concerns accordingly.

Navigation: a top `TabRow` (Browse/Queue) plus a persistent mini-player bar; tapping the mini-player
opens a full Now Playing screen (tap-to-expand, the usual mobile music-app convention) with
transport, seek progress, repeat/shuffle chips and volume. Queue screen adds swipe-to-remove
(`SwipeToDismissBox`), a long-press menu for play-now/move-up/move-down/remove, save-as-playlist,
and clear.

Added `HeosClient.getPlayMode` (`player/get_play_mode`) to paint the repeat/shuffle chips correctly
on screen entry rather than only after the first `set_play_mode` call - the client had `set` but not
`get` before this.

**A real edge-to-edge inset bug found and fixed while verifying on the phone:** the top `TabRow`
initially rendered with its labels completely hidden - `uiautomator dump` showed "BROWSE"/"QUEUE"
existed in the tree with correct bounds, but visually the whole tab strip painted as solid green
with invisible text. Cause: `MainActivity` calls `enableEdgeToEdge()`, so `MainScreen`'s bespoke
`Column` (unlike `Scaffold`, which every individual screen uses and which pads for insets
automatically) drew the `TabRow` directly under the physical status bar with no inset padding, and a
custom `TabRowDefaults.SecondaryIndicator(Modifier, color = Winamp.Green)` passed without anchoring
it to `tabPositions` rendered as a full-bleed fill rather than a thin line. Fixed by adding
`Modifier.statusBarsPadding()`/`.navigationBarsPadding()` on `MainScreen`'s root `Column`, marking
the status-bar inset consumed for the nav-host content below (`consumeWindowInsets`, so the nested
per-screen `TopAppBar`s - which ask for the same inset by default - don't reserve it a second time
and push their titles down further) and just dropping the custom indicator in favour of `TabRow`'s
default. Confirmed fixed via `adb shell screencap` before and after.

Verified against the real AVR-X4500H and phone: Browse/Queue tab switching, the mini-player
(tap-to-expand into Now Playing and back), transport controls, live volume (reading 37 from the
receiver), repeat/shuffle chips, and the Queue screen listing real queued items with working
overflow menus - all using the bridge-mode queue populated during phase-2 testing, since `sid 1024`
is still empty (DLNA/HEOS-share setup on Unraid remains pending, see above). Reorder
(`moveQueueItem`) and save-as-playlist are implemented but not yet exercised against hardware -
worth confirming once there's real queued content to reorder.

## Phase 4 done (2026-09-13): `:core:avr`, verified end-to-end

`:core:avr` is a new pure-JVM module (no Android dependency, same rationale as `:core:heos`):
`AvrConnection` (one telnet:23 socket, bare-`\r` termination, prefix-matched query/response since
this protocol carries no correlation id) and `AvrClient` (power/input, sound mode, volume/mute,
`SSINF*` signal info, `CV?` output map, and `ensureOnAndSelected` for the plan's auto-power-on path).
19 unit tests against a `FakeAvrServer` (real loopback socket, same shape as `FakeHeosServer`).

Wired into the app: `AvrSession` (mirrors `HeosSession`'s reconnect-with-backoff, one socket instead
of two), `AvrScreen`/`AvrViewModel` (new AVR tab: input, sound-mode chips including Pure Direct,
bit-perfect policy, OUTPUT map, signal type), and a `SettingsScreen` for AVR host/input mnemonic and
SMB credentials (plain DataStore for now, not the plan's EncryptedSharedPreferences - see caveat
below). Bit-perfect policy is applied from `PlayerViewModel` on the edge into `PlayState.Play` (not
every progress tick), per the plan's "on queue-start."

**Two real protocol bugs found and fixed by probing the actual AVR-X4500H directly** (raw
`TcpClient` against port 23, bypassing the app) rather than trusting the plan's `CV?` description:

1. **`CV?` has an explicit `CVEND` terminator line** the plan didn't call out ("no count or
   terminator" was wrong) - `CVFL 50`/`CVFR 50`/`CVSW 52`/`CVSW2 50`/`CVEND`. `AvrClient.outputChannels`
   now collects up to and including it (`AvrConnection.queryUntilTerminator`) instead of an
   earlier "wait for quiet" heuristic.
2. **The receiver free-runs a full status block (`SSINF*`/`CV*`/`MVMAX`/`DCAUTO`) roughly once a
   second, unprompted**, independent of anything the app asks for - confirmed by watching raw
   telnet output with no query in flight. A "wait until quiet" read (both for `CV?` and for
   `SSINFAISSIG ?`'s numeric-code + `SYSDA`-label pair) can never see quiet and just times out; this
   is what caused the OUTPUT map and SIGNAL panel to intermittently render empty on the phone despite
   `query()`-based single-line reads (power/input/sound mode) working fine. Fixed two ways:
   `SSINFAISSIG` now uses a fixed burst window anchored to the first matching line instead of
   quiescence (`AvrConnection.queryMatchingAny`), and the OUTPUT map stopped re-querying live
   entirely - `AvrViewModel` now parses the `CV.../CVEND` blocks the receiver already free-runs
   straight off the event stream (`AvrClient.parseChannelLine`), which turns the telemetry into a
   live-updating view for free instead of something to race against. A regression test
   (`output channels ignore further CV telemetry the receiver free-runs after CVEND`) pins this.

**Also found and fixed while verifying on the phone:** `AvrScreen`'s content column had no
`verticalScroll`, so on a phone-height screen the OUTPUT map's bottom row and the SIGNAL section were
laid out but unreachable - confirmed via `uiautomator dump` (nodes existed, off-screen) and fixed by
adding `.verticalScroll(rememberScrollState())` and replacing the nine-tile `LazyVerticalGrid` with a
plain chunked `Column`/`Row` grid (a lazy grid can't nest inside a scrollable column with no fixed
height - it measures against an infinite constraint).

**Verified against the real AVR-X4500H:** input (`SOURCE: NET`), sound mode (`Stereo` correctly
highlighted), bit-perfect policy chips, and the OUTPUT map/SIGNAL panel - `FL`/`FR`/`SW`/`SW2` green
and everything else grey while in Stereo, `SIGNAL: PCM`, matching the plan's documented Stereo-mode
channel set exactly. Power toggle and sound-mode writes are covered by unit tests (wire format
verified) but were *not* exercised interactively against the real receiver during this session, since
it was actively playing audio in the room at the time - avoid flipping `PWSTANDBY` or switching sound
modes on a live listening session without warning whoever's in the room.

**Known gap:** SMB credentials in `SettingsScreen` are stored in plain DataStore, not the plan's
EncryptedSharedPreferences. Deliberately deferred at the time it was written - `androidx.security-crypto`
plus Keystore wiring is its own chunk of work. Now that Phase 5 has real credentials that do get typed
into this screen (see below), treat this as higher priority than "before shipping" - do it next.

## Phase 5 done (2026-09-13): `:core:smb`, verified end-to-end against real files

`:core:smb` is a new pure-JVM module (jcifs-ng has no Android dependency, same rationale as
`:core:heos`/`:core:avr`): stream-based header parsers for every format the plan lists (FLAC
`STREAMINFO`, MP3 frame header + Xing/Info VBR detection, DSF header, DFF `FRM8`/`PROP`/`FS` chunks),
artwork extraction (FLAC `PICTURE` block, ID3v2.3/2.4 `APIC` frame), and `SmbOverlay` - the read-only
jcifs-ng wrapper that stats a file, parses its header (capped at 64 KB per file per the plan), and
finds folder-level `folder.jpg`/`cover.jpg` art. 41 unit tests, all against hand-built byte fixtures
(`TestFixtures.kt`) rather than binary files committed to the repo - the parsers only ever look at a
handful of header bytes, so a real encoder's output adds nothing but binary noise for the same
coverage.

Wired into `:core:data` (`MediaInfoCacheEntity`/`Dao`, keyed by path+mtime+size exactly as the plan
specifies - any of the three changing is a cache miss by construction, no separate invalidation path
to keep in sync) and into the app (`MediaInfoRepository` bridges the two; `ChainIntegrity` is the pure
comparison function the plan calls the payoff for this whole module - file-side truth vs. AVR-side
`SSINF*` truth, flagging a DSD-arrives-as-PCM transcode or a resampled sample rate; 7 unit tests). A
debug-only "SMB PARSE TEST" panel was added to `SettingsScreen`, mirroring the Browse screen's
degraded-bridge tester, specifically so this could be exercised against a real share without a full
technical-panel UI (Now Playing's technical panel + chain-integrity indicator - the UI consumer of
all this - is not yet built; this phase is the data layer under it).

**Verified end-to-end against the real unraid share (2026-09-13).** The plan's own SMB path had never
touched a real server before this - jcifs-ng's network behaviour, not just the parsers, was unverified.
Two real snags surfaced getting there, both about *authentication*, not about this project's code:
- The user shares `music` and `arr` are both browsable with no credentials at all from a Windows
  session (unraid allows guest/anonymous read) - which is a red herring for what jcifs-ng needs, since
  it authenticates explicitly rather than falling back to guest the way Explorer does.
  A first credential guess (`shares`/`shares`) failed both `net use` from Windows and jcifs-ng with
  the same `SmbAuthException: Logon failure: unknown user name or bad password` - confirming it was a
  genuinely bad credential, not a project bug.
- The correct SMB path is `\\10.1.10.10\arr\media\music\...` (share `arr`, not `music`) with a
  dedicated `denon`/`denon` account - once entered, `SmbOverlay` correctly stat'd, parsed, and pulled
  art for real files: a 24-bit/44.1kHz FLAC (Sinatra) and a DSD128 `.dsf` (Bill Evans, `5,644,800 Hz`,
  1-bit, stereo - matches the plan's DSD128 rate exactly). Verified via a throwaway JVM test exercising
  `SmbOverlay` directly (not committed - see the module's tests for the fixture-based coverage that
  *is* kept).

**The Settings screen itself was also filled in and exercised on the phone** (host `10.1.10.10`,
share `arr`, username/password `denon`/`denon`) - this took real trial and error via `adb shell input
tap`/`input text`, not because of any app bug: the on-screen keyboard covers the lower fields, so a
coordinate valid before the keyboard opens is invalid after (it now points at a keyboard key or an
obscured field), and `KEYCODE_BACK` reliably closes the IME alone without navigating away, while
`KEYCODE_ESCAPE` does the opposite. Once filled correctly and saved, the app's own "SMB PARSE TEST"
panel returned `AudioFormatInfo(container=Flac, sampleRateHz=44100, bitsPerSample=24, channels=2, ...)`
for the real Sinatra file above - the full path (`SettingsScreen` -> `SettingsViewModel` ->
`MediaInfoRepository` -> `SmbOverlay` -> real jcifs-ng call -> `FlacHeaderParser`) verified through the
running UI, not just a standalone JVM test.

## Phase 6 done (2026-09-13): bridge-mode fallback, the plan's actual design

The plan's phase-6 fallback is "the phone serves SMB over Range-capable HTTP via `play_stream?url=`."
The phase-2 "DEGRADED BRIDGE TEST" tester (a raw URL field calling `play_stream` directly) satisfied
"opt-in, clearly labelled" but not the "phone serves SMB" part - it required something else (an nginx
container, in the phase-2 testing) to actually be the HTTP server. This phase builds that server.

`SmbBridgeServer` (`:core:smb`) is a minimal single-purpose HTTP/1.1 server over a raw `ServerSocket`:
one file per request, thread-per-connection, `Connection: close`, Range-capable (single range only -
the only form this app ever needs). `BridgeResource` describes one servable file (length, MIME type,
and a function that reopens the underlying stream at any byte offset - reopen-and-skip rather than
holding one shared seekable stream, since nothing here needs more than one receiver fetching one file
at a time). `SmbOverlay.openBridgeResource` backs a resource with a real SMB file. 8 new unit tests
(38 total in `:core:smb` now) cover plain GET, ranged GET (closed and open-ended), unknown token,
out-of-range request, and the content-type mapping.

Wired into the app as `SmbBridgeService` (one server for the process lifetime, started lazily): given
an SMB-relative path and the saved credentials, it registers a token, finds the phone's own LAN IPv4
address (`NetworkInterface` enumeration, skipping loopback), and returns a
`http://<phone-ip>:<port>/<token>` URL ready for `play_stream`. The Browse screen's bridge tester now
offers this as the primary option ("PLAY FROM SMB", a bare file path) alongside the original raw-URL
field, both still gated behind the same "DEGRADED BRIDGE MODE - no gapless, no DSD guarantee" label
the plan calls for.

**Verified against the real unraid share, deliberately stopping short of playing audio.** A direct
JVM test opened a real `SmbOverlay.openBridgeResource` against the Sinatra FLAC (14,258,733 bytes),
started `SmbBridgeServer` on it, and fetched `bytes=0-9` over a real loopback HTTP request: got back
`206 Partial Content`, `Content-Type: audio/flac`, `Content-Range: bytes 0-9/14258733` - the exact
shape a receiver's Range GET would see. Stopped there rather than actually calling `play_stream`
against the real AVR-X4500H, since it was audibly playing something in the room at the time and
swapping its stream without asking first would have interrupted whoever's listening - the full
loop (URL -> `play_stream` -> audible on the receiver) is the one piece of phase 6 still to confirm
in person.

## SMB file browser added to the bridge fallback (2026-09-13)

The phase-6 bridge tester originally needed an exact path typed by hand (`Artist/Album/track.flac`).
`SmbOverlay.listDirectory` (folders first, then only files whose extension a header parser actually
recognises - `.nfo`, playlists, artwork are filtered out since this listing exists to pick something
to play, not to be a file manager) backs a small `SmbBrowseViewModel` and a breadcrumb + folder-list UI
in the Browse screen's "BROWSE SMB SHARE" toggle, so a file can be tapped through folders instead.
Tapping a file calls the same `playBridgeFromSmb` the manual-path field already used.

**Verified against the real unraid share**: opened the browser, saw the real share root
(`copyparty`/`images`/`media`/`test`/`torrents`), navigated `media` -> `music` and saw the real genre
folders (Alternative/Atmos/Blues/City Pop/Classical/...), and confirmed the breadcrumb/collapse toggle
both work without crashing. Stopped short of tapping an actual audio file, for the same reason as the
phase-6 write-up above - it would call `play_stream` against the real AVR-X4500H and swap whatever was
audibly playing in the room. 2 new unit tests for the file-type filter (`isSupportedAudioFile`);
`listDirectory` itself isn't unit tested, consistent with `findFolderArtwork`'s existing pattern -
jcifs-touching directory calls are verified against the real share by hand, not mocked.

## Phase 6's full loop verified, and a real threading bug fixed (2026-09-13)

With the file browser in place, actually pressed "PLAY FROM SMB" against a real file. First attempt
failed with the exact same message the plan-6 write-up above assumed away: *"Set SMB host/share in
Settings first, or path not found"* - misleading, since credentials were plainly fine (the browser
had just listed real folders using them). Temporary logging pinned the real cause: `SmbBridgeService
.urlFor` called `overlay.openBridgeResource(path)` directly on the caller's dispatcher
(`viewModelScope`'s `Main`), and jcifs-ng's blocking socket I/O threw `NetworkOnMainThreadException`
- silently, because it was inside a bare `runCatching { }.getOrNull()` with nothing logging the
failure. `SettingsScreen`'s own SMB tester never hit this because `SettingsViewModel.testSmbPath`
happens to wrap its whole body in `withContext(Dispatchers.IO)`; `SmbBridgeService.urlFor` and
`MediaInfoRepository.getFormatInfo` did not. Both now do - `getFormatInfo` wraps internally so this
can't be forgotten again by some future caller the way it was here.

**Verified end-to-end at volume 1** (set directly via a raw `heos://player/set_volume` call, then
confirmed with `get_volume`, before touching playback): tapped "PLAY FROM SMB" on the real Sinatra
FLAC, got "Streaming (degraded bridge mode)", and confirmed on two independent channels that real
audio was actually flowing - the AVR's own telnet `SSINFAISSIG ?` reported `SYSDA FLAC` at `SSINFAISFSV
441` (44.1 kHz, exactly matching the file), and HEOS's `player/get_play_state` reported `state=play`.
Paused afterward via `set_play_state`. This closes the one gap the original phase-6 write-up left
open - the full loop (browse -> bridge URL -> `play_stream` -> audible, decoded output on the
receiver) is now confirmed, not just the HTTP layer in isolation.

## Bridge queue, folder playback, and real Now Playing info (2026-09-13)

Four follow-up requests turned into one connected piece of work: real technical info in Now Playing
instead of a bare "Url Stream" label, an explanation of what the empty Browse state actually means,
switching the SMB share to `\\10.1.10.10\music` (`denon`/`denon` - a top-level share whose root is
already the genre folders, so paths no longer need a `media/music/` prefix), the SMB browser
resetting to the root every time it's reopened, and - raised mid-turn - wanting to play a whole
folder instead of one file at a time, add to a queue, and get real repeat/shuffle.

**`BridgeQueueLogic`** (`app/bridge/BridgeQueue.kt`) is the answer to all of the queueing asks at
once: pure, unit-tested (12 tests) sequencing rules for the bridge's own client-side queue -
`play_stream` starts exactly one stream with no real HEOS queue behind it, so next/previous/repeat/
shuffle/add-to-queue all have to be reimplemented here rather than delegated to HEOS. Shuffle tracks
which indices have played since the last exhaustion so a pass covers every track before repeating.
`BridgeQueueController` is the thin side-effecting wrapper: starts a stream via `SmbBridgeService`
and `HeosSession.heosClient.playStream`, and watches `player_state_changed` -> `stop` to advance
automatically.

**A real race found via the first test, not by inspection:** "PLAY ALL IN FOLDER" on a 6-track album
immediately jumped to track 2 before track 1 had audibly started. Starting a *new* stream while an
old one is still nominally playing makes the AVR-X4500H emit its own transitional `stop` for the
outgoing stream moments later - confirmed on real hardware, not a theoretical race - and that stray
event reached the controller's advance-on-stop listener and was misread as "track 1 already
finished." Fixed with a 4-second guard on `stop` events measured from when the current track was
started - long enough that no legitimately-finished track could be that short, short enough it never
delays a real advance. Re-tested against a real 14-track deluxe-edition folder: correctly stayed on
track 1 for its full length, then genuinely advanced to track 2 on its own.

**A second real discovery changed how "is the bridge active" gets decided.** The original plan was
"HEOS reports the generic `play_stream` label, so anything else means treat it as real HEOS
playback" - reasonable-sounding, and wrong: the AVR-X4500H actually reads embedded tags out of a
streamed file and reports the *real* title/artist/album once it's parsed them (confirmed live -
`get_now_playing_media` returned "The Story of 'Graceland' (as told by Paul Simon)" / "Paul Simon —
Graceland" mid-bridge-playback). `PlayerViewModel.isBridgeModeActive` now trusts the bridge queue's
own state as ground truth instead (`bridgeQueue.currentItem != null`), and `BrowseViewModel`'s
primary HEOS queue actions (`queue`/`playAllCurrentContainer`) call `bridgeQueueController.clear()`
on success so choosing the primary path explicitly hands Now Playing/transport control back to real
HEOS behaviour.

**Verified end-to-end against the real AVR-X4500H and the real 14-track "Graceland 25th Anniversary
Edition" folder** (volume set to 1 directly via `player/set_volume` beforehand): opened the folder,
tapped "PLAY ALL IN FOLDER", watched track 1 play to completion and genuinely auto-advance to track
2, and confirmed Now Playing showed "02. Graceland.flac" / "DEGRADED BRIDGE MODE" /
"FLAC 24-bit/96.0 kHz Stereo" with live progress (`1:47 / 4:51`) - all three technical fields read
from the real file header via `MediaInfoRepository`, not placeholders. The Queue tab correctly
switched to showing the 14-item bridge queue (all bonus tracks included) with the current item
highlighted, and its own repeat/shuffle/clear controls.

**SMB browser now remembers its folder.** `SmbBrowseViewModel.open()` was unconditionally resetting
to the root every time the collapsible panel was re-expanded - not a persistence gap, an outright
bug (it recreated `SmbBrowseUiState` from scratch on every call regardless of whether the same
credentials were already active). Fixed to no-op when the same credentials are already loaded, and
added actual cross-restart persistence via a new `smbBrowsePath` DataStore field, restored on first
open and written back after every successful `listDirectory` call (falling back to the root if a
saved path no longer resolves, the same recovery the primary HEOS browse stack already uses).
Verified across an app reinstall: reopening the browser landed directly back in
`ROOT > FOLK > PAUL SIMON > GRACELAND 25TH ANN` with no extra taps.

**The empty Browse tab now explains itself in-app**, not just in this doc: "NOTHING HERE." is
followed by "No content indexed by HEOS yet - register a DLNA server or SMB network share in the
HEOS app to browse normally here," so the connection to the DLNA-setup gap tracked above is visible
without reading source.

## Continuing the branch

Work continues on `claude/android-smb-denon-player-9710bx`. Phases 1-6 are all done and verified
against real hardware end-to-end, including the phase-6 bridge's full loop (see just above). The one
remaining gap called out earlier: `EncryptedSharedPreferences` for SMB credentials is still
outstanding - there's a real account's credentials going through plain DataStore now, so this should
be next. Once a DLNA server or HEOS-native SMB share is registered in HEOS itself (still pending - see
the DLNA section above), `sid 1024` stops being empty and the primary HEOS-browse path (not the
phase-6 fallback) becomes exercisable with real content: re-verify browsing into a folder, all four
`aid`
actions, force-stop/relaunch restoring the same folder and scroll position, and queue reorder.

With all six planned phases in place, what's left is UI depth rather than new architecture: the Now
Playing technical panel + chain-integrity indicator (the plan's payoff for `:core:smb`/`ChainIntegrity`
existing at all - currently only reachable via the debug SMB parse tester, not surfaced during actual
playback), folder-level format badges in Browse, and the `EncryptedSharedPreferences` migration.

Next up: EncryptedSharedPreferences for the now-real SMB credentials (see the known gap above), then
the Now Playing technical panel + chain-integrity indicator UI that consumes `:core:smb`/`ChainIntegrity`,
folder-level format badges in Browse, and Phase 6 (bridge-mode fallback polish - the degraded
`play_stream` path already exists from earlier testing but was never meant to be the primary path).

> Superseded: everything in that "next up" list has since shipped, `EncryptedSharedPreferences`
> included (0.1.16). Nothing in it is outstanding.

## Plex DLNA as the primary source, and three real bugs it exposed

`sid 1024` stopped being empty once Plex's DLNA server was fixed up on the unraid side (a port
conflict with Jellyfin over `1900/udp` was blocking Plex's own DLNA announce - unrelated to this app,
fixed at the infra layer). The moment there was real nested content to browse into, three bugs surfaced
that a same-level, always-fast source (like the earlier SMB-bridge testing) never exercised:

1. **`BrowseItem` never parsed the wire's `sid` field.** Browsing the aggregate "Local Music" source
   (sid 1024) returns one row per DLNA/HEOS server behind it, each carrying its own `sid` instead of a
   `cid` within 1024 - the model had no field for that at all, so every such row silently had `cid =
   null`. Added `BrowseItem.sid`, and `BrowseViewModel.open()` now switches to `item.sid` with a null
   `cid` when present, instead of always extending the current level's `sid` with `item.cid`.

2. **A nested-source row has no `container` field at all**, not even `"no"` - `isContainer` defaulted
   to `false` for it, so tapping the row silently no-opped (neither the container nor the track branch
   in `BrowseRow`'s `onClick` matched). Fixed by defaulting `isContainer` to `true` whenever the row
   carries a `sid` to browse into.

3. **The real one, at the protocol layer**: browsing into a DLNA/Plex source is slow enough that the
   receiver sends an interim `"command under process"` acknowledgment before the real result - and that
   ack echoes back every argument from the request, `SEQUENCE` included. `HeosConnection.complete()`
   matched pending requests by `SEQUENCE` first, so it treated the ack as the final answer, completed
   the deferred with no payload, and removed the pending entry - meaning the real result that arrived
   moments later had nothing left waiting for it and was silently dropped. Every browse into Plex came
   back "NOTHING HERE" even though the receiver was about to send exactly the right payload. Fixed by
   adding `HeosFrame.isCommandUnderProcess` (detects the bare `"command under process"` token
   `parseMessage` puts in the attributes map) and having `complete()` ignore that frame instead of
   resolving on it. This was invisible in every previous test because every source touched so far
   answered in a single frame; it only shows up once a source is slow enough to need the two-frame
   ack/result pattern, which the spec allows for any command, not just browse.

Verified against real hardware end-to-end after the fix: BROWSE > LOCAL MUSIC > Plex Media Server:
Sugar > Music > Music > All Artists > Adele > 21 > Rolling in the Deep, queued and playing through real
HEOS (`qid=1`, `get_play_state` returned `state=play`), Now Playing showing the real title/artist/album
- not bridge mode, no `play_stream` involved. Tested at volume 8.

## Atmos/M4A/MP4 support, file-type + hi-res indication, and a real bit-perfect bug

Added `Mp4HeaderParser`: a minimal ISO-BMFF (`moov > trak > mdia > minf > stbl > stsd`) box walker for
`.m4a`/`.mp4`, since the bridge had no parser for either before this - `isSupportedAudioFile` didn't
even list them. Reports the real codec inside the wrapper (`AAC`, `ALAC`, or the object-audio codecs
Atmos rips are muxed as, `E-AC-3 (Atmos)`/`AC-4 (Atmos)`), not just "MP4". ALAC needs its own
`ALACSpecificConfig` child box for the real sample rate: the generic 16.16 fixed-point field elsewhere
in the sample entry can only hold a 16-bit integer part, so it cannot represent anything above 65535Hz
at all - every 96/192kHz hi-res ALAC file would otherwise silently misreport its own sample rate.
`AudioFormatInfo` gained `codecLabel`, `isLossy`, `isAtmos` and `isHiRes` (JAS's own definition:
lossless PCM at 24-bit/48kHz+, or DSD at any rate - a lossy codec, Atmos included, is never Hi-Res
regardless of its nominal sample rate). `SmbOverlay`'s header-read budget went from 64KB to 512KB to
give the MP4 walk more room to reach `moov` on a file with a larger-than-usual one.

Verified against the user's real Atmos rips at `\\10.1.10.10\music\Atmos\Prince\2026 Timeless Atmos`
(`.m4a` and `.mp4` siblings of the same tracks): browsed, played, and the Now Playing technical line
correctly read `MP4 (E-AC-3 (Atmos)) 16-bit/48.0 kHz Stereo • ATMOS` for a real file, confirming actual
audible playback through the bridge. Genuine object-based Atmos rendering isn't achievable this way -
that needs HDMI bitstream passthrough to the AVR's own decoder, not a network audio stream - so this
is "plays the Atmos rip" (E-AC-3 decoded to stereo/whatever channel count HEOS negotiates), not
"renders Atmos objects." Worth being explicit about that distinction if it comes up again.

File-type badges (extension only, no per-row header read - cheap) now show next to every file in the
SMB browser; the fuller picture (codec, bit depth, Hi-Res/Atmos badges) only gets read once a track is
actually played, on the Now Playing screen's technical line, same as `AudioFormatInfo` always worked.

Also found and fixed a real bug while wiring the AVR-technical-info Now Playing panel added earlier:
choosing "AutoDirect"/"AutoPureDirect" under Bit-Perfect Policy on the AVR screen only ever persisted
the setting - `AvrViewModel.setBitPerfectPolicy` never actually called `AvrClient.applyBitPerfectPolicy`,
so nothing happened until whatever track was already queued happened to restart (the only place that
was actually wired, in `PlayerViewModel`'s queue-start edge). Fixed by applying it immediately too.
Verified on real hardware: selecting AutoPureDirect while a track was already playing changed `MS?`
from `MSSTEREO` to `MSPURE DIRECT` right away, no track restart needed.

## Follow-up: display dimming, Atmos-as-PCM, and native-path signal classification

Three things found chasing user reports against real hardware again after the above:

- **The AVR's front display staying lit under Pure Direct is not an app bug.** `MS?` correctly
  reports `MSPURE DIRECT` engaged, but the receiver only auto-dims to `DIM DAR` (Dark), not fully
  off, despite Denon's own docs describing Pure Direct as disabling the display outright. Tried
  overriding this with an explicit `DIM` setter three different ways over telnet directly (`DIM OFF`,
  `DIM DAR`, `DIMDAR` with no separator) - none of them changed what a subsequent `DIM ?` reported
  back. This receiver's display dimmer isn't remotely controllable through this command on this
  model/firmware (or needs a command this project has no documentation for), so it's left alone -
  shipping a "fix" that doesn't actually do anything would be worse than not touching it.
- **Atmos files playing back as "PCM 2.0" on the receiver's own display is expected, not a bug.**
  HEOS's network-streaming module always decodes network audio to PCM internally before handing off
  to the amp section - it does not forward a compressed bitstream (Dolby/DTS/Atmos) through for the
  amp's own decoder to process. That decode path only exists for HDMI/optical inputs. So this app
  (or any HEOS network client) playing an Atmos-muxed file will always show as decoded PCM at
  whatever channel count HEOS negotiates - genuine object-based Atmos rendering needs HDMI bitstream
  passthrough, which no network stream can carry. Not something fixable from this side.
- **`AvrClient.signalType()` now classifies HEOS/network audio as PCM correctly.** The AVR-X4500H
  never sends the human-readable `SYSDA` label for network-sourced audio (only for HDMI/optical
  inputs, confirmed by direct probe) - only a bare numeric `SSINFAISSIG` code, `18`, with no official
  table in this project to look it up against. Empirically, every HEOS/DLNA track queued through this
  app - regardless of its own container or codec - reports this same code, which matches the
  architecture above: a network stream can never arrive as anything but PCM here. Added `18 -> Pcm` to
  `classifyByCode`'s fallback. Verified on real hardware: Now Playing's technical line for a native
  Plex-DLNA track changed from the generic "SIGNAL 44.1 kHz" fallback to "PCM 44.1 kHz - 4 ch active".
  Also added a sample-rate-only Hi-Res proxy for this same native path (PCM >=48kHz, or any DSD) -
  the AVR's telnet port has no bit-depth command for a network-sourced signal, so this is a weaker
  signal than the real, header-derived `AudioFormatInfo.isHiRes` the bridge path gets.

## Now Playing losing sync, and album art

Two more real-hardware-driven fixes:

- **"Now Playing loses sync" traced to `HeosSession`'s event socket silently dying.** The session
  keeps two sockets (one for commands, one registered for change events per the spec), but the
  25-second heartbeat loop only ever watched `commands.isConnected` - a closed local socket object,
  which says nothing about whether the far end is still actually delivering anything on the *other*
  socket. A router NAT timeout or WiFi blip that only clips the event connection left it looking
  "connected" forever while `player_state_changed`/`_progress` events just stopped arriving - the UI
  freezes on whatever it last knew while the receiver keeps moving. Fixed by sending `heart_beat` on
  *both* sockets every cycle, so a dead event connection surfaces as a real timeout/IOException and
  sends the session into its existing reconnect-with-backoff path instead of staying silently stuck.
  Added a second, independent safety net too: `PlayerViewModel` now polls a full `refreshAll`/
  `refreshQueue` every 15s regardless of events, since even a healthy socket can miss the odd event,
  or a third party (the receiver's own remote, another HEOS app) can change state without firing one
  this app happens to be listening for.
- **Album art**, requested for both playback paths. Native/DLNA already had `NowPlaying.imageUrl`
  from HEOS itself - just needed rendering. Bridge mode had no image source at all: added
  `SmbOverlay.findArtwork` (folder `folder.jpg`/`cover.jpg` first, per the plan's own preference,
  falling back to an embedded FLAC/MP3 tag picture via the already-existing but previously
  never-wired-up `ArtworkExtractor`) and `SmbBridgeService.artworkFor`, fetched alongside format
  info in `BridgeQueueController.playCurrent`. Added `RemoteArtwork`/`LocalArtwork` - two small
  hand-rolled Compose loaders (a plain `HttpURLConnection` fetch + `BitmapFactory`, an in-memory
  cache for the URL one) rather than pulling in an image-loading library for the one place this app
  needs one. Verified on real hardware: both a native Plex-DLNA track and a bridge-mode Atmos `.m4a`
  (folder art, since `ArtworkExtractor` has no MP4/M4A embedded-picture support) showed real cover
  art on the Now Playing screen.

## Search probed against real hardware (2026-09-21): not usable, jump-index stays the answer

The improvement plan's Track C2 called for probing `browse/get_search_criteria` before building a
search UI, since HEOS search support is source-dependent. Ran against the real AVR-X4500H
(10.1.10.50):

- `browse/get_search_criteria?sid=1024` (the aggregate "Local Music" source) returns an **empty**
  criteria list - no search at all at that level.
- The nested Plex DLNA server (`browse/browse?sid=1024` returns it as its own source, `sid=-66917606`
  in this session) *does* advertise criteria: Artist (`scid=1`), Album (`scid=2`), Track (`scid=3`,
  playable).
- But every actual `browse/search?sid=-66917606&search=...&scid=...` call against it - tried all
  three criteria, with and without a `range` param - fails identically:
  `eid=12, text=System error, syserrno=-10`. `browse/search?sid=1024&...` (the aggregate) fails
  differently: `eid=7, Command not executed`, i.e. rejected outright rather than attempted.

So this receiver's Plex-DLNA integration lies about search support at the criteria-discovery layer
but the actual search call is broken - not a client-side bug, nothing to retry or reformat around,
and no other source on this system is queueable enough to be worth testing search against. Building
a search UI on top of this would ship a control that always errors. Per the plan's own fallback
branch, the already-shipped client-side A-Z jump index (`AlphabetJumpIndex` in `BrowseScreen.kt`) is
the correct and sufficient answer here - no protocol-level search code was added. If a future
receiver/firmware/Plex version fixes `browse/search`, re-probe with the same three commands before
reconsidering.

## Review pass, and v0.1.8 (2026-09-24)

A read of all ~96 Kotlin files, fixing what it found. The whole list is in the `0.1.8` changelog
entry in `AboutScreen.kt`; the ones worth knowing as protocol or lifecycle facts:

- **`setVolumeDb` sent a badly wrong value for half-dB steps below -70 dB.** Only the whole-dB
  branch zero-padded, so -74.5 dB went out as `MV55`, which the receiver reads as -25 dB - a 50 dB
  error, in the loud direction. This is the direct-AVR path (`:core:avr`), not the app's own volume
  slider, which goes through HEOS and was never affected.
- **`MV?` answers with two MV-prefixed lines, not one.** Verified live against 10.1.10.50: `MV40`
  followed by `MVMAX 98`. `volumeDb()` matched a bare `"MV"` prefix, so it could pick up `MVMAX`,
  parse no digits out of it and return null. It now excludes `MVMAX` explicitly. Any future
  response-prefix match on this protocol needs the same care - there are no correlation ids on
  port 23, so prefix matching is all there is.
- **`HeosSession`/`AvrSession` leaked two connections per reconnect** by calling `close()` (which
  cancels the reader job but leaves the connection's own `CoroutineScope` running) instead of
  `shutdown()`. `HeosSession` also sat at `Connected(host)` through the whole backoff delay while
  `heosClient` was already null, and `start(host)` early-returns on `Connected(host)` - so it
  refused to reconnect to the host it had just lost.
- **`AvrConnection` had a real data race**: the response buffer was appended from the reader
  coroutine under a lock, but read without one from the polling caller on a different IO thread.
  Now snapshot-based.
- The release-notes generator was chopping every wrapped changelog bullet into its own fragment, in
  both `publish-release.ps1` and `.github/workflows/release.yml`. v0.1.6's and v0.1.7's published
  notes still show the damage; see the note under "Cutting a release" in
  [`README-release.md`](../.github/workflows/README-release.md) before editing either parser.

### Open questions left deliberately alone

*Both of these were settled on 2026-09-25 - kept here because the answers are the interesting part.*

- **Does the receiver really free-run telemetry "roughly once a second"?** ~~Unknown.~~ Measured:
  no. The block (`SSINFAISSIG`/`SSINFAISFSV`/`CVFL`..`CVEND`/`MVMAX`/`DCAUTO`, about nine lines
  inside 600ms) repeats roughly **every 15 seconds**, not every second, and is not reliably
  unprompted - three separate 30-second taps that sent nothing, in standby and while playing, saw
  no lines at all, while a connection that had sent one command saw a block every ~15s after. The
  comment in `AvrConnection` now says this; the burst-window design it justifies is unchanged.
- **Both passwords are still in plain DataStore.** ~~Outstanding.~~ Done in 0.1.16 - see the
  encrypted-secrets note further down.

## `add_to_queue` is asynchronous, and says "Out of range" when it isn't (2026-09-25)

Reported from the app: "PLAY ALL" on some folders came back
`HEOS browse/add_to_queue failed: eid=9 Out of range`. It correlated with folders containing an
`Artwork` subfolder, which turned out to be a red herring - measured against 10.1.10.50:

- `browse/add_to_queue` answers **success in ~30ms**, but the receiver keeps filling the queue for
  a good while after - ~450ms for a 13-track container, ending with `event/player_queue_changed`.
- A second `add_to_queue` sent inside that window is rejected with `eid=9`, text "Out of range".
  The spec's code 9 is "parameter out of range" and there is a separate code 13 for "processing
  previous command"; this receiver does not use it here. **Nothing is wrong with the parameters.**
- Sweeping the gap between two container adds: **0ms fails every time, 250ms and up never did.**
  Ten adds fired back to back with retry needed at most three attempts each.

So it broke precisely the folders `QueueTargetResolver` resolves to more than one target - a
multi-disc album, or any album whose tracks are queued individually - because `addAll` loops with
no gap. The first add replaced the queue and the rest were refused, which reads in the UI as the
folder being unplayable. `HeosClient.addToQueue` now retries while `HeosError.isBusy`.

The same session made an oversized folder refuse promptly instead of after minutes: the walk used
to gather everything and let the caller check the size, so a huge container was paged through in
full before being refused. `QueueTargetResolver.collect` now returns `QueueCollection.Complete` or
`.TooMany` and gives up the moment the budget is blown. A subtree legitimately inside the limit is
still walked in full, so a deep one still takes its time - only the refusals got faster.

**Probing note:** `tools/probe.py`'s `heos` subcommand used to return the interim "command under
process" ack for any slow DLNA browse, showing an empty payload where the real answer followed a
moment later - the same trap the app itself hit long before (see
[`heos-dlna-plex-jellyfin-conflict.md`](heos-dlna-plex-jellyfin-conflict.md)). The probe was fixed
too on 2026-09-25 and now skips those frames. Anything else scripted against this protocol needs to
do the same.
## The notification, the state tracker, and encrypted secrets (2026-09-25)

A now-playing notification with transport controls, and the three things that had to change under
it. Shipped across 0.1.12 to 0.1.18.

- **`PlayerStateTracker` now owns the receiver's state**, not `PlayerViewModel`. A view model dies
  with the screen, and the notification has to keep telling the truth after the last screen is
  gone. The view model became a screen's view of that state plus the actions a screen can take, and
  lost about 180 lines without a single screen changing - `PlayerUiState` and every field name
  stayed put. `attach`/`detach` counts who needs live state (a screen, or the notification), so the
  one resync loop stops when nobody does, and drops to a 60s tick when only the shade is watching.
- **`aid=4` is not safe to follow with another add.** Measured: sent alone it replaces the queue and
  plays; with a second add arriving straight after, *both* halves are lost - the old queue survives
  and the new parts are merely appended, stopped. So a multi-part replace clears the queue, appends
  every part with `aid=3`, then plays. Same asynchrony as the `eid=9` above.
- **Both stored passwords are encrypted now** (0.1.16), in `EncryptedSharedPreferences` behind a
  keystore-held key, with a one-time migration out of plain DataStore. Verified on a real phone: a
  token set through the UI authenticates, survives a force-stop, and appears nowhere in the clear -
  not in the encrypted file, where even the key names are encrypted, and not in DataStore.

### Where the notification actually appears, which cost three releases

It renders in the **system media panel, not the notification list**. On Samsung's One UI that is the
bottom of the *second* pull-down, past the Quick Settings tiles. It had been working and being
looked for in the wrong place, and three releases of fixes went out aimed at a fault that was not
there.

What that episode is worth remembering for:

- **Diagnose before fixing.** `dumpsys notification` showed the record posted, `dumpsys
  media_session` showed the session active, and the app's own Settings row said `RUNNING`, all
  before anything was changed. Any one of those would have reframed the question.
- **The emulator settles version-specific behaviour in one boot.** An API 36 AVD reproduced the
  correct behaviour immediately, which is what turned "why is it broken" into "where does it
  render". Reach for it first, not third.
- One real bug did come out of it: the media session advertised `state=PLAYING` with `speed=0.0`,
  a contradiction, so the card drew a dead progress bar. Speed is now 1 while playing and the
  position comes from the receiver's own progress events.
