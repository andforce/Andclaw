package com.andforce.andclaw

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.andforce.andclaw.databinding.ActivityChatHistoryBinding
import com.andforce.andclaw.model.AgentUiState
import com.andforce.andclaw.view.ChatAdapter
import kotlinx.coroutines.launch

class ChatHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatHistoryBinding
    private lateinit var chatAdapter: ChatAdapter
    private var currentUiState = AgentUiState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupChatList()
        setupComposer()
        observeMessages()
        observeUiState()
    }

    private fun setupChatList() {
        chatAdapter = ChatAdapter { action -> AgentController.performConfirmedAction(action) }
        binding.chatRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ChatHistoryActivity)
            adapter = chatAdapter
        }
    }

    private fun setupComposer() {
        binding.btnStartStop.setOnClickListener {
            if (currentUiState.isRunning) {
                AgentController.stopAgent()
            } else {
                submitPrompt()
            }
        }

        binding.etPrompt.doAfterTextChanged {
            updateComposer()
        }

        binding.etPrompt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND && !currentUiState.isRunning) {
                submitPrompt()
                true
            } else {
                false
            }
        }

        updateComposer()
    }

    private fun observeMessages() {
        lifecycleScope.launch {
            AgentController.messages.collect { messageList ->
                chatAdapter.submitList(messageList)
                if (messageList.isNotEmpty()) {
                    binding.chatRecyclerView.smoothScrollToPosition(messageList.size - 1)
                }
            }
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            AgentController.uiStateFlow.collect { state ->
                currentUiState = state
                updateComposer()
            }
        }
    }

    private fun submitPrompt() {
        val prompt = binding.etPrompt.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) return

        binding.etPrompt.setText("")
        AgentController.startAgent(prompt)
    }

    private fun updateComposer() {
        val isRunning = currentUiState.isRunning
        val hasPrompt = !binding.etPrompt.text.isNullOrBlank()

        binding.etPrompt.isEnabled = !isRunning
        binding.inputPromptLayout.isEnabled = !isRunning
        binding.btnStartStop.text = if (isRunning) "停止" else "发送"
        binding.btnStartStop.isEnabled = isRunning || hasPrompt
        binding.tvAgentStatus.text = if (isRunning) {
            "运行中: ${currentUiState.userInput}"
        } else {
            "本地输入任务，直接在手机上启动 Agent"
        }
    }
}
