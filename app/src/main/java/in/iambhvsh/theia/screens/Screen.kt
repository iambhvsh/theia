package `in`.iambhvsh.theia.screens

import `in`.iambhvsh.theia.R
import `in`.iambhvsh.theia.components.Shimmer
import `in`.iambhvsh.theia.components.Visualizer
import `in`.iambhvsh.theia.tools.Actions
import `in`.iambhvsh.theia.tools.Call
import `in`.iambhvsh.theia.data.Database
import `in`.iambhvsh.theia.data.Message
import `in`.iambhvsh.theia.ai.Groq
import `in`.iambhvsh.theia.ai.Engine
import `in`.iambhvsh.theia.audio.Recorder
import `in`.iambhvsh.theia.audio.Speaker
import `in`.iambhvsh.theia.audio.VoiceItem
import `in`.iambhvsh.theia.tools.Parser

import android.Manifest
import android.animation.LayoutTransition
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.UUID
import java.util.concurrent.Executors

class Screen : Activity() {
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var actions: Actions

    private lateinit var rootLayout: FrameLayout
    private lateinit var backdropView: View
    private lateinit var shimmerView: Shimmer
    private lateinit var overlayContainer: LinearLayout
    private lateinit var settingsPage: ScrollView

    // Top Minimise Bar & Response / Greeting Card
    private lateinit var topBar: LinearLayout
    private lateinit var responseCard: LinearLayout
    private lateinit var responseTextScroll: ScrollView
    private lateinit var responseTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var volumeBtn: ImageButton

    // Visualizers
    private lateinit var collapsedVisualizer: Visualizer
    private lateinit var expandedVisualizer: Visualizer

    // Floating Input Pill
    private lateinit var inputCardContainer: FrameLayout
    private lateinit var collapsedInputRow: LinearLayout
    private lateinit var pillStatusText: TextView
    private lateinit var expandedInputCard: LinearLayout
    private lateinit var promptInput: EditText
    private lateinit var collapsedMicBtn: ImageButton
    private lateinit var expandedMicBtn: ImageButton
    private lateinit var expandedSendBtn: ImageButton

    @Volatile private var busy = false
    @Volatile private var isThinkingQuery = false
    private var isExpanded = false
    private val currentSessionId = UUID.randomUUID().toString()

    @Suppress("DEPRECATION")
    private fun disableActivityTransitions(isClosing: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = if (isClosing) OVERRIDE_TRANSITION_CLOSE else OVERRIDE_TRANSITION_OPEN
            overrideActivityTransition(type, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(b: Bundle?) {
        disableActivityTransitions(isClosing = false)
        super.onCreate(b)

        // Clear previous conversation sessions on launch to keep each session isolated
        runCatching {
            Database.get(applicationContext).history().clearOldSessions(currentSessionId)
        }

        window.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = android.graphics.Color.TRANSPARENT
            navigationBarColor = android.graphics.Color.TRANSPARENT
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setDecorFitsSystemWindows(false)
            }
        }

        actions = Actions(this)
        val density = resources.displayMetrics.density

        rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Semi-Transparent Scrim
        backdropView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x77000000) // 45% soft dark dim
            setOnClickListener {
                Speaker.stop()
                Recorder.stop()
                shimmerView.stopShimmer()
                if (isExpanded) {
                    collapseInput()
                } else {
                    finish()
                }
            }
        }
        rootLayout.addView(backdropView)

        // Edge shimmer view
        shimmerView = Shimmer(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            onSettleCallback = {
                overlayContainer.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(260L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
        rootLayout.addView(shimmerView)

        // 2. Main Overlay Vertical Stack
        overlayContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            layoutParams = lp
            layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
            }
            setPadding(0, 0, 0, (14 * density).toInt())
            setOnClickListener {} // Prevent touch pass-through to backdrop
            translationY = 14f * density
            alpha = 0f
        }

        // Handle navigation bar and keyboard insets
        rootLayout.setOnApplyWindowInsetsListener { _, insets ->
            val bottomInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                val nav = insets.getInsets(WindowInsets.Type.systemBars()).bottom
                maxOf(ime, nav)
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            overlayContainer.setPadding(0, 0, 0, bottomInset + (12 * density).toInt())
            insets
        }

        // --- ROW A: Floating Top-Right "↳ Minimise" Button ---
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (16 * density).toInt()
                bottomMargin = (10 * density).toInt()
            }
            layoutParams = lp
            visibility = View.GONE // Hidden by default
        }

        val minimiseBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF1E1F20.toInt()) // Dark Charcoal
                cornerRadius = 18f * density
            }
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            elevation = 6f * density

            val icon = ImageView(this@Screen).apply {
                setImageResource(resDrawable("ic_collapse"))
                setColorFilter(0xFFE3E3E3.toInt())
                val ilp = LinearLayout.LayoutParams((16 * density).toInt(), (16 * density).toInt()).apply {
                    marginEnd = (6 * density).toInt()
                }
                layoutParams = ilp
            }

            val text = TextView(this@Screen).apply {
                this.text = "Minimise"
                textSize = 14f
                setTextColor(0xFFE3E3E3.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }

            addView(icon)
            addView(text)
            setOnClickListener {
                Speaker.stop()
                finish()
            }
        }
        topBar.addView(minimiseBtn)
        overlayContainer.addView(topBar)

        // --- ROW B: Floating Greeting / Response Card ---
        responseCard = buildResponseCard(density).apply {
            visibility = View.GONE // Hidden by default
        }
        overlayContainer.addView(responseCard)

        // Bottom input pill container
        inputCardContainer = buildInputCardContainer(density)
        overlayContainer.addView(inputCardContainer)

        rootLayout.addView(overlayContainer)

        // 3. Full-Page Settings View
        settingsPage = buildSettingsPage(density)
        rootLayout.addView(settingsPage)

        setContentView(rootLayout)

        // Wire speaker state into UI
        Speaker.onSpeakingStateChanged = { speaking ->
            ui {
                if (speaking) {
                    setPillStatus("Theia is speaking…")
                    volumeBtn.setColorFilter(0xFF3084FF.toInt())
                    setVisualizerMode(Visualizer.Mode.SPEAKING)
                } else {
                    volumeBtn.setColorFilter(0xFFC4C7C5.toInt())
                    if (!busy) {
                        setPillStatus(null)
                        setVisualizerMode(Visualizer.Mode.IDLE)
                    }
                }
            }
        }

        // Asynchronous init of AI Engine and database
        exec.execute {
            try {
                Engine.ensureReady(applicationContext)
                Speaker.load(applicationContext)
                Database.get(applicationContext).history().getRecent(1)
            } catch (e: Throwable) {
                ui { showStatus("Init: ${e.message}") }
            }
        }

        // Start listening on launch
        window.decorView.post {
            shimmerView.startShimmer()
            listen()
        }
        window.decorView.postDelayed({
            if (overlayContainer.alpha < 0.9f) {
                overlayContainer.animate().translationY(0f).alpha(1f).setDuration(250L).start()
            }
        }, 450L)

        intent.getStringExtra("speak")?.let {
            if (it.isNotBlank()) exec.execute { Speaker.speak(applicationContext, it) }
        }
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        intent = i
        responseCard.visibility = View.GONE
        topBar.visibility = View.GONE
        collapseInput()
        overlayContainer.translationY = 0f
        overlayContainer.alpha = 1f
        shimmerView.startShimmer()
        listen()
        i.getStringExtra("speak")?.let {
            if (it.isNotBlank()) exec.execute { Speaker.speak(applicationContext, it) }
        }
    }

    override fun onPause() {
        Speaker.stop()
        Recorder.stop()
        shimmerView.stopShimmer()
        setVisualizerMode(Visualizer.Mode.IDLE)
        super.onPause()
    }

    override fun onStop() {
        Speaker.stop()
        Recorder.stop()
        shimmerView.stopShimmer()
        setVisualizerMode(Visualizer.Mode.IDLE)
        super.onStop()
    }

    override fun onDestroy() {
        Speaker.stop()
        Recorder.stop()
        shimmerView.stopShimmer()
        setVisualizerMode(Visualizer.Mode.IDLE)
        runCatching {
            Database.get(applicationContext).history().clearSession(currentSessionId)
        }
        super.onDestroy()
    }

    override fun finish() {
        Speaker.stop()
        Recorder.stop()
        shimmerView.stopShimmer()
        setVisualizerMode(Visualizer.Mode.IDLE)
        runCatching {
            Database.get(applicationContext).history().clearSession(currentSessionId)
        }
        super.finish()
        disableActivityTransitions(isClosing = true)
    }

    private fun resDrawable(name: String): Int = resources.getIdentifier(name, "drawable", packageName)

    private fun createRipple(bgColor: Int = Color.TRANSPARENT, density: Float): RippleDrawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        val bg = if (bgColor != Color.TRANSPARENT) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bgColor)
            }
        } else null
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, mask)
    }

    /**
     * Builds the response card with drag handle and action toolbar.
     */
    private fun buildResponseCard(density: Float): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (16 * density).toInt()
                marginEnd = (16 * density).toInt()
                bottomMargin = (12 * density).toInt()
            }
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF131314.toInt()) // Deep Charcoal Surface
                cornerRadius = 28f * density
            }
            elevation = 8f * density
            setPadding((18 * density).toInt(), (10 * density).toInt(), (18 * density).toInt(), (12 * density).toInt())
        }

        // Drag handle (32dp x 3.5dp, #444746) - tap to dismiss card
        val handleBar = View(this).apply {
            val hlp = LinearLayout.LayoutParams((32 * density).toInt(), (3.5f * density).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (12 * density).toInt()
            }
            layoutParams = hlp
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF444746.toInt())
                cornerRadius = 2f * density
            }
            setOnClickListener {
                topBar.visibility = View.GONE
                responseCard.visibility = View.GONE
            }
        }
        card.addView(handleBar)

        // Main Response Text (Scrollable if long)
        responseTextScroll = ScrollView(this).apply {
            val slp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (6 * density).toInt()
            }
            layoutParams = slp
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            background = null
            isFocusable = false
            isFocusableInTouchMode = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                defaultFocusHighlightEnabled = false
            }
        }

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = null
            isFocusable = false
            isFocusableInTouchMode = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                defaultFocusHighlightEnabled = false
            }
        }

        responseTextView = TextView(this).apply {
            text = ""
            textSize = 17.5f
            setTextColor(0xFFE3E3E3.toInt())
            setLineSpacing(0f, 1.25f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            background = null
            highlightColor = Color.TRANSPARENT
            isFocusable = false
            isFocusableInTouchMode = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                defaultFocusHighlightEnabled = false
            }
        }
        textContainer.addView(responseTextView)

        statusTextView = TextView(this).apply {
            textSize = 12.5f
            setTextColor(0xFF8E918F.toInt())
            visibility = View.GONE
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        textContainer.addView(statusTextView)

        responseTextScroll.addView(textContainer)
        card.addView(responseTextScroll)

        // Bottom Action Toolbar: [ThumbUp, ThumbDown, Share, Copy, MoreVert] ... [Volume]
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val tlp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6 * density).toInt()
            }
            layoutParams = tlp
        }

        fun makeActionBtn(drawableRes: Int, onClick: () -> Unit): ImageButton {
            return ImageButton(this).apply {
                setImageResource(drawableRes)
                setColorFilter(0xFFC4C7C5.toInt())
                background = createRipple(Color.TRANSPARENT, density)
                val blp = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt()).apply {
                    marginEnd = (4 * density).toInt()
                }
                layoutParams = blp
                setOnClickListener { onClick() }
            }
        }

        val shareBtn = makeActionBtn(resDrawable("ic_share")) {
            val content = responseTextView.text.toString()
            if (content.isNotBlank()) {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, content)
                    type = "text/plain"
                }
                startActivity(Intent.createChooser(sendIntent, "Share with"))
            }
        }
        val copyBtn = makeActionBtn(resDrawable("ic_content_copy")) {
            val content = responseTextView.text.toString()
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cb?.setPrimaryClip(ClipData.newPlainText("Theia Response", content))
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        val moreVertBtn = makeActionBtn(resDrawable("ic_more_vert")) {
            val popup = PopupMenu(this@Screen, toolbar, Gravity.START)
            popup.menu.add(0, 1, 0, "Settings")
            popup.menu.add(0, 2, 1, "Clear conversation")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        showSettingsPage()
                        true
                    }
                    2 -> {
                        exec.execute {
                            Database.get(applicationContext).history().clear()
                            ui {
                                responseCard.visibility = View.GONE
                                topBar.visibility = View.GONE
                                responseTextView.text = ""
                                Toast.makeText(this@Screen, "Conversation cleared", Toast.LENGTH_SHORT).show()
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        toolbar.addView(shareBtn)
        toolbar.addView(copyBtn)
        toolbar.addView(moreVertBtn)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        toolbar.addView(spacer)

        volumeBtn = makeActionBtn(resDrawable("ic_volume_up")) {
            if (Speaker.isSpeaking) {
                Speaker.stop()
            } else {
                val textToSpeak = responseTextView.text.toString()
                if (textToSpeak.isNotBlank()) {
                    Speaker.speak(applicationContext, textToSpeak)
                }
            }
        }
        toolbar.addView(volumeBtn)

        card.addView(toolbar)
        return card
    }

    /**
     * Builds the bottom input pill:
     * Prompt text + mic icon (or send icon when typing).
     */
    private fun buildInputCardContainer(density: Float): FrameLayout {
        val container = FrameLayout(this).apply {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (16 * density).toInt()
                marginEnd = (16 * density).toInt()
            }
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF1E1F20.toInt())
                cornerRadius = 32f * density
            }
            elevation = 8f * density
        }

        // --- 1. Collapsed input row ---
        collapsedInputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val clp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (64 * density).toInt()
            )
            layoutParams = clp
            setPadding((24 * density).toInt(), 0, (14 * density).toInt(), 0)
            setOnClickListener {
                expandInput()
            }
        }

        // Center "Ask Theia" Text / Tap Area & Dynamic Status
        pillStatusText = TextView(this).apply {
            text = "Ask Theia"
            textSize = 17.5f
            setTextColor(0xFF8E918F.toInt())
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                expandInput()
            }
        }
        collapsedInputRow.addView(pillStatusText)

        // Right Mic Button (Alone, no icon beside it)
        collapsedMicBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(0xFFC4C7C5.toInt())
            background = createRipple(Color.TRANSPARENT, density)
            val mlp = LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt())
            layoutParams = mlp
            setOnClickListener {
                if (Speaker.isSpeaking) {
                    Speaker.stop()
                } else if (!busy) {
                    listen()
                }
            }
            setOnLongClickListener {
                showSettingsPage()
                true
            }
        }
        collapsedInputRow.addView(collapsedMicBtn)

        collapsedVisualizer = Visualizer(this).apply {
            val vlp = LinearLayout.LayoutParams((38 * density).toInt(), (38 * density).toInt()).apply {
                marginStart = (6 * density).toInt()
            }
            layoutParams = vlp
            setOnClickListener {
                if (Speaker.isSpeaking) {
                    Speaker.stop()
                } else if (!busy) {
                    listen()
                }
            }
        }
        collapsedInputRow.addView(collapsedVisualizer)
        container.addView(collapsedInputRow)

        // --- 2. EXPANDED INPUT CARD ---
        expandedInputCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val elp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (108 * density).toInt()
            )
            layoutParams = elp
            setPadding((20 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
            visibility = View.GONE
        }

        // Top Row: Text Input Field
        promptInput = EditText(this).apply {
            hint = "Ask Theia"
            textSize = 17f
            setTextColor(0xFFE3E3E3.toInt())
            setHintTextColor(0xFF8E918F.toInt())
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            val pilp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutParams = pilp
            gravity = Gravity.TOP or Gravity.START
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    submitPrompt()
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val hasText = !s.isNullOrBlank()
                    expandedSendBtn.visibility = if (hasText) View.VISIBLE else View.GONE
                    expandedMicBtn.visibility = if (hasText) View.GONE else View.VISIBLE
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        expandedInputCard.addView(promptInput)

        // Bottom Row: Spacer ... [ Mic ] or [ Send ] (No plus, no icon beside mic)
        val expandedBottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val blp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams = blp
        }

        val bottomSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        expandedBottomRow.addView(bottomSpacer)

        expandedMicBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(0xFFC4C7C5.toInt())
            background = createRipple(Color.TRANSPARENT, density)
            val mlp = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
            layoutParams = mlp
            setOnClickListener {
                if (Speaker.isSpeaking) {
                    Speaker.stop()
                } else if (!busy) {
                    listen()
                }
            }
            setOnLongClickListener {
                showSettingsPage()
                true
            }
        }
        expandedBottomRow.addView(expandedMicBtn)

        expandedSendBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send)
            setColorFilter(Color.WHITE)
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF3084FF.toInt())
            }
            val slp = LinearLayout.LayoutParams((38 * density).toInt(), (38 * density).toInt())
            layoutParams = slp
            setOnClickListener { submitPrompt() }
        }
        expandedBottomRow.addView(expandedSendBtn)

        expandedVisualizer = Visualizer(this).apply {
            val vlp = LinearLayout.LayoutParams((38 * density).toInt(), (38 * density).toInt()).apply {
                marginStart = (6 * density).toInt()
            }
            layoutParams = vlp
            setOnClickListener {
                if (Speaker.isSpeaking) {
                    Speaker.stop()
                } else if (!busy) {
                    listen()
                }
            }
        }
        expandedBottomRow.addView(expandedVisualizer)

        expandedInputCard.addView(expandedBottomRow)
        container.addView(expandedInputCard)

        return container
    }

    private fun expandInput() {
        if (isExpanded) return
        isExpanded = true
        Recorder.stop()
        shimmerView.stopShimmer()
        collapsedInputRow.visibility = View.GONE
        expandedInputCard.visibility = View.VISIBLE
        inputCardContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFF1E1F20.toInt())
            cornerRadius = 28f * resources.displayMetrics.density
        }
        promptInput.post {
            promptInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(promptInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun collapseInput() {
        if (!isExpanded) return
        isExpanded = false
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(promptInput.windowToken, 0)
        promptInput.clearFocus()
        expandedInputCard.visibility = View.GONE
        collapsedInputRow.visibility = View.VISIBLE
        inputCardContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFF1E1F20.toInt())
            cornerRadius = 32f * resources.displayMetrics.density
        }
        if (!Speaker.isSpeaking && !busy) {
            setPillStatus(null)
        }
    }

    private fun submitPrompt() {
        val q = promptInput.text.toString().trim()
        if (q.isNotEmpty()) {
            promptInput.setText("")
            collapseInput()
            processQuery(q)
        }
    }

    private fun setPillStatus(status: String? = null) {
        ui {
            if (::pillStatusText.isInitialized) {
                if (status.isNullOrBlank()) {
                    pillStatusText.text = "Ask Theia"
                    pillStatusText.setTextColor(0xFF8E918F.toInt())
                } else {
                    pillStatusText.text = status
                    pillStatusText.setTextColor(0xFFE3E3E3.toInt())
                }
            }
        }
    }

    private fun showStatus(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() ||
            trimmed.startsWith("Thinking", ignoreCase = true) ||
            trimmed.startsWith("Listening", ignoreCase = true) ||
            trimmed.startsWith("Theia is speaking", ignoreCase = true) ||
            trimmed.startsWith("Transcribing", ignoreCase = true)) {
            statusTextView.visibility = View.GONE
            return
        }
        statusTextView.text = trimmed
        statusTextView.visibility = View.VISIBLE
    }

    private fun setMicActive(active: Boolean) {
        val density = resources.displayMetrics.density
        if (active) {
            // Circular grey active mic plate with dark icon (Screenshot 2)
            collapsedMicBtn.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFA8AAAC.toInt())
            }
            collapsedMicBtn.setColorFilter(0xFF1E1F20.toInt())
            expandedMicBtn.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFA8AAAC.toInt())
            }
            expandedMicBtn.setColorFilter(0xFF1E1F20.toInt())
        } else {
            collapsedMicBtn.background = createRipple(Color.TRANSPARENT, density)
            collapsedMicBtn.setColorFilter(0xFFC4C7C5.toInt())
            expandedMicBtn.background = createRipple(Color.TRANSPARENT, density)
            expandedMicBtn.setColorFilter(0xFFC4C7C5.toInt())
        }
    }

    private fun setVisualizerMode(mode: Visualizer.Mode) {
        ui {
            if (::collapsedVisualizer.isInitialized) collapsedVisualizer.mode = mode
            if (::expandedVisualizer.isInitialized) expandedVisualizer.mode = mode
        }
    }

    private fun setVisualizerRms(rms: Float) {
        ui {
            if (::collapsedVisualizer.isInitialized) collapsedVisualizer.setRms(rms)
            if (::expandedVisualizer.isInitialized) expandedVisualizer.setRms(rms)
        }
    }

    private var settingsKeyInput: EditText? = null

    private fun showSettingsPage() {
        Speaker.stop()
        backdropView.visibility = View.GONE
        overlayContainer.visibility = View.GONE
        settingsKeyInput?.setText(Groq.getApiKey(this))
        settingsPage.visibility = View.VISIBLE
    }

    private fun showOverlay() {
        settingsPage.visibility = View.GONE
        backdropView.visibility = View.VISIBLE
        overlayContainer.visibility = View.VISIBLE
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        Speaker.stop()
        Recorder.stop()
        if (settingsPage.visibility == View.VISIBLE) {
            showOverlay()
        } else if (isExpanded) {
            collapseInput()
        } else {
            shimmerView.stopShimmer()
            super.onBackPressed()
            disableActivityTransitions(isClosing = true)
        }
    }

    private fun buildSettingsPage(density: Float): ScrollView {
        val pad = (18 * density).toInt()
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF131314.toInt()) // Dark theme settings
            visibility = View.GONE
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (24 * density).toInt(), pad, (28 * density).toInt())
        }

        // Top Navigation Bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (18 * density).toInt())
        }

        val backBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(0xFFE3E3E3.toInt())
            background = createRipple(Color.TRANSPARENT, density)
            val lp = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
            layoutParams = lp
            setOnClickListener { showOverlay() }
        }

        val pageTitle = TextView(this).apply {
            text = "Settings"
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(0xFFE3E3E3.toInt())
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
            setTextColor(0xFFC4C7C5.toInt())
            setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
        })

        layout.addView(TextView(this).apply {
            text = "Required for real-time reasoning, voice conversation, and phone tools. Get your free API key at console.groq.com"
            textSize = 12.5f
            setTextColor(0xFF8E918F.toInt())
            setPadding(0, 0, 0, (6 * density).toInt())
        })

        val openGroqBtn = TextView(this).apply {
            text = "Get free API key ↗"
            textSize = 13.5f
            setTextColor(0xFF3084FF.toInt())
            setPadding(0, 0, 0, (10 * density).toInt())
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
                } catch (_: Exception) {}
            }
        }
        layout.addView(openGroqBtn)

        val keyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF1E1F20.toInt())
                cornerRadius = 14f * density
            }
            setPadding((14 * density).toInt(), 0, (6 * density).toInt(), 0)
        }

        val keyInput = EditText(this).apply {
            hint = "gsk_..."
            setHintTextColor(0xFF8E918F.toInt())
            transformationMethod = PasswordTransformationMethod.getInstance()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Groq.getApiKey(this@Screen))
            background = null
            textSize = 15f
            setTextColor(0xFFE3E3E3.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        settingsKeyInput = keyInput

        var isKeyVisible = false
        val toggleKeyBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_visibility)
            setColorFilter(0xFFC4C7C5.toInt())
            background = createRipple(Color.TRANSPARENT, density)
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
            setTextColor(0xFFC4C7C5.toInt())
            setPadding(0, (18 * density).toInt(), 0, (6 * density).toInt())
        })
        val modelInput = EditText(this).apply {
            setText(Groq.getModel(this@Screen))
            textSize = 15f
            setTextColor(0xFFE3E3E3.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF1E1F20.toInt())
                cornerRadius = 14f * density
            }
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
        }
        layout.addView(modelInput)

        // Voice Selector
        layout.addView(TextView(this).apply {
            text = "Assistant Voice"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFC4C7C5.toInt())
            setPadding(0, (18 * density).toInt(), 0, (6 * density).toInt())
        })

        var currentPreset = Speaker.getPreset(this)
        val presetLabels = Speaker.PRESETS.map { it.label }.toTypedArray()

        val voiceBtn = Button(this).apply {
            text = "Voice: ${currentPreset.label}"
            isAllCaps = false
            textSize = 15f
            setTextColor(0xFFE3E3E3.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF1E1F20.toInt())
                cornerRadius = 14f * density
                setStroke((1 * density).toInt(), 0xFF282A2C.toInt())
            }
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
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
            setTextColor(0xFF3084FF.toInt())
            background = null
            setOnClickListener {
                exec.execute {
                    Speaker.speak(applicationContext, "Hello! I am Theia, your fast and natural voice assistant.")
                }
            }
        }
        layout.addView(testVoiceBtn)

        // Conversation Memory Clear
        layout.addView(TextView(this).apply {
            text = "Conversation Memory"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFC4C7C5.toInt())
            setPadding(0, (18 * density).toInt(), 0, (6 * density).toInt())
        })
        val clearHistoryBtn = Button(this).apply {
            text = "Clear History"
            isAllCaps = false
            textSize = 14f
            setTextColor(0xFFFF8B85.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF281E1E.toInt())
                cornerRadius = 14f * density
            }
            setOnClickListener {
                exec.execute {
                    Database.get(applicationContext).history().clear()
                    ui {
                        responseCard.visibility = View.GONE
                        topBar.visibility = View.GONE
                        responseTextView.text = ""
                        Toast.makeText(this@Screen, "Conversation cleared", Toast.LENGTH_SHORT).show()
                    }
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
                setColor(0xFF3084FF.toInt())
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
                if (k.isNotEmpty()) {
                    Groq.setApiKey(this@Screen, k)
                    Toast.makeText(this@Screen, "Settings saved", Toast.LENGTH_SHORT).show()
                } else {
                    Groq.clearApiKey(this@Screen)
                    Toast.makeText(this@Screen, "API key removed", Toast.LENGTH_SHORT).show()
                }
                Groq.setModel(this@Screen, m)
                Speaker.setPreset(this@Screen, currentPreset.id)
                showOverlay()
            }
        }
        layout.addView(saveBtn)

        scroll.addView(layout)
        return scroll
    }

    private fun ui(f: () -> Unit) = runOnUiThread(f)

    private fun isWhisperHallucination(text: String, durationSec: Float): Boolean {
        val norm = text.trim().lowercase().replace(Regex("[.!?,;:\"]"), "").trim()
        val phantomPhrases = setOf(
            "thank you", "thanks", "thank you very much", "thanks for watching",
            "thank you for watching", "please subscribe", "subtitles by",
            "you", "bye", "bye bye", "so", "yeah", "okay", "watching",
            "amara org", "amara", "silence", "music", "applause"
        )
        // If audio was brief (< 2.5s) and matches any hallucination phantom phrase
        if (durationSec < 2.5f && phantomPhrases.contains(norm)) {
            return true
        }
        // Always catch YouTube outro watermarks regardless of duration if there is no other text
        if (norm.startsWith("thanks for watching") || norm.startsWith("thank you for watching") ||
            norm.startsWith("please subscribe") || norm.startsWith("subtitles by") ||
            norm.contains("amara org")) {
            return true
        }
        return false
    }

    private fun cleanTranscript(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("^(?i)(hittia|hitia|heath\\s+here|hey\\s+theia|hey\\s+tia|hey\\s+thea|theia|tia|thea|hi\\s+theia|hey|hello\\s+theia|hello\\s+here)[,\\s]*"), "")
        s = s.replace(Regex("^(?i)here\\s+(?=(find|search|open|set|what|how|who|can)\\b)"), "")
        s = s.replace(Regex("^(?i)(?:then|can you|could you|please|will you|just)\\s+"), "")
        s = s.replace(Regex("^(?i)opening\\s+"), "open ")
        // Common ASR misrecognitions and speech splits for popular apps
        s = s.replace(Regex("(?i)\\b(?:in\\s+)?stagram\\b"), "instagram")
        s = s.replace(Regex("(?i)\\bwhats\\s+app\\b"), "whatsapp")
        s = s.replace(Regex("(?i)\\byou\\s+tube\\b"), "youtube")
        s = s.replace(Regex("(?i)\\bface\\s+book\\b"), "facebook")
        s = s.replace(Regex("(?i)\\btele\\s+gram\\b"), "telegram")
        return s.trim().removeSuffix(".")
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

    @Volatile private var shouldListenAfter = false

    private fun say(text: String, autoListen: Boolean? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        shouldListenAfter = autoListen ?: (trimmed.endsWith("?") || trimmed.endsWith("?\""))
        ui {
            isThinkingQuery = false
            shimmerView.setThinking(false)
            shimmerView.stopShimmer()
            topBar.visibility = View.VISIBLE
            responseCard.visibility = View.VISIBLE
            responseTextView.text = trimmed
            showStatus("")
            setVisualizerMode(Visualizer.Mode.SPEAKING)
            responseTextScroll.post {
                responseTextScroll.smoothScrollTo(0, responseTextView.bottom)
            }
        }
        Speaker.speak(applicationContext, trimmed)
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (busy) {
            Recorder.stop()
        }
        busy = true
        Speaker.stop()

        ui {
            setMicActive(true)
            setPillStatus("Listening…")
            setVisualizerMode(Visualizer.Mode.LISTENING)
        }

        exec.execute {
            try {
                Engine.ensureReady(applicationContext)

                val pcm = Recorder.capture(
                    maxSec = 15,
                    onRmsUpdate = { rms ->
                        shimmerView.setAudioLevel(rms)
                        setVisualizerRms(rms)
                    },
                    onSpeechDetected = {
                        ui {
                            setMicActive(true)
                            setPillStatus("Listening…")
                            setVisualizerMode(Visualizer.Mode.LISTENING)
                        }
                    }
                )

                // If no audio was heard, silently reset without showing any card
                if (pcm.isEmpty()) {
                    return@execute
                }

                ui {
                    setMicActive(false)
                    setPillStatus("Transcribing…")
                    setVisualizerMode(Visualizer.Mode.THINKING)
                }

                val rawTranscribe = Engine.transcribe(pcm)
                android.util.Log.d("Theia", "Raw transcribe response: $rawTranscribe")
                val text = org.json.JSONObject(rawTranscribe).optString("text").trim()
                android.util.Log.d("Theia", "Extracted transcribed text: '$text'")

                // If transcription is empty, silently reset
                if (text.isEmpty()) {
                    return@execute
                }

                val durationSec = pcm.size / 16000f
                if (isWhisperHallucination(text, durationSec)) {
                    android.util.Log.d("Theia", "Filtered Whisper silence hallucination: '$text' (${durationSec}s)")
                    return@execute
                }

                val prompt = cleanTranscript(text).ifEmpty { text }
                if (prompt.isEmpty() || prompt.matches(Regex("^[.,!?:;\\s]+$"))) {
                    return@execute
                }
                processQuery(prompt)

            } catch (e: Throwable) {
                ui {
                    showStatus("Error: ${e.message}")
                }
            } finally {
                busy = false
                ui {
                    setMicActive(false)
                    if (!Speaker.isSpeaking) {
                        setPillStatus(null)
                        setVisualizerMode(Visualizer.Mode.IDLE)
                    }
                    if (!isThinkingQuery) {
                        shimmerView.setThinking(false)
                        shimmerView.stopShimmer()
                    }
                }
            }
        }
    }

    private fun processQuery(rawPrompt: String) {
        val prompt = cleanTranscript(rawPrompt).ifEmpty { rawPrompt.trim() }
        isThinkingQuery = true
        ui {
            topBar.visibility = View.VISIBLE
            responseCard.visibility = View.VISIBLE
            responseTextView.text = prompt
            setPillStatus("Thinking…")
            shimmerView.setThinking(true)
            setVisualizerMode(Visualizer.Mode.THINKING)
        }

        exec.execute {
            try {
                // Instant fast-path: if user requested opening an installed app, launch immediately
                val directAppMatch = Regex("^(?i)(?:open|launch|start|run)\\s+([a-zA-Z0-9 ]+)").find(prompt)
                val isStoreOrWeb = prompt.contains("play store", ignoreCase = true) ||
                                   prompt.contains("store", ignoreCase = true) ||
                                   prompt.contains("site", ignoreCase = true) ||
                                   prompt.contains("website", ignoreCase = true) ||
                                   prompt.contains("web", ignoreCase = true) ||
                                   prompt.contains("browser", ignoreCase = true)
                if (directAppMatch != null && !isStoreOrWeb) {
                    val targetApp = directAppMatch.groupValues[1].trim()
                    var announced = false
                    val r = actions.openApp(targetApp) { m ->
                        announced = true
                        say(m)
                    }
                    if (r.startsWith("Opening ")) {
                        if (!announced) say(r)
                        return@execute
                    }
                    // If not installed on device, fall through to Groq / Assistant for intelligent handling
                }

                val db = Database.get(applicationContext)

                if (Groq.hasApiKey(applicationContext)) {
                    val history = db.history().getSession(currentSessionId, 10)

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

                            var needsFollowup = false

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
                                if (r.startsWith("Opening ") || r.startsWith("Timer:") || r.startsWith("Alarm:") || r.startsWith("Searching ")) {
                                    if (!announced) say(r)
                                } else {
                                    needsFollowup = true
                                }
                            }

                            if (needsFollowup) {
                                try {
                                    val followUpHistory = db.history().getSession(currentSessionId, 10)
                                    val followUpResp = Groq.complete(applicationContext, followUpHistory, "")
                                    if (followUpResp.toolCalls.isNotEmpty()) {
                                        for (fc in followUpResp.toolCalls) {
                                            actions.run(fc) { m -> say(m) }
                                        }
                                    } else if (!followUpResp.content.isNullOrBlank()) {
                                        db.history().insert(
                                            Message(
                                                sessionId = currentSessionId,
                                                role = "assistant",
                                                content = followUpResp.content
                                            )
                                        )
                                        say(followUpResp.content)
                                    } else {
                                        say("You don't have that app installed on your device. Would you like me to open it on the Play Store to download, or open its website?")
                                    }
                                } catch (_: Exception) {
                                    say("You don't have that app installed on your device. Would you like me to open it on the Play Store to download, or open its website?")
                                }
                            } else if (groqResp.content?.isNotBlank() == true) {
                                say(groqResp.content)
                            }
                            return@execute
                        } else if (!groqResp.content.isNullOrBlank()) {
                            // If model responded with plain text instead of calling open_app for an app request
                            val appMatch = Regex("^(?i)(?:open|launch|start|run)\\s+([a-zA-Z0-9 ]+)").find(prompt.trim().removeSuffix("."))
                            val isStoreOrWebPrompt = prompt.contains("play store", ignoreCase = true) ||
                                                     prompt.contains("store", ignoreCase = true) ||
                                                     prompt.contains("site", ignoreCase = true) ||
                                                     prompt.contains("website", ignoreCase = true) ||
                                                     prompt.contains("web", ignoreCase = true) ||
                                                     prompt.contains("browser", ignoreCase = true)
                            if (appMatch != null && !isStoreOrWebPrompt) {
                                val targetApp = appMatch.groupValues[1].trim()
                                var announced = false
                                val r = actions.openApp(targetApp) { m ->
                                    announced = true
                                    say(m)
                                }
                                if (r.startsWith("Opening ")) {
                                    if (!announced) say(r)
                                    return@execute
                                } else {
                                    val notFoundMsg = "You don't have $targetApp installed on your device. Would you like me to open it on the Play Store to download, or open its website?"
                                    db.history().insert(
                                        Message(
                                            sessionId = currentSessionId,
                                            role = "assistant",
                                            content = notFoundMsg
                                        )
                                    )
                                    say(notFoundMsg)
                                    return@execute
                                }
                            }
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
                        android.util.Log.e("Theia", "Groq request failed, falling back to local engine", e)
                        val err = e.message ?: ""
                        if (err.contains("401") || err.contains("invalid_api_key", ignoreCase = true)) {
                            ui {
                                statusTextView.visibility = View.GONE
                                responseTextView.text = "Your Groq API key appears to be invalid.\n\nTap here or long-press the mic to update it in Settings."
                                responseTextView.setOnClickListener { showSettingsPage() }
                                responseCard.visibility = View.VISIBLE
                                topBar.visibility = View.VISIBLE
                            }
                            say("Your Groq API key appears to be invalid. Please check it in Settings.")
                            return@execute
                        }
                        // Fall through to on-device local engine fallback below
                    }
                }

                // --- ON-DEVICE LOCAL ENGINE FALLBACK ---
                val p = prompt.lowercase()
                val prevAssistantMsg = db.history().getSession(currentSessionId, 4).lastOrNull { it.role == "assistant" }?.content ?: ""
                val prevTargetApp = Regex("(?i)don't have\\s+([a-zA-Z0-9 ]+?)\\s+installed").find(prevAssistantMsg)?.groupValues?.get(1)?.trim()

                // Check if user is asking about Play Store or downloading
                if (p.contains("play store") || p.contains("playstore") || p.contains("download") || p.contains("install")) {
                    var target = prompt.replace(Regex("(?i)\\b(?:open|launch|start|run|in|on|the|from|to)?\\s*(?:google\\s*)?play\\s*store\\b"), "")
                        .replace(Regex("(?i)\\b(?:download|install|get|it|yes|sure|please)\\b"), "")
                        .trim()
                    if (target.isEmpty() && !prevTargetApp.isNullOrEmpty()) {
                        target = prevTargetApp
                    }
                    if (target.isNotEmpty()) {
                        val r = actions.openPlayStore(target) { m -> say(m) }
                        say(r)
                        return@execute
                    }
                }

                // Check if user is asking about website or browser
                if (p.contains("website") || p.contains("site") || p.contains("browser") || p.contains("web")) {
                    var target = prompt.replace(Regex("(?i)\\b(?:open|launch|start|run|in|on|the|view)?\\s*(?:website|site|browser|web)\\b"), "")
                        .replace(Regex("(?i)\\b(?:it|yes|sure|please)\\b"), "")
                        .trim()
                    if (target.isEmpty() && !prevTargetApp.isNullOrEmpty()) {
                        target = prevTargetApp
                    }
                    if (target.isNotEmpty()) {
                        val url = if (target.contains(".")) target else "https://${target.lowercase().replace(" ", "")}.com"
                        val r = actions.openUrl(url) { m -> say(m) }
                        say(r)
                        return@execute
                    }
                }

                // Flashlight / torch
                if (p.contains("flashlight") || p.contains("torch") || p == "lumos" || p == "nox") {
                    val enable = !p.contains("off") && p != "nox"
                    val r = actions.toggleTorch(enable) { m -> say(m) }
                    say(r)
                    return@execute
                }

                // Media volume
                if (p.contains("volume") || p.contains("mute") || p.contains("unmute")) {
                    val isMute = p.contains("mute") && !p.contains("unmute")
                    val dir = if (p.contains("up") || p.contains("raise") || p.contains("increase") || p.contains("louder")) "up"
                              else if (p.contains("down") || p.contains("lower") || p.contains("decrease") || p.contains("quieter")) "down"
                              else null
                    val pctMatch = Regex("(\\d+)\\s*%").find(p)
                    val pct = pctMatch?.groupValues?.get(1)?.toIntOrNull()
                    val r = actions.adjustVolume(direction = dir, percent = pct, mute = if (isMute) true else null) { m -> say(m) }
                    say(r)
                    return@execute
                }

                // Settings
                if (p.contains("setting") || p.contains("settings") || p.contains("bluetooth") || p.contains("wifi") || p.contains("wi-fi")) {
                    val r = actions.openSettings(p) { m -> say(m) }
                    say(r)
                    return@execute
                }

                // Time, date & battery
                if (p.contains("what time") || p.contains("time is it") || p == "time") {
                    val timeStr = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date())
                    say("It's $timeStr.")
                    return@execute
                }
                if (p.contains("what day") || p.contains("what's today") || p.contains("what is today") || p.contains("today's date") || p.contains("what date")) {
                    val dateStr = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.US).format(java.util.Date())
                    say("Today is $dateStr.")
                    return@execute
                }
                if (p.contains("battery") || p.contains("charge") || p.contains("battery percentage")) {
                    val ifilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                    val bIntent = applicationContext.registerReceiver(null, ifilter)
                    val lvl = bIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = bIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                    val pct = if (lvl >= 0 && scale > 0) (lvl * 100 / scale) else -1
                    val status = bIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
                    val chargeMsg = if (isCharging) "and currently charging" else ""
                    if (pct >= 0) {
                        say("Your battery is at $pct percent $chargeMsg.".trim())
                    } else {
                        say("I couldn't read the battery level right now.")
                    }
                    return@execute
                }

                val appLaunch = Regex("^(?i)(?:open|launch|start|run)\\s+([a-zA-Z0-9 ]+)").find(prompt.removeSuffix("."))
                if (appLaunch != null) {
                    val target = appLaunch.groupValues[1].trim()
                    val r = actions.openApp(target)
                    if (r.startsWith("Opening ")) {
                        say(r)
                    } else {
                        val notFoundMsg = "You don't have $target installed on your device. Would you like me to open it on the Play Store to download, or open its website?"
                        db.history().insert(
                            Message(
                                sessionId = currentSessionId,
                                role = "assistant",
                                content = notFoundMsg
                            )
                        )
                        say(notFoundMsg)
                    }
                    return@execute
                }

                val raw = Engine.complete(prompt)
                android.util.Log.d("Theia", "Engine complete raw: '$raw'")
                val calls = Parser.calls(raw)
                if (calls.isEmpty()) {
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
                    } else if (!Groq.hasApiKey(applicationContext)) {
                        ui {
                            statusTextView.visibility = View.GONE
                            responseTextView.text = "Please add your Groq API key in Settings to talk with Theia.\n\nTap here or long-press the mic to open Settings."
                            responseTextView.setOnClickListener { showSettingsPage() }
                            responseCard.visibility = View.VISIBLE
                            topBar.visibility = View.VISIBLE
                        }
                        Speaker.speak(applicationContext, "Please add your Groq API key in Settings to talk with Theia.")
                    } else {
                        ui { statusTextView.visibility = View.GONE }
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
                    if (r.startsWith("Opening ") || r.startsWith("Timer:") || r.startsWith("Alarm:") || r.startsWith("Searching ")) {
                        if (!announced) say(r)
                    } else if (c.name == "open_app") {
                        val target = c.args.optString("name")
                        say("You don't have $target installed on your device. Would you like me to open it on the Play Store to download, or open its website?")
                    } else {
                        if (!announced) say(r)
                    }
                }
            } catch (e: Throwable) {
                ui { showStatus("Error: ${e.message}") }
            } finally {
                busy = false
                isThinkingQuery = false
                ui {
                    shimmerView.setThinking(false)
                    shimmerView.stopShimmer()
                    if (shouldListenAfter && !isFinishing && !isDestroyed) {
                        shouldListenAfter = false
                        listen()
                    } else {
                        setMicActive(false)
                        if (!Speaker.isSpeaking) {
                            setPillStatus(null)
                            setVisualizerMode(Visualizer.Mode.IDLE)
                        }
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
        val resultSummary = if (hits.isEmpty()) {
            if (query.isBlank()) "I found no files on your storage."
            else "I found no files for \"$query\"."
        } else {
            "I found ${hits.size} files:\n" + hits.take(5).joinToString("\n") { "• ${it.name}" }
        }
        say(resultSummary)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) listen()
        else showStatus("Microphone permission required")
    }
}
