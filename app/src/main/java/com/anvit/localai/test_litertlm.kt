package com.anvit.localai

import com.google.ai.edge.litertlm.SamplerConfig

fun testSamplerConfig() {
    val cfg = SamplerConfig(topK = 40, topP = 0.9, temperature = 1.0)
}
