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

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val chart: List<ChartSlice>? = null,
    val chartTitle: String? = null
)
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

    // Flash-Lite first = fastest default. Order here also drives the picker menu order.
    private val availableModels = listOf(
        AiModelOption("gemini-3.1-flash-lite", "3.1 Flash-Lite (Fastest)"),
        AiModelOption("gemini-3.5-flash", "3.5 Flash"),
        AiModelOption("gemini-3-flash", "3 Flash"),
        AiModelOption("gemini-2.5-flash", "2.5 Flash (legacy)"),
        AiModelOption("gemini-3.8-flash", "Gemini 3.8 Flash")
    )
    private var currentModelId = availableModels.first().id
    private var lastFarmContext: String = ""
    private var isSending: Boolean = false

    // Cooldown after each AI reply, in seconds. Change this one number to adjust it.
    private val cooldownSeconds: Int = 15
    private var cooldownJob: kotlinx.coroutines.Job? = null


    private val generalQuestions = listOf(
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
        "What's a normal quail egg weight?",
        "How often should I clean the quail cages?",
        "What's the ideal male to female quail ratio?",
        "How long do quail typically lay eggs before production drops?",
        "What causes soft-shelled or thin-shelled quail eggs?",
        "How do I reduce stress in my quail flock?",
        "What lighting schedule is best for egg-laying quail?"
    )

    private val dataQuestions = listOf(
        "What's my laying rate today?",
        "Analyze my egg production this week",
        "Why might my Grade B (cracked) eggs be high?",
        "How does this week compare to last week?",
        "What should I improve based on my egg quality?",
        "Are my cages crowded for my bird count?",
        "What's my Grade A percentage this month?",
        "Is my egg production trending up or down?",
        "How many eggs per cage am I averaging?",
        "What was my best day this month?"
    )

    private val systemPrompt = """
    You are Quail Assistant, a friendly and knowledgeable expert on quail
    (specifically Coturnix quail) farming and quail egg production. You help
    farm staff with questions about incubation, quail egg-laying cycles, feed,
    housing, temperature/humidity requirements, common quail diseases, hatch
    rates, and general quail husbandry. Keep answers practical, concise, and
    easy to read on a mobile screen. If a question is unrelated to quail/quail
    egg farming, politely redirect the conversation back to quail topics.

    You are given live QUAIL FARM DATA (egg collection records) and FLOCK INFO
    (bird count, cages) from this farm's database.
    Egg grades: Grade A = normal/good quail eggs, Grade B = cracked, Grade C = rejected.
    Base answers about this farm's production, quality, or laying rate on the
    provided data and quote the specific numbers. Never invent numbers that
    aren't in the data.
    The total bird count is entered manually by the farm owner and can be out
    of date. If a laying rate looks unusually high or low, or exceeds 100%,
    say the bird count may be inaccurate before concluding anything else about
    the birds' health or the feed.
    If FLOCK INFO is marked not available, don't guess a laying rate — ask the
    user for their current bird count, or answer only about totals and grades.
""".trimIndent()

    private lateinit var generativeModel: GenerativeModel
    private lateinit var chatSession: Chat

    private lateinit var typingLayout: View
    private lateinit var typingStatusText: TextView
    private lateinit var chatInputRef: EditText
    private lateinit var sendButtonRef: ImageButton
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
        typingStatusText = typingLayout.findViewById(R.id.typingStatusText)
        suggestedContainer = findViewById(R.id.suggestedQuestionsContainer)
        modelSelectorText = findViewById(R.id.modelSelectorText)
        conversationTitleText = findViewById(R.id.conversationTitleText)

        val input = findViewById<EditText>(R.id.chatInput)
        val sendBtn = findViewById<ImageButton>(R.id.sendButton)
        chatInputRef = input
        sendButtonRef = sendBtn
        val backBtn = findViewById<ImageButton>(R.id.backButton)
        val modelSelectorButton = findViewById<View>(R.id.modelSelectorButton)
        val newChatButton = findViewById<ImageButton>(R.id.newChatButton)
        val historyButton = findViewById<ImageButton>(R.id.historyButton)

        backBtn.setOnClickListener { finish() }

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
            if (isSending) return
            val text = input.text.toString().trim()
            if (text.isEmpty()) return

            hideSuggestedQuestions()
            addMessage(text, isUser = true)
            input.text.clear()
            recyclerView.scrollToPosition(messages.size - 1)

            lifecycleScope.launch {
                streamReply(text)
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
// ---------- Tap outside to dismiss keyboard ----------

    /**
     * Dispatches every touch through here first. If the chat input has focus
     * and the user taps down somewhere outside its bounds, clear focus and
     * hide the keyboard — but let the touch continue on to its normal target
     * (button clicks, RecyclerView scrolling, etc. still work as usual).
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused is EditText) {
                val location = IntArray(2)
                focused.getLocationOnScreen(location)
                val rect = android.graphics.Rect(
                    location[0], location[1],
                    location[0] + focused.width, location[1] + focused.height
                )
                if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    focused.clearFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                            as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(focused.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---------- Streaming reply ----------

    private suspend fun streamReply(text: String) {
        if (isSending) return
        isSending = true
        cooldownJob?.cancel()              // cancel any leftover cooldown from before
        chatInputRef.isEnabled = false     // lock immediately, not just after reply
        sendButtonRef.isEnabled = false
        typingLayout.visibility = View.VISIBLE
        typingStatusText.text = getString(R.string.quail_assistant_typing)

        val (days, farmStats) = FarmDataContext.getSnapshot()
        if (days != null) {
            lastFarmContext = FarmDataContext.buildSummary(days, farmStats)

            val period = QueryPeriodParser.parse(text)
            if (period != null) {
                val stat = FarmDataContext.computePeriodStats(days, period)
                lastFarmContext += "\n\nEXACT ANSWER FOR THIS QUESTION — use these numbers verbatim, do not recalculate:\n" +
                        "Period: ${stat.label} (${stat.start} to ${stat.end})\n" +
                        "Total eggs: ${stat.total} over ${stat.daysWithData} recorded days\n" +
                        "Grade A: ${stat.a}, Grade B: ${stat.b}, Grade C: ${stat.c}\n" +
                        if (stat.total == 0) "No records exist for this period — say so plainly, don't estimate.\n" else ""

                // Chart only appears if the user actually asked for one.
                if (stat.total > 0 && QueryPeriodParser.wantsChart(text)) {
                    messages.add(
                        ChatMessage(
                            text = "",
                            isUser = false,
                            chartTitle = "${stat.label} — Grade Distribution",
                            chart = listOf(
                                ChartSlice("Grade A", stat.a, "#355E1A"),
                                ChartSlice("Grade B", stat.b, "#7C3AED"),
                                ChartSlice("Grade C", stat.c, "#F4B400")
                            ).filter { it.value > 0 }
                        )
                    )
                    adapter.notifyItemInserted(messages.size - 1)
                    recyclerView.scrollToPosition(messages.size - 1)
                }
            }
        }

        val historyForSession = messages.filter { it.chart == null }
        startSession(historyForSession.dropLast(1), lastFarmContext)

        var botMessageIndex = -1
        var accumulated = ""

        val maxAttempts = 3
        var attempt = 0
        var succeeded = false

        while (attempt < maxAttempts && !succeeded) {
            attempt++
            try {
                botMessageIndex = -1
                accumulated = ""
                chatSession.sendMessageStream(text).collect { chunk ->
                val piece = chunk.text ?: return@collect

                if (botMessageIndex == -1) {
                    // First chunk arrived — hide the typing dots, insert the live bubble
                    typingLayout.visibility = View.GONE
                    messages.add(ChatMessage(piece, isUser = false))
                    botMessageIndex = messages.size - 1
                    accumulated = piece
                    adapter.notifyItemInserted(botMessageIndex)
                } else {
                    accumulated += piece
                    messages[botMessageIndex] = ChatMessage(accumulated, isUser = false)
                    adapter.notifyItemChanged(botMessageIndex)
                }
                recyclerView.scrollToPosition(messages.size - 1)
            }

            if (botMessageIndex == -1) {
                addMessage("Sorry, I couldn't come up with an answer for that.", isUser = false)
            } else {
                // First user message may become the title — keep that logic intact
                if (conversationTitleText.text == "Quail Assistant" && messages.isNotEmpty()) {
                    val firstUserMsg = messages.firstOrNull { it.isUser }
                    if (firstUserMsg != null) conversationTitleText.text = firstUserMsg.text.take(40)
                }
                persistCurrentConversation()
            }
                succeeded = true
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: ""
                val isOverloaded = msg.contains("overloaded", ignoreCase = true) ||
                        msg.contains("503") || msg.contains("UNAVAILABLE", ignoreCase = true)

                if (isOverloaded && attempt < maxAttempts) {
                    typingStatusText.text = "AI is busy, retrying (${attempt}/${maxAttempts - 1})…"
                    kotlinx.coroutines.delay(1500L * attempt) // 1.5s, then 3s
                    typingStatusText.text = getString(R.string.quail_assistant_typing)
                } else {
                    typingLayout.visibility = View.GONE
                    if (botMessageIndex == -1) {
                        val friendly = if (isOverloaded)
                            "The AI is a bit busy right now — please try asking again in a moment."
                        else
                            "Oops, something went wrong: $msg"
                        addMessage(friendly, isUser = false)
                    } else {
                        persistCurrentConversation()
                    }
                    succeeded = true // stop looping, we've shown an error
                }
            }
        }
        typingLayout.visibility = View.GONE
        isSending = false
        startCooldown()
    }
    private var defaultInputHint: CharSequence? = null

    private fun startCooldown() {
        cooldownJob?.cancel()
        if (defaultInputHint == null) defaultInputHint = chatInputRef.hint
        cooldownJob = lifecycleScope.launch {
            chatInputRef.isEnabled = false
            sendButtonRef.isEnabled = false
            for (remaining in cooldownSeconds downTo 1) {
                chatInputRef.hint = "Please wait ${remaining}s…"
                kotlinx.coroutines.delay(1000)
            }
            chatInputRef.hint = defaultInputHint
            chatInputRef.isEnabled = true
            sendButtonRef.isEnabled = true
        }
    }
    // ---------- Model handling ----------

    private fun labelFor(modelId: String) =
        availableModels.find { it.id == modelId }?.label ?: modelId

    private fun startSession(history: List<ChatMessage>, farmContext: String = lastFarmContext) {
        val instruction = if (farmContext.isBlank()) systemPrompt else "$systemPrompt\n\n$farmContext"

        generativeModel = Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel(
                modelName = currentModelId,
                systemInstruction = content { text(instruction) }
            )
        val trimmedHistory = history.takeLast(12).dropWhile { !it.isUser }
        val historyContent: List<Content> = trimmedHistory.map { msg ->
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
        val picks = (generalQuestions.shuffled().take(2) + dataQuestions.shuffled().take(2)).shuffled()

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
        if (isSending) return
        addMessage(question, isUser = true)
        recyclerView.scrollToPosition(messages.size - 1)

        lifecycleScope.launch {
            streamReply(question)
        }
    }

    // ---------- Messages + persistence ----------

    private fun addMessage(text: String, isUser: Boolean, persist: Boolean = true) {
        messages.add(ChatMessage(text, isUser))
        adapter.notifyItemInserted(messages.size - 1)
        recyclerView.scrollToPosition(messages.size - 1)

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

                startSession(messages)
                persistCurrentConversation()

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