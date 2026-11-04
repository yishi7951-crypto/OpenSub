# Builds hy-translate for the tablet (arm64) with the Android NDK, from the same source and .tools/llama-stq as Windows, with
# docs/patches/llama-stq-arm-2x2.patch applied as well (the model's weights decoded once for two prompt tokens).
# The program is named like a library, libhytranslate.so, because Android only unpacks lib*.so from an APK, into the one
# app folder it lets programs run from. Needs the NDK in the Android SDK (ndk/29.0.14206865).
$ErrorActionPreference = 'Stop'
$OpenSubRoot = Split-Path -Parent $PSScriptRoot
$Ndk = Join-Path $env:LOCALAPPDATA 'Android\Sdk\ndk\29.0.14206865'
if (-not (Test-Path -LiteralPath $Ndk)) { throw "Android NDK was not found: $Ndk" }
$Source = Join-Path $OpenSubRoot 'native\hy-translate'
$Llama = Join-Path $OpenSubRoot '.tools\llama-stq'
$Build = Join-Path $OpenSubRoot '.tools\hy-translate-android-build'
$Output = Join-Path $OpenSubRoot '.runtime\android\jniLibs\arm64-v8a'
$Tools = Join-Path $OpenSubRoot '.tools\py-build'
$env:PATH = "$Tools\cmake\data\bin;$Tools\bin;$env:PATH"
# dotprod is where llama.cpp's fast ARM code for the model's 1.25-bit weights starts; most tablets and phones since 2019 have it.
# Plain arm64-v8a does not promise it, so a second, slower build for the base instruction set goes along, and the app picks
# by the CPU's features (AndroidTranslator.kt).
$Strip = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe'
New-Item -ItemType Directory -Force -Path $Output | Out-Null
foreach ($Variant in @(@{ Arch = 'armv8.2-a+dotprod+fp16'; Name = 'libhytranslate.so'; Dir = $Build },
                       @{ Arch = 'armv8-a'; Name = 'libhytranslate_base.so'; Dir = "$Build-base" })) {
    & cmake -S $Source -B $Variant.Dir -G Ninja -DCMAKE_BUILD_TYPE=Release `
        "-DCMAKE_TOOLCHAIN_FILE=$Ndk\build\cmake\android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 `
        "-DLLAMA_DIR=$Llama" "-DGGML_CPU_ARM_ARCH=$($Variant.Arch)"
    if ($LASTEXITCODE -ne 0) { throw 'hy-translate for Android failed to configure. See the complete error above.' }
    & cmake --build $Variant.Dir --target hy-translate
    if ($LASTEXITCODE -ne 0) { throw 'hy-translate for Android failed to build. See the complete error above.' }
    $Program = Join-Path $Output $Variant.Name
    Copy-Item -LiteralPath (Join-Path $Variant.Dir 'hy-translate') -Destination $Program -Force
    # Without its debugging symbols the program is a fraction of the size in the APK.
    & $Strip --strip-unneeded $Program
    if ($LASTEXITCODE -ne 0) { throw 'Stripping hy-translate failed.' }
    Write-Host "Ready: $Program"
}
