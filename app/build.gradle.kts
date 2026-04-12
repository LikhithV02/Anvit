plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.sage.localai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sage.localai"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
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
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

configurations.all {
    resolutionStrategy {
        force("com.google.protobuf:protobuf-java:3.25.1")
    }
    exclude(group = "com.google.protobuf", module = "protobuf-javalite")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    implementation(libs.coil.compose)

    // DataStore for settings persistence
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // PDF extraction - iText7 Community
    implementation("com.itextpdf:itext7-core:7.2.5")
    implementation("org.slf4j:slf4j-nop:2.0.9")

    // LiteRT-LM: Gemma 4 inference engine
    // Bumped to 0.10.1 — required for Content.Image vision API (Gemma 4 is vision-capable).
    // If 0.10.1 is not yet available in Maven Central, revert to 0.10.0; the image feature
    // will then use a base64-in-prompt fallback path in GemmaInferenceService.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.0")

    // MediaPipe Tasks (required by LiteRT-LM + embedding SDK)
    implementation("com.google.mediapipe:tasks-genai:0.10.33")
    implementation("com.google.mediapipe:tasks-text:0.10.33")

    // AI Edge RAG SDK for Gecko/EmbeddingGemma embeddings
    implementation("com.google.ai.edge.localagents:localagents-rag:0.3.0")

    // Protobuf (required by MediaPipe)
    implementation("com.google.protobuf:protobuf-java:3.25.1")

    // Markdown rendering
    implementation("com.github.jeziellago:compose-markdown:0.3.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
