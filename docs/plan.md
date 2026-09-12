# DenonMusic — Android controller for SMB music on an AVR-X4500H

## Context

Play music held on SMB (Unraid) through a Denon AVR-X4500H from an Android app: simple playback and
queueing, seamless playback, a browser that remembers where you were, MP3/FLAC/DSD straight to the
AVR with **no processing on the phone**, a live speaker-output map like the Denon AVR Remote app's
OUTPUT screen, and technical detail on what's actually playing (format, bitrate, channels).

Repo `hkrob/denonmusic` is empty — greenfield, branch `claude/android-smb-denon-player-9710bx`.

### The fact that shapes everything

From the HEOS CLI Protocol Spec v1.17:

| Capability | Command | Queue? |
|---|---|---|
| Play a HEOS-known item | `browse/add_to_queue?pid=&sid=&cid=[&mid=]&aid=1..4` | ✅ real queue, AVR handles gapless |
| Play an arbitrary URL | `browse/play_stream?pid=&url=` | ❌ one stream, immediate, no next-track |

An app that bridges SMB→HTTP from the phone and pushes URLs **cannot** be seamless: a gap on every
track change, playback dies when the phone sleeps, and it will not carry DSD.

**Therefore: the AVR reads the files itself; the app is a pure controller.** The phone is never in
the audio path — which is also exactly what "no processing on the Android" demands. Force-stop the
app mid-album and nothing stops.

---

## Architecture

```
┌────────────────────────┐   telnet :1255  HEOS CLI ── browse/queue/transport/events
│  Android app           │   telnet :23    Denon AVR ── power/input/mode/SSINF signal info
│  (controller only)     │◄─ https :10443  /ajax/*  ── speaker OUTPUT map, input signal
│                        │
│  SMB (read-only) ──────┼──► Unraid share ── file headers: format/rate/depth/channels, artwork
└────────────────────────┘
              audio never touches the phone
                          ┌─────────────────────────┐
   AVR reads directly ───►│ DLNA server (Unraid) or │
                          │ HEOS native SMB share   │
                          └─────────────────────────┘
```

### Source auto-detection

On first run and on `sources_changed`, call `browse/get_music_sources` and rank:

1. **sid 1024** — "Local USB Media / Local DLNA servers". Covers a DLNA server on Unraid *and* a
   HEOS-native SMB Network Share (both surface here). Queueable, gapless, DSD-capable. Preferred.
2. Any other source exposing a playable container tree.
3. **Bridge fallback** — phone serves SMB over Range-capable HTTP via `play_stream?url=`. Opt-in
   only, labelled in-UI as **"Degraded — no gapless, no DSD"**. Built last.

Persist the chosen `sid` + matched container path; re-probe on error or explicit refresh.

---

## Format support: MP3 / FLAC / DSD, bit-perfect

The X4500H does FLAC/ALAC/WAV to 24/192 and **DSD 2.8 / 5.6 MHz**, gapless, over network. Two
constraints fall out, and they are correctness issues rather than preferences:

**A. The server must not transcode.** Denon's own docs note a capable server (Twonky/JRiver) is
needed for full format support. minidlna handles DSF poorly. Recommend **Gerbera or Jellyfin** with
explicit mime mapping (`.dsf`→`audio/x-dsd`, `.dff`→`audio/x-dff`) and transcoding disabled — or the
HEOS native SMB share, which indexes DSF directly. This is Unraid-side config; the README documents
it, and the app *detects* violations (see chain integrity below) rather than silently tolerating them.

**B. DSD only survives in DIRECT / PURE DIRECT.** In any other sound mode the AVR converts DSD→PCM
so it can run DSP. So the app gets a **Bit-perfect policy** setting:

- *Off* — never touch the sound mode.
- *Auto Direct* — on starting playback, set `MSDIRECT`.
- *Auto Pure Direct* — set `MSPURE DIRECT`. Note in UI: also disables the front display, video
  circuitry, tone controls and Audyssey.

Policy applies on queue-start, and the mode chips stay manually overridable — the AVR emits `MS` as
an event, so the UI always reflects the receiver, never optimistic local state.

---

## Speaker OUTPUT map + now-playing technical detail

The Denon AVR Remote app renders configured-but-silent channels grey and currently-outputting
channels green (`SW FL FR` lit, `C SL SR SBL SBR` grey, in Stereo). That data is retrievable; the
exact endpoint needs pinning down on your unit, so the plan is *discovery by sweep and diff*, not a
guess.

**Candidate transports, ranked:**

1. `https://<avr>:10443/ajax/speakers/get_config?type=1..8` and `/ajax/general/get_config?type=12`
   (the latter is known to return `<InputSignal>`). This family is what the modern Denon app uses.
2. Telnet `SSINF` family — `SSINFAISSIG ?` (signal type: analog / PCM / DSD) and `SSINFAISFSV ?`
   (sample rate) are confirmed; neighbouring `SSINF*` and `SSSPC*` commands likely carry speaker
   config and active-channel data.
3. `/goform/AppCommand0300.xml` POST.

**Discovery method (step 1 of the build):** the probe sweeps every
`ajax/{home,audio,video,inputs,speakers,network,general}/get_config?type=N` combination and every
`SSINF*`/`SSSPC*` telnet query, saves the full response set, then you change sound mode
(Stereo → Multi Ch Stereo → Direct) and it sweeps again and **diffs**. The field that tracks the
green tiles falls out of the diff immediately. ~50 requests, deterministic, one sitting.

### Now Playing — technical panel

Two independent truths, shown side by side:

| Field | Source |
|---|---|
| Title / artist / album / art | HEOS `player/get_now_playing_media` |
| Container, codec, sample rate, bit depth, channels, bitrate | **SMB overlay** — parse file headers directly |
| Signal type the AVR is *receiving* (PCM / DSD / analog) | telnet `SSINFAISSIG ?` |
| Sample rate the AVR is *receiving* | telnet `SSINFAISFSV ?` |
| Channels currently outputting | ajax speakers endpoint (confirmed by probe) |
| Sound mode | telnet `MS?` |

**Chain integrity indicator.** Because the file-side truth (from SMB) and the AVR-side truth (from
`SSINF`) are gathered independently, the app can compare them and tell you when something in the
chain is lying — a DSF file arriving as 176.4 kHz PCM means your DLNA server transcoded it; a 24/192
FLAC arriving as 48 kHz means something resampled. Green check when they agree, warning with the
mismatch spelled out when they don't. This is the payoff for browsing with an SMB overlay rather than
HEOS metadata alone.

Header parsing is small custom parsers over jcifs-ng streams reading ≤64 KB per file — FLAC
`STREAMINFO`, MP3 frame header + Xing/Info for VBR, DSF header (`2822400`/`5644800`, channel num,
bits), DFF `FRM8`/`PROP`/`FS` chunks. No heavy tag library. Artwork prefers `folder.jpg`/`cover.jpg`
in the directory over embedded pictures (far cheaper), falling back to FLAC `PICTURE` / ID3 `APIC`.
Parsed results cached in Room keyed by path+mtime+size.

---

## Modules

| Module | Contents |
|---|---|
| `:app` | Compose UI, navigation, MediaSession notification |
| `:core:heos` | HEOS CLI socket client, framing, event stream, browse/queue repos |
| `:core:avr` | Denon telnet:23 line client, `SSINF` parsers, `:10443/ajax` + `/goform` HTTP |
| `:core:smb` | jcifs-ng read-only overlay: header parsers, artwork, tags |
| `:core:data` | Room + DataStore: browse stack, browse cache, media-info cache, settings |
| `:feature:probe` | Debug protocol probe + sweep/diff tool |

Kotlin, Compose (Material 3), coroutines/Flow, Hilt, Room, DataStore, OkHttp, jcifs-ng,
minSdk 26 / target 35.

---

## `:core:heos` — the load-bearing piece

- **Discovery**: SSDP M-SEARCH for `urn:schemas-denon-com:device:ACT-Denon:1`; manual IP override.
- **Transport**: TCP 1255. Commands `heos://<group>/<cmd>?a=b\r\n`, newline-delimited JSON responses.
- **Two sockets** (spec allows 32): a long-lived *event* socket with
  `system/register_for_change_events?enable=on`, and a *command* socket. Stops unsolicited events
  interleaving with command responses.
- **Correlation**: append `SEQUENCE=<n>` — the spec blesses this, since responses echo all arguments.
- **Escaping**: `&`→`%26`, `=`→`%3D`, `%`→`%25`. `url=` **must be the last attribute**. IDs returned
  by `browse` are already encoded — pass through verbatim, decode only for display.
- **Keepalive**: `system/heart_beat` every 25 s; reconnect with exponential backoff + jitter.
- **Paging**: `browse/browse?sid=&cid=&range=start,end`; `count=0` means unknown → page until
  `returned=0`.
- **Queue**: `player/get_queue`, `play_queue?qid=`, `remove_from_queue`, `move_queue_item`,
  `clear_queue`, `save_queue_as_playlist`.
- **Events**: `player_state_changed`, `player_now_playing_changed`, `player_now_playing_progress`,
  `player_queue_changed`, `player_volume_changed`, `repeat_mode_changed`, `shuffle_mode_changed`,
  `player_playback_error`, `sources_changed`.

Queue actions map 1:1 to `aid`, so the long-press menu *is* the protocol:

| Menu item | aid |
|---|---|
| Play now | 1 |
| Play next | 2 |
| Add to end | 3 |
| Replace queue and play | 4 |

"Play all" is offered on a container only when its browse response carries option id **21 (Playable
Container)**.

---

## Browse memory — "remember where I was"

Room `browse_stack(position, sid, cid, displayName, scrollIndex, scrollOffset)` + `last_active_at`.

- Written on every navigation; scroll position debounced ~300 ms on settle.
- On launch, **always** restore (no TTL — "a few minutes" and "next week" behave identically), with a
  Home action as the escape hatch.
- If the deepest `cid` errors (server reindexed, share renamed), **pop the stack until a level
  resolves** rather than dumping you at root.
- `browse_cache` keyed `(sid, cid, rangeStart)`, ~24 h TTL, so restore paints instantly and works
  with the AVR off; revalidated in background.

---

## `:core:avr` — AVR control

Telnet :23, `\r`-terminated, unsolicited events on the same socket.

- **Auto power-on + input**: before the first queue action, if `PW?` → `PWSTANDBY`, send `PWON`,
  await the `PWON` event, then `ZMON`, then select the HEOS/network input. *Input mnemonic needs
  confirming* (`SINET` vs `SIHEOS`) — the probe resolves it once, then it's persisted.
- **Volume/mute**: prefer HEOS `player/set_volume` (0–100) for session consistency; expose AVR
  `MV`/`MU` behind an "absolute dB" advanced toggle.
- **Repeat/shuffle**: HEOS `player/set_play_mode`.
- **Sound modes**: `MS?` to query; `MSMOVIE MSMUSIC MSGAME MSDIRECT MSPURE DIRECT MSSTEREO MSAUTO
  MSMCH STEREO MSDOLBY DIGITAL MSDTS SURROUND MSVIRTUAL MSAURO3D MSAURO2DSURR` to set. Surround mode
  also changes on its own when the input signal changes and is emitted as an event — drive UI from
  the event stream.

---

## Protocol probe — build this first

`:feature:probe` (debug builds only) + `tools/probe.py`:

- Opens telnet 23 and 1255 simultaneously, timestamps every unsolicited line.
- Fires raw commands; sweeps the whole `ajax/*/get_config` matrix and the `SSINF*`/`SSSPC*` space.
- **Sweep → change mode → sweep → diff**, which is how the speaker-output field gets identified.
- Transcripts committed to `tools/transcripts/` and replayed by a fake-HEOS JVM fixture, so the
  protocol layer is unit-testable without hardware.

This one step de-risks every open question: the HEOS input mnemonic, the OUTPUT-map endpoint, the
`SSINF` value encodings, where your share appears, and whether DSD queues cleanly on your firmware.

---

## Screens

| Screen | Contents |
|---|---|
| **Browse** | Breadcrumb + lazy paged list; long-press → the four `aid` actions; format badge (FLAC/DSD/MP3) per row from the SMB overlay |
| **Now Playing** | Art, progress from `player_now_playing_progress`, transport, repeat/shuffle, volume; technical panel + chain-integrity indicator; speaker OUTPUT map |
| **Queue** | Reorder, swipe-remove, tap to jump (`play_queue?qid=`), clear, save as playlist |
| **AVR** | Power, input, sound-mode chips incl. Pure Direct, bit-perfect policy, OUTPUT map |
| **Settings** | AVR discovery/IP, detected source + mode banner, SMB credentials (EncryptedSharedPreferences), probe |

MediaSession + notification for lockscreen transport. Foreground service is **optional, off by
default** — playback continuity doesn't need it; it only keeps the event socket warm for a live
notification.

---

## CI

- `.github/workflows/ci.yml` — `assembleDebug` + unit tests on push/PR, upload debug APK artifact.
- `.github/workflows/release.yml` — tag-triggered; decodes secrets `KEYSTORE_B64`,
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`; `assembleRelease`; attaches APK to a Release.
- Keystore never committed. README documents the `keytool` + `base64 -w0` steps; you add the four
  repo secrets once.

---

## Phasing

1. Gradle skeleton + CI + **protocol probe with sweep/diff** + `:core:heos` client core.
2. Source auto-detect, Browse screen, **browse memory**, the four queue actions. ← usable here
3. Now Playing + Queue wired to the event stream.
4. `:core:avr`: power/input, volume/mute, sound modes, bit-perfect policy, **OUTPUT map**.
5. `:core:smb` overlay: header parsers, technical panel, **chain integrity**, artwork, format badges.
6. Bridge-mode fallback (degraded, opt-in, clearly labelled).

---

## Verification

**Automated**
- Unit tests: frame codec; attribute escaping (`&`/`=`/`%`, `url=` last); `SEQUENCE` correlation;
  browse paging with `count=0`; browse-stack restore and pop-on-missing-cid; **header parsers against
  committed fixture files for MP3 CBR, MP3 VBR, FLAC 16/44.1, FLAC 24/192, DSF 2.8, DSF 5.6, DFF**;
  `SSINF` response decoding.
- Fake HEOS server replaying `tools/transcripts/*` — protocol layer green without hardware.
- CI runs both on every push.

**Manual, against the real unit**
1. `python tools/probe.py <avr-ip>` — sweep, change sound mode, sweep, diff. Confirm the OUTPUT-map
   field, the HEOS input mnemonic, and `SSINF` encodings.
2. Browse an album → "Replace queue and play". **Track transition must be gapless** — the acceptance
   test for the whole architecture.
3. Force-stop the app mid-album — music keeps playing.
4. Reopen after ~5 minutes — same folder, same scroll position.
5. AVR in standby → hit play — powers on, selects input, plays unattended.
6. Play a DSF file with bit-perfect policy on — `SSINFAISSIG` must report DSD, not PCM, and the
   chain-integrity indicator must be green. Then switch to Multi Ch Stereo and confirm it flips to a
   PCM-conversion warning (proves the indicator actually works).
7. Play 24/192 FLAC — `SSINFAISFSV` reports 192 kHz; a mismatch means the server is transcoding.
8. Switch Stereo → Multi Ch Stereo — OUTPUT map must relight from the event stream without a manual
   refresh, matching what the Denon app shows.

---

## References

- [HEOS CLI Protocol Specification v1.17](https://rn.dmglobal.com/usmodel/HEOS_CLI_ProtocolSpecification-Version-1.17.pdf)
- [AVR-X4500H — Playing back files stored on a PC or NAS](https://manuals.denon.com/AVRX4500H/NA/EN/GFNFSYtormgerw.php)
- [AVR-X4500H specification sheet (formats, DSD, gapless)](https://onlinehifi.co.nz/wp-content/uploads/2018/08/denon-av-receiver-avr-x4500h-specification-sheet-1.pdf)
- [HEOS App Network Shares setup](https://support-eu.denon.com/app/answers/detail/a_id/5795/~/heos-app-network-shares)
- [Denon AVR control protocol (telnet)](https://assets.denon.com/documentmaster/uk/avr1713_avr1613_protocol_v860.pdf)
- [Reverse Engineering the Denon Amplifier Web API](https://blog.abbey1.org.uk/index.php/technology/reverse-engineering-the-denon-amplifier-web-api) — the `:10443/ajax` family
- [ol-iver/denonavr](https://github.com/ol-iver/denonavr) — `/goform/*` reference
