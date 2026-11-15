package `in`.iambhvsh.theia

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

// Theia's UI: Assistant Bottom Sheet with Expandable Settings Page
class Screen : Activity() {
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var actions: Actions
    private lateinit var rootLayout: FrameLayout
    private lateinit var backdropView: View
    private lateinit var sheetContainer: LinearLayout
    private lateinit var settingsPage: ScrollView

    private lateinit var btn: Button
    private lateinit var stopBtn: Button
    private lateinit var heard: TextView
    private lateinit var status: TextView
    private lateinit var results: LinearLayout

    @Volatile private var busy = false
    private val currentSessionId = UUID.randomUUID().toString()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        actions = Actions(this)

        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Transparent Backdrop: Clicking outside sheet dismisses Theia
        backdropView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x80000000.toInt()) // 50% Dim
            setOnClickListener { finish() }
        }
        rootLayout.addView(backdropView)

        // 2. Bottom Sheet Container
        sheetContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                val r = 24f * density
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            elevation = 24f * density
            setPadding(pad, (10 * density).toInt(), pad, pad)
            // Consume touches inside sheet so backdrop isn't clicked
            setOnClickListener {}
        }

        // Pill drag handle
        val handleBar = View(this).apply {
            val hlp = LinearLayout.LayoutParams((40 * density).toInt(), (4 * density).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (12 * density).toInt()
            }
            layoutParams = hlp
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFFD1D5DB.toInt())
                cornerRadius = 2f * density
            }
        }
        sheetContainer.addView(handleBar)

        // Header bar with Title and Top-Right Settings button
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        val title = TextView(this).apply {
            text = "Theia"
            textSize = 22f
            setTextColor(0xFF1F2937.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val settingsBtn = Button(this).apply {
            text = "⚙"
            textSize = 20f
            background = null
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            setOnClickListener { showSettingsPage() }
        }
        topBar.addView(title)
        topBar.addView(settingsBtn)
        sheetContainer.addView(topBar)

        // Query and Status text
        heard = TextView(this).apply {
            textSize = 18f
            setTextColor(0xFF111827.toInt())
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
        }
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        sheetContainer.addView(heard)
        sheetContainer.addView(status)

        // Action Buttons Row (Talk Button + Stop Button)
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
        }

        btn = Button(this).apply {
            text = "🎙️ Tap to talk"
            textSize = 16f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { listen() }
        }

        stopBtn = Button(this).apply {
            text = "⏹ Stop"
            textSize = 16f
            isAllCaps = false
            visibility = View.GONE
            val slp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (10 * density).toInt()
            }
            layoutParams = slp
            setOnClickListener {
                Speaker.stop()
                ui {
                    visibility = View.GONE
                    status.text = "Speech stopped"
                }
            }
        }

        btnRow.addView(btn)
        btnRow.addView(stopBtn)
        sheetContainer.addView(btnRow)

        // Results container
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scrollResults = ScrollView(this).apply {
            addView(results)
        }
        sheetContainer.addView(scrollResults)

        rootLayout.addView(sheetContainer)

        // 3. Full-page Settings Container
        settingsPage = buildSettingsPage(density, pad)
        rootLayout.addView(settingsPage)

        setContentView(rootLayout)

        // Connect speaking listener to toggle Stop button dynamically
        Speaker.onSpeakingStateChanged = { speaking ->
            ui {
                stopBtn.visibility = if (speaking) View.VISIBLE else View.GONE
                if (!speaking && !busy) {
                    btn.text = "🎙️ Tap to talk"
                    btn.isEnabled = true
                }
            }
        }

        // Preload speech, TTS, and database
        exec.execute {
            try {
                Engine.ensureReady(applicationContext)
                Speaker.load(applicationContext)
                Database.get(applicationContext).history().getRecent(1)
            } catch (e: Throwable) {
                ui { status.text = "Error: ${e.message}" }
            }
        }

        // Automatic listen on launch like Google Assistant
        window.decorView.post {
            listen()
        }

        intent.getStringExtra("speak")?.let {
            if (it.isNotBlank()) exec.execute { Speaker.speak(applicationContext, it) }
        }
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        intent = i
        // When launched again or via voice gesture, start listening automatically
        listen()
        i.getStringExtra("speak")?.let {
            if (it.isNotBlank()) exec.execute { Speaker.speak(applicationContext, it) }
        }
    }

    private fun showSettingsPage() {
        backdropView.visibility = View.GONE
        sheetContainer.visibility = View.GONE
        settingsPage.visibility = View.VISIBLE
    }

    private fun showBottomSheet() {
        settingsPage.visibility = View.GONE
        backdropView.visibility = View.VISIBLE
        sheetContainer.visibility = View.VISIBLE
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (settingsPage.visibility == View.VISIBLE) {
            showBottomSheet()
        } else {
            super.onBackPressed()
        }
    }

    private fun buildSettingsPage(density: Float, pad: Int): ScrollView {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (24 * density).toInt(), pad, pad)
        }

        // Top Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (16 * density).toInt())
        }
        val backBtn = Button(this).apply {
            text = "← Back"
            isAllCaps = false
            setOnClickListener { showBottomSheet() }
        }
        val pageTitle = TextView(this).apply {
            text = "Settings"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF111827.toInt())
            setPadding((12 * density).toInt(), 0, 0, 0)
        }
        header.addView(backBtn)
        header.addView(pageTitle)
        layout.addView(header)

        // --- Groq API Key (Masked with Eye Toggle) ---
        layout.addView(TextView(this).apply {
            text = "Groq API Key:"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
        })

        val keyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val keyInput = EditText(this).apply {
            hint = "gsk_..."
            transformationMethod = PasswordTransformationMethod.getInstance()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Groq.getApiKey(this@Screen))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        var isKeyVisible = false
        val toggleKeyBtn = Button(this).apply {
            text = "👁"
            isAllCaps = false
            setOnClickListener {
                isKeyVisible = !isKeyVisible
                if (isKeyVisible) {
                    keyInput.transformationMethod = null
                    this.text = "🙈"
                } else {
                    keyInput.transformationMethod = PasswordTransformationMethod.getInstance()
                    this.text = "👁"
                }
                keyInput.setSelection(keyInput.text.length)
            }
        }
        keyRow.addView(keyInput)
        keyRow.addView(toggleKeyBtn)
        layout.addView(keyRow)

        // --- Model Input ---
        layout.addView(TextView(this).apply {
            text = "LLM Model (Real-time):"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
        })
        val modelInput = EditText(this).apply {
            setText(Groq.getModel(this@Screen))
        }
        layout.addView(modelInput)

        // --- Voice Selector ---
        layout.addView(TextView(this).apply {
            text = "Assistant Voice:"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
        })

        var currentPreset = Speaker.getPreset(this)
        val presetLabels = Speaker.PRESETS.map { it.label }.toTypedArray()

        val voiceBtn = Button(this).apply {
            text = "Voice: ${currentPreset.label}"
            isAllCaps = false
            setOnClickListener {
                val currentIdx = Speaker.PRESETS.indexOfFirst { it.id == currentPreset.id }.coerceAtLeast(0)
                AlertDialog.Builder(this@Screen)
                    .setTitle("Choose Assistant Voice")
                    .setSingleChoiceItems(presetLabels, currentIdx) { d, which ->
                        currentPreset = Speaker.PRESETS[which]
                        this.text = "Voice: ${currentPreset.label}"
                        Speaker.setPreset(this@Screen, currentPreset.id)
                        d.dismiss()
                        exec.execute {
                            Speaker.speak(applicationContext, "Hello! I am Theia, how do you like my voice?")
                        }
                    }
                    .show()
            }
        }
        layout.addView(voiceBtn)

        val testVoiceBtn = Button(this).apply {
            text = "▶ Preview Voice"
            isAllCaps = false
            setOnClickListener {
                exec.execute {
                    Speaker.speak(applicationContext, "Hello! I am Theia, your fast and natural voice assistant.")
                }
            }
        }
        layout.addView(testVoiceBtn)

        // --- Conversation History Management ---
        layout.addView(TextView(this).apply {
            text = "Conversation Memory:"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
        })
        val clearHistoryBtn = Button(this).apply {
            text = "Clear History"
            isAllCaps = false
            setOnClickListener {
                exec.execute {
                    Database.get(applicationContext).history().clear()
                    ui { status.text = "Conversation history cleared" }
                }
            }
        }
        layout.addView(clearHistoryBtn)

        // --- Save Settings Button ---
        val saveBtn = Button(this).apply {
            text = "Save Settings"
            textSize = 16f
            isAllCaps = false
            val slp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (24 * density).toInt()
            }
            layoutParams = slp
            setOnClickListener {
                val k = keyInput.text.toString().trim()
                val m = modelInput.text.toString().trim().ifEmpty { Groq.DEFAULT_MODEL }
                Groq.setApiKey(this@Screen, k)
                Groq.setModel(this@Screen, m)
                Speaker.setPreset(this@Screen, currentPreset.id)
                status.text = "Settings saved (${currentPreset.label})"
                showBottomSheet()
            }
        }
        layout.addView(saveBtn)

        scroll.addView(layout)
        return scroll
    }

    private fun ui(f: () -> Unit) = runOnUiThread(f)

    private fun cleanTranscript(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("^(?i)(hittia|hitia|heath\\s+here|hey\\s+theia|hey\\s+tia|hey\\s+thea|theia|tia|thea|hi\\s+theia|hey|hello\\s+theia|hello\\s+here)[,\\s]*"), "")
        s = s.replace(Regex("^(?i)here\\s+(?=(find|search|open|set|what|how|who|can)\\b)"), "")
        return s.trim()
    }

    private fun extractMessage(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            try {
                val obj = org.json.JSONObject(trimmed)
                for (k in listOf("content", "message", "response", "text", "answer")) {
                    val v = obj.optString(k).trim()
                    if (v.isNotEmpty()) return v
                }
            } catch (_: Exception) {}
        } else if (!trimmed.startsWith("[") && trimmed.length < 500 && !trimmed.contains("Error:")) {
            return trimmed
        }
        return ""
    }

    private fun say(text: String) {
        ui { status.text = text }
        Speaker.speak(applicationContext, text)
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (busy) return
        busy = true
        Speaker.stop()
        btn.isEnabled = false
        btn.text = "🎙️ Listening…"
        results.removeAllViews()
        heard.text = ""
        exec.execute {
            try {
                ui { status.text = "Loading…" }
                Engine.ensureReady(applicationContext)
                ui { status.text = "Listening…" }
                val pcm = Recorder.capture {
                    ui { status.text = "Listening… (hearing you)" }
                }
                if (pcm.isEmpty()) {
                    say("I didn't hear anything.")
                    return@execute
                }
                ui {
                    status.text = "Transcribing…"
                    btn.text = "Transcribing…"
                }
                val rawTranscribe = Engine.transcribe(pcm)
                android.util.Log.d("Theia", "Raw transcribe response: $rawTranscribe")
                val text = org.json.JSONObject(rawTranscribe).optString("text").trim()
                android.util.Log.d("Theia", "Extracted transcribed text: '$text'")
                ui { heard.text = text.ifEmpty { "(empty)" } }
                if (text.isEmpty()) {
                    say("I didn't catch that.")
                    return@execute
                }
                val prompt = cleanTranscript(text).ifEmpty { text }
                android.util.Log.d("Theia", "Cleaned prompt for LLM: '$prompt'")

                val db = Database.get(applicationContext)

                if (Groq.hasApiKey(applicationContext)) {
                    // --- GROQ LLM WITH PERSISTENT SESSION CONTEXT ---
                    ui {
                        status.text = "Thinking (Groq)…"
                        btn.text = "Thinking…"
                    }

                    // Fetch prior conversation history before adding current prompt
                    val history = db.history().getRecent(10)

                    // Record current user prompt in database
                    db.history().insert(
                        Message(
                            sessionId = currentSessionId,
                            role = "user",
                            content = prompt
                        )
                    )

                    try {
                        val groqResp = Groq.complete(applicationContext, history, prompt)

                        if (groqResp.toolCalls.isNotEmpty()) {
                            // Assistant requested native tool calls
                            db.history().insert(
                                Message(
                                    sessionId = currentSessionId,
                                    role = "assistant",
                                    content = groqResp.content,
                                    toolCallsJson = groqResp.rawToolCallsJson
                                )
                            )

                            for (c in groqResp.toolCalls) {
                                if (c.name == "search_files") {
                                    searchFlow(c.args.optString("query"))
                                    db.history().insert(
                                        Message(
                                            sessionId = currentSessionId,
                                            role = "tool",
                                            content = "Search executed for ${c.args.optString("query")}",
                                            toolCallId = c.id
                                        )
                                    )
                                    continue
                                }
                                var announced = false
                                val r = actions.run(c) { m ->
                                    announced = true
                                    say(m)
                                }
                                db.history().insert(
                                    Message(
                                        sessionId = currentSessionId,
                                        role = "tool",
                                        content = r,
                                        toolCallId = c.id
                                    )
                                )
                                if (!announced) say(r)
                            }
                            groqResp.content?.let { if (it.isNotBlank()) say(it) }
                        } else if (!groqResp.content.isNullOrBlank()) {
                            // Conversational assistant text response
                            db.history().insert(
                                Message(
                                    sessionId = currentSessionId,
                                    role = "assistant",
                                    content = groqResp.content
                                )
                            )
                            say(groqResp.content)
                        } else {
                            say("I'm not sure how to respond to that.")
                        }
                        return@execute
                    } catch (e: Exception) {
                        android.util.Log.e("Theia", "Groq request failed", e)
                        val err = e.message ?: "Connection error"
                        ui { status.text = "Groq error: $err" }
                        say("Sorry, Groq returned an error: $err")
                        return@execute
                    }
                }

                // --- ON-DEVICE LOCAL ENGINE FALLBACK ---
                ui {
                    status.text = "Thinking (local)…"
                    btn.text = "Thinking…"
                }
                val raw = Engine.complete(prompt)
                android.util.Log.d("Theia", "Engine complete raw: '$raw'")
                val calls = Parser.calls(raw)
                if (calls.isEmpty()) {
                    val p = prompt.lowercase()
                    if (p.contains("how are you")) {
                        say("I'm doing well thank you. How can I help you?")
                        return@execute
                    } else if (p.contains("who are you") || p.contains("what is your name") || p.contains("what's your name")) {
                        say("I am Theia, your private local assistant.")
                        return@execute
                    } else if (p.startsWith("hello") || p.startsWith("hi")) {
                        say("Hello! How can I help you?")
                        return@execute
                    }
                    val message = extractMessage(raw)
                    if (message.isNotEmpty()) {
                        say(message)
                    } else {
                        ui { status.text = "No action matched.\nraw: $raw" }
                        Speaker.speak(applicationContext, "Sorry, I can't do that yet.")
                    }
                    return@execute
                }
                for (c in calls) {
                    if (c.name == "search_files") { searchFlow(c.args.optString("query")); continue }
                    var announced = false
                    val r = actions.run(c) { m -> announced = true; say(m) }
                    if (!announced) say(r)
                }
            } catch (e: Throwable) {
                ui { status.text = "Error: ${e.message}" }
            } finally {
                busy = false
                ui {
                    btn.isEnabled = true
                    if (!Speaker.isSpeaking) btn.text = "🎙️ Tap to talk"
                }
            }
        }
    }

    private fun searchFlow(query: String) {
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) { say("Unlock your phone to search files."); return }
        if (!actions.hasFileAccess()) {
            ui { actions.requestFileAccess() }
            say("Please allow all files access, then ask again.")
            return
        }
        ui { status.text = "Searching for \"$query\"…" }
        val hits = actions.searchFiles(query)
        ui { hits.forEach { addResult(it) } }
        say(if (hits.isEmpty()) {
            if (query.isBlank()) "I found no files on your storage."
            else "I found no files for $query."
        } else {
            "I found ${hits.size} files."
        })
    }

    private fun addResult(f: File) {
        results.addView(Button(this).apply {
            text = "${f.name}\n${f.parent}"
            isAllCaps = false
            setOnClickListener { actions.openFile(f) }
        })
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) listen()
        else status.text = "Microphone permission required"
    }

    override fun onDestroy() {
        Speaker.stop()
        super.onDestroy()
    }
}

