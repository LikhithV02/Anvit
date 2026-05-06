package com.anvit.localai.document

import android.content.Context

class AndroidIngestionForegroundController(
    private val context: Context
) : IngestionForegroundController {

    override fun start(initialStatus: String) {
        IngestionForegroundService.start(context, initialStatus)
    }

    override fun update(fraction: Float, status: String) {
        IngestionForegroundService.update(context, fraction, status)
    }

    override fun stop() {
        IngestionForegroundService.stop(context)
    }
}
