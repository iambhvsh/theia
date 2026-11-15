package `in`.iambhvsh.theia

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
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
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

/**
 * Theia: Minimalist Google Gemini Overlay Architecture.
 *
 * Designed per user specifications:
 * - By default: ONLY the clean floating bottom pill is shown (no response container by default).
 * - No plus icon and no icon beside mic.
 * - Response container and Minimise button appear ONLY when an actual AI response is generated.
 * - If no speech is heard ("I didn't hear anything"), it silently resets without showing any card.
 * - Instant speech cancellation as soon as the user closes or exits the sheet.
 */
class Screen : Activity() {
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var actions: Actions

    private lateinit var rootLayout: FrameLayout
    private lateinit var backdropView: View
    private lateinit var overlayContainer: LinearLayout
    private lateinit var settingsPage: ScrollView

    // Top Minimise Bar & Response / Greeting Card (hidden by default)
    private lateinit var topBar: LinearLayout
    private lateinit var responseCard: LinearLayout
    private lateinit var responseTextScroll: ScrollView
    private lateinit var responseTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var volumeBtn: ImageButton

    // Floating Context Row (optional)
    private lateinit var contextRow: LinearLayout

    // Floating Input Pill Elements (Clean: Text + Mic / Send only)
    private lateinit var inputCardContainer: FrameLayout
    private lateinit var collapsedInputRow: LinearLayout
    private lateinit var expandedInputCard: LinearLayout
    private lateinit var promptInput: EditText
    private lateinit var collapsedMicBtn: ImageButton
    private lateinit var expandedMicBtn: ImageButton
    private lateinit var expandedSendBtn: ImageButton

    @Volatile private var busy = false
    private var isExpanded = false
    private val currentSessionId = UUID.randomUUID().toString()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }

        actions = Actions(this)
        val density = resources.displayMetrics.density

        rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Semi-Transparent Scrim (tapping outside stops speech and dismisses overlay)
        backdropView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x77000000) // 45% soft dark dim
            setOnClickListener {
                Speaker.stop()
                if (isExpanded) {
                    collapseInput()
                } else {
                    finish()
                }
            }
        }
        rootLayout.addView(backdropView)

        // 2. Main Overlay Vertical Stack (Aligned to BOTTOM)
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

        // --- ROW A: Floating Top-Right "↳ Minimise" Button (Hidden by default) ---
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
                setColor(0xFF1E1F20.toInt()) // Google Dark Charcoal
                cornerRadius = 18f * density
            }
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            elevation = 6f * density

            val icon = ImageView(this@Screen).apply {
                setImageResource(R.drawable.ic_collapse)
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

        // --- ROW B: Floating Greeting / Response Card (Hidden by default) ---
        responseCard = buildResponseCard(density).apply {
            visibility = View.GONE // Hidden by default
        }
        overlayContainer.addView(responseCard)

        // --- ROW C: Floating Suggestion / Context Row (Hidden by default) ---
        contextRow = buildContextRow(density).apply {
            visibility = View.GONE // Hidden by default
        }
        overlayContainer.addView(contextRow)

        // --- ROW D: Floating Gemini Bottom Pill (Visible by default) ---
        inputCardContainer = buildInputCardContainer(density)
        overlayContainer.addView(inputCardContainer)

        rootLayout.addView(overlayContainer)

        // 3. Full-Page Settings View (Hidden by default)
        settingsPage = buildSettingsPage(density)
        rootLayout.addView(settingsPage)

        setContentView(rootLayout)

        // Wire speaker state into UI
        Speaker.onSpeakingStateChanged = { speaking ->
            ui {
                if (speaking) {
                    statusTextView.text = "Theia is speaking…"
                    statusTextView.visibility = View.VISIBLE
                    volumeBtn.setColorFilter(0xFF3084FF.toInt())
                } else if (!busy) {
                    statusTextView.visibility = View.GONE
                    volumeBtn.setColorFilter(0xFFC4C7C5.toInt())
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

        // Automatic listen on launch like Google Assistant / Gemini
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

    override fun onPause() {
        Speaker.stop()
        super.onPause()
    }

    override fun onStop() {
        Speaker.stop()
        super.onStop()
    }

    override fun onDestroy() {
        Speaker.stop()
        super.onDestroy()
    }

    override fun finish() {
        Speaker.stop()
        super.finish()
    }

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
     * Builds the Gemini response card (#131314 dark charcoal with drag handle and action toolbar).
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
        }

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        responseTextView = TextView(this).apply {
            text = ""
            textSize = 18f
            setTextColor(0xFFE3E3E3.toInt())
            setLineSpacing(0f, 1.25f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextIsSelectable(true)
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

        val thumbUpBtn = makeActionBtn(R.drawable.ic_thumb_up) {
            Toast.makeText(this, "Feedback received", Toast.LENGTH_SHORT).show()
        }
        val thumbDownBtn = makeActionBtn(R.drawable.ic_thumb_down) {
            Toast.makeText(this, "Feedback received", Toast.LENGTH_SHORT).show()
        }
        val shareBtn = makeActionBtn(R.drawable.ic_share) {
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
        val copyBtn = makeActionBtn(R.drawable.ic_content_copy) {
            val content = responseTextView.text.toString()
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cb?.setPrimaryClip(ClipData.newPlainText("Theia Response", content))
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        val moreVertBtn = makeActionBtn(R.drawable.ic_more_vert) {
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
                                showStatus("Conversation cleared")
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        toolbar.addView(thumbUpBtn)
        toolbar.addView(thumbDownBtn)
        toolbar.addView(shareBtn)
        toolbar.addView(copyBtn)
        toolbar.addView(moreVertBtn)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        toolbar.addView(spacer)

        volumeBtn = makeActionBtn(R.drawable.ic_volume_up) {
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
     * Optional floating context row.
     */
    private fun buildContextRow(density: Float): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (16 * density).toInt()
                marginEnd = (16 * density).toInt()
                bottomMargin = (10 * density).toInt()
            }
            layoutParams = lp
        }

        val shareScreenPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF1E1F20.toInt())
                cornerRadius = 19f * density
            }
            val slp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (38 * density).toInt()
            ).apply {
                marginEnd = (8 * density).toInt()
            }
            layoutParams = slp
            setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
            elevation = 4f * density

            val icon = ImageView(this@Screen).apply {
                setImageResource(R.drawable.ic_screen_share)
                setColorFilter(0xFFE3E3E3.toInt())
                val ilp = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt()).apply {
                    marginEnd = (8 * density).toInt()
                }
                layoutParams = ilp
            }

            val text = TextView(this@Screen).apply {
                this.text = "Share screen with Live"
                textSize = 13.5f
                setTextColor(0xFFE3E3E3.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }

            addView(icon)
            addView(text)
            setOnClickListener {
                Toast.makeText(this@Screen, "Sharing screen with Theia…", Toast.LENGTH_SHORT).show()
                listen()
            }
        }

        val photosBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_photos)
            setColorFilter(0xFFC4C7C5.toInt())
            background = createRipple(0xFF282A2C.toInt(), density)
            val plp = LinearLayout.LayoutParams((38 * density).toInt(), (38 * density).toInt()).apply {
                marginEnd = (8 * density).toInt()
            }
            layoutParams = plp
            elevation = 4f * density
            setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW).apply { type = "image/*" }
                try { startActivity(intent) } catch (_: Exception) { processQuery("search photos") }
            }
        }

        val cameraBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_camera)
            setColorFilter(0xFFC4C7C5.toInt())
            background = createRipple(0xFF282A2C.toInt(), density)
            val clp = LinearLayout.LayoutParams((38 * density).toInt(), (38 * density).toInt())
            layoutParams = clp
            elevation = 4f * density
            setOnClickListener {
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                try { startActivity(intent) } catch (_: Exception) { processQuery("open camera") }
            }
        }

        row.addView(shareScreenPill)
        row.addView(photosBtn)
        row.addView(cameraBtn)
        return row
    }

    /**
     * Builds the clean Google Gemini bottom pill:
     * - NO plus icon
     * - NO icon beside mic
     * - Only prompt text + Mic (or Send when typing)
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
                setColor(0xFF1E1F20.toInt()) // #1E1F20 Surface
                cornerRadius = 32f * density
            }
            elevation = 8f * density
        }

        // --- 1. COLLAPSED INPUT ROW: [ "Ask Gemini" ] ... [ Mic ] ---
        collapsedInputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val clp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (64 * density).toInt()
            )
            layoutParams = clp
            setPadding((24 * density).toInt(), 0, (14 * density).toInt(), 0)
        }

        // Center "Ask Gemini" Text / Tap Area
        val hintText = TextView(this).apply {
            text = "Ask Gemini"
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
        collapsedInputRow.addView(hintText)

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
            hint = "Ask Gemini"
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
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
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
        }
        expandedBottomRow.addView(expandedMicBtn)

        expandedSendBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send)
            setColorFilter(Color.WHITE)
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF3084FF.toInt()) // Gemini Electric Blue
            }
            val slp = LinearLayout.LayoutParams((38 * density).toInt(), (38 * density).toInt())
            layoutParams = slp
            setOnClickListener { submitPrompt() }
        }
        expandedBottomRow.addView(expandedSendBtn)

        expandedInputCard.addView(expandedBottomRow)
        container.addView(expandedInputCard)

        return container
    }

    private fun expandInput() {
        if (isExpanded) return
        isExpanded = true
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
    }

    private fun submitPrompt() {
        val q = promptInput.text.toString().trim()
        if (q.isNotEmpty()) {
            promptInput.setText("")
            collapseInput()
            processQuery(q)
        }
    }

    private fun showStatus(text: String) {
        statusTextView.text = text
        statusTextView.visibility = if (text.isNotBlank()) View.VISIBLE else View.GONE
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

    private fun showSettingsPage() {
        Speaker.stop()
        backdropView.visibility = View.GONE
        overlayContainer.visibility = View.GONE
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
        if (settingsPage.visibility == View.VISIBLE) {
            showOverlay()
        } else if (isExpanded) {
            collapseInput()
        } else {
            super.onBackPressed()
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
            setPadding(0, (8 * density).toInt(), 0, (6 * density).toInt())
        })

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
                        showStatus("Conversation memory cleared")
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
                setColor(0xFF3084FF.toInt()) // Gemini Electric Blue
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
                showOverlay()
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
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        ui {
            topBar.visibility = View.VISIBLE
            responseCard.visibility = View.VISIBLE
            contextRow.visibility = View.GONE
            responseTextView.text = trimmed
            showStatus("")
            responseTextScroll.post {
                responseTextScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
        Speaker.speak(applicationContext, trimmed)
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
            setMicActive(true)
        }

        exec.execute {
            try {
                Engine.ensureReady(applicationContext)

                val pcm = Recorder.capture(
                    maxSec = 15,
                    onRmsUpdate = { _ -> },
                    onSpeechDetected = {
                        ui {
                            setMicActive(true)
                        }
                    }
                )

                // If no audio was heard, silently reset without showing any card
                if (pcm.isEmpty()) {
                    return@execute
                }

                ui {
                    setMicActive(false)
                }

                val rawTranscribe = Engine.transcribe(pcm)
                android.util.Log.d("Theia", "Raw transcribe response: $rawTranscribe")
                val text = org.json.JSONObject(rawTranscribe).optString("text").trim()
                android.util.Log.d("Theia", "Extracted transcribed text: '$text'")

                // If transcription is empty, silently reset
                if (text.isEmpty()) {
                    return@execute
                }

                val prompt = cleanTranscript(text).ifEmpty { text }
                processQuery(prompt)

            } catch (e: Throwable) {
                ui {
                    showStatus("Error: ${e.message}")
                }
            } finally {
                busy = false
                ui {
                    setMicActive(false)
                }
            }
        }
    }

    private fun processQuery(prompt: String) {
        ui {
            topBar.visibility = View.VISIBLE
            responseCard.visibility = View.VISIBLE
            contextRow.visibility = View.GONE
            responseTextView.text = prompt
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
                    setMicActive(false)
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
