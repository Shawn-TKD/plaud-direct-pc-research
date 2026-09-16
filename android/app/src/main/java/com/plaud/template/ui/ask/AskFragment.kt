package com.plaud.template.ui.ask

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.plaud.template.R
import com.plaud.template.common.TranscriptText
import com.plaud.template.databinding.FragmentAskBinding
import com.plaud.template.hub.integrations.AiIntegrations
import com.plaud.template.models.RecordingFile
import com.plaud.template.storage.RecordingStore
import com.plaud.template.ui.settings.IntegrationSettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local, source-grounded Q&A across PLAUD, DingTalk A1 and Feishu recordings. */
class AskFragment : Fragment() {
    private var _binding: FragmentAskBinding? = null
    private val binding get() = _binding!!
    private lateinit var integrations: AiIntegrations
    private var asking = false
    private val messages = mutableListOf<ChatMessage>()

    private data class ChatMessage(
        val text: String,
        val fromUser: Boolean,
        val sources: List<RecordingFile> = emptyList(),
        val isError: Boolean = false
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAskBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        integrations = AiIntegrations(requireContext())
        binding.promptSummary.setOnClickListener { submit("总结我最近的录音，并列出最重要的三个主题") }
        binding.promptActions.setOnClickListener { submit("从录音中找出所有待办事项、负责人和截止时间") }
        binding.promptRecall.setOnClickListener { submit("我最近反复提到了哪些人、项目或想法？") }
        binding.sendButton.setOnClickListener { submit(binding.questionInput.text.toString()) }
        binding.questionInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit(binding.questionInput.text.toString())
                true
            } else false
        }
        updateLibraryStatus()
        renderMessages()
        renderBusyState()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) updateLibraryStatus()
    }

    private fun submit(rawQuestion: String) {
        val question = rawQuestion.trim()
        if (question.isBlank() || asking) return
        if (!integrations.isChatReady()) {
            AlertDialog.Builder(requireContext())
                .setTitle("先配置 AI")
                .setMessage("Ask 会读取本机已有的转录，并使用你自己的硅基流动或 DeepSeek API Key 生成带来源的回答。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(requireContext(), IntegrationSettingsActivity::class.java))
                }
                .show()
            return
        }

        messages += ChatMessage(question, fromUser = true)
        binding.questionInput.text?.clear()
        hideKeyboard()
        asking = true
        renderMessages()
        renderBusyState()

        // Fragment scope keeps the answer alive when the user briefly switches tabs. The result is
        // rendered only when the view still exists.
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    integrations.ask(question, RecordingStore.allFiles)
                }
            }
            result.onSuccess {
                messages += ChatMessage(it.answer, fromUser = false, sources = it.sources)
            }.onFailure {
                messages += ChatMessage(
                    it.message ?: "Ask 暂时无法回答，请稍后再试",
                    fromUser = false,
                    isError = true
                )
            }
            asking = false
            if (_binding != null) {
                renderMessages()
                renderBusyState()
                updateLibraryStatus()
            }
        }
    }

    private fun updateLibraryStatus() {
        val total = RecordingStore.allFiles.size
        val searchable = RecordingStore.allFiles.count { TranscriptText.plain(it.transcriptJSON).isNotBlank() }
        binding.libraryStatus.text = "问你的全部录音 · $searchable 条可检索 / $total 条录音"
    }

    private fun renderBusyState() {
        binding.askProgress.visibility = if (asking) View.VISIBLE else View.GONE
        binding.sendButton.visibility = if (asking) View.GONE else View.VISIBLE
        binding.questionInput.isEnabled = !asking
    }

    private fun renderMessages() {
        val container = binding.messagesContainer
        container.removeAllViews()
        if (messages.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "从一次会议里找决定，跨多条录音找线索，或把最近的想法整理成行动项。回答只会引用已完成转录的录音。"
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, R.color.dark_gray))
                setLineSpacing(0f, 1.25f)
                setPadding(dp(2), dp(18), dp(2), 0)
            })
            return
        }
        messages.forEach { addBubble(container, it) }
        if (asking) {
            addBubble(container, ChatMessage("正在检索录音并整理答案…", fromUser = false))
        }
        binding.messagesScroll.post { binding.messagesScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addBubble(container: LinearLayout, message: ChatMessage) {
        val row = LinearLayout(requireContext()).apply {
            gravity = if (message.fromUser) Gravity.END else Gravity.START
            orientation = LinearLayout.VERTICAL
        }
        val bubble = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(
                context,
                if (message.fromUser) R.drawable.bg_ask_user else R.drawable.bg_ask_assistant
            )
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        bubble.addView(TextView(requireContext()).apply {
            text = message.text
            textSize = 15f
            setLineSpacing(0f, 1.18f)
            setTextColor(ContextCompat.getColor(context, when {
                message.fromUser -> R.color.white
                message.isError -> R.color.red
                else -> R.color.text_primary
            }))
            maxWidth = (resources.displayMetrics.widthPixels * 0.84f).toInt()
        })
        if (message.sources.isNotEmpty()) {
            bubble.addView(TextView(requireContext()).apply {
                text = buildString {
                    append("来源\n")
                    message.sources.forEachIndexed { index, file ->
                        append("[${index + 1}] ${file.name}")
                        if (index != message.sources.lastIndex) append('\n')
                    }
                }
                textSize = 12f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setTextColor(ContextCompat.getColor(context, R.color.gray5))
                setPadding(0, dp(12), 0, 0)
                maxWidth = (resources.displayMetrics.widthPixels * 0.84f).toInt()
            })
        }
        row.addView(bubble, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        container.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
    }

    private fun hideKeyboard() {
        val manager = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
        manager?.hideSoftInputFromWindow(binding.questionInput.windowToken, 0)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
