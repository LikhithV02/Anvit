package com.anvit.localai.inference

internal fun resolveCactusModelDirectory(
    modelDirectory: String,
    fileName: String,
    fileExists: (String) -> Boolean
): String {
    val directManifest = "$modelDirectory/components/manifest.json"
    if (fileExists(directManifest)) return modelDirectory

    val nestedDirectory = "$modelDirectory/$fileName"
    val nestedManifest = "$nestedDirectory/components/manifest.json"
    return if (fileExists(nestedManifest)) nestedDirectory else modelDirectory
}
