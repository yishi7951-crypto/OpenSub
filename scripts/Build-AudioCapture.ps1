# Builds the small program that captures other programs' sound before the system volume. Needs Visual Studio with C++.
$ErrorActionPreference = 'Stop'
$OpenSubRoot = Split-Path -Parent $PSScriptRoot
$VisualStudio = Get-ChildItem 'C:\Program Files\Microsoft Visual Studio' -Directory | Sort-Object Name -Descending | Select-Object -First 1
$VcVars = Join-Path $VisualStudio.FullName 'Community\VC\Auxiliary\Build\vcvars64.bat'
if (-not (Test-Path -LiteralPath $VcVars)) { throw "Visual Studio C++ tools were not found: $VcVars" }
$Source = Join-Path $OpenSubRoot 'native\audio-capture'
$Build = Join-Path $OpenSubRoot '.tools\audio-capture-build'
$Output = Join-Path $OpenSubRoot '.runtime\audio-capture'
$Tools = Join-Path $OpenSubRoot '.tools\py-build'
$Commands = @(
    "call `"$VcVars`" >nul",
    "set PATH=$Tools\cmake\data\bin;$Tools\bin;%PATH%",
    "cmake -S `"$Source`" -B `"$Build`" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_COMPILER=cl || exit /b 1",
    "cmake --build `"$Build`" --target audio-capture || exit /b 1"
)
$Script = Join-Path $env:TEMP 'opensub-build-audio-capture.cmd'
Set-Content -LiteralPath $Script -Value ($Commands -join "`r`n") -Encoding Ascii
& cmd /c $Script
if ($LASTEXITCODE -ne 0) { throw 'audio-capture failed to build. See the complete error above.' }
New-Item -ItemType Directory -Force -Path $Output | Out-Null
Copy-Item -LiteralPath (Join-Path $Build 'audio-capture.exe') -Destination $Output -Force
Write-Host "Ready: $Output\audio-capture.exe"
