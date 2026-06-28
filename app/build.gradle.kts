import java.io.File
import java.util.Base64
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val connectedDeviceEvalRequested = gradle.startParameter.taskNames.any {
    it == "connectedDeviceEval" || it.endsWith(":connectedDeviceEval")
}
val deviceEvalBackupModelsRequested = providers.gradleProperty("anvit.eval.backupModels")
    .map { it.toBoolean() }
    .orElse(false)
val deviceEvalGenerateAnswersRequested = providers.gradleProperty("anvit.eval.generateAnswers")
    .map { it.toBoolean() }
    .orElse(false)
val deviceEvalInstrumentationClass = "com.anvit.localai.eval.device.DeviceEvalInstrumentedTest"
val deviceEvalInstrumentationArgs = listOf(
    "anvit.eval.datasetAsset",
    "anvit.eval.corpusAssets",
    "anvit.eval.runId",
    "anvit.eval.modelId",
    "anvit.eval.modelFileName",
    "anvit.eval.accelerator",
    "anvit.eval.contextWindow",
    "anvit.eval.maxOutputTokens",
    "anvit.eval.maxChunks",
    "anvit.eval.sampleLimit",
    "anvit.eval.sampleTimeoutMs",
    "anvit.eval.generateAnswers",
    "anvit.eval.enableAgenticRag",
    "anvit.eval.enableSelfCritique",
    "anvit.eval.enableThinking",
    "anvit.eval.useAgentTools",
    "anvit.eval.topK",
    "anvit.eval.temperature"
)

val localProps = Properties().also { p ->
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { p.load(it) }
}

android {
    namespace  = "com.anvit.localai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.likhith.anvit"
        minSdk        = 27
        targetSdk     = 35
        versionCode   = 13
        versionName   = "1.0.12"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (connectedDeviceEvalRequested) {
            testInstrumentationRunnerArguments["class"] = deviceEvalInstrumentationClass
        }
        deviceEvalInstrumentationArgs.forEach { key ->
            val value = providers.gradleProperty(key).orNull ?: providers.systemProperty(key).orNull
            if (!value.isNullOrBlank()) {
                testInstrumentationRunnerArguments[key] = value
            }
        }
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile     = localProps.getProperty("signing.storeFile")?.let { rootProject.file(it) }
            storePassword = localProps.getProperty("signing.storePassword")
            keyAlias      = localProps.getProperty("signing.keyAlias")
            keyPassword   = localProps.getProperty("signing.keyPassword")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose     = true
        buildConfig = true
    }

    installation {
        enableBaselineProfile = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/*.kotlin_module"
            excludes += "google/protobuf/*.proto"
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf("**/libmediapipe_tasks_text_jni.so")
        }
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDirs(
                rootProject.file("eval/datasets/v1"),
                rootProject.file("Test Docs")
            )
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

configurations.all {
    resolutionStrategy {
        force("org.bouncycastle:bcprov-jdk15to18:1.72")
        force("org.bouncycastle:bcpkix-jdk15to18:1.72")
    }
    if (!name.contains("AndroidTest", ignoreCase = true)) {
        exclude(group = "com.google.protobuf", module = "protobuf-javalite")
    }
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    exclude(group = "org.bouncycastle", module = "bcpkix-jdk15on")
    exclude(group = "org.bouncycastle", module = "bcutil-jdk15on")
}

dependencies {
    // Shared KMP module — carries all business logic, UI, and platform actuals
    implementation(project(":composeApp"))

    // Android launcher essentials
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.android)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(project(":composeApp"))
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.kotlinx.datetime)
    androidTestImplementation(libs.kotlinx.serialization.json)
    androidTestRuntimeOnly("com.google.protobuf:protobuf-javalite:4.26.1")
}

// ---------------------------------------------------------------------------
// Device eval model backup / restore
//
// When connectedDebugAndroidTest reinstalls the app (e.g. because the device
// has the Play Store version signed with Google's distribution key), internal
// storage — including downloaded model files — is wiped. These two tasks
// pull model files to the host before the test run and push them back after,
// so you don't need to re-download multi-GB models on every eval run.
//
// One-time setup: run `./gradlew :app:installDebug` once, open the app and
// download models. After that the debug→debug upgrade keeps data intact and
// the backup/restore only activates if the app is uninstalled mid-run.
// ---------------------------------------------------------------------------

// Hardcoded to avoid the deprecated Project.android accessor.
val evalPackageName = "com.likhith.anvit"
val localModelBackup = rootProject.layout.buildDirectory.dir("eval-model-backup/models").get().asFile
val adbExecutable = providers.provider {
    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use { localProperties.load(it) }
    }
    val sdkDir = localProperties.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
    sdkDir?.let { File(it, "platform-tools/adb").absolutePath } ?: "adb"
}

tasks.register("backupEvalModels") {
    group = "verification"
    description = "Pulls model files from the device before the eval reinstall."
    doLast {
        localModelBackup.parentFile.deleteRecursively()
        localModelBackup.mkdirs()
        logger.lifecycle("[backupEvalModels] Pulling model files from $evalPackageName...")
        val listProcess = ProcessBuilder(
            adbExecutable.get(),
            "shell",
            "run-as",
            evalPackageName,
            "ls",
            "-1",
            "files/models"
        ).redirectErrorStream(true).start()
        val fileNames = listProcess.inputStream.bufferedReader().readText()
            .lineSequence()
            .map { it.trim() }
            .filter { it.endsWith(".litertlm") || it.endsWith(".tflite") }
            .filter { deviceEvalGenerateAnswersRequested.get() || !it.endsWith(".litertlm") }
            .toList()
        val listExit = listProcess.waitFor()
        if (listExit != 0 || fileNames.isEmpty()) {
            logger.lifecycle("[backupEvalModels] No model files found (exit=$listExit) — restore will be skipped.")
            return@doLast
        }

        var copied = 0
        fileNames.forEach { fileName ->
            val target = File(localModelBackup, fileName)
            logger.lifecycle("[backupEvalModels]  $fileName")
            val copyProcess = ProcessBuilder(
                adbExecutable.get(),
                "exec-out",
                "run-as",
                evalPackageName,
                "cat",
                "files/models/$fileName"
            ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            target.outputStream().use { output ->
                copyProcess.inputStream.copyTo(output)
            }
            val copyExit = copyProcess.waitFor()
            if (copyExit == 0 && target.length() > 0) {
                copied++
            } else {
                target.delete()
                logger.lifecycle("[backupEvalModels]  skipped $fileName (exit=$copyExit)")
            }
        }
        logger.lifecycle("[backupEvalModels] Pulled $copied model file(s) to ${localModelBackup.absolutePath}.")
    }
}

tasks.register("restoreEvalModels") {
    group = "verification"
    description = "Pushes backed-up model files back to the device after the eval test run."
    doLast {
        val modelFiles = localModelBackup.listFiles()?.filter { it.isFile }.orEmpty()
        if (modelFiles.isEmpty()) {
            logger.lifecycle("[restoreEvalModels] Nothing to restore.")
            return@doLast
        }
        logger.lifecycle("[restoreEvalModels] Restoring ${modelFiles.size} model file(s) to device...")

        // Re-install debug APK so the app is present in case it was uninstalled.
        val apkFile = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        if (apkFile.exists()) {
            providers.exec {
                commandLine(adbExecutable.get(), "install", "-r", "-t", "-g", apkFile.absolutePath)
                isIgnoreExitValue = true
            }.result.get()
        }

        providers.exec {
            commandLine(adbExecutable.get(), "shell", "run-as", evalPackageName, "mkdir", "-p", "files/models")
            isIgnoreExitValue = true
        }.result.get()
        modelFiles.forEach { file ->
            // adb push writes to /data/local/tmp (shell-writable); run-as then copies to internal storage.
            val tmp = "/data/local/tmp/${file.name}"
            logger.lifecycle("[restoreEvalModels]  ${file.name} (${file.length() / 1_048_576} MB)")
            providers.exec {
                commandLine(adbExecutable.get(), "push", file.absolutePath, tmp)
                isIgnoreExitValue = true
            }.result.get()
            providers.exec {
                commandLine(adbExecutable.get(), "shell", "run-as", evalPackageName, "cp", tmp, "files/models/${file.name}")
                isIgnoreExitValue = true
            }.result.get()
            providers.exec {
                commandLine(adbExecutable.get(), "shell", "rm", "-f", tmp)
                isIgnoreExitValue = true
            }.result.get()
        }
        logger.lifecycle("[restoreEvalModels] Done — models are back in app internal storage.")
    }
}

// ---------------------------------------------------------------------------
// Pre-eval device setup: install the debug APK and push backed-up model files
// to the device BEFORE connectedDebugAndroidTest runs.
//
// This solves the core problem: connectedDebugAndroidTest does its own
// `adb install -r` which preserves app data only when the installed APK was
// signed with the same key. If the device had the Play Store version (signed
// with Google's key), Android wipes internal storage on reinstall, taking the
// embedding model with it. setupDeviceForEval runs first to ensure the app is
// installed with the correct debug key and models are in place before the test
// runner's install step (which then does a same-key `-r` that preserves data).
// ---------------------------------------------------------------------------

tasks.register("setupDeviceForEval") {
    group = "verification"
    description = "Installs the debug APK and pushes backed-up model files to device before the eval test run."
    dependsOn("packageDebug")
    doLast {
        val apkFile = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        if (apkFile.exists()) {
            logger.lifecycle("[setupDeviceForEval] Installing debug APK (ensures correct signing key)...")
            providers.exec {
                commandLine(adbExecutable.get(), "install", "-r", "-t", "-g", apkFile.absolutePath)
                isIgnoreExitValue = true
            }.result.get()
            // Force-stop any running instance so the test runner starts with a clean process.
            providers.exec {
                commandLine(adbExecutable.get(), "shell", "am", "force-stop", evalPackageName)
                isIgnoreExitValue = true
            }.result.get()
        }
        val modelFiles = localModelBackup.listFiles()?.filter { it.isFile }.orEmpty()
        if (modelFiles.isEmpty()) {
            logger.lifecycle("[setupDeviceForEval] No backed-up model files found in ${localModelBackup.absolutePath} — skipping model push.")
            logger.lifecycle("[setupDeviceForEval] If the embedding model is missing, open the app and download it from Settings first,")
            logger.lifecycle("[setupDeviceForEval] then run ./gradlew :app:backupEvalModels to create a local backup.")
            return@doLast
        }
        logger.lifecycle("[setupDeviceForEval] Pushing ${modelFiles.size} model file(s) to device...")
        providers.exec {
            commandLine(adbExecutable.get(), "shell", "run-as", evalPackageName, "mkdir", "-p", "files/models")
            isIgnoreExitValue = true
        }.result.get()
        modelFiles.forEach { file ->
            val tmp = "/data/local/tmp/${file.name}"
            logger.lifecycle("[setupDeviceForEval]  ${file.name} (${file.length() / 1_048_576} MB)")
            providers.exec {
                commandLine(adbExecutable.get(), "push", file.absolutePath, tmp)
                isIgnoreExitValue = true
            }.result.get()
            providers.exec {
                commandLine(adbExecutable.get(), "shell", "run-as", evalPackageName, "cp", tmp, "files/models/${file.name}")
                isIgnoreExitValue = true
            }.result.get()
            providers.exec {
                commandLine(adbExecutable.get(), "shell", "rm", "-f", tmp)
                isIgnoreExitValue = true
            }.result.get()
        }
        logger.lifecycle("[setupDeviceForEval] Done — models pushed to app internal storage.")
    }
}

// Always wire setup (before) and pull+restore (after) for connectedDeviceEval runs.
// Order: setupDeviceForEval → connectedDebugAndroidTest → pullDeviceEvalArtifacts → restoreEvalModels
// restoreEvalModels reinstalls the APK (which wipes app data), so pull must happen first.
if (connectedDeviceEvalRequested) {
    tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
        mustRunAfter("setupDeviceForEval", "startDeviceEvalLogcatCapture")
        // Pull artifacts right after the test, before restore can wipe them.
        finalizedBy("pullDeviceEvalArtifacts")
        if (deviceEvalBackupModelsRequested.get()) {
            mustRunAfter("backupEvalModels")
        }
    }
    tasks.matching { it.name == "pullDeviceEvalArtifacts" }.configureEach {
        // Restore runs after pull to put models back (APK reinstall wipes device data).
        finalizedBy("restoreEvalModels")
    }
}

// ---------------------------------------------------------------------------
// Post-eval artifact pull: copy device_eval_run.json and siblings from the
// app's internal storage (files/device-eval/<runId>/) to the host machine.
//
// Uses `adb exec-out run-as <pkg> cat` to stream files out of internal storage
// without needing root or MANAGE_EXTERNAL_STORAGE.
//
// Output lands in eval/reports/device-pulled/ on the host so DeviceEvalScoringSuite
// can score it with -Panvit.eval.deviceArtifacts=<path>.
// ---------------------------------------------------------------------------

val deviceEvalPullDir = rootProject.layout.buildDirectory.dir("device-eval-pull").get().asFile
val deviceEvalLogcatFile = rootProject.layout.buildDirectory.file("device-eval-logcat.txt").get().asFile

tasks.register("startDeviceEvalLogcatCapture") {
    group = "verification"
    description = "Starts an adb logcat tail capturing DeviceEvalArtifact chunks during the test run."
    doLast {
        val adb = adbExecutable.get()
        // Clear logcat buffer so we only capture this run's output, then spawn a detached
        // logcat tail that writes to a file. Killed by stopDeviceEvalLogcatCapture afterwards.
        ProcessBuilder(adb, "logcat", "-c").start().waitFor()
        deviceEvalLogcatFile.parentFile.mkdirs()
        deviceEvalLogcatFile.delete()
        val pidFile = File(deviceEvalLogcatFile.parentFile, "device-eval-logcat.pid")
        // Use sh -c to redirect output and capture the pid of the background process.
        ProcessBuilder(
            "sh", "-c",
            "$adb logcat -v raw -s DeviceEvalArtifact:I > '${deviceEvalLogcatFile.absolutePath}' 2>&1 & echo \$! > '${pidFile.absolutePath}'"
        ).start().waitFor()
        logger.lifecycle("[startDeviceEvalLogcatCapture] Capturing → ${deviceEvalLogcatFile.absolutePath}")
    }
}

tasks.register("pullDeviceEvalArtifacts") {
    group = "verification"
    description = "Pulls device_eval_run.json and siblings from the device's app internal storage."
    mustRunAfter("connectedDebugAndroidTest")
    doLast {
        val adb = adbExecutable.get()
        // On Android, Context.filesDir resolves to /data/user/0/<pkg>/files (not /data/data/)
        val appDataDir = "/data/user/0/$evalPackageName"

        val pkgCheck = ProcessBuilder(adb, "shell", "pm", "list", "packages", evalPackageName)
            .redirectErrorStream(true).start()
        val pkgInstalled = pkgCheck.inputStream.bufferedReader().readText().contains(evalPackageName)
        pkgCheck.waitFor()

        val jsonPaths = if (pkgInstalled) {
            val listOutput = ProcessBuilder(
                adb, "exec-out", "run-as", evalPackageName,
                "find", "$appDataDir/files/device-eval", "-maxdepth", "2", "-name", "*.json"
            ).redirectErrorStream(true).start()
            val paths = listOutput.inputStream.bufferedReader().readText()
                .lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && it.startsWith("/") }.toList()
            listOutput.waitFor()
            paths
        } else {
            logger.lifecycle("[pullDeviceEvalArtifacts] App was uninstalled by test runner — falling back to logcat reconstruction.")
            emptyList()
        }

        if (jsonPaths.isEmpty()) {
            // Fallback: reconstruct device_eval_run.json from base64-chunked logcat output
            // captured during the test run by startDeviceEvalLogcatCapture.
            val pidFile = File(deviceEvalLogcatFile.parentFile, "device-eval-logcat.pid")
            if (pidFile.isFile) {
                runCatching {
                    ProcessBuilder("sh", "-c", "kill $(cat '${pidFile.absolutePath}') 2>/dev/null").start().waitFor()
                }
                pidFile.delete()
            }
            if (!deviceEvalLogcatFile.isFile) {
                logger.lifecycle("[pullDeviceEvalArtifacts] No artifacts in app storage and no captured logcat at ${deviceEvalLogcatFile.absolutePath}.")
                return@doLast
            }
            val chunkPattern = Regex("""^CHUNK (\S+) (\d+) (\S+)\s*$""")
            val chunks = mutableMapOf<String, MutableMap<Int, String>>()
            deviceEvalLogcatFile.forEachLine { line ->
                val m = chunkPattern.matchEntire(line.trim()) ?: return@forEachLine
                val (runId, idx, data) = m.destructured
                chunks.getOrPut(runId) { mutableMapOf() }[idx.toInt()] = data
            }
            val latestRun = chunks.keys.maxOrNull()
            if (latestRun == null) {
                logger.lifecycle("[pullDeviceEvalArtifacts] Captured logcat at ${deviceEvalLogcatFile.absolutePath} contains no DeviceEvalArtifact CHUNK lines.")
                return@doLast
            }
            val sortedChunks = chunks.getValue(latestRun).toSortedMap()
            val b64 = sortedChunks.values.joinToString("")
            val decoded = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
            val pullTarget = File(deviceEvalPullDir, latestRun).also { it.mkdirs() }
            File(pullTarget, "device_eval_run.json").writeText(decoded)
            logger.lifecycle("[pullDeviceEvalArtifacts] Reconstructed device_eval_run.json from logcat (${sortedChunks.size} chunks, ${decoded.length} bytes) → ${pullTarget.absolutePath}")
            logger.lifecycle("[pullDeviceEvalArtifacts] To score: ./gradlew :app:scoreDeviceEval -Panvit.eval.deviceArtifacts=${pullTarget.absolutePath}")
            return@doLast
        }

        // Determine latest run dir (runId = "device-<timestamp>" → lexicographic max = most recent)
        val deviceEvalDir = "$appDataDir/files/device-eval"
        val runDirs = jsonPaths.map { it.removePrefix("$deviceEvalDir/").substringBefore("/") }.distinct()
        val latestRun = runDirs.maxOrNull() ?: return@doLast
        val pullTarget = File(deviceEvalPullDir, latestRun).also { it.mkdirs() }

        logger.lifecycle("[pullDeviceEvalArtifacts] Pulling run: $latestRun → ${pullTarget.absolutePath}")
        jsonPaths.filter { it.contains("/$latestRun/") }.forEach { remotePath ->
            val fileName = remotePath.substringAfterLast("/")
            val localFile = File(pullTarget, fileName)
            val catProcess = ProcessBuilder(
                adb, "exec-out", "run-as", evalPackageName, "cat", remotePath
            ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            localFile.outputStream().use { catProcess.inputStream.copyTo(it) }
            val exit = catProcess.waitFor()
            if (exit == 0 && localFile.length() > 0) {
                logger.lifecycle("[pullDeviceEvalArtifacts]  $fileName (${localFile.length()} bytes)")
            } else {
                localFile.delete()
                logger.lifecycle("[pullDeviceEvalArtifacts]  skipped $fileName (exit=$exit)")
            }
        }
        logger.lifecycle("[pullDeviceEvalArtifacts] Done. To score: ./gradlew :app:scoreDeviceEval -Panvit.eval.deviceArtifacts=${pullTarget.absolutePath}")
    }
}

tasks.register("connectedDeviceEval") {
    group = "verification"
    description = "Runs the device-local Agentic RAG eval on a connected Android device."
    if (deviceEvalBackupModelsRequested.get()) {
        dependsOn("backupEvalModels", "setupDeviceForEval", "startDeviceEvalLogcatCapture", "connectedDebugAndroidTest", "pullDeviceEvalArtifacts")
    } else {
        dependsOn("setupDeviceForEval", "startDeviceEvalLogcatCapture", "connectedDebugAndroidTest", "pullDeviceEvalArtifacts")
    }
}
