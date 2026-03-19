package com.andforce.andclaw

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class AgentDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START_AGENT -> {
                val prompt = intent.getStringExtra(EXTRA_PROMPT)?.trim().orEmpty()
                if (prompt.isBlank()) {
                    Log.w(TAG, "Ignored empty debug prompt")
                    return
                }

                Log.d(TAG, "Starting agent from broadcast: $prompt")
                AgentController.startAgent(prompt)
                Toast.makeText(context, "Andclaw started: $prompt", Toast.LENGTH_SHORT).show()
            }

            ACTION_STOP_AGENT -> {
                Log.d(TAG, "Stopping agent from broadcast")
                AgentController.stopAgent()
                Toast.makeText(context, "Andclaw stopped", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val TAG = "AgentDebugReceiver"
        const val ACTION_START_AGENT = "com.andforce.andclaw.action.START_AGENT"
        const val ACTION_STOP_AGENT = "com.andforce.andclaw.action.STOP_AGENT"
        const val EXTRA_PROMPT = "prompt"
    }
}
