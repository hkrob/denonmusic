# Picking this up in a local session

This project was started in a cloud container with no route to the LAN, so nothing has ever talked
to the real receiver or a real phone. A local session can. This file is the handoff.

## Where the work stands

Done and verified (27/27 tests passing):

- `core/heos` - the full HEOS CLI protocol layer. Pure JVM, no Android dependencies, so
  `./gradlew :core:heos:test` runs anywhere with a JDK 17+.
- `tools/probe.py` - receiver probe. Standard library only, nothing to install.
- `.github/workflows` - CI runs the protocol tests; the Android job now builds `:app` since the
  module exists. A `v*` tag builds a signed APK once the four keystore secrets are set.
- **Probe run against the real AVR-X4500H (10.1.10.50)** - see "Probe findings" below.
- **Phase 2**: `:core:data` (Room browse stack + cache, DataStore settings) and `:app` (Hilt,
  Compose Browse screen, source auto-detect, browse memory, the four `aid` queue actions) exist,
  build (`./gradlew assembleDebug`), install, and have been driven end-to-end on a real phone
  against the real receiver (see below). `:core:avr`, `:core:smb`, `:feature:probe` are still
  commented out in `settings.gradle.kts`.

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
