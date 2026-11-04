# OpenSub

完全离线的 Windows / Android 英译中字幕工具。捕获设备正在播放的声音，用本机模型识别英语并翻译成中文，以悬浮字幕显示。

当前版本：Windows 0.9.29，安卓 0.7.1。两端都用 Parakeet 110M 滚动识别英语、腾讯 Hy-MT2 翻译，字幕一次显示一句和它自己的中文。

## 下载

安装包在本仓库的 **Releases** 页面，模型已经打包在里面，运行时不联网：

- **Windows**：下载 `OpenSub-Windows-0.9.29.zip`，解压后双击 `OpenSub.exe`。需要 Windows 10 2004 或更新版本、支持 AVX2 的 64 位 CPU（近十年的 Intel/AMD 基本都支持）。
- **安卓**：下载 `OpenSub-Android-0.7.1.apk` 安装。需要安卓 10 或更新版本、64 位 ARM 处理器，建议 8GB 内存。

### 第一次运行可能遇到的提示

- **Windows 弹出「Windows 已保护你的电脑」**：OpenSub 没有购买代码签名证书，第一次运行会被 SmartScreen 拦一下。点「更多信息」→「仍要运行」即可。
- **杀毒软件报警**：`native/audio-capture.exe` 会录制其他程序发出的声音，个别杀毒软件可能误报，可以添加信任。程序完全离线，不联网、不上传任何内容。
- **安卓安装时提示「未知来源」**：按提示允许从浏览器或文件管理器安装应用。

## 在 Windows 上使用

1. 在浏览器或任意播放器里播放英语视频。
2. 打开 OpenSub，点击「开始字幕」。
3. 字幕窗一次显示一句：英文边听边出，下面是这一句的中文。拖动字幕调整位置；主窗口可以切换双语/中文/英文、黑体/宋体和中英文上下顺序，分别调整中英文字号和背景不透明度。
4. 有字幕后可点「导出本次字幕」，选择 Word、TXT、Markdown 或 SRT。SRT 的时间从开始字幕算起，不是视频自身的时间轴。
5. 点击「停止」，程序处理完最后一句后停止。

OpenSub 录的是其他程序发出的声音，在系统音量之前截取，所以扬声器静音或音量很小也能出字幕。**独占模式的播放器和受版权保护的播放（如部分付费视频网站）录不到。** 请用窗口或无边框播放，播放器的独占全屏可能盖住字幕窗。OpenSub 不会调整系统音量，也不会播放声音。

设置和日志保存在 `%LOCALAPPDATA%/OpenSub/`。程序不保存录音；本次字幕只保留在内存里用于导出，重新开始后清空。

## 在安卓上使用

1. 打开 OpenSub，点「开始字幕」。首次需要允许「显示在其他应用上层」，再允许本次屏幕录制（只录声音，用来抓视频的声音）。
2. 等显示「正在聆听」后，切到视频 App 播放英语。字幕的样式在主页面调整，有字幕后可导出。
3. 回到 OpenSub 或在通知栏点「停止」。

- 第一次启动要把翻译模型复制出来，需要几秒钟。
- 媒体音量为 0 也能出字幕。部分视频 App 禁止系统内录，这时录不到声音。
- **荣耀、华为、小米、OPPO、vivo 等手机的系统会自动关掉后台程序**。字幕中途停止或悬浮窗消失时，请在系统设置里给 OpenSub 打开「悬浮窗」「允许后台运行 / 自启动」，并把它加入「电池优化」白名单（不优化）。
- 手机或平板同时运行很多程序时字幕会变慢，可以先关掉其他后台程序。

## 工程与开发

使用 Kotlin Multiplatform + Compose Multiplatform。`commonMain` 放平台接口、PCM、重采样、VAD 断句、识别调度和逐词动画；`desktopMain` 实现 WASAPI、sherpa-onnx 和桌面窗口；`androidMain` 实现 MediaProjection 系统音频、前台服务、sherpa-onnx AAR 和原生悬浮窗，不依赖 GMS。

开发需要 Windows x64、JDK 21、Android SDK 36（项目保留安卓目标）和 Python 3.12+。Android Studio 已安装的 JDK 21 会由脚本自动找到。

```powershell
# 首次准备官方依赖及模型，并验证 Windows 识别
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Setup-Windows.ps1

# 启动开发版
. scripts/Environment.ps1
& $OpenSubGradle :composeApp:run --console=plain

# 算法与队列测试、真实离线模型测试
& $OpenSubGradle :composeApp:desktopTest :composeApp:selfTest --console=plain

# 播放官方英语样本，实际通过系统播放设备环回录制 10 秒并识别
& $OpenSubGradle :composeApp:captureTest --console=plain

# 静默检查当前音频链路 5 秒，不播放样本、不修改音量、不显示窗口
& $OpenSubGradle :composeApp:probeAudio --offline --console=plain

# 按真实播放速度静默输入官方录音，对比首条显示、排队、定稿质量
& $OpenSubGradle :composeApp:latencyBenchmark --offline --console=plain

# 离屏渲染正式字幕绘制组件，不打开或激活任何窗口
& $OpenSubGradle :composeApp:animationPreview --offline --console=plain

# 生成带 Java 和模型的可直接运行目录
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Build-Windows.ps1

# 首次准备 Android 官方识别库（模型与 Windows 共用）
python scripts/prepare_android.py

# 首次准备英文识别模型（约 108MB，地址和 SHA-256 固定在 speech-dependencies.lock.json）
python scripts/prepare_speech.py

# 首次准备翻译模型（Hy-MT2 约 462MB，开发对比用的 OPUS-MT 约 116MB、Firefox 模型约 58MB，地址和 SHA-256 固定在 translation-dependencies.lock.json）
python scripts/prepare_translation.py

# 编译加载 Hy-MT2 的小程序（需要 Visual Studio C++ 和打过 docs/patches/llama-stq-avx2.patch 的 .tools/llama-stq）
.\scripts\Build-HyTranslate.ps1

# 编译按程序内录的小程序（需要 Visual Studio C++）；--probe 10 会同时列出两种录法的电平和正在出声的程序
.\scripts\Build-AudioCapture.ps1
.runtime\audio-capture\audio-capture.exe --probe 10

# 回放一段 16kHz 单声道录音，日志里有字幕窗每一刻显示的内容（rows）；再看声音到字幕的时间花在哪
& $OpenSubGradle :composeApp:recognitionReplay --offline "-Pwav=<16kHz单声道录音.wav>" -Pseconds=75 --console=plain
python scripts/latency_breakdown.py "$env:LOCALAPPDATA/OpenSub/logs/recognition-replay.jsonl"

# 新的翻译规则先逐条对比有无规则的译文，格式同 SubtitleRules.kt（english = 中文 # 例句）
python scripts/test_translation_rules.py <候选规则.txt> <报告.txt>

# 无窗口检查翻译质量和速度；结果也写入 %LOCALAPPDATA%/OpenSub/logs/translation-test.txt
& $OpenSubGradle :composeApp:translationTest --offline --console=plain

# 用保存下来的读取结果给识别打分，换设置只要几秒（第一次需要几分钟）；原文在 docs/evaluation/
& $OpenSubGradle :composeApp:transcriptScore --offline "-Pwav=<16kHz单声道录音.wav>" -Pseconds=180 "-Ptrials=0.5/20/8/0.25;0.5/20/8/0.4" --console=plain
python scripts/score_transcript.py --reference docs/evaluation/churchill.txt --show 20 "$env:LOCALAPPDATA/OpenSub/logs/score-churchill-0.5_20_8_0.25.txt"
python scripts/score_captions.py --show --clip kennedy_b --reference docs/evaluation/kennedy.txt "$env:LOCALAPPDATA/OpenSub/logs/recognition-replay.jsonl"

# 用一组容易译错的句子检查 Hy-MT2 的硬错误
python scripts/score_translation.py --exe <hy-translate.exe 的完整路径> --model <gguf 的完整路径> --output out/translation-score.txt

# 无窗口、无声音运行 3 分钟完整流水线，结果写入 %LOCALAPPDATA%/OpenSub/logs/soak-test.txt
& $OpenSubGradle :composeApp:soakTest --offline --console=plain

# 离屏渲染主界面到 out/previews，不打开窗口
& $OpenSubGradle :composeApp:uiPreview --offline --console=plain

# 构建并检查安卓英文字幕 APK，附加 -Install 可安装到已授权的平板
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Build-Android.ps1

# 发布用的签名正式版（不可调试、不保存声音），需要 .signing/keystore.properties 和密钥文件
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Build-Android.ps1 -Release
```

`dependencies.lock.json` 与 `android-dependencies.lock.json` 记录官方下载地址和 SHA-256；准备脚本只在开发准备阶段联网。Gradle Wrapper 固定 8.14.3。模型、原生库、构建工具和产物均不进入 Git。安卓构建脚本在复制/安装前检查最终 APK 的页面登记、服务类型、权限、ABI、模型哈希和体积，防止清单漏合并等问题。

运行状态写入 `%LOCALAPPDATA%/OpenSub/logs/runtime-status.properties`（是否在运行、用的哪种录音、音量、字幕计数、识别和翻译耗时），每次运行屏幕上出过的英文、中文片段和整句译文写入同目录的 `live-captions.log`，排查时无需接管鼠标。

第三方组件、模型的来源和许可证见 `THIRD_PARTY.md` 和 `licenses/`。

## 许可证

OpenSub 的代码使用 [MIT 许可证](LICENSE)。随软件附带的模型、库和字体各自保留原许可证，见 `THIRD_PARTY.md`。
