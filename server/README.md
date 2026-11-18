# denonmusic-server

A headless server that owns the receiver's control connections and offers them over HTTP, so Home
Assistant and a voice assistant can drive the receiver without the phone. It is the same protocol
code as the Android app - `:core:heos` and `:core:avr`, unchanged - behind a small Ktor front end.

The invariant holds here too: **the server queues and controls; it never carries audio.** The
receiver reads the files itself. There is no SMB bridge in the server and there should not be one.

## Run it

Everything is configured by environment variable. Nothing about your network is in the image.

| Variable | Required | Default | |
|---|---|---|---|
| `DENON_HOST` | yes | | Receiver address |
| `DENON_API_TOKEN` | yes | | Sent by every client as `X-Lan-Control-Token` (or `Authorization: Bearer`). The server refuses to start without it |
| `DENON_HTTP_PORT` | | `8901` | |
| `DENON_DATA_DIR` | | `/data` | Where the search index is saved |
| `DENON_MAX_VOLUME` | | `60` | Ceiling on the HEOS volume (0-100) this API will set |
| `DENON_VOLUME_STEP` | | `5` | Size of `volume_up` / `volume_down` |
| `DENON_REINDEX_AT` | | off | Time of day, `HH:MM`, to rebuild the search index. In the container's time zone (`TZ`) |
| `DENON_REINDEX_DAYS` | | every day | Days for that rebuild: `sun`, or `mon,thu` |

Locally, with a JDK 17+ and no Android SDK needed:

```sh
./gradlew :server:installDist
DENON_HOST=<receiver> DENON_API_TOKEN=<token> DENON_DATA_DIR=./data \
  server/build/install/denonmusic-server/bin/denonmusic-server
```

On Unraid, build the image on the server itself from a checkout of this repository, then add the
container from the template:

```sh
docker build -f server/Dockerfile -t denonmusic-server:local .
cp server/unraid/denonmusic-server.xml /boot/config/plugins/dockerMan/templates-user/my-denonmusic-server.xml
```

Docker tab -> Add Container -> pick the template, fill in the receiver address and a token. To
update: rebuild the image, then edit the container in the Docker tab and Apply, which recreates it.

## The index

Search needs an index of the library, built by crawling the receiver's browse tree - the same crawl
as the app's Search tab (`LibraryIndexer`). A crawl is about half an hour of browse traffic at the
receiver (27,425 entries took 32 minutes), and control commands keep working throughout. It works
with the receiver in standby. It never runs on its own at start-up; there are two ways to run it:

- **On a schedule:** set `DENON_REINDEX_AT` (and `DENON_REINDEX_DAYS` for less than daily), for
  example `03:30` and `sun`. `GET /index` reports the next one as `nextScheduledAt`.
- **On demand:** `POST /index/rebuild`, from a shell, a Home Assistant automation or anything else.

```sh
curl -X POST -H "X-Lan-Control-Token: $TOKEN" http://<server>:8901/index/rebuild
curl -H "X-Lan-Control-Token: $TOKEN" http://<server>:8901/index      # "found" climbs while it runs
```

The result is saved to `library-index.json` in the data directory and loaded at start. A crawl that
fails leaves the previous index in place. Rebuild after adding music; the ids in the index are only
as good as the receiver's current view of the library.

Four kinds of thing can be found. Folders and tracks are what the crawl saw. Albums and artists are
derived from the tracks' own artist/album tags, because folders alone could not answer "play The
Wall" on a real library (three sibling disc folders, none of that name). An album or artist plays
the folders its tracks are in, in order; the same album filed in two places plays once. The first
folder is queued and the reply sent at once; the rest are appended behind it while it plays, about a
second and a half a folder, and the next request that changes the queue stops them. One request
queues at most 25 folders, so "play Prince" plays the first 25 of however many there are and says so.

With no `type`, a name shared by several kinds goes artist, then album, then folder, then track.

`/search?type=` keeps only that kind. `/search?prefer=`, and the `type` an `/intent` carries, only
rank that kind higher. The `type` in an intent comes from a language model, and measured against
four models it guessed "track" or "album" from what it knew about the music about one time in three
when nobody had said either word; filtering on those guesses found the wrong record or nothing.
An unknown `type` in an intent is ignored rather than refused.

Matching ignores case, accents, punctuation and apostrophes of either kind ("Let’s" is "lets"), and
reads dotted initials as one word ("Run-D.M.C." is "run dmc"). An artist named alongside a title
adds to a match's score when every word of it is found, costs it nothing when half is ("basement
jacks" for Basement Jaxx), and counts against it otherwise.

## API

Every route but `/` and `/health` needs the token.

| Route | What it does |
|---|---|
| `GET /` | A page for a browser: the same connection and index state, when the index was built and the next scheduled rebuild. No token |
| `GET /health` | Whether both control connections are up, and the index state. No token |
| `GET /status` | Now playing, play state, volume, mute, power, input, sound mode |
| `GET /search?q=&artist=&type=&limit=` | Ranked matches, each with the `sid`/`cid`/`mid` to queue it, or `folders` for an album or artist. `type` (a filter) and `prefer` (a preference) are `track`, `album`, `artist`, `folder` or `any` |
| `POST /intent` | JSON body `{"action","query","artist","type"}` - see below |
| `POST /queue/add?sid=&cid=&mid=&criteria=` | Queue a track, or everything under a folder when `mid` is absent. `criteria` is `PlayNow`, `PlayNext`, `AddToEnd` or `ReplaceAndPlay` |
| `GET /queue`, `POST /queue/play?qid=`, `POST /queue/clear` | The real HEOS queue |
| `POST /play`, `/pause`, `/stop`, `/next`, `/previous` | Transport |
| `POST /volume?level=` or `?delta=`, `POST /mute?on=` | Volume, clamped to `DENON_MAX_VOLUME`; the reply carries the level applied |
| `POST /power?on=` | Power, over the telnet port |
| `GET /index`, `POST /index/rebuild` | Index status, and starting a crawl |

`/intent` is the voice route: it searches, takes the best match, queues it and replies with a
`speech` line saying what it did, plus the runners-up under `alternatives`.

| `action` | Does | `query` |
|---|---|---|
| `play` | Replace the queue and play the best match; with no query, resume | what to find |
| `play_now`, `play_next`, `queue` | The other three `aid` queue actions | what to find |
| `resume`, `pause`, `stop`, `next`, `previous`, `mute`, `unmute` | Transport | |
| `volume_up`, `volume_down` | One step | |
| `set_volume` | | the level, 0-100 |

Errors are `{"error": "..."}` with a status that says whose fault it was: 400 the request, 401 the
token, 404 nothing matched, 502 the receiver refused, 503 the receiver or the index is not there.

Measured on an AVR-X4500H: a `play` to a receiver in standby wakes it, switches it to the network
input and starts the music, at whatever volume the amp was last left at - a volume set while it is
in standby is discarded. For about ten seconds while it wakes, `/status` reports stale now-playing
and no play state. The HEOS volume this API uses is the same number as the amp's own `MV` scale.

`/queue/add` of a folder walks it and queues every part before it returns, which is slow for a big
one (24 seconds for a 16-part artist folder). `/intent` is the fast path.

## Home Assistant

Config to paste is in [`docs/home-assistant/`](../docs/home-assistant/README.md).

The server ends the contention over telnet:23 and HEOS:1255 only if Home Assistant's own `denonavr`
integration is disabled and Home Assistant talks to this API instead. The server does not proxy
those ports.
