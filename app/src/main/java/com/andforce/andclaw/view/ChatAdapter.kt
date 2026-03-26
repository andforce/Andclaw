package com.andforce.andclaw.view

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.andforce.andclaw.R
import com.andforce.andclaw.databinding.ItemChatBubbleBinding
import com.andforce.andclaw.model.AiAction
import com.andforce.andclaw.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(
    private val onConfirmAction: (AiAction) -> Unit,
    private val onSelectionChanged: (isSelecting: Boolean, selectedCount: Int) -> Unit
) : ListAdapter<ChatMessage, ChatAdapter.ChatViewHolder>(ChatDiffCallback()) {

    var isSelectionMode = false
        private set
    private val selectedIds = mutableSetOf<Long>()

    fun enterSelectionMode(firstId: Long) {
        isSelectionMode = true
        selectedIds.clear()
        selectedIds.add(firstId)
        notifyDataSetChanged()
        onSelectionChanged(true, selectedIds.size)
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(false, 0)
    }

    fun selectAll() {
        selectedIds.clear()
        for (i in 0 until itemCount) {
            selectedIds.add(getItem(i).id)
        }
        notifyDataSetChanged()
        onSelectionChanged(true, selectedIds.size)
    }

    fun getSelectedIds(): List<Long> = selectedIds.toList()

    private fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) {
            selectedIds.remove(id)
        } else {
            selectedIds.add(id)
        }
        if (selectedIds.isEmpty()) {
            exitSelectionMode()
        } else {
            onSelectionChanged(true, selectedIds.size)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatBubbleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChatViewHolder(
        private val binding: ItemChatBubbleBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(msg: ChatMessage) {
            val isUser = msg.role == "user"
            val isAi = msg.role == "ai"
            val isSystem = msg.role == "system"
            val ctx = binding.root.context

            // 选择模式 CheckBox
            binding.cbSelect.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            binding.cbSelect.isChecked = selectedIds.contains(msg.id)
            binding.cbSelect.setOnClickListener { toggleSelection(msg.id) }

            // 设置气泡位置
            val lp = binding.bubbleContainer.layoutParams as FrameLayout.LayoutParams
            lp.gravity = if (isUser) Gravity.END else Gravity.START
            binding.bubbleContainer.layoutParams = lp

            // 设置气泡背景和边距
            when {
                isUser -> {
                    // 用户气泡 - 渐变蓝色背景
                    binding.bubbleCard.setBackgroundResource(R.drawable.bg_bubble_user)
                    binding.bubbleCard.strokeWidth = 0
                    binding.bubbleCard.radius = ctx.resources.getDimension(R.dimen.bubble_corner_radius)
                    binding.tvContent.setTextColor(ContextCompat.getColor(ctx, R.color.on_bubble_user))
                    // 用户气泡起始边距更大（靠右）
                    binding.bubbleContainer.layoutParams = (binding.bubbleContainer.layoutParams as FrameLayout.LayoutParams).apply {
                        marginStart = ctx.resources.getDimensionPixelSize(R.dimen.spacing_xl)
                        marginEnd = ctx.resources.getDimensionPixelSize(R.dimen.spacing_md)
                    }
                }
                isAi -> {
                    // AI 气泡 - 深灰卡片
                    binding.bubbleCard.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.bubble_ai))
                    binding.bubbleCard.strokeColor = ContextCompat.getColor(ctx, R.color.bubble_ai_border)
                    binding.bubbleCard.strokeWidth = 1
                    binding.bubbleCard.radius = ctx.resources.getDimension(R.dimen.bubble_corner_radius)
                    binding.tvContent.setTextColor(ContextCompat.getColor(ctx, R.color.on_bubble_ai))
                    binding.bubbleContainer.layoutParams = (binding.bubbleContainer.layoutParams as FrameLayout.LayoutParams).apply {
                        marginStart = ctx.resources.getDimensionPixelSize(R.dimen.spacing_md)
                        marginEnd = ctx.resources.getDimensionPixelSize(R.dimen.spacing_xl)
                    }
                }
                else -> {
                    // 系统气泡 - 最深灰色
                    binding.bubbleCard.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.bubble_system))
                    binding.bubbleCard.strokeWidth = 0
                    binding.bubbleCard.radius = ctx.resources.getDimension(R.dimen.radius_lg)
                    binding.tvContent.setTextColor(ContextCompat.getColor(ctx, R.color.on_bubble_system))
                    binding.bubbleContainer.layoutParams = (binding.bubbleContainer.layoutParams as FrameLayout.LayoutParams).apply {
                        marginStart = ctx.resources.getDimensionPixelSize(R.dimen.spacing_md)
                        marginEnd = ctx.resources.getDimensionPixelSize(R.dimen.spacing_md)
                    }
                }
            }

            binding.tvContent.text = msg.content

            // 截图显示
            if (msg.screenshotBase64 != null) {
                binding.ivScreenshot.visibility = View.VISIBLE
                try {
                    val bytes = Base64.decode(msg.screenshotBase64, Base64.NO_WRAP)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    binding.ivScreenshot.setImageBitmap(bmp)
                } catch (_: Exception) {
                    binding.ivScreenshot.visibility = View.GONE
                }
            } else {
                binding.ivScreenshot.visibility = View.GONE
                binding.ivScreenshot.setImageDrawable(null)
            }

            // 操作信息显示
            val action = msg.action
            if (action != null) {
                binding.divider.visibility = View.VISIBLE
                binding.tvActionType.visibility = View.VISIBLE
                binding.tvActionType.text = "执行操作: ${action.type.uppercase()}"

                when (action.type) {
                    "click" -> {
                        binding.tvActionDetail.visibility = View.VISIBLE
                        binding.tvActionDetail.text = "坐标: (${action.x}, ${action.y})"
                        binding.tvActionDetail.setTextColor(
                            ContextCompat.getColor(ctx, R.color.text_secondary)
                        )
                    }
                    else -> {
                        binding.tvActionDetail.visibility = View.GONE
                    }
                }

                if (action.type == "click") {
                    binding.btnExecute.visibility = View.VISIBLE
                    binding.btnExecute.setOnClickListener { onConfirmAction(action) }
                } else {
                    binding.btnExecute.visibility = View.GONE
                }
            } else {
                binding.divider.visibility = View.GONE
                binding.tvActionType.visibility = View.GONE
                binding.tvActionDetail.visibility = View.GONE
                binding.btnExecute.visibility = View.GONE
            }

            // 时间戳
            binding.tvTimestamp.text = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(msg.timestamp))

            // 长按进入选择模式
            itemView.setOnLongClickListener {
                if (!isSelectionMode) {
                    enterSelectionMode(msg.id)
                }
                true
            }
            itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(msg.id)
                    notifyItemChanged(bindingAdapterPosition)
                }
            }
        }
    }

    class ChatDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id && oldItem.timestamp == newItem.timestamp
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}