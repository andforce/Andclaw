package com.andforce.andclaw

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilsPromptTest {

    @Test
    fun safeModePromptKeepsOnlyBenignActions() {
        val prompt = Utils.buildAgentSystemPrompt(
            userGoal = "打开浏览器查看公开网页并截图",
            isDeviceOwner = true,
            safeMode = true
        )

        assertTrue(prompt.contains("\"intent\""))
        assertTrue(prompt.contains("\"click\""))
        assertTrue(prompt.contains("\"swipe\""))
        assertTrue(prompt.contains("\"text_input\""))
        assertTrue(prompt.contains("\"global_action\""))
        assertTrue(prompt.contains("\"wait\""))
        assertTrue(prompt.contains("\"screenshot\""))
        assertTrue(prompt.contains("\"finish\""))

        assertFalse(prompt.contains("\"camera\""))
        assertFalse(prompt.contains("\"screen_record\""))
        assertFalse(prompt.contains("\"download\""))
        assertFalse(prompt.contains("\"volume\""))
        assertFalse(prompt.contains("\"dpm\""))
        assertFalse(prompt.contains("android.intent.action.DIAL"))
        assertFalse(prompt.contains("android.intent.action.SENDTO"))
        assertTrue(prompt.contains("factory reset"))
    }
}
