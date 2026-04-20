package com.anvit.localai

import android.content.Context

/**
 * App-level singleton context holder.
 * Set once in AnvitApplication.onCreate() before any Koin modules are accessed.
 */
object AnvitContextHolder {
    lateinit var appContext: Context
}
