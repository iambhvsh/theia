package `in`.iambhvsh.theia

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

// Theia's UI
class Screen : Activity() {
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var actions: Actions
    private lateinit var btn: Button
    private lateinit var heard: TextView
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    @Volatile private var busy = false
    private val currentSessionId = UUID.randomUUID().toString()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        actions = Actions(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // Top bar with Title and Settings button
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
        }
        val title = TextView(this).apply {
            text = "Theia"
            textSize = 32f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val settingsBtn = Button(this).apply {
            text = "⚙ Groq"
            isAllCaps = false
            setOnClickListener { showSettingsDialog() }
        }
        topBar.addView(title)
        topBar.addView(settingsBtn)

        btn = Button(this).apply { text = "Talk to Theia"; setOnClickListener { listen() } }
        heard = TextView(this).apply { textSize = 20f; setPadding(0, pad, 0, pad) }
        status = TextView(this).apply { textSize = 14f }
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(topBar)
        root.addView(btn)
        root.addView(heard)
        root.addView(status)
        root.addView(ScrollView(this).apply { addView(results) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // Warm up speech, voice, and database
        exec.execute {
            try {
                Engine.ensureReady(applicationContext)
                Speaker.load(applicationContext)
                Database.get(applicationContext).history().getRecent(1)
            } catch (e: Throwable) {
                ui { status.text = "Error: ${e.message}" }
            }
        }
        if (intent.getBooleanExtra("autolisten", false)) window.decorView.post { listen() }
        intent.getStringExtra("speak")?.let { if (it.isNotBlank()) exec.execute { Speaker.speak(applicationContext, it) } }
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        intent = i
        if (i.getBooleanExtra("autolisten", false)) listen()
        i.getStringExtra("speak")?.let { if (it.isNotBlank()) exec.execute { Speaker.speak(applicationContext, it) } }
    }

    private fun ui(f: () -> Unit) = runOnUiThread(f)

    private fun showSettingsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val keyInput = EditText(this).apply {
            hint = "gsk_..."
            setText(Groq.getApiKey(this@Screen))
        }
        val modelInput = EditText(this).apply {
            setText(Groq.getModel(this@Screen))
        }

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

        val testVoiceBtn = Button(this).apply {
            text = "▶ Preview Voice"
            isAllCaps = false
            setOnClickListener {
                exec.execute {
                    Speaker.speak(applicationContext, "Hello! I am Theia, your fast and natural voice assistant.")
                }
            }
        }

        layout.addView(TextView(this).apply { text = "Groq API Key:"; textSize = 14f })
        layout.addView(keyInput)
        layout.addView(TextView(this).apply {
            text = "Model (Real-time: qwen/qwen3.8-27b):"
            textSize = 14f
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        })
        layout.addView(modelInput)
        layout.addView(TextView(this).apply {
            text = "Assistant Voice:"
            textSize = 14f
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        })
        layout.addView(voiceBtn)
        layout.addView(testVoiceBtn)

        AlertDialog.Builder(this)
            .setTitle("Theia Assistant Settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val k = keyInput.text.toString().trim()
                val m = modelInput.text.toString().trim().ifEmpty { Groq.DEFAULT_MODEL }
                Groq.setApiKey(this, k)
                Groq.setModel(this, m)
                Speaker.setPreset(this, currentPreset.id)
                status.text = "Settings saved ($m, ${currentPreset.label})"
            }
            .setNeutralButton("Clear History") { _, _ ->
                exec.execute {
                    Database.get(applicationContext).history().clear()
                    ui { status.text = "Conversation history cleared" }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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
                if (pcm.isEmpty()) { say("I didn't hear anything."); return@execute }
                ui { status.text = "Transcribing…" }
                val rawTranscribe = Engine.transcribe(pcm)
                android.util.Log.d("Theia", "Raw transcribe response: $rawTranscribe")
                val text = org.json.JSONObject(rawTranscribe).optString("text").trim()
                android.util.Log.d("Theia", "Extracted transcribed text: '$text'")
                ui { heard.text = text.ifEmpty { "(empty)" } }
                if (text.isEmpty()) { say("I didn't catch that."); return@execute }
                val prompt = cleanTranscript(text).ifEmpty { text }
                android.util.Log.d("Theia", "Cleaned prompt for LLM: '$prompt'")

                val db = Database.get(applicationContext)

                if (Groq.hasApiKey(applicationContext)) {
                    // --- GROQ LLM WITH PERSISTENT SESSION CONTEXT ---
                    ui { status.text = "Thinking (Groq)…" }

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
                ui { status.text = "Thinking (local)…" }
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
                ui { btn.isEnabled = true }
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
