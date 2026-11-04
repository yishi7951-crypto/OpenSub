# Builds the small program that keeps Hy-MT2 loaded. Needs Visual Studio with C++ and .tools/llama-stq,
# which is llama.cpp commit 1e411d8 (PR #22836) with docs/patches/llama-stq-avx2.patch applied.
$ErrorActionPreference = 'Stop'
$OpenSubRoot = Split-Path -Parent $PSScriptRoot
$VisualStudio = Get-ChildItem 'C:\Program Files\Microsoft Visual Studio' -Directory | Sort-Object Name -Descending | Select-Object -First 1
$VcVars = Join-Path $VisualStudio.FullName 'Community\VC\Auxiliary\Build\vcvars64.bat'
if (-not (Test-Path -LiteralPath $VcVars)) { throw "Visual Studio C++ tools were not found: $VcVars" }
$Source = Join-Path $OpenSubRoot 'native\hy-translate'
$Llama = Join-Path $OpenSubRoot '.tools\llama-stq'
$Build = Join-Path $OpenSubRoot '.tools\hy-translate-build'
$Output = Join-Path $OpenSubRoot '.runtime\hy-mt'
$Tools = Join-Path $OpenSubRoot '.tools\py-build'
$Commands = @(
    "call `"$VcVars`" >nul",
    "set PATH=$Tools\cmake\data\bin;$Tools\bin;%PATH%",
    "cmake -S `"$Source`" -B `"$Build`" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=cl -DCMAKE_CXX_COMPILER=cl -DLLAMA_DIR=`"$Llama`" || exit /b 1",
    "cmake --build `"$Build`" --target hy-translate || exit /b 1"
)
$Script = Join-Path $env:TEMP 'opensub-build-hy-translate.cmd'
Set-Content -LiteralPath $Script -Value ($Commands -join "`r`n") -Encoding Ascii
& cmd /c $Script
if ($LASTEXITCODE -ne 0) { throw 'hy-translate failed to build. See the complete error above.' }
New-Item -ItemType Directory -Force -Path $Output | Out-Null
Copy-Item -LiteralPath (Join-Path $Build 'hy-translate.exe') -Destination $Output -Force
Write-Host "Ready: $Output\hy-translate.exe"
