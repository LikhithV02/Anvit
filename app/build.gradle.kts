import java.io.File
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

android {
    namespace  = "com.anvit.localai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.likhith.anvit"
        minSdk        = 27
        targetSdk     = 35
        versionCode   = 8
        versionName   = "1.0.7"
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
            storeFile = file("release.jks")
            storePassword = "DishanthV#5649"
            keyAlias = "key0"
            keyPassword = "DishanthV#5649"
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

// Optional backup -> test -> restore. The backup streams multi-GB files through
// adb, so keep it opt-in for cases where the test install may wipe app data.
if (connectedDeviceEvalRequested && deviceEvalBackupModelsRequested.get()) {
    tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
        mustRunAfter("backupEvalModels")
        finalizedBy("restoreEvalModels")
    }
}

tasks.register("connectedDeviceEval") {
    group = "verification"
    description = "Runs the device-local Agentic RAG eval on a connected Android device."
    if (deviceEvalBackupModelsRequested.get()) {
        dependsOn("backupEvalModels", "connectedDebugAndroidTest")
    } else {
        dependsOn("connectedDebugAndroidTest")
    }
}
