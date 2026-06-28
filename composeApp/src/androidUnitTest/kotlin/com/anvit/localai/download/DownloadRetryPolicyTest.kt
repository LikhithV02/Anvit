package com.anvit.localai.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRetryPolicyTest {

    @Test
    fun socketTimeoutIsRetriable() {
        assertTrue(isRetriableDownloadFailure("Socket timeout has expired"))
        assertTrue(isRetriableDownloadFailure("The network connection was lost"))
    }

    @Test
    fun authorizationFailureIsNotRetriable() {
        assertFalse(isRetriableDownloadFailure("HTTP 401: enter a HuggingFace token"))
    }
}
