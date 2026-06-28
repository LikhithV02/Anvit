package com.anvit.localai.device

fun acceleratorDisplayName(accelerator: String?): String = when (accelerator?.lowercase()) {
    "gpu" -> "GPU"
    "ane", "npu" -> "Apple Neural Engine"
    else -> "CPU"
}

fun acceleratorRecommendationSuffix(accelerator: String?): String = when (accelerator?.lowercase()) {
    "gpu" -> " · GPU recommended"
    "ane", "npu" -> " · ANE recommended"
    else -> " · CPU recommended"
}
