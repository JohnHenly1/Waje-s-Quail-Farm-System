package com.example.exp1

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Firebase
import com.google.firebase.ai.Chat
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChatMessage(val text: String, val isUser: Boolean)
data class AiModelOption(val id: String, val label: String)
data class SavedConversation(
    val id: String,
    var title: String,
    var timestamp: Long,
    var modelId: String,
    var messages: MutableList<ChatMessage>
)

class ChatBotActivity : AppCompatActivity() {

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var prefs: android.content.SharedPreferences

    private var currentConversationId: String = UUID.randomUUID().toString()

    private val availableModels = listOf(
        AiModelOption("gemini-3.5-flash", "3.5 Flash"),
        AiModelOption("gemini-3-flash", "3 Flash"),
        AiModelOption("gemini-3.1-flash-lite", "3.1 Flash-Lite"),
        AiModelOption("gemini-2.5-flash", "2.5 Flash (legacy)")
    )
    private var currentModelId = availableModels.first().id

    private val samplePool = listOf(
        "How long is the incubation period for quail eggs?",
        "What's the ideal temperature for hatching quail eggs?",
        "How many eggs does a quail lay per week?",
        "What should I feed laying quail for better egg production?",
        "How do I tell if a quail egg is fertile?",
        "What's a healthy humidity level during incubation?",
        "How much space does a quail need in its housing?",
        "What are common signs of disease in quail?",
        "How long does it take quail to start laying eggs?",
        "What's the best way to store quail eggs before hatching?",
        "How can I improve my hatch rate?",
        "What's a normal quail egg weight?"
    )

    private val systemPrompt = """
        You are Quail Assistant, a friendly and knowledgeable expert on quail 
        (specifically Coturnix quail) farming and egg production. You help farm 
        staff with questions about incubation, egg-laying cycles, feed, housing, 
        temperature/humidity requirements, common diseases, hatch rates, and 
        general quail husbandry. Keep answers practical, concise, and easy to 
        read on a mobile screen. If a question is unrelated to quail/poultry 
        farming, politely redirect the conversation back to quail farming topics.
    """.trimIndent()

    private lateinit var generativeModel: GenerativeModel
    private lateinit var chatSession: Chat

    private lateinit var typingLayout: View
    private lateinit var suggestedContainer: GridLayout
    private lateinit var modelSelectorText: TextView
    private lateinit var conversationTitleText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_bot)

        prefs = getSharedPreferences("quail_chat_prefs", Context.MODE_PRIVATE)

        window.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        // Same edge-to-edge inset handling as the dashboard
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                maxOf(systemBars.bottom, ime.bottom)
            )
            insets
        }

        recyclerView = findViewById(R.id.chatRecyclerView)
        adapter = ChatAdapter(messages) { position ->
            showDeleteMessageDialog(position)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        typingLayout = findViewById(R.id.typingIndicatorLayout)
        suggestedContainer = findViewById(R.id.suggestedQuestionsContainer)
        modelSelectorText = findViewById(R.id.modelSelectorText)
        conversationTitleText = findViewById(R.id.conversationTitleText)

        val input = findViewById<EditText>(R.id.chatInput)
        val sendBtn = findViewById<ImageButton>(R.id.sendButton)
        val backBtn = findViewById<ImageButton>(R.id.backButton)
        val modelSelectorButton = findViewById<View>(R.id.modelSelectorButton)
        val newChatButton = findViewById<ImageButton>(R.id.newChatButton)
        val historyButton = findViewById<ImageButton>(R.id.historyButton)

        backBtn.setOnClickListener { finish() }

        // Resume the most recently active conversation, or start fresh
        val lastId = prefs.getString("current_conversation_id", null)
        val allConvos = loadAllConversations()
        val resumeConvo = allConvos.find { it.id == lastId }

        if (resumeConvo != null && resumeConvo.messages.isNotEmpty()) {
            loadConversation(resumeConvo, showGreeting = false)
        } else {
            startNewConversation(persistImmediately = false)
        }

        modelSelectorButton.setOnClickListener { showModelPicker(it) }
        newChatButton.setOnClickListener { startNewConversation(persistImmediately = false) }
        historyButton.setOnClickListener { showHistoryPopup(it) }

        fun sendCurrentInput() {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return

            hideSuggestedQuestions()
            addMessage(text, isUser = true)
            input.text.clear()
            typingLayout.visibility = View.VISIBLE
            recyclerView.scrollToPosition(messages.size - 1)

            lifecycleScope.launch {
                try {
                    val response = chatSession.sendMessage(text)
                    val reply = response.text ?: "Sorry, I couldn't come up with an answer for that."
                    addMessage(reply, isUser = false)
                } catch (e: Exception) {
                    addMessage("Oops, something went wrong: ${e.localizedMessage}", isUser = false)
                } finally {
                    typingLayout.visibility = View.GONE
                }
            }
        }

        sendBtn.setOnClickListener { sendCurrentInput() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentInput()
                true
            } else {
                false
            }
        }
    }

    // ---------- Model handling ----------

    private fun labelFor(modelId: String) =
        availableModels.find { it.id == modelId }?.label ?: modelId

    private fun startSession(history: List<ChatMessage>) {
        generativeModel = Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel(
                modelName = currentModelId,
                systemInstruction = content { text(systemPrompt) }
            )
        val historyContent: List<Content> = history.map { msg ->
            content(role = if (msg.isUser) "user" else "model") { text(msg.text) }
        }
        chatSession = generativeModel.startChat(history = historyContent)
    }

    private fun showModelPicker(anchor: View) {
        val popup = PopupMenu(this, anchor)
        availableModels.forEachIndexed { index, model ->
            popup.menu.add(0, index, index, model.label)
        }
        popup.setOnMenuItemClickListener { item ->
            val selected = availableModels[item.itemId]
            if (selected.id != currentModelId) {
                currentModelId = selected.id
                modelSelectorText.text = selected.label
                startSession(messages)
                persistCurrentConversation()
            }
            true
        }
        popup.show()
    }

    // ---------- Conversation lifecycle ----------

    private fun startNewConversation(persistImmediately: Boolean) {
        currentConversationId = UUID.randomUUID().toString()
        currentModelId = availableModels.first().id
        modelSelectorText.text = labelFor(currentModelId)
        conversationTitleText.text = "Quail Assistant"

        messages.clear()
        adapter.notifyDataSetChanged()

        startSession(emptyList())
        addMessage(
            "Hi! I'm your Quail Assistant 🐣 Ask me anything about your quail eggs or flock.",
            isUser = false,
            persist = false
        )
        showSuggestedQuestions()

        prefs.edit().putString("current_conversation_id", currentConversationId).apply()
        if (persistImmediately) persistCurrentConversation()
    }

    private fun loadConversation(convo: SavedConversation, showGreeting: Boolean) {
        currentConversationId = convo.id
        currentModelId = convo.modelId
        modelSelectorText.text = labelFor(currentModelId)
        conversationTitleText.text = convo.title.ifBlank { "Quail Assistant" }

        messages.clear()
        messages.addAll(convo.messages)
        adapter.notifyDataSetChanged()
        recyclerView.scrollToPosition(maxOf(0, messages.size - 1))

        startSession(messages)
        hideSuggestedQuestions()

        if (showGreeting && messages.isEmpty()) {
            addMessage(
                "Hi! I'm your Quail Assistant 🐣 Ask me anything about your quail eggs or flock.",
                isUser = false,
                persist = false
            )
            showSuggestedQuestions()
        }

        prefs.edit().putString("current_conversation_id", currentConversationId).apply()
    }

    // ---------- History popup ----------

    private fun showHistoryPopup(anchor: View) {
        val convos = loadAllConversations()
            .filter { it.messages.isNotEmpty() }
            .sortedByDescending { it.timestamp }
            .toMutableList()

        val popupView = LayoutInflater.from(this)
            .inflate(R.layout.popup_conversation_history, null)
        val list = popupView.findViewById<RecyclerView>(R.id.historyRecyclerView)
        val emptyText = popupView.findViewById<TextView>(R.id.historyEmptyText)

        emptyText.visibility = if (convos.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (convos.isEmpty()) View.GONE else View.VISIBLE

        val popup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 16f

        lateinit var historyAdapter: ConversationHistoryAdapter
        historyAdapter = ConversationHistoryAdapter(
            conversations = convos,
            onOpen = { convo ->
                loadConversation(convo, showGreeting = false)
                popup.dismiss()
            },
            onDelete = { convo ->
                AlertDialog.Builder(this)
                    .setTitle("Delete conversation?")
                    .setMessage("This can't be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        deleteConversation(convo.id)
                        convos.remove(convo)
                        historyAdapter.notifyDataSetChanged()
                        emptyText.visibility = if (convos.isEmpty()) View.VISIBLE else View.GONE
                        list.visibility = if (convos.isEmpty()) View.GONE else View.VISIBLE
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = historyAdapter

        popup.showAsDropDown(anchor, 0, 0)
    }

    // ---------- Sample question chips ----------

    private fun showSuggestedQuestions() {
        suggestedContainer.removeAllViews()
        suggestedContainer.columnCount = 2
        val picks = samplePool.shuffled().take(4)

        picks.forEachIndexed { index, question ->
            val chip = TextView(this).apply {
                text = question
                textSize = 12f
                setTextColor(0xFF374151.toInt())
                background = androidx.core.content.ContextCompat.getDrawable(
                    context, R.drawable.bg_suggestion_chip
                )
                setPadding(24, 20, 24, 20)
                maxLines = 3
                gravity = Gravity.CENTER
                setOnClickListener {
                    hideSuggestedQuestions()
                    sendSuggested(question)
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(index % 2, 1f)
                rowSpec = GridLayout.spec(index / 2)
                setMargins(6, 6, 6, 6)
            }
            suggestedContainer.addView(chip, params)
        }
        suggestedContainer.visibility = View.VISIBLE
    }

    private fun hideSuggestedQuestions() {
        suggestedContainer.visibility = View.GONE
    }

    private fun sendSuggested(question: String) {
        addMessage(question, isUser = true)
        typingLayout.visibility = View.VISIBLE
        recyclerView.scrollToPosition(messages.size - 1)

        lifecycleScope.launch {
            try {
                val response = chatSession.sendMessage(question)
                val reply = response.text ?: "Sorry, I couldn't come up with an answer for that."
                addMessage(reply, isUser = false)
            } catch (e: Exception) {
                addMessage("Oops, something went wrong: ${e.localizedMessage}", isUser = false)
            } finally {
                typingLayout.visibility = View.GONE
            }
        }
    }

    // ---------- Messages + persistence ----------

    private fun addMessage(text: String, isUser: Boolean, persist: Boolean = true) {
        messages.add(ChatMessage(text, isUser))
        adapter.notifyItemInserted(messages.size - 1)
        recyclerView.scrollToPosition(messages.size - 1)

        // First real user message becomes the conversation's title
        if (isUser && conversationTitleText.text == "Quail Assistant") {
            conversationTitleText.text = text.take(40)
        }

        if (persist) persistCurrentConversation()
    }

    private fun persistCurrentConversation() {
        val title = conversationTitleText.text.toString()
            .takeIf { it != "Quail Assistant" } ?: ""

        val convo = SavedConversation(
            id = currentConversationId,
            title = title,
            timestamp = System.currentTimeMillis(),
            modelId = currentModelId,
            messages = messages.toMutableList()
        )

        val all = loadAllConversations().toMutableList()
        val existingIndex = all.indexOfFirst { it.id == currentConversationId }
        if (existingIndex >= 0) all[existingIndex] = convo else all.add(0, convo)

        saveAllConversations(all)
    }

    private fun deleteConversation(id: String) {
        val all = loadAllConversations().toMutableList()
        all.removeAll { it.id == id }
        saveAllConversations(all)

        if (id == currentConversationId) {
            startNewConversation(persistImmediately = false)
        }
    }

    private fun saveAllConversations(list: List<SavedConversation>) {
        val arr = JSONArray()
        for (convo in list) {
            val msgArr = JSONArray()
            for (m in convo.messages) {
                msgArr.put(JSONObject().apply {
                    put("text", m.text)
                    put("isUser", m.isUser)
                })
            }
            arr.put(JSONObject().apply {
                put("id", convo.id)
                put("title", convo.title)
                put("timestamp", convo.timestamp)
                put("modelId", convo.modelId)
                put("messages", msgArr)
            })
        }
        prefs.edit().putString("conversations_list", arr.toString()).apply()
    }

    private fun showDeleteMessageDialog(position: Int) {
        if (position < 0 || position >= messages.size) return

        AlertDialog.Builder(this)
            .setTitle("Delete message?")
            .setMessage("This will remove it from the conversation.")
            .setPositiveButton("Delete") { _, _ ->
                messages.removeAt(position)
                adapter.notifyItemRemoved(position)

                // Rebuild the model's memory without the deleted message,
                // so it doesn't reference something no longer shown
                startSession(messages)

                persistCurrentConversation()

                // If that was the last message, the conversation is now empty —
                // bring back the greeting + suggestion chips
                if (messages.isEmpty()) {
                    addMessage(
                        "Hi! I'm your Quail Assistant 🐣 Ask me anything about your quail eggs or flock.",
                        isUser = false,
                        persist = false
                    )
                    showSuggestedQuestions()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadAllConversations(): List<SavedConversation> {
        val json = prefs.getString("conversations_list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val msgArr = obj.getJSONArray("messages")
                val msgs = (0 until msgArr.length()).map { j ->
                    val m = msgArr.getJSONObject(j)
                    ChatMessage(m.getString("text"), m.getBoolean("isUser"))
                }.toMutableList()
                SavedConversation(
                    id = obj.getString("id"),
                    title = obj.optString("title", ""),
                    timestamp = obj.getLong("timestamp"),
                    modelId = obj.optString("modelId", availableModels.first().id),
                    messages = msgs
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}