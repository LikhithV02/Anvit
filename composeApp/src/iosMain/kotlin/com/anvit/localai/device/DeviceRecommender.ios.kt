package com.anvit.localai.device

actual fun getDeviceRecommendation(): DeviceRecommendation =
    DeviceRecommendation("gemma4-mlx-e2b", "gpu")
