. "$PSScriptRoot\Environment.ps1"
$OpenSubPython = Get-Command python -ErrorAction Stop
& $OpenSubPython.Source "$PSScriptRoot\prepare_windows.py"
if ($LASTEXITCODE -ne 0) { throw 'Dependency preparation failed. See the complete error above.' }
& $OpenSubGradle :composeApp:desktopTest :composeApp:selfTest --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Windows verification failed. See the complete error above.' }
