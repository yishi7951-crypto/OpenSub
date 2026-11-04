param([switch]$Install, [switch]$Release)
. "$PSScriptRoot\Environment.ps1"
# -Release builds the signed APK for publishing; it needs the key in .signing\ (see README).
$OpenSubVariant = if ($Release) { 'release' } else { 'debug' }
if ($Release -and -not (Test-Path (Join-Path $OpenSubRoot '.signing\keystore.properties'))) { throw 'No release key: .signing\keystore.properties is missing.' }
$OpenSubBuiltApk = Join-Path $OpenSubRoot "composeApp\build\outputs\apk\$OpenSubVariant\composeApp-$OpenSubVariant.apk"
# Gradle's incremental packaging leaves the old bytes behind when a large model is replaced: 803 MB of files made a 1.2 GB APK.
Remove-Item -LiteralPath $OpenSubBuiltApk -ErrorAction SilentlyContinue
$OpenSubTask = if ($Release) { ':composeApp:assembleRelease' } else { ':composeApp:assembleDebug' }
& $OpenSubGradle $OpenSubTask :composeApp:desktopTest --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Android build failed. See the complete error above.' }
$OpenSubAapt2 = Join-Path $env:ANDROID_HOME 'build-tools\36.0.0\aapt2.exe'
$OpenSubAndroidOutput = Join-Path $OpenSubRoot 'out\android'
New-Item -ItemType Directory -Force -Path $OpenSubAndroidOutput | Out-Null
$OpenSubCheck = @((Join-Path $PSScriptRoot 'verify_android_apk.py'), $OpenSubBuiltApk, '--aapt2', $OpenSubAapt2, '--report', (Join-Path $OpenSubAndroidOutput "apk-check-$OpenSubVariant.json"))
if ($Release) { $OpenSubCheck += '--release' }
& python @OpenSubCheck
if ($LASTEXITCODE -ne 0) { throw 'Android APK verification failed; the APK was not copied or installed.' }
$OpenSubApkName = if ($Release) { 'OpenSub-Android-0.7.1.apk' } else { 'OpenSub-Android-0.7.1-debug.apk' }
$OpenSubApk = Join-Path $OpenSubAndroidOutput $OpenSubApkName
Copy-Item -LiteralPath $OpenSubBuiltApk -Destination $OpenSubApk -Force
Write-Host "Ready: $OpenSubApk"
if ($Install) {
    $OpenSubAdb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    & $OpenSubAdb install -r $OpenSubApk
    if ($LASTEXITCODE -ne 0) { throw 'Tablet installation failed. Confirm USB debugging authorization on the device.' }
    & $OpenSubAdb shell am start -W -n com.opensub.app/.MainActivity
    if ($LASTEXITCODE -ne 0) { throw 'The tablet app did not start.' }
}
