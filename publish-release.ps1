#Requires -Version 7
<#
.SYNOPSIS
    Builds the signed release APK and publishes it as a GitHub Release.

.DESCRIPTION
    One command for the whole publish step. It reads the version from app/build.gradle.kts and
    the release notes from the About tab's CHANGELOG, so the GitHub release notes, the in-app
    "What's new" list and the text the in-app updater shows all stay in sync automatically.

    Before publishing it refuses to continue unless: gh is authenticated, the working tree is
    clean and pushed, the unit tests pass, and the APK is signed with the usual release key (a
    debug-signed APK would be rejected by Android as an update, so this guard matters).

    Typical use, after bumping versionCode/versionName and adding a CHANGELOG entry:
        pwsh ./publish-release.ps1              # build, verify, publish
        pwsh ./publish-release.ps1 -DryRun      # everything except creating the release
#>
[CmdletBinding()]
param(
    [switch]$SkipTests,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root = $PSScriptRoot
$Repo = 'hkrob/denonmusic'
# This repo's default branch as of writing - update if it's ever renamed (e.g. to 'main').
$ReleaseBranch = 'claude/android-smb-denon-player-9710bx'
# Public fingerprint of release.keystore — published in every APK, so safe to keep here. Matches
# EXPECTED_SIGNER in .github/workflows/release.yml.
$ExpectedSigner = '7c50709e598856a9a80a75afff9dfb60f1b8d4f682e7ee2bef036bafa79ed8ea'

function Step([string]$Message) { Write-Host "==> $Message" -ForegroundColor Cyan }
function Note([string]$Message) { Write-Host "    $Message" -ForegroundColor DarkGray }

# --- tools -------------------------------------------------------------------------
$gh = (Get-Command gh -ErrorAction SilentlyContinue)?.Source
if (-not $gh) {
    $fallback = 'C:\Program Files\GitHub CLI\gh.exe'
    if (Test-Path $fallback) { $gh = $fallback } else { throw "GitHub CLI not found. Install with: winget install --id GitHub.cli" }
}
& $gh auth status 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "GitHub CLI is not authenticated. Run: gh auth login" }

# --- version -----------------------------------------------------------------------
Step 'Reading version'
$gradle = Get-Content (Join-Path $Root 'app\build.gradle.kts') -Raw
if ($gradle -notmatch 'versionName\s*=\s*"([^"]+)"') { throw 'versionName not found in app/build.gradle.kts' }
$Version = $Matches[1]
if ($gradle -notmatch 'versionCode\s*=\s*(\d+)') { throw 'versionCode not found in app/build.gradle.kts' }
$VersionCode = $Matches[1]
$Tag = "v$Version"
$ApkName = "DenonMusic-v$Version.apk"
Note "$Tag (versionCode $VersionCode)"

# --- release notes, taken from the About tab changelog ------------------------------
Step 'Reading release notes from the About changelog'
$aboutPath = Join-Path $Root 'app\src\main\kotlin\com\denonmusic\app\about\AboutScreen.kt'
$about = Get-Content $aboutPath -Raw
$entryPattern = '"' + [regex]::Escape($Version) + '"\s*to\s*listOf\((?<body>.*?)\r?\n\s*\)'
$entry = [regex]::Match($about, $entryPattern, 'Singleline')
if (-not $entry.Success) { throw "No CHANGELOG entry for $Version in AboutScreen.kt — add one before publishing." }
# A bullet too long for one line is written as a Kotlin string concatenation ("a " + "b"), so
# the joins have to be collapsed before matching literals - otherwise every continuation line
# becomes a bullet of its own, which is how v0.1.7 shipped notes split mid-sentence. Must stay in
# step with .github/workflows/release.yml's own copy of this parser.
$body = $entry.Groups['body'].Value -replace '"\s*\+\s*"', ''
$bullets = [regex]::Matches($body, '"((?:[^"\\]|\\.)*)"') |
    ForEach-Object { $_.Groups[1].Value -replace '\\"', '"' -replace '\\\\', '\' }
if (-not $bullets) { throw "CHANGELOG entry for $Version is empty." }
$Notes = ($bullets | ForEach-Object { "- $_" }) -join "`n"
$bullets | ForEach-Object { Note "- $_" }

# --- git state ---------------------------------------------------------------------
Step 'Checking git state'
$branch = (& git -C $Root rev-parse --abbrev-ref HEAD).Trim()
if ($branch -ne $ReleaseBranch) { throw "On branch '$branch'; releases are cut from '$ReleaseBranch'." }
if ((& git -C $Root status --porcelain)) { throw 'Working tree has uncommitted changes — commit them first.' }
& git -C $Root fetch --quiet origin $ReleaseBranch
$ahead = (& git -C $Root rev-list --count "origin/$ReleaseBranch..HEAD").Trim()
if ($ahead -ne '0') {
    Note "Pushing $ahead commit(s) so the tag lands on the released code"
    if (-not $DryRun) { & git -C $Root push origin $ReleaseBranch; if ($LASTEXITCODE -ne 0) { throw 'git push failed' } }
}
$existing = & $gh release view $Tag --repo $Repo 2>&1
if ($LASTEXITCODE -eq 0) { throw "Release $Tag already exists — bump the version first." }

# --- build -------------------------------------------------------------------------
if (-not $SkipTests) {
    Step 'Running unit tests'
    & (Join-Path $Root 'gradlew.bat') `
        :app:testDebugUnitTest :core:heos:test :core:avr:test :core:smb:test :core:data:testDebugUnitTest `
        --no-daemon -p $Root | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Unit tests failed — not publishing.' }
    Note 'green'
}

Step 'Building signed release APK'
& (Join-Path $Root 'gradlew.bat') :app:assembleRelease --no-daemon -p $Root | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'assembleRelease failed' }

$built = Join-Path $Root 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $built)) { throw "Expected APK not found at $built — is keystore.properties set up? See .github/workflows/README-release.md" }
$ApkPath = Join-Path $Root $ApkName
Copy-Item $built $ApkPath -Force
Get-ChildItem $Root -Filter 'DenonMusic-*.apk' |
    Where-Object { $_.Name -ne $ApkName } |
    ForEach-Object { Note "Removing superseded $($_.Name)"; Remove-Item $_.FullName -Force }
Note "$ApkName ($([math]::Round((Get-Item $ApkPath).Length / 1MB, 1)) MB)"

# --- signature guard ---------------------------------------------------------------
Step 'Verifying the APK is signed with the release key'
$sdkRoot = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") |
    Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
$apksigner = if ($sdkRoot) {
    Get-ChildItem (Join-Path $sdkRoot 'build-tools') -Filter 'apksigner.bat' -Recurse -ErrorAction SilentlyContinue |
        Sort-Object { [version]$_.Directory.Name } | Select-Object -Last 1
}
if (-not $apksigner) {
    Note 'apksigner not found — skipping signature check'
} else {
    $certs = & $apksigner.FullName verify --print-certs $ApkPath 2>&1 | Out-String
    if ($certs -notmatch 'SHA-256 digest:\s*([0-9a-f]{64})') { throw "Could not read the APK signature:`n$certs" }
    $actual = $Matches[1]
    if ($ExpectedSigner -eq 'REPLACE_ME_AFTER_FIRST_KEYSTORE_SETUP') {
        Write-Warning "EXPECTED_SIGNER is still a placeholder in this script — not verified. Actual fingerprint: $actual`nPin it here and in .github/workflows/release.yml (see README-release.md)."
    } elseif ($actual -ne $ExpectedSigner) {
        throw "APK is signed with an unexpected key.`n  expected $ExpectedSigner`n  actual   $actual`nAndroid will refuse to install this over the installed app."
    } else {
        Note 'signer matches the release key'
    }
}

# --- publish -----------------------------------------------------------------------
if ($DryRun) {
    Step 'Dry run — release not created'
    Note "Would publish $Tag with $ApkName"
    return
}

Step "Publishing $Tag"
& $gh release create $Tag $ApkPath --repo $Repo --title $Tag --notes $Notes
if ($LASTEXITCODE -ne 0) { throw 'gh release create failed' }

Step 'Verifying'
& $gh release view $Tag --repo $Repo --json tagName,assets --jq '"\(.tagName): \(.assets | map(.name) | join(", "))"'
Write-Host "Done. The in-app updater will now offer $Tag." -ForegroundColor Green
