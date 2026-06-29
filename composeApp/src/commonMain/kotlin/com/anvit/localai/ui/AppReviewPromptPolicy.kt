package com.anvit.localai.ui

data class AppReviewPromptState(
    val totalMessages: Long,
    val lastShownAtMessages: Long,
    val snoozedUntilMessages: Long,
    val permanentlyDismissed: Boolean,
)

object AppReviewPromptPolicy {
    const val FIRST_PROMPT_MESSAGE_COUNT = 6L
    const val MAYBE_LATER_MESSAGE_INTERVAL = 5L
    const val NO_THANKS_SNOOZE_MESSAGES = 25L

    fun shouldPrompt(state: AppReviewPromptState): Boolean {
        if (state.totalMessages < FIRST_PROMPT_MESSAGE_COUNT) return false
        if (state.snoozedUntilMessages > state.totalMessages) return false

        val neverShown = state.lastShownAtMessages < 0L
        return neverShown || state.totalMessages - state.lastShownAtMessages >= MAYBE_LATER_MESSAGE_INTERVAL
    }

    fun afterNativeReviewRequested(state: AppReviewPromptState): AppReviewPromptState =
        state.copy(
            lastShownAtMessages = state.totalMessages,
            snoozedUntilMessages = 0L,
            permanentlyDismissed = false,
        )

    fun snoozeUntilAfterNoThanks(totalMessages: Long): Long =
        totalMessages + NO_THANKS_SNOOZE_MESSAGES
}
