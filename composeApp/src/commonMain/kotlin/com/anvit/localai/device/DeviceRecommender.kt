package com.anvit.localai.device

data class DeviceRecommendation(val modelId: String, val accelerator: String)

expect fun getDeviceRecommendation(): DeviceRecommendation
