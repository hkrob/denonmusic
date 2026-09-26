# Handoff prompt

Paste the block below as the first message of a Claude Code session on a new machine. It is
deliberately short: the repo documents itself, so the prompt's job is to point at the docs and to
stop the session trusting an environment it has not checked.

If this file disagrees with [`CLAUDE.md`](../CLAUDE.md) or [`local-setup.md`](local-setup.md),
those two are the authority.

---

```text
You're picking up the denonmusic project on a new machine. It's an Android controller for playing
SMB-hosted music through a Denon AVR-X4500H. It is released and in use - treat changes as changes
to shipping software.

Read these first, in order:
  1. CLAUDE.md - the invariant, the build, and the protocol and Android facts that have already
     cost real debugging time. Don't re-derive them.
  2. docs/local-setup.md - the "Where the work stands" section at the top, including the
     "Picking this up on a different machine" table in it.

Then verify the environment before relying on it, and tell me what you find:
  - JAVA_HOME points at a JDK 17+ (Android Studio's bundled JBR is what was used). Gradle fails
    obscurely without it.
  - local.properties sdk.dir is valid here - it's an absolute path and may be stale.
  - gh auth status is authenticated.
  - keystore.properties storeFile exists, and its keytool SHA-256 matches $ExpectedSigner in
    publish-release.ps1. A mismatched key means no new build can install over an existing one.
  - ping 10.1.10.50 reaches the receiver; adb devices lists a phone if one is attached.
  - ./gradlew ktlintCheck :app:lintDebug and the unit tests pass. CI runs lint too, so a green
    local test run alone is not enough.

Two notes about tooling:
  - CLAUDE.md records that the Bash tool on the previous machine mangled literal '&' and doubled
    backslashes, even inside quoted heredocs. That was observed there, not necessarily here -
    re-test it rather than assuming either way.
  - An emulator is the right tool for anything version-specific. The physical test phone is old
    and cannot reach most modern Android behaviour; assuming otherwise has cost releases before.

Current state: v0.1.18, branch claude/android-smb-denon-player-9710bx (also the default branch),
working tree clean, CI green, 244 unit tests.

Nothing is urgently broken. Report what the checks say, and don't start work until I've picked
something.
```

---

## Machine-specific setup

How a particular machine is wired - which folder-sync tool, which profile paths, what has to be
restored by hand - is recorded in `.claude/MACHINE-MOVE.md`, which is gitignored and so is not
public. Read that alongside this file when actually moving.

## Open, if something is wanted next

Neither is urgent, and neither is a defect anyone has hit:

- The multi-part **"Play now"** path (`aid=1` with more than one target) was never measured. The
  replace-and-play path beside it turned out to lose both its replace and its play when another add
  followed, so this one deserves the same scepticism before it is trusted.
- The media card's transport buttons were verified on the older phone but **not on One UI** - the
  adb taps there kept missing the panel rather than failing. The code path is shared, so this is
  confirmation rather than suspicion.
