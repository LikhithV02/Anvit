package com.anvit.localai.data.reporting

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.request.forms.submitForm
import io.ktor.http.Parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class ReportingService {
    private val client = HttpClient {
        install(HttpRedirect) {
            checkHttpMethod = false
        }
    }

    private val formUrl = "https://docs.google.com/forms/d/e/1FAIpQLScilJ7-efL--wYYiQnwPDIpkUK27c0hMLtetIrxUHBm7s0XQQ/formResponse"

    private val entryIdMessage   = "entry.1219605723"
    private val entryIdReason    = "entry.1638081770"
    private val entryIdQuery     = "entry.275713675"
    private val entryIdResponse  = "entry.1256731295"
    private val entryIdUserEmail = "entry.373507393"

    suspend fun sendReport(
        messageId: String,
        query: String,
        response: String,
        reason: String,
        userEmail: String
    ) {
        withContext(Dispatchers.IO) {
            try {
                client.submitForm(
                    url = formUrl,
                    formParameters = Parameters.build {
                        append(entryIdMessage,   messageId)
                        append(entryIdReason,    reason)
                        append(entryIdQuery,     query)
                        append(entryIdResponse,  response)
                        append(entryIdUserEmail, userEmail)
                    }
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
