param(
  [string]$AdbPath = "C:\Users\L\Downloads\scrcpy-win64-v4.0\adb.exe",
  [string]$PackageName = "com.studysuit.aiqa",
  [string]$ApkPath = "app\build\outputs\apk\benchmarkRelease\app-benchmarkRelease.apk",
  [string]$OutputDir = "",
  [int]$Iterations = 3,
  [int]$ScenarioWarmupSeconds = 8,
  [switch]$SkipBuild,
  [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $AdbPath)) {
  throw "adb not found: $AdbPath"
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $repoRoot

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
  $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
  $OutputDir = Join-Path $repoRoot "build\performance\miui-smoke-$stamp"
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

function Invoke-Adb {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
  & $AdbPath @Args
}

function Invoke-AdbText {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
  $text = & $AdbPath @Args 2>&1
  if ($LASTEXITCODE -ne 0) {
    throw "adb $($Args -join ' ') failed:`n$text"
  }
  return ($text -join "`n")
}

function Save-Text {
  param([string]$Path, [string]$Text)
  $parent = Split-Path -Parent $Path
  New-Item -ItemType Directory -Force -Path $parent | Out-Null
  Set-Content -LiteralPath $Path -Value $Text -Encoding UTF8
}

function Install-Apk {
  param([string]$Path)
  $fullPath = Resolve-Path $Path
  $installOutput = & $AdbPath install -r -d $fullPath 2>&1
  if ($LASTEXITCODE -eq 0) {
    Save-Text (Join-Path $OutputDir "install.txt") ($installOutput -join "`n")
    return
  }

  $remoteApk = "/data/local/tmp/artiflow-benchmark.apk"
  Invoke-Adb push $fullPath $remoteApk | Out-Null
  $rootInstall = & $AdbPath shell su -c "pm install -r -d $remoteApk" 2>&1
  Save-Text (Join-Path $OutputDir "install.txt") (($installOutput + $rootInstall) -join "`n")
  if ($LASTEXITCODE -ne 0) {
    throw "APK install failed. See $OutputDir\install.txt"
  }
}

function Reset-GfxInfo {
  Invoke-Adb shell dumpsys gfxinfo $PackageName reset | Out-Null
}

function Force-Stop-App {
  Invoke-Adb shell am force-stop $PackageName | Out-Null
}

function Start-Scenario {
  param([string]$Scenario)
  $args = @(
    "shell", "am", "start", "-W",
    "-n", "$PackageName/.MainActivity",
    "--es", "com.studysuit.aiqa.BENCHMARK_SCENARIO", $Scenario
  )
  return Invoke-AdbText @args
}

function Invoke-Swipe {
  param(
    [int]$StartX,
    [int]$StartY,
    [int]$EndX,
    [int]$EndY,
    [int]$DurationMs
  )

  $command = "input swipe $StartX $StartY $EndX $EndY $DurationMs"
  try {
    Invoke-AdbText shell su -c $command | Out-Null
    return
  } catch {
    $rootFailure = $_.Exception.Message
  }

  try {
    Invoke-AdbText shell $command | Out-Null
  } catch {
    throw "Unable to inject swipe with root or shell input.`nRoot attempt:`n$rootFailure`nShell attempt:`n$($_.Exception.Message)"
  }
}

function Swipe-Up {
  Invoke-Swipe 540 1770 540 600 180
  Start-Sleep -Milliseconds 450
}

function Swipe-Down {
  Invoke-Swipe 540 600 540 1770 180
  Start-Sleep -Milliseconds 450
}

function Run-Interaction {
  param([string]$Scenario)

  switch ($Scenario) {
    "chat_100_scroll" {
      1..5 | ForEach-Object { Swipe-Up }
      1..5 | ForEach-Object { Swipe-Down }
    }
    "mistake_200_search" {
      1..4 | ForEach-Object { Swipe-Up }
    }
    "three_image_preview" {
      1..3 | ForEach-Object { Swipe-Up; Swipe-Down }
    }
    "formula_image_heavy" {
      1..4 | ForEach-Object { Swipe-Up; Swipe-Down }
    }
    "stream_2_minutes" {
      Start-Sleep -Seconds 125
    }
    default {
      1..3 | ForEach-Object { Swipe-Up; Swipe-Down }
    }
  }
}

function Parse-GfxSummary {
  param([string]$Text)
  $total = ""
  $janky = ""
  $percent = ""

  if ($Text -match "Total frames rendered:\s+(\d+)") {
    $total = $Matches[1]
  }
  if ($Text -match "Janky frames:\s+(\d+)\s+\(([0-9.]+)%\)") {
    $janky = $Matches[1]
    $percent = $Matches[2]
  }

  [pscustomobject]@{
    totalFrames = $total
    jankyFrames = $janky
    jankyPercent = $percent
  }
}

if (-not $SkipBuild) {
  & .\gradlew.bat :app:assembleBenchmarkRelease
  if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed"
  }
}

if (-not $SkipInstall) {
  Install-Apk $ApkPath
}

$device = Invoke-AdbText shell getprop ro.product.model
$fingerprint = Invoke-AdbText shell getprop ro.build.fingerprint
$sdk = Invoke-AdbText shell getprop ro.build.version.sdk
$miui = Invoke-AdbText shell getprop ro.miui.ui.version.name

$scenarios = @(
  "cold_start",
  "chat_100_scroll",
  "mistake_200_search",
  "three_image_preview",
  "formula_image_heavy",
  "stream_2_minutes"
)

$rows = New-Object System.Collections.Generic.List[object]

for ($iteration = 1; $iteration -le $Iterations; $iteration++) {
  foreach ($scenario in $scenarios) {
    $scenarioDir = Join-Path $OutputDir "$iteration-$scenario"
    New-Item -ItemType Directory -Force -Path $scenarioDir | Out-Null

    Force-Stop-App
    Reset-GfxInfo

    $scenarioExtra = if ($scenario -eq "cold_start") { "chat_100_scroll" } else { $scenario }
    $startup = Start-Scenario $scenarioExtra
    Save-Text (Join-Path $scenarioDir "startup.txt") $startup

    if ($startup -match "TotalTime:\s+(\d+)") {
      $startupMs = $Matches[1]
    } else {
      $startupMs = ""
    }

    if ($scenario -ne "cold_start") {
      Start-Sleep -Seconds $ScenarioWarmupSeconds
      Run-Interaction $scenario
    }

    $gfx = Invoke-AdbText shell dumpsys gfxinfo $PackageName
    $framestats = Invoke-AdbText shell dumpsys gfxinfo $PackageName framestats
    Save-Text (Join-Path $scenarioDir "gfxinfo.txt") $gfx
    Save-Text (Join-Path $scenarioDir "framestats.txt") $framestats

    $summary = Parse-GfxSummary $gfx
    $rows.Add([pscustomobject]@{
      iteration = $iteration
      scenario = $scenario
      startupMs = $startupMs
      totalFrames = $summary.totalFrames
      jankyFrames = $summary.jankyFrames
      jankyPercent = $summary.jankyPercent
    })
  }
}

$csvPath = Join-Path $OutputDir "summary.csv"
$rows | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding UTF8

$readme = @"
# MIUI Performance Smoke

Device: $device
SDK: $sdk
MIUI: $miui
Fingerprint: $fingerprint
Package: $PackageName
Iterations: $Iterations

This is a root-capable fallback for MIUI devices where AndroidX Macrobenchmark
hangs during ShellImpl root probing before the app scenario starts. It keeps the
standard benchmark module intact and records startup plus gfxinfo evidence for
the same in-app benchmark scenarios.

See summary.csv and each scenario folder for raw startup, gfxinfo, and framestats
outputs.
"@
Save-Text (Join-Path $OutputDir "README.md") $readme

Write-Host "MIUI performance smoke complete: $OutputDir"
Write-Host "Summary: $csvPath"
