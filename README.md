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

**This app therefore never touches the audio.** The receiver reads the files itself; the phone only
browses, queues and controls. Force-stop the app mid-album and the music keeps playing. This is also
what "no processing on the Android" means in practice - there is no decoder in the app at all.

## Repository layout

```
core/heos/      HEOS CLI protocol: codec, socket, event stream, typed client.  Pure JVM.
tools/probe.py  Protocol probe for answering open questions against a real receiver.
```

`core/heos` has no Android dependencies on purpose, so the protocol layer builds and tests on any
JDK 17+ machine with no Android SDK installed:

```sh
./gradlew :core:heos:test
```

The Android modules are only configured when an SDK is present (`ANDROID_HOME`, `ANDROID_SDK_ROOT`
or a `local.properties`), so the command above works anywhere.

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
and cross-checks the result: the file's own header is read from SMB, the receiver is asked what it
thinks it is receiving (`SSINFAISSIG`, `SSINFAISFSV`), and a mismatch is reported rather than
ignored. A DSF file arriving as 176.4 kHz PCM means something in the chain transcoded it.

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

## Release signing

The keystore is never committed. Generate one:

```sh
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias denonmusic
base64 -w0 release.jks          # macOS: base64 release.jks | tr -d '\n'
```

Add four repository secrets: `KEYSTORE_B64` (the base64 above), `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`. Pushing a `v*` tag then builds a signed APK and attaches it to a GitHub release.
Keep `release.jks` somewhere safe and out of the repository - losing it means a new application
identity.

## Status

Protocol layer and probe are in place and tested. The Android modules (browse, queue, now playing,
AVR panel, SMB metadata overlay) are next.
