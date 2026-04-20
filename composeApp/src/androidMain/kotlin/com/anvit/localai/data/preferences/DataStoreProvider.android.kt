package com.anvit.localai.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.anvit.localai.AnvitContextHolder
import okio.Path.Companion.toPath

actual fun createDataStore(): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath {
        val dir = AnvitContextHolder.appContext.filesDir.resolve("datastore")
        dir.mkdirs()
        dir.resolve("anvit.preferences_pb").absolutePath.toPath()
    }
