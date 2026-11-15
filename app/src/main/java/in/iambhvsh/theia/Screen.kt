package `in`.iambhvsh.theia

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Theia: Google Assistant-grade Bottom Sheet UI.
 *
 * Reengineered to match the authentic Google Assistant experience:
 * - Native Material vector drawables for all actions (keyboard, mic, settings, close, send, stop)
 * - Authentic 4-color soundwave visualizer (Blue, Red, Yellow, Green)
 * - Minimalist, floating bottom voice plate
 * - Dual voice and text typing workflow
 * - "Hi, how can I help?" greeting with clean Material suggestion chips
 * - Conversational cards and expandable Settings page
 */
class Screen : Activity() {
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var actions: Actions

    private lateinit var rootLayout: FrameLayout
    private lateinit var backdropView: View
    private lateinit var sheetContainer: LinearLayout
    private lateinit var settingsPage: ScrollView

    // Google Assistant Bottom Plate Elements
    private lateinit var bottomPlate: LinearLayout
    private lateinit var visualizer: Visualizer
    private lateinit var keyboardBtn: ImageButton
    private lateinit var micBtn: ImageButton
    private lateinit var plateInputBar: LinearLayout
    private lateinit var plateInputField: EditText
    private lateinit var plateSendBtn: ImageButton

    // Conversation Surface
    private lateinit var conversationScroll: ScrollView
    private lateinit var conversationContainer: LinearLayout
    private lateinit var greetingView: LinearLayout
    private lateinit var transcriptionText: TextView
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

        // 1. Dismissable Semi-Transparent Scrim
        backdropView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x66000000) // 40% soft dim
            setOnClickListener { finish() }
        }
        rootLayout.addView(backdropView)

        // 2. Google Assistant Bottom Sheet
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
            elevation = 16f * density
            setPadding(pad, (10 * density).toInt(), pad, (16 * density).toInt())
            setOnClickListener {} // Prevent touch passthrough
        }

        // Top Drag Handle (36dp x 4dp centered rounded bar)
        val handleBar = View(this).apply {
            val hlp = LinearLayout.LayoutParams((36 * density).toInt(), (4 * density).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (10 * density).toInt()
            }
            layoutParams = hlp
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFFDADCE0.toInt())
                cornerRadius = 2f * density
            }
        }
        sheetContainer.addView(handleBar)

        // Top Header: Sparkle Logo + "Theia" + Settings & Close Vector Buttons
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (6 * density).toInt())
        }

        val sparkleLogo = ImageView(this).apply {
            setImageResource(R.drawable.ic_sparkle)
            val slp = LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt()).apply {
                marginEnd = (8 * density).toInt()
            }
            layoutParams = slp
        }
        val appTitle = TextView(this).apply {
            text = "Theia"
            textSize = 17f
            setTextColor(0xFF3C4043.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val settingsBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(0xFF5F6368.toInt())
            background = createCircularRipple(density)
            val lp = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt()).apply {
                marginEnd = (4 * density).toInt()
            }
            layoutParams = lp
            setOnClickListener { showSettingsPage() }
        }

        val closeBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(0xFF5F6368.toInt())
            background = createCircularRipple(density)
            val lp = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt())
            layoutParams = lp
            setOnClickListener { finish() }
        }

        topBar.addView(sparkleLogo)
        topBar.addView(appTitle)
        topBar.addView(settingsBtn)
        topBar.addView(closeBtn)
        sheetContainer.addView(topBar)

        // Conversation Surface Container
        conversationContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * density).toInt(), 0, (6 * density).toInt())
        }

        // Greeting View ("Hi, how can I help?" + Material suggestion chips)
        greetingView = buildGreetingView(density)
        conversationContainer.addView(greetingView)

        // Live transcription view (large, elegant Google Sans text)
        transcriptionText = TextView(this).apply {
            textSize = 21f
            setTextColor(0xFF202124.toInt())
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, (6 * density).toInt(), 0, (8 * density).toInt())
            visibility = View.GONE
        }
        conversationContainer.addView(transcriptionText)

        // Subtle status indicator
        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF70757A.toInt())
            setPadding(0, (2 * density).toInt(), 0, (4 * density).toInt())
            visibility = View.GONE
        }
        conversationContainer.addView(statusText)

        conversationScroll = ScrollView(this).apply {
            val clp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (210 * density).toInt()
            )
            layoutParams = clp
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(conversationContainer)
        }
        sheetContainer.addView(conversationScroll)

        // Google Assistant Floating Bottom Plate (Voice Visualizer & Controls)
        bottomPlate = buildBottomPlate(density)
        sheetContainer.addView(bottomPlate)

        rootLayout.addView(sheetContainer)

        // 3. Expandable Full-Page Settings
        settingsPage = buildSettingsPage(density, pad)
        rootLayout.addView(settingsPage)

        setContentView(rootLayout)

        // Connect speaking listener to update visualizer
        Speaker.onSpeakingStateChanged = { speaking ->
            ui {
                if (speaking) {
                    visualizer.mode = Visualizer.Mode.SPEAKING
                    statusText.text = "Speaking…"
                    statusText.visibility = View.VISIBLE
                } else if (!busy) {
                    visualizer.mode = Visualizer.Mode.IDLE
                    statusText.visibility = View.GONE
                }
            }
        }

        // Preload engine, speech, database asynchronously
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

    private fun createCircularRipple(density: Float): RippleDrawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFF1F3F4.toInt())
        }
        return RippleDrawable(ColorStateList.valueOf(0x33000000), bg, mask)
    }

    private fun buildGreetingView(density: Float): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (6 * density).toInt(), 0, (10 * density).toInt())
        }

        val greetingTitle = TextView(this).apply {
            text = "Hi, how can I help?"
            textSize = 24f
            setTextColor(0xFF202124.toInt())
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        layout.addView(greetingTitle)

        // Suggestion Chips (Google Assistant Style)
        val chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            val slp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (14 * density).toInt()
            }
            layoutParams = slp
        }
        val chipsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val suggestions = listOf(
            "Search files",
            "Set an alarm",
            "What can you do?",
            "Battery status"
        )

        for (item in suggestions) {
            val chip = Button(this).apply {
                text = item
                textSize = 13.5f
                isAllCaps = false
                setTextColor(0xFF3C4043.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(0xFFF8F9FA.toInt())
                    cornerRadius = 18f * density
                    setStroke((1 * density).toInt(), 0xFFDADCE0.toInt())
                }
                val blp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    (36 * density).toInt()
                ).apply {
                    marginEnd = (8 * density).toInt()
                }
                layoutParams = blp
                setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                setOnClickListener { processQuery(item) }
            }
            chipsContainer.addView(chip)
        }

        chipsScroll.addView(chipsContainer)
        layout.addView(chipsScroll)

        return layout
    }

    private fun buildBottomPlate(density: Float): LinearLayout {
        val plate = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6 * density).toInt()
            }
            layoutParams = lp
        }

        // Voice Controls Row (Keyboard Icon on Left, 4-Dots Visualizer in Center, Mic & Stop on Right)
        val voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val rlp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (56 * density).toInt()
            )
            layoutParams = rlp
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFFF8F9FA.toInt())
                cornerRadius = 28f * density
                setStroke((1 * density).toInt(), 0xFFE8EAED.toInt())
            }
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        }

        // Keyboard Icon Button (Left)
        keyboardBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_keyboard)
            setColorFilter(0xFF5F6368.toInt())
            background = createCircularRipple(density)
            val klp = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
            layoutParams = klp
            setOnClickListener { toggleTextMode(true) }
        }

        // Center: Animated 4-Color Dots Visualizer
        visualizer = Visualizer(this).apply {
            val vlp = LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f).apply {
                gravity = Gravity.CENTER
            }
            layoutParams = vlp
            setOnClickListener {
                if (Speaker.isSpeaking) {
                    Speaker.stop()
                    visualizer.mode = Visualizer.Mode.IDLE
                } else if (!busy) {
                    listen()
                }
            }
        }

        // Mic Button (Right)
        micBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(0xFF4285F4.toInt()) // Google Blue
            background = createCircularRipple(density)
            val mlp = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt()).apply {
                marginStart = (4 * density).toInt()
            }
            layoutParams = mlp
            setOnClickListener {
                if (Speaker.isSpeaking) {
                    Speaker.stop()
                    visualizer.mode = Visualizer.Mode.IDLE
                    statusText.visibility = View.GONE
                } else if (!busy) {
                    listen()
                }
            }
        }

        voiceRow.addView(keyboardBtn)
        voiceRow.addView(visualizer)
        voiceRow.addView(micBtn)

        // Text Input Bar (Revealed when tapping keyboard)
        plateInputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            val ilp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (56 * density).toInt()
            )
            layoutParams = ilp
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFFF1F3F4.toInt())
                cornerRadius = 28f * density
                setStroke((1 * density).toInt(), 0xFFDADCE0.toInt())
            }
            setPadding((16 * density).toInt(), 0, (6 * density).toInt(), 0)

            plateInputField = EditText(this@Screen).apply {
                hint = "Ask anything…"
                textSize = 15f
                setTextColor(0xFF202124.toInt())
                setHintTextColor(0xFF80868B.toInt())
                background = null
                imeOptions = EditorInfo.IME_ACTION_SEND
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEND) {
                        submitTypedQuery()
                        true
                    } else false
                }
            }

            // Switch back to voice button
            val backToVoiceBtn = ImageButton(this@Screen).apply {
                setImageResource(R.drawable.ic_mic)
                setColorFilter(0xFF5F6368.toInt())
                background = createCircularRipple(density)
                val blp = LinearLayout.LayoutParams((38 * density).toInt(), (38 * density).toInt())
                layoutParams = blp
                setOnClickListener { toggleTextMode(false) }
            }

            plateSendBtn = ImageButton(this@Screen).apply {
                setImageResource(R.drawable.ic_send)
                setColorFilter(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF1A73E8.toInt()) // Google Blue
                }
                val slp = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt()).apply {
                    marginStart = (6 * density).toInt()
                }
                layoutParams = slp
                setOnClickListener { submitTypedQuery() }
            }

            addView(plateInputField)
            addView(backToVoiceBtn)
            addView(plateSendBtn)
        }

        plate.addView(voiceRow)
        plate.addView(plateInputBar)

        return plate
    }

    private fun toggleTextMode(enableText: Boolean) {
        val voiceRow = bottomPlate.getChildAt(0)
        if (enableText) {
            voiceRow.visibility = View.GONE
            plateInputBar.visibility = View.VISIBLE
            plateInputField.requestFocus()
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(plateInputField, InputMethodManager.SHOW_IMPLICIT)
        } else {
            plateInputBar.visibility = View.GONE
            voiceRow.visibility = View.VISIBLE
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(plateInputField.windowToken, 0)
        }
    }

    private fun submitTypedQuery() {
        val q = plateInputField.text.toString().trim()
        if (q.isNotEmpty()) {
            plateInputField.setText("")
            toggleTextMode(false)
            processQuery(q)
        }
    }

    private fun showStatus(text: String) {
        statusText.text = text
        statusText.visibility = if (text.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun addMessageCard(sender: String, text: String) {
        val density = resources.displayMetrics.density
        greetingView.visibility = View.GONE
        transcriptionText.visibility = View.GONE

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
                cornerRadius = 18f * density
                if (isUser) {
                    setColor(0xFFE8F0FE.toInt()) // Google soft blue
                    setStroke((1 * density).toInt(), 0xFFD2E3FC.toInt())
                } else {
                    setColor(0xFFF8F9FA.toInt())
                    setStroke((1 * density).toInt(), 0xFFE8EAED.toInt())
                }
            }
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())

            val content = TextView(this@Screen).apply {
                this.text = text
                textSize = 16f
                setTextColor(0xFF202124.toInt())
                setLineSpacing(0f, 1.25f)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            addView(content)

            if (!isUser) {
                val badge = TextView(this@Screen).apply {
                    val p = Speaker.getPreset(this@Screen)
                    this.text = "${p.label} • Theia"
                    textSize = 11.5f
                    setTextColor(0xFF70757A.toInt())
                    setPadding(0, (6 * density).toInt(), 0, 0)
                }
                addView(badge)
            }
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
            setPadding(pad, (20 * density).toInt(), pad, (24 * density).toInt())
        }

        // Top Navigation Bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (18 * density).toInt())
        }

        val backBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(0xFF202124.toInt())
            background = createCircularRipple(density)
            val lp = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
            layoutParams = lp
            setOnClickListener { showBottomSheet() }
        }

        val pageTitle = TextView(this).apply {
            text = "Settings"
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(0xFF202124.toInt())
            setPadding((12 * density).toInt(), 0, 0, 0)
        }
        header.addView(backBtn)
        header.addView(pageTitle)
        layout.addView(header)

        // Groq API Key
        layout.addView(TextView(this).apply {
            text = "Groq API Key"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF3C4043.toInt())
            setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
        })

        val keyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFFF1F3F4.toInt())
                cornerRadius = 12f * density
            }
            setPadding((12 * density).toInt(), 0, (4 * density).toInt(), 0)
        }

        val keyInput = EditText(this).apply {
            hint = "gsk_..."
            transformationMethod = PasswordTransformationMethod.getInstance()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Groq.getApiKey(this@Screen))
            background = null
            textSize = 15f
            setTextColor(0xFF202124.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        var isKeyVisible = false
        val toggleKeyBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_visibility)
            setColorFilter(0xFF5F6368.toInt())
            background = createCircularRipple(density)
            val tlp = LinearLayout.LayoutParams((38 * density).toInt(), (38 * density).toInt())
            layoutParams = tlp
            setOnClickListener {
                isKeyVisible = !isKeyVisible
                if (isKeyVisible) {
                    keyInput.transformationMethod = null
                    setImageResource(R.drawable.ic_visibility_off)
                } else {
                    keyInput.transformationMethod = PasswordTransformationMethod.getInstance()
                    setImageResource(R.drawable.ic_visibility)
                }
                keyInput.setSelection(keyInput.text.length)
            }
        }
        keyRow.addView(keyInput)
        keyRow.addView(toggleKeyBtn)
        layout.addView(keyRow)

        // Model Input
        layout.addView(TextView(this).apply {
            text = "Real-time Model"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF3C4043.toInt())
            setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
        })
        val modelInput = EditText(this).apply {
            setText(Groq.getModel(this@Screen))
            textSize = 15f
            setTextColor(0xFF202124.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFFF1F3F4.toInt())
                cornerRadius = 12f * density
            }
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
        }
        layout.addView(modelInput)

        // Assistant Voice Selector
        layout.addView(TextView(this).apply {
            text = "Assistant Voice"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF3C4043.toInt())
            setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
        })

        var currentPreset = Speaker.getPreset(this)
        val presetLabels = Speaker.PRESETS.map { it.label }.toTypedArray()

        val voiceBtn = Button(this).apply {
            text = "Voice: ${currentPreset.label}"
            isAllCaps = false
            textSize = 15f
            setTextColor(0xFF202124.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFFF1F3F4.toInt())
                cornerRadius = 12f * density
                setStroke((1 * density).toInt(), 0xFFDADCE0.toInt())
            }
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
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
            text = "Preview Voice"
            isAllCaps = false
            textSize = 14f
            setTextColor(0xFF1A73E8.toInt())
            background = null
            setOnClickListener {
                exec.execute {
                    Speaker.speak(applicationContext, "Hello! I am Theia, your fast and natural voice assistant.")
                }
            }
        }
        layout.addView(testVoiceBtn)

        // Conversation Memory Management
        layout.addView(TextView(this).apply {
            text = "Conversation Memory"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF3C4043.toInt())
            setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
        })
        val clearHistoryBtn = Button(this).apply {
            text = "Clear History"
            isAllCaps = false
            textSize = 14f
            setTextColor(0xFFD93025.toInt()) // Material Red
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFFFCE8E6.toInt()) // Soft red tint
                cornerRadius = 12f * density
            }
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
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
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
            addMessageCard("assistant", text)
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
            greetingView.visibility = View.GONE
            transcriptionText.visibility = View.VISIBLE
            transcriptionText.text = "Listening…"
            transcriptionText.setTextColor(0xFF5F6368.toInt())
            visualizer.mode = Visualizer.Mode.LISTENING
            showStatus("Listening…")
        }

        exec.execute {
            try {
                Engine.ensureReady(applicationContext)

                val pcm = Recorder.capture(
                    maxSec = 15,
                    onRmsUpdate = { rms ->
                        ui { visualizer.setRms(rms) }
                    },
                    onSpeechDetected = {
                        ui {
                            visualizer.mode = Visualizer.Mode.LISTENING
                            transcriptionText.text = "Listening…"
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
                    transcriptionText.text = "Transcribing…"
                    showStatus("Transcribing…")
                }

                val rawTranscribe = Engine.transcribe(pcm)
                android.util.Log.d("Theia", "Raw transcribe response: $rawTranscribe")
                val text = org.json.JSONObject(rawTranscribe).optString("text").trim()
                android.util.Log.d("Theia", "Extracted transcribed text: '$text'")

                if (text.isEmpty()) {
                    say("I didn't catch that.")
                    return@execute
                }

                ui {
                    transcriptionText.text = text
                    transcriptionText.setTextColor(0xFF202124.toInt())
                }

                val prompt = cleanTranscript(text).ifEmpty { text }
                processQuery(prompt)

            } catch (e: Throwable) {
                ui {
                    showStatus("Error: ${e.message}")
                    visualizer.mode = Visualizer.Mode.IDLE
                }
            } finally {
                busy = false
                ui {
                    if (!Speaker.isSpeaking) {
                        visualizer.mode = Visualizer.Mode.IDLE
                    }
                }
            }
        }
    }

    private fun processQuery(prompt: String) {
        ui {
            addMessageCard("user", prompt)
            visualizer.mode = Visualizer.Mode.THINKING
            showStatus("Thinking…")
        }

        exec.execute {
            try {
                val db = Database.get(applicationContext)

                if (Groq.hasApiKey(applicationContext)) {
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
                    text = "${f.name}\n${f.parent}"
                    textSize = 13.5f
                    isAllCaps = false
                    setTextColor(0xFF1F2937.toInt())
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(0xFFF1F3F4.toInt())
                        cornerRadius = 14f * density
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
