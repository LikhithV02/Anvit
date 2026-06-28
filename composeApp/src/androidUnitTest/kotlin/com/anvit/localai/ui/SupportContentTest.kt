package com.anvit.localai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportContentTest {

    @Test
    fun websiteUsesCanonicalLandingPage() {
        assertEquals("https://www.anvit.app/", AnvitSupportContent.websiteUrl)
        assertTrue(AnvitSupportContent.websiteDescription.contains("learn more", ignoreCase = true))
    }

    @Test
    fun feedbackFormUsesPublicGoogleForm() {
        assertEquals(
            "https://docs.google.com/forms/d/e/1FAIpQLScilJ7-efL--wYYiQnwPDIpkUK27c0hMLtetIrxUHBm7s0XQQ/viewform",
            AnvitSupportContent.feedbackFormUrl
        )
    }

    @Test
    fun supportCopyDoesNotAskForUserInformation() {
        val copy = listOf(
            AnvitSupportContent.feedbackTitle,
            AnvitSupportContent.feedbackDescription,
            AnvitSupportContent.feedbackButtonLabel,
            AnvitSupportContent.feedbackPrivacyNote,
        ).joinToString(" ")

        assertFalse(copy.contains("email", ignoreCase = true))
        assertFalse(copy.contains("contact", ignoreCase = true))
        assertTrue(copy.contains("Google Form"))
    }

    @Test
    fun privacyCopyReflectsNoUserInformationCollection() {
        val copy = AnvitSupportContent.privacyFeedbackCopy

        assertTrue(copy.contains("voluntary", ignoreCase = true))
        assertTrue(copy.contains("Google Form"))
        assertTrue(copy.contains("Anvit does not collect user information"))
        assertFalse(copy.contains("email", ignoreCase = true))
    }
}
