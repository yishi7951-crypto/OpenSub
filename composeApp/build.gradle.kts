import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.application")
}

kotlin {
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                implementation("net.java.dev.jna:jna:5.17.0")
                implementation("net.java.dev.jna:jna-platform:5.17.0")
                implementation(fileTree(rootProject.file(".runtime/sherpa")) { include("**/*.jar") })
                // Matches the runtime inside sherpa-onnx 1.12.17, so one native library serves both engines.
                implementation("com.microsoft.onnxruntime:onnxruntime:1.17.1")
            }
        }
        val desktopTest by getting { dependencies { implementation(kotlin("test-junit")) } }
        // Code for the JVM on both: Hy-MT2 through hy-translate, and the subtitle export.
        desktopMain.kotlin.srcDir("src/jvmShared/kotlin")
        // One font file: an Android font resource and a desktop classpath resource at font/noto_serif_sc.ttf.
        desktopMain.resources.srcDir("src/sharedFonts")
        androidMain.get().kotlin.srcDir("src/jvmShared/kotlin")
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.11.0")
            implementation(files(rootProject.file(".runtime/android/sherpa-onnx-1.12.17.aar")))
        }
    }
}

android {
    namespace = "com.opensub.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.opensub.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 14
        versionName = "0.7.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    // hy-translate is a program, libhytranslate.so, and Android only runs programs from the folder it unpacks libraries into.
    packaging.jniLibs.useLegacyPackaging = true
    sourceSets.getByName("main").jniLibs.srcDir(rootProject.file(".runtime/android/jniLibs"))
    // Stored as is, the model is copied out on first start in seconds; compressed it would have to be unpacked.
    androidResources.noCompress += "gguf"
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/androidAssets"))
    sourceSets.getByName("main").res.srcDir("src/sharedFonts")
    sourceSets.getByName("debug").manifest.srcFile("src/androidDebug/AndroidManifest.xml")
    // The release key stays outside Git in .signing/; without it only the debug build can be made.
    val signing = rootProject.file(".signing/keystore.properties")
    if (signing.exists()) {
        val keys = Properties().apply { signing.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = rootProject.file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    // A 1.25-bit model is already incompressible noise to lint; the release build only needs the checks run on debug.
    lint.checkReleaseBuilds = false
}

// Sync, not Copy: a replaced model must disappear from the package rather than linger in the output folder.
// The tablet takes Hy-MT2 rewritten as TQ2_0 by scripts/stq_to_tq2.py: the same weights, a fifth faster on its cores.
val prepareAndroidAssets by tasks.registering(Sync::class) {
    from(rootProject.file("models")) { into("models"); exclude("**/test_wavs/**", "sherpa-onnx-moonshine-tiny-en-int8/**", "firefox-en-zh-int8/**", "opus-mt-en-zh-int8/**", "speaker/**", "hy-mt2-1.8b-1.25bit/**") }
    from(rootProject.file("models/sherpa-onnx-moonshine-tiny-en-int8/test_wavs/0.wav")) { into("qa") }
    from(rootProject.files("LICENSE", "THIRD_PARTY.md")) { into("licenses") }
    from(rootProject.file("licenses")) { into("licenses") }
    into(layout.buildDirectory.dir("generated/androidAssets"))
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareAndroidAssets) }

compose.desktop {
    application {
        mainClass = "com.opensub.app.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "OpenSub"
            packageVersion = "0.9.29"
            description = "Offline English-to-Chinese subtitles for Windows"
            vendor = "OpenSub"
            modules("java.desktop", "java.logging", "java.management", "jdk.unsupported")
            appResourcesRootDir.set(rootProject.layout.projectDirectory.dir("appResources"))
            outputBaseDir.set(rootProject.layout.buildDirectory.dir("windows-dist"))
            windows { menuGroup = "OpenSub"; shortcut = true }
        }
    }
}

tasks.register<Sync>("copyOfflineResources") {
    from(rootProject.file("models")) { into("models"); exclude("**/test_wavs/**", "sherpa-onnx-moonshine-tiny-en-int8/**", "opus-mt-en-zh-int8/**", "firefox-en-zh-int8/**", "speaker/**", "hy-mt2-1.8b-1.25bit-tq2/**") }
    from(rootProject.file(".runtime/sherpa")) { into("native"); include("**/*.dll") }
    from(rootProject.file(".runtime/hy-mt")) { into("native"); include("hy-translate.exe") }
    from(rootProject.file(".runtime/audio-capture")) { into("native"); include("audio-capture.exe") }
    from(rootProject.files("THIRD_PARTY.md", "LICENSE"))
    from(rootProject.file("licenses")) { into("licenses") }
    into(rootProject.file("appResources/common"))
}
tasks.matching { it.name == "createDistributable" || it.name == "packageDistributionForCurrentOS" }.configureEach {
    dependsOn("copyOfflineResources")
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn("copyOfflineResources") }
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    workingDir = rootProject.projectDir
    systemProperty("opensub.home", rootProject.projectDir.absolutePath)
}

tasks.register<JavaExec>("selfTest") {
    group = "verification"
    dependsOn("desktopJar")
    val target = kotlin.targets.getByName("desktop") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    val compilation = target.compilations.getByName("main")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("com.opensub.app.MainKt")
    args("--self-test")
    systemProperty("opensub.home", rootProject.projectDir.absolutePath)
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("captureTest") {
    group = "verification"
    dependsOn("desktopJar")
    val target = kotlin.targets.getByName("desktop") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    val compilation = target.compilations.getByName("main")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("com.opensub.app.MainKt")
    args("--capture-test", "--play-sample")
    systemProperty("opensub.home", rootProject.projectDir.absolutePath)
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("probeAudio") {
    group = "verification"
    dependsOn("desktopJar")
    val target = kotlin.targets.getByName("desktop") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    val compilation = target.compilations.getByName("main")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("com.opensub.app.MainKt")
    args("--probe-audio")
    systemProperty("opensub.home", rootProject.projectDir.absolutePath)
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("verifyRecording") {
    group = "verification"
    dependsOn("desktopJar")
    val target = kotlin.targets.getByName("desktop") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    val compilation = target.compilations.getByName("main")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("com.opensub.app.MainKt")
    args("--verify-recording")
    systemProperty("opensub.home", rootProject.projectDir.absolutePath)
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("latencyBenchmark") {
    group = "verification"
    dependsOn("desktopJar")
    val target = kotlin.targets.getByName("desktop") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    val compilation = target.compilations.getByName("main")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("com.opensub.app.MainKt")
    args("--latency-benchmark")
    systemProperty("opensub.home", rootProject.projectDir.absolutePath)
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("translationTest") {
    group = "verification"
    dependsOn("desktopJar")
    val target = kotlin.targets.getByName("desktop") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    val compilation = target.compilations.getByName("main")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("com.opensub.app.MainKt")
    args("--translation-test")
    jvmArgs("-Dstdout.encoding=UTF-8")
    systemProperty("opensub.home", rootProject.projectDir.absolutePath)
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("speechEvaluation") {
    group = "verification"
    dependsOn("desktopJar")
    val target = kotlin.targets.getByName("desktop") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    val compilation = target.compilations.getByName("main")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("com.opensub.app.MainKt")
    args("--speech-evaluation", providers.gradleProperty("wav").getOrElse(""))
    jvmArgs("-Dstdout.encoding=UTF-8")
    systemProperty("opensub.home", rootProject.projectDir.absolutePath)
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("transcriptScore") {
    group = "verification"
    dependsOn("desktopJar")
    val target = kotlin.targets.getByName("desktop") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    val compilation = target.compilations.getByName("main")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("com.opensub.app.MainKt")
    args(listOf("--transcript-score", providers.gradleProperty("wav").getOrElse(""), providers.gradleProperty("seconds").getOrElse("180")) +
        providers.gradleProperty("trials").getOrElse("0.5/20/8/0.25").split(";"))
    jvmArgs("-Dstdout.encoding=UTF-8")
    systemProperty("opensub.home", rootProject.projectDir.absolutePath)
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("speakerProbe") {
    group = "verification"
    dependsOn("desktopJar")
    val target = kotlin.targets.getByName("desktop") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    val compilation = target.compilations.getByName("main")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("com.opensub.app.MainKt")
    args("--speaker-probe", providers.gradleProperty("wav").getOrElse(""), providers.gradleProperty("segments").getOrElse(""))
    jvmArgs("-Dstdout.encoding=UTF-8")
    systemProperty("opensub.home", rootProject.projectDir.absolutePath)
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("recognitionReplay") {
    group = "verification"
    dependsOn("desktopJar")
    val target = kotlin.targets.getByName("desktop") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    val compilation = target.compilations.getByName("main")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("com.opensub.app.MainKt")
    args("--recognition-replay", providers.gradleProperty("wav").getOrElse(""), providers.gradleProperty("seconds").getOrElse("60"))
    jvmArgs("-Dstdout.encoding=UTF-8")
    systemProperty("opensub.home", rootProject.projectDir.absolutePath)
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("translateFile") {
    group = "verification"
    dependsOn("desktopJar")
    val target = kotlin.targets.getByName("desktop") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    val compilation = target.compilations.getByName("main")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("com.opensub.app.MainKt")
    args("--translate-file", providers.gradleProperty("text").getOrElse(""))
    jvmArgs("-Dstdout.encoding=UTF-8")
    systemProperty("opensub.home", rootProject.projectDir.absolutePath)
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("soakTest") {
    group = "verification"
    dependsOn("desktopJar")
    val target = kotlin.targets.getByName("desktop") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    val compilation = target.compilations.getByName("main")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("com.opensub.app.MainKt")
    args("--soak-test")
    jvmArgs("-Dstdout.encoding=UTF-8")
    systemProperty("opensub.home", rootProject.projectDir.absolutePath)
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("uiPreview") {
    group = "verification"
    dependsOn("desktopJar")
    val target = kotlin.targets.getByName("desktop") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    val compilation = target.compilations.getByName("main")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("com.opensub.app.MainKt")
    args("--ui-preview")
    systemProperty("opensub.home", rootProject.projectDir.absolutePath)
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("animationPreview") {
    group = "verification"
    dependsOn("desktopJar")
    val target = kotlin.targets.getByName("desktop") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    val compilation = target.compilations.getByName("main")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("com.opensub.app.MainKt")
    args("--animation-preview")
    systemProperty("opensub.home", rootProject.projectDir.absolutePath)
    workingDir = rootProject.projectDir
}
