# Native HEOS SMB share add fails; Plex DLNA works but exposed HEOS protocol bugs

## Background

Goal was native HEOS playback of the music library at `/mnt/user/arr/media/music` on unraid
(`sugar`, 10.1.10.10), without going through this app's degraded SMB-proxy bridge mode.

## What didn't work: HEOS "Add Network Share"

Adding `\\10.1.10.10\music` directly as a HEOS network share consistently failed with "share could
not be added", tried across two hypotheses:

1. **SMB protocol version** - unraid's Samba 4.22.10 defaults to `server min protocol = SMB2`;
   older/embedded HEOS SMB clients often only support SMB1/NT1. Temporarily set
   `server min protocol = NT1` in `/boot/config/smb-extra.conf`, restarted Samba, retried in HEOS -
   still failed. Reverted the downgrade immediately (SMB1 is insecure, no reason to leave it enabled
   once it didn't fix anything).
2. **Share permissions** - the custom `[music]` share in `/etc/samba/smb-custom.conf` was
   `guest ok = no, read only = yes`. Made it guest-accessible and read-write - still failed.

Root cause not identified; native HEOS SMB share adding for this receiver/firmware
(Denon AVR-X4500H, HEOS build embedded in it) remains unresolved. Pivoted to DLNA instead.

## What worked: Plex DLNA, after fixing a port conflict

Plex Media Server (`Plex-Media-Server` container on unraid) already had `DlnaEnabled=1` in its
Preferences.xml, but HEOS never saw it. Root cause: **Jellyfin's docker container was holding host
ports `1900/udp` and `7359/udp`** (SSDP discovery ports), which Plex - also host-networked - needs
to bind for its own DLNA announcement. `DlnaEnabled=1` is necessary but not sufficient: Plex's DLNA
server process only binds these ports at Plex's own startup, so a port conflict present at that
startup persists until Plex itself restarts, even if the conflicting container is later removed.

An earlier/unrelated edit had already updated Jellyfin's unraid template
(`/boot/config/plugins/dockerMan/templates-user/my-Jellyfin.xml`) to drop the `1900`/`7359` UDP port
mappings, but the running container was never recreated to pick that up (compared against
`my-Jellyfin.xml.bak-denonmusic`, the pre-edit backup). Fix:

1. unraid WebUI > Docker > Jellyfin > Edit > Apply (recreates the container without those port
   mappings).
2. `docker restart Plex-Media-Server` (Plex needs its own restart to bind the now-free ports; simply
   having them free isn't enough after the fact).

Verified via `ss -tulnp`: `1900/udp` and `32469/tcp` now owned by "Plex DLNA Serve[r]", not Jellyfin.

HEOS then found "Plex Media Server: Sugar" as a `heos_server` source under the aggregate
"Local Music" source (sid 1024), with a full browsable library (366 artists, By Album/Genre/Decade/
Folder, etc).

## Three app-side bugs this then exposed

Every source tested before this was either same-level (no nested `sid`) or fast enough to answer
HEOS's `browse/browse` in a single frame. A slow, nested DLNA source hit three separate bugs, all
now fixed in this repo:

1. **`BrowseItem` never parsed the wire's `sid` field.** Browsing the aggregate "Local Music" source
   returns one row per DLNA/HEOS server behind it, each carrying its own `sid` instead of a `cid`
   within `1024` - the app's model had no field for that, so the row's `cid` was always null and
   navigating into it was a dead end.
2. **A nested-source row has no `container` field at all** (not even `"no"`) - `isContainer`
   defaulted to `false`, so tapping the row silently did nothing (matched neither the container-open
   branch nor the play-track branch).
3. **The real one, at the protocol layer:** browsing into a DLNA/Plex source is slow enough that the
   receiver sends an interim `"command under process"` acknowledgment before the real result - and
   that ack echoes back every argument from the original request, `SEQUENCE` included. The app's
   `HeosConnection` matched pending requests by `SEQUENCE` first, so it resolved the pending command
   on the ack (no payload), and the real result that arrived moments later had nothing left waiting
   for it and was silently dropped. Every browse into Plex came back empty even though the receiver
   was about to send the right payload a moment later. This is allowed by the HEOS CLI spec for any
   slow command, not just browse - it just never showed up before because every source tested so far
   answered in one frame.

Fixed in commit `bf82db9` (browse/protocol fixes) on `claude/android-smb-denon-player-9710bx`.
Verified end-to-end on real hardware: Browse > Local Music > Plex Media Server: Sugar > Music > All
Artists > Adele > 21 > "Rolling in the Deep", queued and playing through real HEOS (`qid=1`,
`get_play_state` returned `state=play`).

## Open question

Why native HEOS SMB share adding fails on this receiver/firmware is still unknown. Not chasing
further since Plex DLNA now covers the same need with real HEOS queueing; revisit only if DLNA turns
out to have format/DSD limitations SMB wouldn't (see `docs/plan.md`'s original reasoning for
preferring a dedicated DLNA server with correct `.dsf`/`.dff` MIME mapping over generic DLNA).
