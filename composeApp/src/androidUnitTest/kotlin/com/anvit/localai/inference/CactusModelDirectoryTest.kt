package com.anvit.localai.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class CactusModelDirectoryTest {

    @Test
    fun resolvesSingleTopLevelDirectoryFromDownloadedArchive() {
        val modelDir = "/models/gemma-4-e2b-it-cq4-apple"
        val nestedManifest = "$modelDir/gemma-4-e2b-it-cq4-apple/components/manifest.json"

        val resolved = resolveCactusModelDirectory(
            modelDirectory = modelDir,
            fileName = "gemma-4-e2b-it-cq4-apple",
            fileExists = { it == nestedManifest }
        )

        assertEquals("$modelDir/gemma-4-e2b-it-cq4-apple", resolved)
    }
}
