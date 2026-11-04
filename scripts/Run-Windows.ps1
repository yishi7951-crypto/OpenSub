$ErrorActionPreference = 'Stop'
$OpenSubRoot = Split-Path -Parent $PSScriptRoot
$OpenSubExe = Join-Path $OpenSubRoot 'out\windows\OpenSub-0.9.29\OpenSub.exe'
if (-not (Test-Path -LiteralPath $OpenSubExe)) { $OpenSubExe = Join-Path $OpenSubRoot 'out\windows\OpenSub-0.6.0\OpenSub.exe' }
if (-not (Test-Path -LiteralPath $OpenSubExe)) { $OpenSubExe = Join-Path $OpenSubRoot 'out\windows\OpenSub-0.3.1\OpenSub.exe' }
if (-not (Test-Path -LiteralPath $OpenSubExe)) { $OpenSubExe = Join-Path $OpenSubRoot 'out\windows\OpenSub\OpenSub.exe' }
if (Test-Path -LiteralPath $OpenSubExe) {
    Start-Process -FilePath $OpenSubExe -WorkingDirectory (Split-Path -Parent $OpenSubExe)
    exit 0
}
. "$PSScriptRoot\Environment.ps1"
& $OpenSubGradle :composeApp:run --console=plain
if ($LASTEXITCODE -ne 0) { throw 'OpenSub failed to start. See the complete error above.' }
