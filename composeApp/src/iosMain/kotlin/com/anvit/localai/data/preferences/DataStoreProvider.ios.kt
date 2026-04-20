package com.anvit.localai.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path.Companion.toPath
import platform.Foundation.NSFileManager
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
actual fun createDataStore(): DataStore<Preferences> {
    val docsDir = NSFileManager.defaultManager.URLForDirectory(
        directory         = NSDocumentDirectory,
        inDomain          = NSUserDomainMask,
        appropriateForURL = null,
        create            = true,
        error             = null
    )
    val path = (docsDir?.path ?: "") + "/anvit.preferences_pb"
    return PreferenceDataStoreFactory.createWithPath { path.toPath() }
}
