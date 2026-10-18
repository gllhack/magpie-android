<#
.SYNOPSIS
  Builds the Android APK: magpie's binaries first, then the app around them.

.EXAMPLE
  ./scripts/build-apk.ps1                 # debug APK
  ./scripts/build-apk.ps1 -Release        # release APK, signed with the debug key
  ./scripts/build-apk.ps1 -SkipGateway    # the app only, reusing the last build
  ./scripts/build-apk.ps1 -NoBun          # no Bun: external plugins stay unavailable
#>
param(
    [switch]$Release,
    [switch]$SkipGateway,
    [switch]$NoBun,
    [string]$BunMirror = "",
    [string]$Abis = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$android = Join-Path $root "android"

# ---------------------------------------------------------------------------
# The Bun external plugins run on: the Android build of it, carried as
# jniLibs/<abi>/libmagpiebun.so, the one directory Android lets an app exec
# from. The shell points $MAGPIE_BUN at it, so magpie never downloads a Bun
# into its own files directory, where one couldn't be run.
# ---------------------------------------------------------------------------
$bunVersion = "1.4.2"
$bunRelease = "https://github.com/oven-sh/bun/releases/download"
# from upstream's bunSums (internal/plugin/bun.go); update both together
$bunTargets = [ordered]@{
    "arm64-v8a" = @{
        Target = "bun-linux-aarch64-android"
        Sha256 = "a1c7e2983f1bb65146beb256a4d72449f23042412bc2cf278aa6397ba27e0274"
    }
    "x86_64" = @{
        Target = "bun-linux-x64-android-baseline"
        Sha256 = "fe36d8d4795e0eadc22fb6696d44d168491c2e5b9b7cbb12b8c96b0c0c40a4f9"
    }
}

function Bun-Cached {
    param([string]$File, [string]$Sha256)
    if (-not (Test-Path $File)) { return $false }
    $hash = (Get-FileHash -LiteralPath $File -Algorithm SHA256).Hash.ToLower()
    if ($hash -eq $Sha256) { return $true }
    Write-Host "  the cached zip's SHA-256 doesn't match ($hash); downloading it again"
    return $false
}

# Get-BunZip fetches url to out with curl.exe. A curl that fails leaves no
# file behind, so a half-written zip is never taken for a cached one. The
# timeouts are what make a source that can't be reached fall through to the
# next one instead of hanging on it: no connection in 20s, or a transfer
# under 1 KB/s for a minute, is that source failing.
function Get-BunZip {
    param([string]$Url, [string]$Out)
    Write-Host "  downloading $Url"
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & curl.exe -L --fail --connect-timeout 20 --speed-time 60 --speed-limit 1024 --output $Out $Url
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    return ($code -eq 0 -and (Test-Path $Out) -and (Get-Item $Out).Length -gt 0)
}

# Get-Bun downloads $Url into $Out, $Mirror first when one is named, then
# the release page, then gh-proxy.com in front of it: GitHub is what a
# phone build machine in China can't always reach.
function Get-Bun {
    param([string]$Url, [string]$Mirror, [string]$Out)
    $places = @()
    if ($Mirror) { $places += ($Mirror.TrimEnd("/") + "/" + $Url) }
    $places += $Url
    $places += ("https://gh-proxy.com/" + $Url)
    foreach ($place in $places) {
        if (Get-BunZip -Url $place -Out $Out) { return $true }
        Write-Host "  that source failed; trying the next one"
    }
    return $false
}

$bundledBun = @()
foreach ($abi in $bunTargets.Keys) {
    if ($Abis -and ($Abis -split "[,;]") -notcontains $abi) { continue }
    $bun = $bunTargets[$abi]
    $out = Join-Path $android "app\src\main\jniLibs\$abi\libmagpiebun.so"

    if ($NoBun) {
        if (Test-Path $out) {
            Write-Host ("  {0,-12} -NoBun: not fetching Bun; {1} is from an earlier build and still goes into the APK (delete it yourself to leave it out)" -f $abi, $out.Replace($root + "\", ""))
        }
        continue
    }

    # One zip per ABI, kept between builds: 35 MB is worth not downloading twice.
    $cache = Join-Path $android "build\bun-cache\$($bun.Target).zip"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $cache) | Out-Null
    $url = "$bunRelease/bun-v$bunVersion/$($bun.Target).zip"
    if (Bun-Cached -File $cache -Sha256 $bun.Sha256) {
        Write-Host ("  {0,-12} cached {1}" -f $abi, $cache.Replace($root + "\", ""))
    } else {
        Write-Host ("  {0,-12} {1}" -f $abi, $bun.Target)
        $mirror = if ($BunMirror) { $BunMirror } else { $env:MAGPIE_BUN_MIRROR }
        if (-not (Get-Bun -Url $url -Mirror $mirror -Out $cache)) {
            Write-Host ""
            Write-Host ("  no source had Bun $bunVersion for $abi; leaving plugins off this build") -ForegroundColor Yellow
            Write-Host ("  (pass -BunMirror https://<mirror>/ for a prefix mirror, or set MAGPIE_BUN_MIRROR)")
            Write-Host ""
            continue
        }
        $hash = (Get-FileHash -LiteralPath $cache -Algorithm SHA256).Hash.ToLower()
        if ($hash -ne $bun.Sha256) {
            Remove-Item -LiteralPath $cache -Force
            Write-Host ("  what was downloaded isn't Bun $bunVersion for $abi (SHA-256 {0}); plugins are left off this build" -f $hash) -ForegroundColor Yellow
            continue
        }
    }

    # the zip holds <target>/bun, an ELF the native library directory runs
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("magpie-bun-" + [guid]::NewGuid().ToString("N"))
    try {
        Expand-Archive -LiteralPath $cache -DestinationPath $tmp -Force
        $entry = Join-Path $tmp (Join-Path $bun.Target "bun")
        if (-not (Test-Path $entry)) { throw "the zip has no $($bun.Target)/bun" }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $out) | Out-Null
        Copy-Item -LiteralPath $entry -Destination $out -Force
    }
    catch {
        Write-Host ("  unpacking Bun for $abi failed: {0}; plugins are left off this build" -f $_.Exception.Message) -ForegroundColor Yellow
        continue
    }
    finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
    $mb = [math]::Round((Get-Item $out).Length / 1MB, 1)
    Write-Host ("               -> {0} ({1} MB)" -f $out.Replace($root + "\", ""), $mb)
    $bundledBun += $abi
}

Write-Host ""
if ($bundledBun.Count -gt 0) {
    Write-Host ("plugins: Bun $bunVersion goes into the APK as libmagpiebun.so, for " + ($bundledBun -join ", "))
} elseif ($NoBun) {
    Write-Host "plugins: -NoBun: external plugins won't run in this build"
}

if (-not $SkipGateway) {
    $gateway = @()
    if ($Abis) { $gateway += @("-Abis", $Abis) }
    & (Join-Path $PSScriptRoot "build-magpie-android.ps1") @gateway
    if ($LASTEXITCODE -ne 0) { throw "building magpie for Android failed" }
}

# Gradle wants the SDK path; Android Studio writes this file itself.
$local = Join-Path $android "local.properties"
if (-not (Test-Path $local)) {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    if (-not (Test-Path $sdk)) {
        throw "no Android SDK found; set ANDROID_HOME or write android\local.properties"
    }
    "sdk.dir=$($sdk -replace '\\','\\')" | Set-Content $local -Encoding ASCII
    Write-Host "wrote $local (sdk.dir=$sdk)"
}

$task = if ($Release) { "assembleRelease" } else { "assembleDebug" }
Push-Location $android
try {
    # Gradle writes notes to stderr; with $ErrorActionPreference = "Stop" that
    # alone would be reported as a failed run, so the code decides here.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & .\gradlew.bat $task --console=plain
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -ne 0) { throw "gradle $task failed ($code)" }
}
finally {
    Pop-Location
}

$apk = Get-ChildItem (Join-Path $android "app\build\outputs\apk") -Recurse -Filter *.apk |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
Write-Host ""
Write-Host ("APK: {0} ({1} MB)" -f $apk.FullName, [math]::Round($apk.Length / 1MB, 1))
Write-Host ("install: adb install -r `"{0}`"" -f $apk.FullName)
exit 0
