package com.andforce.andclaw

import com.andforce.andclaw.model.AiAction
import com.andforce.andclaw.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentConversationContextTest {
    @Test
    fun buildHistory_ignoresMessagesFromPreviousSessions() {
        val messages = listOf(
            ChatMessage(role = "user", content = "Open Settings"),
            ChatMessage(role = "ai", content = "Opening", action = AiAction(type = AiAction.TYPE_CLICK, x = 10, y = 20)),
            ChatMessage(role = "system", content = "Finished."),
            ChatMessage(role = "user", content = "Go home and tap Settings")
        )

        val history = AgentConversationContext.buildHistory(messages, sessionStartIndex = 3)

        assertEquals(1, history.size)
        assertEquals("user", history[0]["role"])
        assertEquals("Go home and tap Settings", history[0]["content"])
    }

    @Test
    fun buildHistory_keepsRelevantSystemFeedbackWithinCurrentSession() {
        val messages = listOf(
            ChatMessage(role = "user", content = "Open Settings"),
            ChatMessage(
                role = "ai",
                content = "Trying intent",
                action = AiAction(type = AiAction.TYPE_INTENT, action = "android.settings.SETTINGS")
            ),
            ChatMessage(role = "system", content = "Intent failed: No Activity found")
        )

        val history = AgentConversationContext.buildHistory(messages, sessionStartIndex = 0)

        assertEquals(3, history.size)
        assertEquals("user", history[0]["role"])
        assertEquals("assistant", history[1]["role"])
        assertEquals("user", history[2]["role"])
        assertEquals("System feedback: Intent failed: No Activity found", history[2]["content"])
        assertFalse(history[1]["content"].isNullOrBlank())
    }
}
