plugins {
    id("com.android.application")
}

android {
    namespace = "com.anvit.experiments.paddleocr"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.anvit.experiments.paddleocr"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.asFile.get().resolve("generated/assets/testDocs"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

val copyTestDocs by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.dir("../../Test Docs"))
    include("*.pdf")
    into(layout.buildDirectory.dir("generated/assets/testDocs"))
}

tasks.named("preBuild") {
    dependsOn(copyTestDocs)
}

dependencies {
    implementation("io.github.hzkitty:rapidocr4j-android:1.0.0")
}
