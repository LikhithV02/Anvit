package com.anvit.localai.inference

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IosSimulatorCactusWiringTest {

    @Test
    fun arm64SimulatorUsesCactusInsteadOfLegacyMlxStub() {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .first { File(it, "composeApp/build.gradle.kts").isFile }
        val buildScript = File(root, "composeApp/build.gradle.kts").readText()
        val xcodeProject = File(root, "iosApp/AnvitApp.xcodeproj/project.pbxproj").readText()
        val simulatorService = File(
            root,
            "composeApp/src/iosSimulatorArm64Main/kotlin/com/anvit/localai/inference/IosInferenceService.kt"
        )
        val sharedService = File(
            root,
            "composeApp/src/iosMain/kotlin/com/anvit/localai/inference/IosInferenceService.kt"
        )

        assertTrue(buildScript.contains("Cactus.xcframework/ios-arm64-simulator"))
        assertTrue(xcodeProject.contains("alwaysOutOfDate = 1;"))
        assertTrue(xcodeProject.contains("CactusCurlStubs.c in Sources"))
        assertTrue(xcodeProject.contains("CoreML"))
        assertTrue(sharedService.isFile)
        assertFalse(simulatorService.isFile)
    }
}
