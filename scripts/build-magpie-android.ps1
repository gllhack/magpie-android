<#
.SYNOPSIS
  Builds magpie's gateway for Android and puts it where the app finds it.

.DESCRIPTION
  Each ABI becomes android/app/src/main/jniLibs/<abi>/libmagpie.so: an APK can
  only carry an executable as a library, and the one directory Android lets an
  app exec from is its native library directory.

  The builds go through cgo and the Android NDK, which is what magpie's own
  Makefile means by "Termux uses Android's libc": Go's own DNS resolver is
  documented not to work on Android (golang/go#10714), so a CGO_ENABLED=0
  build cannot look a host up at all — it reads a /etc/resolv.conf Android
  doesn't have and falls back to localhost. With cgo it goes through bionic
  and netd, exactly as Termux's install does.

.EXAMPLE
  ./scripts/build-magpie-android.ps1
  ./scripts/build-magpie-android.ps1 -Abis arm64-v8a -Version v1.2.3
  ./scripts/build-magpie-android.ps1 -Ndk C:\android-ndk-r28c
#>
param(
    [string]$Upstream = "",
    [string[]]$Abis = @("arm64-v8a", "x86_64"),
    [string]$Version = "android-port",
    [string]$Ndk = "",
    [int]$ApiLevel = 0,
    [string]$GoToolchain = "",
    [string]$Goproxy = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $Upstream) { $Upstream = Join-Path $root "upstream" }
$Upstream = (Resolve-Path $Upstream).Path

$gomod = Join-Path $Upstream "go.mod"
if (-not (Test-Path $gomod)) {
    throw "go.mod is not in $Upstream; pass -Upstream <magpie source folder>"
}
if (-not (Select-String -Path $gomod -Pattern '^module github.com/yetone/magpie' -Quiet)) {
    throw "$Upstream does not look like magpie's own source folder"
}

# ---------------------------------------------------------------------------
# The NDK: only the compiler wrappers are needed, no Gradle integration.
# ---------------------------------------------------------------------------
function Find-Ndk {
    param([string]$Given)
    $candidates = @()
    if ($Given) { $candidates += $Given }
    foreach ($env in @($env:ANDROID_NDK_HOME, $env:ANDROID_NDK_ROOT, $env:ANDROID_NDK)) {
        if ($env) { $candidates += $env }
    }
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA "Android\Sdk"))) {
        if ($sdk -and (Test-Path (Join-Path $sdk "ndk"))) {
            $candidates += (Get-ChildItem (Join-Path $sdk "ndk") -Directory | Sort-Object Name -Descending |
                ForEach-Object { $_.FullName })
        }
    }
    $candidates += (Get-ChildItem (Join-Path $env:USERPROFILE "sdk") -Directory -Filter "android-ndk-*" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | ForEach-Object { $_.FullName })
    foreach ($candidate in $candidates) {
        $bin = Join-Path $candidate "toolchains\llvm\prebuilt\windows-x86_64\bin"
        if (Test-Path $bin) { return $candidate }
    }
    throw @"
the Android NDK was not found. Download it once (713 MB) and unpack it:
  https://dl.google.com/android/repository/android-ndk-r28c-windows.zip
then pass it: -Ndk C:\path\to\android-ndk-r28c
or put it in the SDK's ndk folder, or set ANDROID_NDK_HOME.
"@
}

$ndkRoot = Find-Ndk -Given $Ndk
$ndkBin = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin"
Write-Host "NDK: $ndkRoot"

# The NDK ships one wrapper per API level; the highest one is what a build for
# Android 16 wants (r28c stops at 35, which links and runs under 36 all the
# same; r29 and later have 36).
function Wrapper-For {
    param([string]$Arch, [string]$Prefix)
    $min = if ($ApiLevel -gt 0) { $ApiLevel } else { 21 }
    $levels = Get-ChildItem $ndkBin -Filter "$Arch-linux-android*-clang.cmd" |
        ForEach-Object { if ($_.Name -match 'android(\d+)-clang\.cmd$') { [int]$Matches[1] } } |
        Where-Object { $_ -ge $min } | Sort-Object -Descending
    if (-not $levels) { throw "no $Arch clang wrapper in $ndkBin" }
    $level = if ($ApiLevel -gt 0 -and $levels -contains $ApiLevel) { $ApiLevel } else { $levels[0] }
    Write-Host ("  {0,-12} API {1}" -f $Prefix, $level)
    Join-Path $ndkBin "$Arch-linux-android$level-clang.cmd"
}

# Building for a phone in China: goproxy.cn serves the module and the Go
# toolchain a go.mod of this age asks for. Set GOPROXY yourself to change it.
if ($Goproxy) { $env:GOPROXY = $Goproxy }
if (-not $env:GOPROXY) { $env:GOPROXY = "https://goproxy.cn,direct" }
if (-not $env:GOTOOLCHAIN) { $env:GOTOOLCHAIN = if ($GoToolchain) { $GoToolchain } else { "auto" } }
$env:CGO_ENABLED = "1"
$env:GOOS = "android"
$env:GOFLAGS = "-trimpath"

$targets = @{
    "arm64-v8a"   = @{ Arch = "aarch64"; GOARCH = "arm64" }
    "armeabi-v7a" = @{ Arch = "armv7a"; GOARCH = "arm"; GOARM = "7" }
    "x86_64"      = @{ Arch = "x86_64"; GOARCH = "amd64" }
    "x86"         = @{ Arch = "i686"; GOARCH = "386" }
}

$jniLibs = Join-Path $root "android\app\src\main\jniLibs"

Write-Host "magpie source: $Upstream"
foreach ($abi in $Abis) {
    if (-not $targets.ContainsKey($abi)) {
        throw "unknown ABI $abi; one of: $($targets.Keys -join ', ')"
    }
    $t = $targets[$abi]
    $outDir = Join-Path $jniLibs $abi
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $out = Join-Path $outDir "libmagpie.so"

    Write-Host ("  {0,-12} android/{1}" -f $abi, $t.GOARCH)
    $env:GOARCH = $t.GOARCH
    $env:GOARM = if ($t.ContainsKey("GOARM")) { $t.GOARM } else { "" }
    $env:CC = Wrapper-For -Arch $t.Arch -Prefix $abi

    $ldflags = "-s -w -X main.version=$Version"
    Push-Location $Upstream
    try {
        # go prints progress on stderr; the exit code is what says whether it worked.
        $previous = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & go build -tags nogui -ldflags $ldflags -o $out .
        $code = $LASTEXITCODE
        $ErrorActionPreference = $previous
        if ($code -ne 0) { throw "go build for $abi failed ($code)" }
    }
    finally {
        Pop-Location
    }
    $mb = [math]::Round((Get-Item $out).Length / 1MB, 1)
    Write-Host ("               -> {0} ({1} MB)" -f $out.Replace($root + "\", ""), $mb)
}

Write-Host "done. build the app with scripts/build-apk.ps1"
exit 0
