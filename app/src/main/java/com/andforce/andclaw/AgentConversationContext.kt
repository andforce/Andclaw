package com.andforce.andclaw

import com.andforce.andclaw.model.ChatMessage
import com.google.gson.Gson

internal object AgentConversationContext {
    private val gson = Gson()

    fun buildHistory(messages: List<ChatMessage>, sessionStartIndex: Int): List<Map<String, String>> {
        val safeStartIndex = sessionStartIndex.coerceIn(0, messages.size)
        return messages
            .drop(safeStartIndex)
            .takeLast(12)
            .mapNotNull { message ->
                when (message.role) {
                    "user" -> mapOf("role" to "user", "content" to message.content)
                    "ai" -> message.action?.let { action ->
                        mapOf("role" to "assistant", "content" to gson.toJson(action))
                    }
                    "system" -> {
                        val content = message.content
                        val shouldKeep = content.startsWith("Intent failed:") ||
                            content.startsWith("Loop detected") ||
                            content.startsWith("Execution Exception:") ||
                            content.startsWith("Error occurred:") ||
                            content.startsWith("AI Request Failed:") ||
                            (content.startsWith("Action success.") && content.contains("\n"))
                        if (shouldKeep) {
                            mapOf("role" to "user", "content" to "System feedback: $content")
                        } else {
                            null
                        }
                    }
                    else -> null
                }
            }
    }
}
