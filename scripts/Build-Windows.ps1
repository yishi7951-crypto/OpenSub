param([ValidatePattern('^[A-Za-z0-9._-]+$')][string]$OutputName = 'OpenSub-0.9.29')
. "$PSScriptRoot\Environment.ps1"
& $OpenSubGradle :composeApp:desktopTest :composeApp:createDistributable --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Windows build failed. See the complete error above.' }
$OpenSubBuiltApp = Join-Path $OpenSubRoot 'build\windows-dist\main\app\OpenSub'
$OpenSubOutput = Join-Path $OpenSubRoot "out\windows\$OutputName"
New-Item -ItemType Directory -Force -Path $OpenSubOutput | Out-Null
Get-ChildItem -LiteralPath $OpenSubBuiltApp -Force | Copy-Item -Destination $OpenSubOutput -Recurse -Force
Write-Host "Ready: $OpenSubOutput\OpenSub.exe"
