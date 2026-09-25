#!/usr/bin/env python3
"""Protocol probe for a Denon AVR with HEOS Built-in.

Answers the questions that cannot be settled from documentation, against your actual receiver:

  * which mnemonic selects the HEOS/network input (SINET vs SIHEOS vs something else)
  * which endpoint reports the speaker OUTPUT map the Denon AVR Remote app draws
  * how SSINFAISSIG / SSINFAISFSV encode signal type and sample rate
  * which music source your SMB or DLNA share appears under, and whether it queues

For the AVR-X4500H this project was built against, those four are already answered and recorded
under "Probe findings" in docs/local-setup.md - read that before re-measuring. The probe stays
useful for a different receiver, and for checking behaviour against the hardware rather than
against a comment, which is how more than one wrong assumption in this codebase was caught.

Standard library only. Python 3.8+.

Subcommands
-----------
  tap      Stream every unsolicited line from telnet 23 and 1255. Poke the receiver
           with its remote and watch what it says.
  sweep    Snapshot every readable endpoint to a JSON file.
  diff     Compare two sweeps and print only what changed. This is how the speaker
           OUTPUT field gets identified: sweep, change sound mode, sweep, diff.
  heos     Run a HEOS CLI command and pretty-print the response.
  avr      Send a raw AVR telnet command and print replies.
  sources  List HEOS music sources and flag the ones that support real queueing.

Typical session
---------------
  ./probe.py sweep 192.168.1.50 -o a.json
  # change the sound mode on the receiver, e.g. Stereo -> Multi Ch Stereo
  ./probe.py sweep 192.168.1.50 -o b.json
  ./probe.py diff a.json b.json
"""

from __future__ import annotations

import argparse
import json
import re
import select
import socket
import ssl
import sys
import time
import urllib.request
from datetime import datetime
from typing import Dict, List, Optional, Tuple

HEOS_PORT = 1255
AVR_TELNET_PORT = 23
AJAX_PORT = 10443
GOFORM_PORT = 8080

# The categories and type ranges exposed by the modern Denon/Marantz web UI. The speaker OUTPUT
# map the phone app draws is expected to live somewhere in here; the diff will say where.
AJAX_MATRIX: Dict[str, int] = {
    "home": 1,
    "audio": 7,
    "video": 4,
    "inputs": 6,
    "speakers": 8,
    "network": 9,
    "general": 17,
}

# Status documents served over plain HTTP on the older endpoint.
GOFORM_PATHS = [
    "/goform/Deviceinfo.xml",
    "/goform/formMainZone_MainZoneXmlStatus.xml",
    "/goform/formMainZone_MainZoneXmlStatusLite.xml",
    "/goform/formNetAudio_StatusXml.xml",
]

# Telnet queries worth asking. SSINFAISSIG (signal type) and SSINFAISFSV (sample rate) are the
# confirmed pair behind the now-playing technical panel; the rest are probed because neighbouring
# commands in the same family plausibly carry speaker and channel state.
AVR_QUERIES = [
    "PW?", "ZM?", "SI?", "MV?", "MU?", "MS?", "CV?",
    "SSINFAISSIG ?", "SSINFAISFSV ?", "SSINFAISFTR ?",
    "SSSPC ?", "SSLEV ?", "PSMULTEQ: ?", "SSINFSIGRDF ?",
]


def timestamp() -> str:
    return datetime.now().strftime("%H:%M:%S.%f")[:-3]


# ---------------------------------------------------------------------------
# Telnet helpers
# ---------------------------------------------------------------------------


def avr_query(host: str, commands: List[str], settle: float = 1.2) -> Dict[str, List[str]]:
    """Sends each AVR command and collects whatever comes back within the settle window.

    The AVR does not correlate replies to queries, and one query can produce several lines
    (CV? in particular emits one line per channel), so replies are grouped by query using
    arrival time rather than any identifier in the protocol.
    """
    results: Dict[str, List[str]] = {}
    with socket.create_connection((host, AVR_TELNET_PORT), timeout=5) as sock:
        sock.setblocking(False)
        drain(sock, 0.3)
        for command in commands:
            sock.sendall((command + "\r").encode("ascii"))
            results[command] = drain(sock, settle)
    return results


def drain(sock: socket.socket, seconds: float) -> List[str]:
    """Reads CR-delimited lines until the socket goes quiet for `seconds`."""
    deadline = time.time() + seconds
    buffer = ""
    lines: List[str] = []
    while time.time() < deadline:
        ready, _, _ = select.select([sock], [], [], max(0.0, deadline - time.time()))
        if not ready:
            continue
        try:
            chunk = sock.recv(4096).decode("ascii", errors="replace")
        except BlockingIOError:
            continue
        if not chunk:
            break
        buffer += chunk
        while "\r" in buffer:
            line, buffer = buffer.split("\r", 1)
            if line.strip():
                lines.append(line.strip())
    return lines


def heos_command(host: str, command: str, timeout: float = 10.0) -> Optional[dict]:
    """Runs one `heos://...` command and returns the decoded response frame."""
    if not command.startswith("heos://"):
        command = "heos://" + command
    with socket.create_connection((host, HEOS_PORT), timeout=5) as sock:
        sock.sendall((command + "\r\n").encode("utf-8"))
        sock.settimeout(timeout)
        buffer = b""
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                chunk = sock.recv(65536)
            except socket.timeout:
                break
            if not chunk:
                break
            buffer += chunk
            while b"\r\n" in buffer:
                raw, buffer = buffer.split(b"\r\n", 1)
                try:
                    frame = json.loads(raw.decode("utf-8"))
                except (ValueError, UnicodeDecodeError):
                    continue
                head = frame.get("heos", {})
                name = head.get("command", "").strip()
                # Skip unsolicited events that happen to arrive while we wait.
                if name.startswith("event/"):
                    continue
                # Browsing a slow source (a DLNA server, say) answers twice: an immediate
                # "command under process" acknowledgment carrying no payload, then the real
                # result a moment later. Returning the ack makes every such browse look empty.
                # The app itself was fixed for this long ago - see
                # docs/heos-dlna-plex-jellyfin-conflict.md - but the probe was not, and it cost a
                # session chasing a library that appeared to have nothing in it.
                if "command under process" in str(head.get("message", "")):
                    continue
                return frame
    return None


# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------


def http_get(url: str, timeout: float = 4.0) -> Optional[str]:
    """Fetches a URL, tolerating the receiver's self-signed certificate on the ajax port."""
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    try:
        with urllib.request.urlopen(url, timeout=timeout, context=context) as response:
            return response.read().decode("utf-8", errors="replace")
    except Exception:
        return None


def sweep(host: str) -> dict:
    """Snapshots every readable endpoint into one structure."""
    snapshot: dict = {"host": host, "taken_at": datetime.now().isoformat(), "ajax": {}, "goform": {}, "avr": {}}

    print("sweeping ajax endpoints...", file=sys.stderr)
    for category, max_type in AJAX_MATRIX.items():
        for type_id in range(1, max_type + 1):
            url = f"https://{host}:{AJAX_PORT}/ajax/{category}/get_config?type={type_id}"
            body = http_get(url)
            if body:
                snapshot["ajax"][f"{category}/{type_id}"] = body.strip()

    print("sweeping goform endpoints...", file=sys.stderr)
    for path in GOFORM_PATHS:
        for port in (GOFORM_PORT, 80):
            body = http_get(f"http://{host}:{port}{path}")
            if body:
                snapshot["goform"][path] = body.strip()
                break

    print("querying AVR telnet...", file=sys.stderr)
    try:
        snapshot["avr"] = avr_query(host, AVR_QUERIES)
    except OSError as exc:
        snapshot["avr"] = {"_error": [str(exc)]}

    return snapshot


def flatten(snapshot: dict) -> Dict[str, str]:
    """Flattens a sweep to comparable `section/key -> text` pairs."""
    flat: Dict[str, str] = {}
    for section in ("ajax", "goform"):
        for key, value in snapshot.get(section, {}).items():
            flat[f"{section}:{key}"] = value
    for key, lines in snapshot.get("avr", {}).items():
        flat[f"avr:{key}"] = "\n".join(lines)
    return flat


def diff_snapshots(before: dict, after: dict) -> List[Tuple[str, str, str]]:
    left, right = flatten(before), flatten(after)
    changes = []
    for key in sorted(set(left) | set(right)):
        old, new = left.get(key, "<absent>"), right.get(key, "<absent>")
        if old != new:
            changes.append((key, old, new))
    return changes


def show_line_diff(old: str, new: str, indent: str = "    ") -> None:
    """Prints only the differing lines, so a large XML document stays readable."""
    old_lines = re.split(r"(?<=>)\s*(?=<)|\n", old)
    new_lines = re.split(r"(?<=>)\s*(?=<)|\n", new)
    old_set, new_set = set(old_lines), set(new_lines)
    for line in old_lines:
        if line and line not in new_set:
            print(f"{indent}- {line.strip()[:200]}")
    for line in new_lines:
        if line and line not in old_set:
            print(f"{indent}+ {line.strip()[:200]}")


# ---------------------------------------------------------------------------
# Subcommands
# ---------------------------------------------------------------------------


def cmd_tap(args) -> int:
    """Streams both control sockets at once, timestamped and labelled."""
    avr = socket.create_connection((args.host, AVR_TELNET_PORT), timeout=5)
    heos = socket.create_connection((args.host, HEOS_PORT), timeout=5)
    heos.sendall(b"heos://system/register_for_change_events?enable=on\r\n")
    print(f"tapping {args.host}: telnet 23 + HEOS 1255. Operate the receiver; Ctrl-C to stop.\n")

    buffers = {avr: "", heos: ""}
    labels = {avr: "AVR ", heos: "HEOS"}
    try:
        while True:
            ready, _, _ = select.select([avr, heos], [], [], 1.0)
            for sock in ready:
                chunk = sock.recv(65536).decode("utf-8", errors="replace")
                if not chunk:
                    return 0
                buffers[sock] += chunk
                separator = "\r\n" if sock is heos else "\r"
                while separator in buffers[sock]:
                    line, buffers[sock] = buffers[sock].split(separator, 1)
                    if line.strip():
                        print(f"{timestamp()}  {labels[sock]}  {line.strip()}")
    except KeyboardInterrupt:
        print("\nstopped.")
        return 0
    finally:
        avr.close()
        heos.close()


def cmd_sweep(args) -> int:
    snapshot = sweep(args.host)
    text = json.dumps(snapshot, indent=2)
    if args.output:
        with open(args.output, "w") as handle:
            handle.write(text)
        counts = (len(snapshot["ajax"]), len(snapshot["goform"]), len(snapshot["avr"]))
        print(f"wrote {args.output}: {counts[0]} ajax, {counts[1]} goform, {counts[2]} avr responses")
        if counts[0] == 0:
            print("note: no ajax endpoint answered. This firmware may not expose the :10443 API;")
            print("      the AVR telnet replies above are then the place to look.")
    else:
        print(text)
    return 0


def cmd_diff(args) -> int:
    with open(args.before) as handle:
        before = json.load(handle)
    with open(args.after) as handle:
        after = json.load(handle)

    changes = diff_snapshots(before, after)
    if not changes:
        print("no differences. If the sound mode really did change, nothing readable tracks it")
        print("in the endpoints probed - widen AJAX_MATRIX or AVR_QUERIES and sweep again.")
        return 1

    print(f"{len(changes)} endpoint(s) changed between sweeps:\n")
    for key, old, new in changes:
        print(f"  {key}")
        if len(old) + len(new) < 400:
            print(f"    - {old.strip()[:200]}")
            print(f"    + {new.strip()[:200]}")
        else:
            show_line_diff(old, new)
        print()
    print("The endpoint whose value tracks the speaker tiles is the one to wire into the OUTPUT map.")
    return 0


def cmd_heos(args) -> int:
    frame = heos_command(args.host, args.command)
    if frame is None:
        print("no response (is the receiver awake and on the network?)", file=sys.stderr)
        return 1
    print(json.dumps(frame, indent=2))
    return 0


def cmd_avr(args) -> int:
    for command, lines in avr_query(args.host, args.command).items():
        print(f"> {command}")
        for line in lines:
            print(f"  {line}")
        if not lines:
            print("  (no reply - command likely unsupported on this model)")
    return 0


def cmd_sources(args) -> int:
    frame = heos_command(args.host, "browse/get_music_sources")
    if frame is None:
        print("no response from the HEOS CLI port", file=sys.stderr)
        return 1
    payload = frame.get("payload", [])
    if not payload:
        print("no music sources reported.")
        return 1

    print(f"{'sid':>6}  {'type':<16} {'queueable':<10} name")
    print("-" * 64)
    for source in payload:
        sid = str(source.get("sid", ""))
        source_type = source.get("type", "")
        # Only sources the receiver can browse support add_to_queue, and therefore gapless and DSD.
        queueable = sid == "1024" or source_type in ("dlna_server", "heos_server")
        print(f"{sid:>6}  {source_type:<16} {'yes' if queueable else 'no':<10} {source.get('name','')}")

    print()
    print("A 'yes' row is what the app should latch onto: it supports browse/add_to_queue, which is")
    print("what makes gapless playback and DSD possible. If your share is missing entirely, add it")
    print("in the HEOS app (Music Sources) or point a DLNA server at it.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    subparsers = parser.add_subparsers(dest="subcommand", required=True)

    def with_host(sub):
        sub.add_argument("host", help="receiver IP address or hostname")
        return sub

    with_host(subparsers.add_parser("tap", help="stream unsolicited lines from both ports")).set_defaults(func=cmd_tap)

    sweep_parser = with_host(subparsers.add_parser("sweep", help="snapshot every readable endpoint"))
    sweep_parser.add_argument("-o", "--output", help="write JSON here instead of stdout")
    sweep_parser.set_defaults(func=cmd_sweep)

    diff_parser = subparsers.add_parser("diff", help="compare two sweeps")
    diff_parser.add_argument("before")
    diff_parser.add_argument("after")
    diff_parser.set_defaults(func=cmd_diff)

    heos_parser = with_host(subparsers.add_parser("heos", help="run one HEOS CLI command"))
    heos_parser.add_argument("command", help="e.g. browse/browse?sid=1024")
    heos_parser.set_defaults(func=cmd_heos)

    avr_parser = with_host(subparsers.add_parser("avr", help="send raw AVR telnet commands"))
    avr_parser.add_argument("command", nargs="+", help="e.g. MS? CV?")
    avr_parser.set_defaults(func=cmd_avr)

    with_host(subparsers.add_parser("sources", help="list music sources and flag queueable ones")).set_defaults(
        func=cmd_sources
    )

    args = parser.parse_args()
    try:
        return args.func(args)
    except OSError as exc:
        print(f"network error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
