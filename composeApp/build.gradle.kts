import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    // ── Targets ────────────────────────────────────────────────────────────────
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }
    iosArm64 {
        compilations.all {
            compilerOptions.configure {
                optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
            }
        }
        compilations["main"].cinterops {
            val mlxbridge by creating {
                defFile(project.file("src/nativeInterop/cinterop/mlxbridge.def"))
                packageName("com.anvit.mlxbridge")
            }
        }
        // Export as static framework for the Xcode host to embed
        binaries {
            framework {
                baseName = "ComposeApp"
                isStatic = true
                export(libs.androidx.lifecycle.viewmodel.compose)
                export(libs.androidx.lifecycle.viewmodel)
                export(libs.androidx.lifecycle.runtime.compose)
                export(libs.androidx.lifecycle.runtime)
                export(libs.androidx.savedstate)
                export(libs.androidx.navigation.compose)
            }
        }
    }

    // iosSimulatorArm64 target — MLX symbols are device-only (arm64); inference is a stub
    iosSimulatorArm64 {
        binaries {
            framework {
                baseName = "ComposeApp"
                isStatic = true
                export(libs.androidx.lifecycle.viewmodel.compose)
                export(libs.androidx.lifecycle.viewmodel)
                export(libs.androidx.lifecycle.runtime.compose)
                export(libs.androidx.lifecycle.runtime)
                export(libs.androidx.savedstate)
                export(libs.androidx.navigation.compose)
            }
        }
    }

    // iosX64 simulator target (x86_64, no LiteRT cinterop)
    iosX64 {
        binaries {
            framework {
                baseName = "ComposeApp"
                isStatic = true
                export(libs.androidx.lifecycle.viewmodel.compose)
                export(libs.androidx.lifecycle.viewmodel)
                export(libs.androidx.lifecycle.runtime.compose)
                export(libs.androidx.lifecycle.runtime)
                export(libs.androidx.savedstate)
                export(libs.androidx.navigation.compose)
            }
        }
    }

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.Framework>().configureEach {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    // Apply the default KMP source-set hierarchy (creates iosMain intermediate set)
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate()

    // ── Source sets ────────────────────────────────────────────────────────────
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)

            // Lifecycle / ViewModel KMP
            api(libs.androidx.lifecycle.runtime)
            api(libs.androidx.lifecycle.runtime.compose)
            api(libs.androidx.lifecycle.viewmodel)
            api(libs.androidx.lifecycle.viewmodel.compose)

            // Navigation KMP
            api(libs.androidx.navigation.compose)
            api(libs.androidx.savedstate)

            // Room KMP
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)

            // DataStore KMP
            implementation(libs.androidx.datastore.preferences.core)

            // Coroutines
            implementation(libs.kotlinx.coroutines.core)

            // Serialization
            implementation(libs.kotlinx.serialization.json)

            // Datetime
            implementation(libs.kotlinx.datetime)

            // Koin
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            // Ktor
            implementation(libs.ktor.client.core)

            // Coil 3 (coil-compose-core is the KMP-safe artifact)
            implementation(libs.coil.compose)
        }

        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.koin.android)
            implementation(libs.coil.network.okhttp)
            implementation(libs.ktor.client.okhttp)

            // LiteRT-LM Android inference
            implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.2")
            // MediaPipe for embeddings
            implementation("com.google.mediapipe:tasks-genai:0.10.33")
            implementation("com.google.mediapipe:tasks-text:0.10.33")
            // AI Edge RAG (GeckoEmbedding)
            implementation("com.google.ai.edge.localagents:localagents-rag:0.3.0")
            // Protobuf
            implementation("com.google.protobuf:protobuf-java:3.25.1")
            // iText7 for PDF
            implementation("com.itextpdf:itext7-core:7.2.5")
            implementation("org.slf4j:slf4j-nop:2.0.9")
            // Markdown
            implementation("com.github.jeziellago:compose-markdown:0.3.0")
        }

        iosMain.dependencies {
            // Coil network engine for iOS
            implementation(libs.coil.network.ktor3)
            implementation(libs.ktor.client.darwin)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Supplement CMP plugin's substitution: the kotlin-api metadata configuration for
// iosSimulatorArm64 is not always covered, leaving androidx.compose.* artifacts
// (pulled in transitively by navigation-compose) unresolved for iOS.
// However, official AndroidX KMP libraries should handle this better now.
// If issues persist, consider re-adding specific substitutions.

// ── Room KSP per target ────────────────────────────────────────────────────────
dependencies {
    add("kspAndroid",              libs.androidx.room.compiler)
    add("kspIosArm64",             libs.androidx.room.compiler)
    add("kspIosSimulatorArm64",    libs.androidx.room.compiler)
    add("kspIosX64",               libs.androidx.room.compiler)
}

// ── Android library config ─────────────────────────────────────────────────────
android {
    namespace   = "com.anvit.localai"
    compileSdk  = 35

    defaultConfig {
        minSdk = 27
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/NOTICE*"
            excludes += "google/protobuf/*.proto"
        }
    }
}

// Suppress spurious KMP hierarchy warning
kotlin.sourceSets.all {
    languageSettings.optIn("kotlin.RequiresOptIn")
}
