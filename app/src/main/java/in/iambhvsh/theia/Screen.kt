package `in`.iambhvsh.theia

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
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

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        actions = Actions(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(this).apply { text = "Theia"; textSize = 32f }
        btn = Button(this).apply { text = "Talk to Theia"; setOnClickListener { listen() } }
        heard = TextView(this).apply { textSize = 20f; setPadding(0, pad, 0, pad) }
        status = TextView(this).apply { textSize = 14f }
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(title)
        root.addView(btn)
        root.addView(heard)
        root.addView(status)
        root.addView(ScrollView(this).apply { addView(results) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // Warm up both models and the voice in the background.
        exec.execute {
            try { Engine.ensureReady(applicationContext); Speaker.load(applicationContext) }
            catch (e: Throwable) { ui { status.text = "Error: ${e.message}" } }
        }
        if (intent.getBooleanExtra("autolisten", false)) window.decorView.post { listen() }
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        if (i.getBooleanExtra("autolisten", false)) listen()
    }

    private fun ui(f: () -> Unit) = runOnUiThread(f)

    private fun cleanTranscript(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("^(?i)(hittia|hitia|heath\\s+here|hey\\s+theia|hey\\s+tia|hey\\s+thea|theia|tia|thea|hi\\s+theia|hey)[,\\s]*"), "")
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
                ui { status.text = "Thinking…" }
                val raw = Engine.complete(prompt)
                android.util.Log.d("Theia", "Engine complete raw: '$raw'")
                val calls = Parser.calls(raw)
                if (calls.isEmpty()) {
                    val p = prompt.lowercase()
                    if (p.contains("how are you")) {
                        say("I'm doing well, thank you! How can I help you?")
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
        say(if (hits.isEmpty()) "I found no files for $query." else "I found ${hits.size} files.")
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
