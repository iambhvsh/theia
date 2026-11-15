package `in`.iambhvsh.theia

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Theia's Main UI: Reengineered Google Assistant Bottom Sheet & Voice Button Experience.
 *
 * Features:
 * - Signature Google Assistant 4-dots visualizer with audio wave & orbit motions
 * - Sleek floating voice pill button & instant stop button
 * - Clean Material 3 bottom-sheet with drag handle, welcome greeting, and suggestion chips
 * - Dual-input mode: Voice or Type (keyboard toggle)
 * - Expandable Settings page (Groq API key masking, LLM model, Voice presets, memory)
 * - Android system Assistant intent integration
 */
class Screen : Activity() {
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var actions: Actions

    private lateinit var rootLayout: FrameLayout
    private lateinit var backdropView: View
    private lateinit var sheetContainer: LinearLayout
    private lateinit var settingsPage: ScrollView

    // Google Assistant Bottom Bar Elements
    private lateinit var voicePill: LinearLayout
    private lateinit var visualizer: Visualizer
    private lateinit var voiceBtnLabel: TextView
    private lateinit var stopBtn: Button
    private lateinit var keyboardToggleBtn: Button
    private lateinit var textInputRow: LinearLayout
    private lateinit var textInputField: EditText
    private lateinit var textSendBtn: Button

    // Conversation & Cards Display
    private lateinit var conversationScroll: ScrollView
    private lateinit var conversationContainer: LinearLayout
    private lateinit var greetingView: LinearLayout
    private lateinit var statusText: TextView

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

        // 1. Semi-transparent backdrop (tapping dismisses bottom sheet)
        backdropView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x80000000.toInt())
            setOnClickListener { finish() }
        }
        rootLayout.addView(backdropView)

        // 2. Google Assistant Bottom Sheet Container
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
                val r = 28f * density
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            elevation = 24f * density
            setPadding(pad, (12 * density).toInt(), pad, (20 * density).toInt())
            setOnClickListener {} // Prevent touch pass-through to backdrop
        }

        // Pill Drag Handle
        val handleBar = View(this).apply {
            val hlp = LinearLayout.LayoutParams((38 * density).toInt(), (4 * density).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (12 * density).toInt()
            }
            layoutParams = hlp
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFFDADCE0.toInt())
                cornerRadius = 2f * density
            }
        }
        sheetContainer.addView(handleBar)

        // Top Header Bar: Theia Logo/Title and Settings Button
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (8 * density).toInt())
        }

        val logoBadge = TextView(this).apply {
            text = "✦"
            textSize = 20f
            setTextColor(0xFF4285F4.toInt()) // Google Blue
            setPadding(0, 0, (8 * density).toInt(), 0)
        }
        val appTitle = TextView(this).apply {
            text = "Theia"
            textSize = 21f
            setTextColor(0xFF202124.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val settingsBtn = Button(this).apply {
            text = "⚙"
            textSize = 18f
            setTextColor(0xFF5F6368.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFF1F3F4.toInt())
            }
            val slp = LinearLayout.LayoutParams((38 * density).toInt(), (38 * density).toInt()).apply {
                marginEnd = (6 * density).toInt()
            }
            layoutParams = slp
            setOnClickListener { showSettingsPage() }
        }
        val closeBtn = Button(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(0xFF5F6368.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFF1F3F4.toInt())
            }
            val clp = LinearLayout.LayoutParams((38 * density).toInt(), (38 * density).toInt())
            layoutParams = clp
            setOnClickListener { finish() }
        }

        topBar.addView(logoBadge)
        topBar.addView(appTitle)
        topBar.addView(settingsBtn)
        topBar.addView(closeBtn)
        sheetContainer.addView(topBar)

        // Scrollable Conversation Area
        conversationContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
        }

        // Google Assistant Style Welcome Greeting
        greetingView = buildGreetingView(density)
        conversationContainer.addView(greetingView)

        // Subtle Status Text
        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF5F6368.toInt())
            setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            visibility = View.GONE
        }
        conversationContainer.addView(statusText)

        conversationScroll = ScrollView(this).apply {
            val clp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (200 * density).toInt()
            )
            layoutParams = clp
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(conversationContainer)
        }
        sheetContainer.addView(conversationScroll)

        // Text Input Row (revealed when user taps keyboard icon)
        textInputRow = buildTextInputRow(density)
        sheetContainer.addView(textInputRow)

        // Google Assistant Bottom Voice Bar Row
        val voiceBarRow = buildVoiceBarRow(density)
        sheetContainer.addView(voiceBarRow)

        rootLayout.addView(sheetContainer)

        // 3. Full-page Settings Container
        settingsPage = buildSettingsPage(density, pad)
        rootLayout.addView(settingsPage)

        setContentView(rootLayout)

        // Connect speaking listener to toggle Stop button dynamically
        Speaker.onSpeakingStateChanged = { speaking ->
            ui {
                stopBtn.visibility = if (speaking) View.VISIBLE else View.GONE
                if (speaking) {
                    visualizer.mode = Visualizer.Mode.SPEAKING
                    voiceBtnLabel.text = "Speaking…"
                } else if (!busy) {
                    visualizer.mode = Visualizer.Mode.IDLE
                    voiceBtnLabel.text = "Tap to talk"
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
                ui { showStatus("Init: ${e.message}") }
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
        listen()
        i.getStringExtra("speak")?.let {
            if (it.isNotBlank()) exec.execute { Speaker.speak(applicationContext, it) }
        }
    }

    private fun buildGreetingView(density: Float): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * density).toInt(), 0, (12 * density).toInt())
        }

        val greetingText = TextView(this).apply {
            text = "Hi, how can I help?"
            textSize = 22f
            setTextColor(0xFF202124.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        layout.addView(greetingText)

        val subText = TextView(this).apply {
            text = "Ask anything, search your files, or tap to speak."
            textSize = 14f
            setTextColor(0xFF5F6368.toInt())
            setPadding(0, (2 * density).toInt(), 0, (12 * density).toInt())
        }
        layout.addView(subText)

        // Suggestion Chips (Horizontal Scroll)
        val chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val chipsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val chips = listOf(
            "📁 Search files",
            "⏰ Set alarm",
            "💡 What can you do?",
            "🔋 Battery info"
        )

        for (chip in chips) {
            val btn = Button(this).apply {
                text = chip
                textSize = 13f
                isAllCaps = false
                setTextColor(0xFF3C4043.toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(0xFFF1F3F4.toInt())
                    cornerRadius = 16f * density
                    setStroke((1 * density).toInt(), 0xFFE0E0E0.toInt())
                }
                val blp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    (34 * density).toInt()
                ).apply {
                    marginEnd = (8 * density).toInt()
                }
                layoutParams = blp
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                setOnClickListener {
                    val prompt = chip.substringAfter(" ")
                    processQuery(prompt)
                }
            }
            chipsContainer.addView(btn)
        }

        chipsScroll.addView(chipsContainer)
        layout.addView(chipsScroll)

        return layout
    }

    private fun buildTextInputRow(density: Float): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())

            textInputField = EditText(this@Screen).apply {
                hint = "Ask Theia anything…"
                textSize = 15f
                setTextColor(0xFF202124.toInt())
                setHintTextColor(0xFF9AA0A6.toInt())
                imeOptions = EditorInfo.IME_ACTION_SEND
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(0xFFF1F3F4.toInt())
                    cornerRadius = 24f * density
                    setStroke((1 * density).toInt(), 0xFFDADCE0.toInt())
                }
                setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEND) {
                        submitTextQuery()
                        true
                    } else false
                }
            }

            textSendBtn = Button(this@Screen).apply {
                text = "➤"
                textSize = 16f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF4285F4.toInt()) // Google Blue
                }
                val lp = LinearLayout.LayoutParams((42 * density).toInt(), (42 * density).toInt()).apply {
                    marginStart = (8 * density).toInt()
                }
                layoutParams = lp
                setOnClickListener { submitTextQuery() }
            }

            addView(textInputField)
            addView(textSendBtn)
        }
    }

    private fun buildVoiceBarRow(density: Float): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (4 * density).toInt(), 0, 0)
        }

        // Keyboard toggle button (left)
        keyboardToggleBtn = Button(this).apply {
            text = "⌨"
            textSize = 18f
            setTextColor(0xFF5F6368.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFF1F3F4.toInt())
            }
            val klp = LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt()).apply {
                marginEnd = (10 * density).toInt()
            }
            layoutParams = klp
            setOnClickListener {
                if (textInputRow.visibility == View.VISIBLE) {
                    textInputRow.visibility = View.GONE
                    hideKeyboard()
                } else {
                    textInputRow.visibility = View.VISIBLE
                    textInputField.requestFocus()
                    showKeyboard()
                }
            }
        }

        // Signature Google Assistant 4-Dots Voice Pill (center)
        voicePill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, (54 * density).toInt(), 1f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                cornerRadius = 27f * density
                setStroke((1.5f * density).toInt(), 0xFFE0E0E0.toInt())
            }
            elevation = 6f * density

            // The animated Google Assistant 4-dots Canvas visualizer
            visualizer = Visualizer(this@Screen).apply {
                val vlp = LinearLayout.LayoutParams((64 * density).toInt(), (36 * density).toInt())
                layoutParams = vlp
            }

            voiceBtnLabel = TextView(this@Screen).apply {
                text = "Tap to talk"
                textSize = 15f
                setTextColor(0xFF3C4043.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding((6 * density).toInt(), 0, (8 * density).toInt(), 0)
            }

            addView(visualizer)
            addView(voiceBtnLabel)

            setOnClickListener {
                if (Speaker.isSpeaking) {
                    Speaker.stop()
                    visualizer.mode = Visualizer.Mode.IDLE
                    voiceBtnLabel.text = "Tap to talk"
                } else if (busy) {
                    // Recording in progress
                } else {
                    listen()
                }
            }
        }

        // Stop button (right) - appears whenever speech is active
        stopBtn = Button(this).apply {
            text = "⏹ Stop"
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFFEA4335.toInt()) // Google Red
                cornerRadius = 22f * density
            }
            val slp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (44 * density).toInt()
            ).apply {
                marginStart = (10 * density).toInt()
            }
            layoutParams = slp
            setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
            setOnClickListener {
                Speaker.stop()
                ui {
                    visibility = View.GONE
                    visualizer.mode = Visualizer.Mode.IDLE
                    voiceBtnLabel.text = "Tap to talk"
                    showStatus("Speech stopped")
                }
            }
        }

        row.addView(keyboardToggleBtn)
        row.addView(voicePill)
        row.addView(stopBtn)

        return row
    }

    private fun submitTextQuery() {
        val q = textInputField.text.toString().trim()
        if (q.isNotEmpty()) {
            textInputField.setText("")
            textInputRow.visibility = View.GONE
            hideKeyboard()
            processQuery(q)
        }
    }

    private fun showKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(textInputField, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(textInputField.windowToken, 0)
    }

    private fun showStatus(text: String) {
        statusText.text = text
        statusText.visibility = if (text.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun addMessageBubble(sender: String, text: String) {
        val density = resources.displayMetrics.density
        greetingView.visibility = View.GONE

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val isUser = sender == "user"
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6 * density).toInt()
                bottomMargin = (6 * density).toInt()
            }
            layoutParams = lp

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                if (isUser) {
                    setColor(0xFFE8F0FE.toInt()) // Google light blue
                    setStroke((1 * density).toInt(), 0xFFD2E3FC.toInt())
                } else {
                    setColor(0xFFF8F9FA.toInt())
                    setStroke((1 * density).toInt(), 0xFFE8EAED.toInt())
                }
            }
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())

            val label = TextView(this@Screen).apply {
                this.text = if (isUser) "You" else "Theia"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isUser) 0xFF1967D2.toInt() else 0xFF5F6368.toInt())
                setPadding(0, 0, 0, (2 * density).toInt())
            }
            val content = TextView(this@Screen).apply {
                this.text = text
                textSize = 16f
                setTextColor(0xFF202124.toInt())
                setLineSpacing(0f, 1.2f)
            }

            addView(label)
            addView(content)
        }

        conversationContainer.addView(card)
        conversationScroll.post {
            conversationScroll.fullScroll(View.FOCUS_DOWN)
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
            setPadding(pad, (24 * density).toInt(), pad, (24 * density).toInt())
        }

        // Header with Back button and Settings title
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (16 * density).toInt())
        }
        val backBtn = Button(this).apply {
            text = "← Back"
            isAllCaps = false
            textSize = 15f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFFF1F3F4.toInt())
                cornerRadius = 16f * density
            }
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            setOnClickListener { showBottomSheet() }
        }
        val pageTitle = TextView(this).apply {
            text = "Settings"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF202124.toInt())
            setPadding((14 * density).toInt(), 0, 0, 0)
        }
        header.addView(backBtn)
        header.addView(pageTitle)
        layout.addView(header)

        // Groq API Key
        layout.addView(TextView(this).apply {
            text = "Groq API Key:"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF3C4043.toInt())
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

        // Model Input
        layout.addView(TextView(this).apply {
            text = "LLM Model (Real-time):"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF3C4043.toInt())
            setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
        })
        val modelInput = EditText(this).apply {
            setText(Groq.getModel(this@Screen))
        }
        layout.addView(modelInput)

        // Assistant Voice Selector
        layout.addView(TextView(this).apply {
            text = "Assistant Voice:"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF3C4043.toInt())
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

        // Conversation Memory Management
        layout.addView(TextView(this).apply {
            text = "Conversation Memory:"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF3C4043.toInt())
            setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
        })
        val clearHistoryBtn = Button(this).apply {
            text = "Clear History"
            isAllCaps = false
            setOnClickListener {
                exec.execute {
                    Database.get(applicationContext).history().clear()
                    ui { showStatus("Conversation memory cleared") }
                }
            }
        }
        layout.addView(clearHistoryBtn)

        // Save Settings Button
        val saveBtn = Button(this).apply {
            text = "Save Settings"
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF1A73E8.toInt()) // Google Blue
                cornerRadius = 24f * density
            }
            val slp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (48 * density).toInt()
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
                showStatus("Settings saved (${currentPreset.label})")
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
        ui {
            addMessageBubble("assistant", text)
            showStatus(text)
        }
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

        ui {
            visualizer.mode = Visualizer.Mode.LISTENING
            voiceBtnLabel.text = "Listening…"
            showStatus("Listening…")
        }

        exec.execute {
            try {
                ui { showStatus("Loading…") }
                Engine.ensureReady(applicationContext)
                ui { showStatus("Listening…") }

                val pcm = Recorder.capture(
                    maxSec = 15,
                    onRmsUpdate = { rms ->
                        ui { visualizer.setRms(rms) }
                    },
                    onSpeechDetected = {
                        ui {
                            visualizer.mode = Visualizer.Mode.LISTENING
                            voiceBtnLabel.text = "Listening…"
                            showStatus("Hearing you…")
                        }
                    }
                )

                if (pcm.isEmpty()) {
                    say("I didn't hear anything.")
                    return@execute
                }

                ui {
                    visualizer.mode = Visualizer.Mode.THINKING
                    voiceBtnLabel.text = "Transcribing…"
                    showStatus("Transcribing speech…")
                }

                val rawTranscribe = Engine.transcribe(pcm)
                android.util.Log.d("Theia", "Raw transcribe response: $rawTranscribe")
                val text = org.json.JSONObject(rawTranscribe).optString("text").trim()
                android.util.Log.d("Theia", "Extracted transcribed text: '$text'")

                if (text.isEmpty()) {
                    say("I didn't catch that.")
                    return@execute
                }

                val prompt = cleanTranscript(text).ifEmpty { text }
                processQuery(prompt)

            } catch (e: Throwable) {
                ui {
                    showStatus("Error: ${e.message}")
                    visualizer.mode = Visualizer.Mode.IDLE
                    voiceBtnLabel.text = "Tap to talk"
                }
            } finally {
                busy = false
                ui {
                    if (!Speaker.isSpeaking) {
                        visualizer.mode = Visualizer.Mode.IDLE
                        voiceBtnLabel.text = "Tap to talk"
                    }
                }
            }
        }
    }

    private fun processQuery(prompt: String) {
        ui {
            addMessageBubble("user", prompt)
            visualizer.mode = Visualizer.Mode.THINKING
            voiceBtnLabel.text = "Thinking…"
            showStatus("Thinking…")
        }

        exec.execute {
            try {
                val db = Database.get(applicationContext)

                if (Groq.hasApiKey(applicationContext)) {
                    // --- GROQ REAL-TIME LLM WITH MEMORY ---
                    val history = db.history().getRecent(10)

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
                        say("Sorry, Groq returned an error: $err")
                        return@execute
                    }
                }

                // --- ON-DEVICE LOCAL ENGINE FALLBACK ---
                val raw = Engine.complete(prompt)
                android.util.Log.d("Theia", "Engine complete raw: '$raw'")
                val calls = Parser.calls(raw)
                if (calls.isEmpty()) {
                    val p = prompt.lowercase()
                    if (p.contains("how are you")) {
                        say("I'm doing well, thank you. How can I help you today?")
                        return@execute
                    } else if (p.contains("who are you") || p.contains("what is your name")) {
                        say("I am Theia, your fast and natural assistant.")
                        return@execute
                    } else if (p.startsWith("hello") || p.startsWith("hi")) {
                        say("Hello! How can I help you?")
                        return@execute
                    }
                    val message = extractMessage(raw)
                    if (message.isNotEmpty()) {
                        say(message)
                    } else {
                        ui { showStatus("No action matched.\n$raw") }
                        Speaker.speak(applicationContext, "Sorry, I can't do that yet.")
                    }
                    return@execute
                }

                for (c in calls) {
                    if (c.name == "search_files") {
                        searchFlow(c.args.optString("query"))
                        continue
                    }
                    var announced = false
                    val r = actions.run(c) { m ->
                        announced = true
                        say(m)
                    }
                    if (!announced) say(r)
                }
            } catch (e: Throwable) {
                ui { showStatus("Error: ${e.message}") }
            } finally {
                busy = false
                ui {
                    if (!Speaker.isSpeaking) {
                        visualizer.mode = Visualizer.Mode.IDLE
                        voiceBtnLabel.text = "Tap to talk"
                    }
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
        ui { showStatus("Searching files for \"$query\"…") }
        val hits = actions.searchFiles(query)
        ui {
            for (f in hits) {
                val density = resources.displayMetrics.density
                val fileBtn = Button(this@Screen).apply {
                    text = "📄 ${f.name}\n${f.parent}"
                    textSize = 13f
                    isAllCaps = false
                    setTextColor(0xFF1F2937.toInt())
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(0xFFF1F3F4.toInt())
                        cornerRadius = 12f * density
                        setStroke((1 * density).toInt(), 0xFFE5E7EB.toInt())
                    }
                    val flp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (4 * density).toInt()
                        bottomMargin = (4 * density).toInt()
                    }
                    layoutParams = flp
                    setOnClickListener { actions.openFile(f) }
                }
                conversationContainer.addView(fileBtn)
            }
            conversationScroll.post {
                conversationScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
        say(if (hits.isEmpty()) {
            if (query.isBlank()) "I found no files on your storage."
            else "I found no files for $query."
        } else {
            "I found ${hits.size} files."
        })
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) listen()
        else showStatus("Microphone permission required")
    }

    override fun onDestroy() {
        Speaker.stop()
        super.onDestroy()
    }
}
