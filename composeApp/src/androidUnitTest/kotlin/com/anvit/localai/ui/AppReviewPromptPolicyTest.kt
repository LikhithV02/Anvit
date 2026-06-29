package com.anvit.localai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppReviewPromptPolicyTest {

    @Test
    fun doesNotPromptBeforeFirstEligibleMessageCount() {
        val state = AppReviewPromptState(
            totalMessages = AppReviewPromptPolicy.FIRST_PROMPT_MESSAGE_COUNT - 1,
            lastShownAtMessages = -1L,
            snoozedUntilMessages = 0L,
            permanentlyDismissed = false,
        )

        assertFalse(AppReviewPromptPolicy.shouldPrompt(state))
    }

    @Test
    fun promptsAtFirstEligibleMessageCountWhenNeverShown() {
        val state = AppReviewPromptState(
            totalMessages = AppReviewPromptPolicy.FIRST_PROMPT_MESSAGE_COUNT,
            lastShownAtMessages = -1L,
            snoozedUntilMessages = 0L,
            permanentlyDismissed = false,
        )

        assertTrue(AppReviewPromptPolicy.shouldPrompt(state))
    }

    @Test
    fun promptsAgainAfterMaybeLaterInterval() {
        val state = AppReviewPromptState(
            totalMessages = 11L,
            lastShownAtMessages = 6L,
            snoozedUntilMessages = 0L,
            permanentlyDismissed = false,
        )

        assertTrue(AppReviewPromptPolicy.shouldPrompt(state))
    }

    @Test
    fun doesNotPromptWhileSnoozedAfterNoThanks() {
        val state = AppReviewPromptState(
            totalMessages = 20L,
            lastShownAtMessages = 6L,
            snoozedUntilMessages = 31L,
            permanentlyDismissed = false,
        )

        assertFalse(AppReviewPromptPolicy.shouldPrompt(state))
    }

    @Test
    fun promptsAfterLegacyPermanentDismissalWhenIntervalHasElapsed() {
        val state = AppReviewPromptState(
            totalMessages = 38L,
            lastShownAtMessages = 26L,
            snoozedUntilMessages = 0L,
            permanentlyDismissed = true,
        )

        assertTrue(AppReviewPromptPolicy.shouldPrompt(state))
    }

    @Test
    fun nativeReviewRequestRecordsShownWithoutPermanentDismissal() {
        val state = AppReviewPromptState(
            totalMessages = 6L,
            lastShownAtMessages = -1L,
            snoozedUntilMessages = 0L,
            permanentlyDismissed = false,
        )

        val next = AppReviewPromptPolicy.afterNativeReviewRequested(state)

        assertEquals(6L, next.lastShownAtMessages)
        assertEquals(0L, next.snoozedUntilMessages)
        assertFalse(next.permanentlyDismissed)
        assertFalse(AppReviewPromptPolicy.shouldPrompt(next))
        assertTrue(AppReviewPromptPolicy.shouldPrompt(next.copy(totalMessages = 11L)))
    }
}
