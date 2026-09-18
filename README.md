# denonmusic

An Android controller for playing music held on an SMB share through a Denon AVR-X4500H
(HEOS Built-in), with queueing, a browser that remembers where you were, and a now-playing panel
that tells you what the receiver is actually doing with the file.

## The one design decision everything follows from

The HEOS CLI offers two ways to start audio:

| Capability | Command | Queue? |
|---|---|---|
| Play something HEOS already knows about | `browse/add_to_queue?pid=&sid=&cid=[&mid=]&aid=1..4` | yes - real queue, receiver handles gapless |
| Play an arbitrary URL | `browse/play_stream?pid=&url=` | no - one stream, no next track |

So an app that reads SMB on the phone and feeds the receiver URLs cannot play an album seamlessly:
there is a gap at every track change, playback stops when the phone sleeps or leaves the network,
and DSD will not survive the trip.

**The primary path therefore never touches the audio.** The receiver reads the files itself; the
phone only browses, queues and controls. Force-stop the app mid-album and the music keeps playing.
This is also what "no processing on the Android" means in practice - there is no decoder in the app
at all.

There is a second, deliberately secondary path for files HEOS hasn't indexed: the app reads them off
the share and relays the bytes to the receiver over `play_stream`, stepping its own client-side queue
at each track end. It still doesn't decode anything, but it does carry the audio, so it inherits
every drawback in the table above. Use it to reach something the receiver can't see on its own, not
to play an album.

## Repository layout

```
app/            Compose UI, ViewModels, Hilt wiring, the SMB bridge queue, the LAN control API.
core/heos/      HEOS CLI protocol: codec, socket, event stream, typed client.  Pure JVM.
core/avr/       Raw Denon telnet control (port 23): power, input, sound mode, signal info.  Pure JVM.
core/smb/       SMB access and audio header parsing (FLAC/DSF/DFF/WAV/MP3).  Pure JVM.
core/data/      Room caches (browse listings, breadcrumb, media info) and DataStore settings.
tools/probe.py  Protocol probe for answering open questions against a real receiver.
```

The three protocol modules have no Android dependencies on purpose, so they build and test on any
JDK 17+ machine with no Android SDK installed:

```sh
./gradlew :core:heos:test :core:avr:test :core:smb:test
```

`app` and `core:data` are only configured when an SDK is present (`ANDROID_HOME`,
`ANDROID_SDK_ROOT` or a `local.properties`), so the command above works anywhere. With an SDK:

```sh
./gradlew :app:testDebugUnitTest :core:heos:test :core:avr:test :core:smb:test
```

The Android Gradle plugin needs JDK 17 or newer; Android Studio's bundled JBR works if your system
default is older.

## Getting your music to the receiver

The receiver has to be able to see the files. Two options, and the app auto-detects either:

1. **A DLNA server on the NAS** - Gerbera or Jellyfin pointed at the same shares. Recommended.
   It must not transcode, and it needs explicit mime mappings for DSD (`.dsf` -> `audio/x-dsd`,
   `.dff` -> `audio/x-dff`), or DSD files will either be skipped or silently converted.
2. **A HEOS native network share** - added in the HEOS app under Music Sources. No server to run,
   but HEOS's SMB support is SMB1-era, which means enabling NT1 on the NAS.

Check what the receiver can currently see:

```sh
./tools/probe.py <avr-ip> sources
```

Rows marked queueable support `browse/add_to_queue`, and therefore gapless playback and DSD.

### DSD only stays DSD in DIRECT or PURE DIRECT

In any other sound mode the receiver converts DSD to PCM so it can run its DSP. The app exposes a
bit-perfect policy (off / auto Direct / auto Pure Direct) that sets the mode when playback starts,
and shows you the result rather than assuming it: the receiver is asked what it thinks it is
receiving (`SSINFAISSIG`, `SSINFAISFSV`) and that appears in the Now Playing technical panel, next
to the file's own header as read from SMB. A DSF file arriving as 176.4 kHz PCM means something in
the chain transcoded it.

The two are automatically compared for the bridge (SMB direct-stream) path, where a mismatch is a
real problem, and only there: this receiver reports PCM for *all* network-sourced audio, because HEOS
decodes internally before the amp section, so "DSD file, PCM at the amp" is expected - not a fault -
for anything played through the native HEOS queue.

## The probe

Several things cannot be settled from documentation and have to be measured on your unit: which
mnemonic selects the HEOS input, which endpoint backs the speaker OUTPUT map, and how the signal-info
commands encode their values. `tools/probe.py` answers them. Standard library only.

```sh
./tools/probe.py <avr-ip> tap                 # watch both control ports live
./tools/probe.py <avr-ip> sources             # list music sources, flag queueable ones
./tools/probe.py <avr-ip> avr 'MS?' 'CV?'     # send raw AVR commands
./tools/probe.py <avr-ip> heos browse/get_music_sources
```

Finding the speaker OUTPUT map is a three-step measurement:

```sh
./tools/probe.py <avr-ip> sweep -o before.json
# change the sound mode on the receiver, e.g. Stereo -> Multi Ch Stereo
./tools/probe.py <avr-ip> sweep -o after.json
./tools/probe.py diff before.json after.json
```

The endpoint whose value tracks the speaker tiles is the one to wire up.

Sweeps capture your receiver's configuration, so `tools/sweeps/` is gitignored. Check a sweep before
attaching it to an issue.

## Releases and in-app updates

Versioned releases are published to GitHub Releases and the app checks there for updates itself
(About tab) - see `.github/workflows/README-release.md` for the full one-time signing setup and
`publish-release.ps1` for the one-command local publish path. Briefly: bump `versionCode`/
`versionName` in `app/build.gradle.kts`, add a changelog entry in `AboutScreen.kt`, then either run
the **Release** GitHub Action or `pwsh ./publish-release.ps1`.

The keystore is never committed (`*.keystore`/`*.jks` are gitignored) and lives only as encrypted
repository secrets during a build - the linked doc covers that one-time setup, which is already in
place for this repo.

## Controlling it from a browser

The app can also serve a small control API and a matching web UI over the LAN, so a laptop or a
second phone can drive the receiver without installing anything. Off by default; enable it in
Settings and set a password, which doubles as the API token. It listens on port **8901**.

Visit `http://<phone-ip>:8901/` for the web UI - it mirrors the app's own tabs (Now Playing, Queue,
AVR, Browse, Files) and prompts for the password. The page itself is served unauthenticated, since it
can't know the token before you type it; every call it makes is gated.

API clients send the password as an `X-Lan-Control-Token` header (or `?token=`, though that leaks
into logs and shell history - prefer the header):

```sh
curl -H "X-Lan-Control-Token: $TOKEN" http://<phone-ip>:8901/status
curl -H "X-Lan-Control-Token: $TOKEN" -X POST 'http://<phone-ip>:8901/volume?level=25'
```

| Route | What it does |
|---|---|
| `GET /status` | Now playing, transport state, volume, plus the AVR's power/input/sound-mode/signal info |
| `GET /queue`, `POST /queue/play?qid=` | The real HEOS queue, and jumping to an item in it |
| `POST /play`, `/pause`, `/stop`, `/next`, `/previous` | Transport |
| `POST /volume?level=`, `/mute?on=` | Volume |
| `GET /browse?sid=&cid=` | One level of the HEOS-indexed library; stateless, items carry their own ids |
| `POST /browse/queue`, `/browse/playall` | Queue one item, or walk a whole subtree and queue all of it |
| `GET /smb`, `POST /smb/play`, `/smb/queue` | The SMB fallback browser - works even with the receiver off |
| `POST /power`, `/soundmode`, `/input`, `/bitperfect` | Direct AVR control |
| `GET /progress` | Live progress of whatever folder-wide scan or queue walk is running |

There is no rate limiting on failed auth, and the server refuses to start at all without a password
set. It is meant for a trusted home LAN, not the open internet.

## Status

Shipping. The protocol layer, probe, and the full Android app (browse, queue, now playing, AVR panel,
SMB bridge playback, metadata overlay, LAN control) are in place and released - see the About tab or
GitHub Releases for the current version.
