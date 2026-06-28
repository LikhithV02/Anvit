package com.anvit.localai.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceRecommendationPresentationTest {

    @Test
    fun aneRecommendationUsesAppleNeuralEngineLabel() {
        assertEquals("Apple Neural Engine", acceleratorDisplayName("ane"))
        assertEquals(" · ANE recommended", acceleratorRecommendationSuffix("ane"))
    }
}
