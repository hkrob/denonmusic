# Handoff prompt

Paste the block below as the first message of a Claude Code session on the new machine. It is
deliberately short: the repo already documents itself, so the prompt's job is to point at the docs
and to stop the session trusting an environment it has not checked.

Keep this file current. If it disagrees with `CLAUDE.md` or
[`local-setup.md`](local-setup.md), those two are the authority.

---

```text
You're picking up the denonmusic project on a new machine. It's an Android controller for playing
SMB-hosted music through a Denon AVR-X4500H. It is released and in use - treat changes as changes
to shipping software.

Start by reading, in this order:
  1. CLAUDE.md - the invariant, the build, and the protocol and Android facts that have already
     cost real debugging time. Don't re-derive them.
  2. docs/local-setup.md - "Where the work stands" at the top, and "Moving this to another
     machine" near the end.

Then verify the environment before relying on any of it, and tell me what you find:
  - JAVA_HOME points at a JDK 17+ (Android Studio's JBR here). Gradle fails obscurely without it.
  - local.properties sdk.dir is valid on this machine - it's an absolute path and may be stale.
  - gh auth status is authenticated.
  - keystore.properties storeFile exists, and its keytool SHA-256 matches $ExpectedSigner in
    publish-release.ps1. A mismatched key means no update can install over an existing one.
  - ping 10.1.10.50 reaches the receiver, and adb devices lists a phone (if one is attached).
  - ./gradlew ktlintCheck :app:lintDebug and the unit tests pass. CI runs lint too.

Two notes about this project's own tooling:
  - CLAUDE.md records that the Bash tool on the old machine mangled literal '&' and doubled
    backslashes, even inside quoted heredocs. That was observed there, not necessarily here -
    re-test it before trusting or ignoring it.
  - An Android emulator is the right tool for anything version-specific; the physical test phone
    is old and can't reach most modern Android behaviour.

Current state: v0.1.18, branch claude/android-smb-denon-player-9710bx (which is also the default
branch), working tree clean, CI green, 244 unit tests.

Nothing is urgently broken. Report what the checks say before changing anything, and don't start
work until I've picked something.
```

---

## What isn't in git, and so isn't guaranteed to be here

`C:\Rob\sync` is a Resilio Sync share with the stock ignore list, so the whole working tree
replicates - including the files git deliberately doesn't carry. At the same path on the other
machine, most of this is already done. The table in
[`local-setup.md`](local-setup.md#moving-this-to-another-machine) has the detail; the short version:

- **`release.keystore` is irreplaceable.** Confirm it arrived. Without it no further release can be
  installed over an existing one.
- **`keystore.properties` and `local.properties` hold absolute paths.** They are valid only at the
  same path under the same user; check, don't assume.
- **`gh` credentials and the emulator AVD do not sync.** Re-authenticate, recreate the AVD.
- **Claude's memory directory does not sync.** It lives under the user profile and is keyed by the
  project's path. Everything load-bearing from it has been moved into `CLAUDE.md`, so losing it
  costs little.

## Open, if something is wanted next

Neither is urgent and neither is a defect anyone has hit:

- The multi-part **"Play now"** path (`aid=1` with more than one target) was never measured. The
  replace-and-play path next to it turned out to drop both its replace and its play when followed
  by another add, so this one deserves the same scepticism before it is trusted.
- The media card's transport buttons were verified on the older phone but **not on One UI** - the
  adb taps there kept missing the panel rather than failing. The code path is shared, so this is
  confirmation rather than suspicion.
