# 第三方组件与模型

本项目在开发准备阶段下载官方预编译包；程序运行只读取本机文件。固定下载地址和 SHA-256 见 `dependencies.lock.json`、`android-dependencies.lock.json` 与 `translation-dependencies.lock.json`、`speech-dependencies.lock.json`。

| 组件 | 版本及用途 | 许可证与来源 |
| --- | --- | --- |
| Kotlin | 2.2.21，跨平台核心 | [Apache 2.0](https://github.com/JetBrains/kotlin/blob/v2.2.21/license/LICENSE.txt) |
| Compose Multiplatform | 1.9.1，桌面界面与窗口 | [Apache 2.0](https://github.com/JetBrains/compose-multiplatform/blob/v1.9.1/LICENSE.txt) |
| kotlinx.coroutines | 1.10.2，音频与识别调度 | [Apache 2.0](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/LICENSE.txt) |
| JNA / JNA Platform | 5.17.0，调用 Windows WASAPI | [Apache 2.0 或 LGPL 2.1+](https://github.com/java-native-access/jna/blob/5.17.0/LICENSE)，采用 Apache 2.0 选项 |
| sherpa-onnx | 1.12.17，Java 接口与 Windows x64 JNI 原生库 | [Apache 2.0](https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.17/LICENSE) |
| sherpa-onnx Android | 1.12.17，官方 AAR 的 Kotlin 接口与 arm64 JNI 库 | [Apache 2.0](https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.17/LICENSE)，[官方发布包](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.12.17)；下载及 SHA-256 固定在 Android 依赖清单 |
| ONNX Runtime | 1.17.1，sherpa 内附运行库；Windows 版另带 `onnxruntime` Maven 包（开发对比用的 OPUS-MT） | [MIT](https://github.com/microsoft/onnxruntime/blob/main/LICENSE)；版本与 sherpa 内置版本保持一致 |
| Hy-MT2-1.8B 1.25-bit | 腾讯混元翻译模型，两端的英译中 | [Apache 2.0](https://huggingface.co/tencent/Hy-MT2-1.8B-1.25Bit-GGUF)，Copyright (C) 2026 Tencent。修改内容：Windows 版把张量类型编号 42 改为 llama.cpp 使用的 43；安卓版由 `scripts/stq_to_tq2.py` 改写成 TQ2_0 格式，权重和缩放逐位不变。声明见 `licenses/Hy-MT2-NOTICE.txt`，许可证全文见 `licenses/Apache-2.0.txt` |
| llama.cpp | PR #22836（提交 1e411d8），静态链接进 `hy-translate.exe` 和安卓的 `libhytranslate.so`；本项目补了 STQ1_0 的 AVX2 运算和 STQ1_0/TQ2_0 的 ARM 两行点积，见 `docs/patches/` | [MIT](https://github.com/ggml-org/llama.cpp/blob/master/LICENSE)，Copyright (c) The ggml authors |
| Firefox Translations en-zh | Mozilla 模型 2.2 版，开发时对比用，不进安装包 | [MPL-2.0](https://www.mozilla.org/MPL/2.0/)，来源 [mozilla/firefox-translations-models](https://github.com/mozilla/firefox-translations-models)。使用 [marcosremar2/firefox-translations-ucast-onnx](https://huggingface.co/marcosremar2/firefox-translations-ucast-onnx) 的社区 ONNX int8 转换版，修改内容为格式转换和量化，未重新训练；模型目录附许可证全文 |
| OPUS-MT English-Chinese | `Helsinki-NLP/opus-mt-en-zh`，开发时对比用，不进安装包 | [CC-BY 4.0](https://huggingface.co/Helsinki-NLP/opus-mt-en-zh)，作者 Language Technology Research Group at the University of Helsinki。使用 [Xenova/opus-mt-en-zh](https://huggingface.co/Xenova/opus-mt-en-zh) 的社区 ONNX int8 转换版，修改内容为格式转换和量化 |
| Parakeet TDT 110M int8 | `sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8`，英文识别，来自 sherpa-onnx 官方 asr-models 发布 | [CC-BY 4.0](https://huggingface.co/nvidia/parakeet-tdt_ctc-110m)，原模型 `nvidia/parakeet-tdt_ctc-110m`，作者 NVIDIA。sherpa-onnx 项目转换为 ONNX 并量化为 int8，未重新训练；声明见 `licenses/Parakeet-CC-BY-4.0.txt` |
| Moonshine Tiny English int8 | `sherpa-onnx-moonshine-tiny-en-int8`，开发诊断用，模型不进安装包；安卓带其中一段样本音频 `qa/0.wav` 用于离线自检 | [模型及下载说明](https://k2-fsa.github.io/sherpa/onnx/moonshine/models.html)，模型包 `LICENSE` 为 MIT，Copyright 2024 Useful Sensors；随包保留 |
| Silero VAD | `silero_vad.onnx`，判断说话和停顿 | [MIT](https://github.com/snakers4/silero-vad/blob/master/LICENSE)，文件来自 [sherpa 官方模型发布](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) |
| Noto Serif SC | 常用简体字子集（常规字重，约 7600 字），用于「宋体」选项；由 `scripts/prepare_font.py` 生成 | [SIL OFL 1.1](http://scripts.sil.org/OFL)，© 2017-2023 Adobe；修改内容为字重固定和字符裁剪。OFL 全文见 `licenses/OFL-1.1-NotoSerifSC.txt` |
| Gradle Wrapper | 8.14.3，构建工具 | [Apache 2.0](https://github.com/gradle/gradle/blob/v8.14.3/LICENSE) |
| Microsoft OpenJDK | 21.0.8，当前本机打包所用 Java 运行环境 | [OpenJDK GPLv2 + Classpath Exception](https://openjdk.org/legal/gplv2+ce.html)，发行目录保留运行环境的 legal 文档 |
| LiveSub | 参考其 `OfflinePhraseRecognizer` 与 `SentenceAssembler` 的做法：长语音在最长词间空隙切开、切开的片段不算句尾、按标点切句时跳过缩写和小数。`WordGap.kt` 与 `splitSentences` 为本项目自行编写，未复制其代码 | [MIT](https://github.com/R-Dilipkumar/LiveSub-Offline-Translator/blob/HEAD/LICENSE)，Copyright (c) 2026 R-Dilipkumar |

模型的 `test_wavs` 目录和参考文本不打入程序。Windows 发行目录和安卓 APK 都带本文件、`LICENSE` 和 `licenses/` 文件夹（Apache 2.0 全文、Hy-MT2 与 Parakeet 的声明、字体 OFL 全文）。Skiko/Skia、AndroidX 等传递依赖保留其 JAR 内许可证；Windows 发行目录保留 Java 运行环境的 legal 文档。

开发和评测用的录音、视频只在本机使用，不随安装包或仓库分发。
