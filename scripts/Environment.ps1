$ErrorActionPreference = 'Stop'
$OpenSubRoot = Split-Path -Parent $PSScriptRoot
$OpenSubJdkCandidates = @()
if ($env:JAVA_HOME) { $OpenSubJdkCandidates += $env:JAVA_HOME }
$OpenSubJdkCandidates += Get-ChildItem -LiteralPath 'C:\Program Files\Android\openjdk' -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
$OpenSubJdkCandidates += Get-ChildItem -LiteralPath 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
$OpenSubJdk = $OpenSubJdkCandidates | Where-Object {
    $releaseFile = Join-Path $_ 'release'
    (Test-Path -LiteralPath $releaseFile) -and ((Get-Content -LiteralPath $releaseFile -Raw) -match 'JAVA_VERSION="21[.\"]')
} | Select-Object -First 1
if (-not $OpenSubJdk) { throw 'JDK 21 is required for development. Install a JDK 21 or set JAVA_HOME to its directory.' }
$env:JAVA_HOME = $OpenSubJdk
$OpenSubSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
if (Test-Path -LiteralPath $OpenSubSdk) { $env:ANDROID_HOME = $OpenSubSdk }
$OpenSubGradle = Join-Path $OpenSubRoot '.tools\gradle-8.14.3\bin\gradle.bat'
if (-not (Test-Path -LiteralPath $OpenSubGradle)) { $OpenSubGradle = Join-Path $OpenSubRoot 'gradlew.bat' }
Set-Location -LiteralPath $OpenSubRoot
