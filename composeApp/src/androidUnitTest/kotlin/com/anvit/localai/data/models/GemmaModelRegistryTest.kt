package com.anvit.localai.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaModelRegistryTest {

    @Test
    fun iosDefaultsToCactusE2BAndUsesArchiveBundle() {
        val iosModels = GemmaModels.forPlatform(ios = true)

        assertEquals("gemma4-cactus-e2b", GemmaModels.defaultForPlatform(ios = true).id)
        assertTrue(iosModels.any { it.id == "gemma4-cactus-e2b" && it.archiveFileName == "gemma-4-e2b-it-cq4-apple.zip" })
        assertTrue(iosModels.any { it.id == "gemma4-cactus-e4b" && it.archiveFileName == "gemma-4-e4b-it-cq4-apple.zip" })
        assertFalse(iosModels.any { it.id.contains("mlx", ignoreCase = true) })
    }
}
