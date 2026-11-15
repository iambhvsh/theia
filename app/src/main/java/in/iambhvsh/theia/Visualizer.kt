package `in`.iambhvsh.theia

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Authentic Google Gemini Live Waveform & Sparkle Visualizer.
 *
 * Reengineered directly from Google Gemini (`com.google.android.apps.bard`):
 * - Colors: Gemini Electric Blue (#3084FF) -> Iris Purple (#7C4DFF) -> Lilac (#CD9AEC)
 * - IDLE: 3-bar signature Gemini Live icon with organic breathing motion
 * - LISTENING: Dynamic Gemini Live waveform undulating to real-time voice RMS energy
 * - THINKING: Glowing rotating Gemini gradient sparkle
 * - SPEAKING: Rhythmic speech cadence waveform
 */
class Visualizer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode {
        IDLE,
        LISTENING,
        THINKING,
        SPEAKING
    }

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Gemini Brand Gradient: Blue -> Purple -> Lilac
    private val geminiColors = intArrayOf(
        0xFF3084FF.toInt(), // Electric Blue
        0xFF078EFB.toInt(), // Cyan
        0xFF7C4DFF.toInt(), // Iris Purple
        0xFFCD9AEC.toInt()  // Lilac
    )
    private val geminiPositions = floatArrayOf(0.0f, 0.3f, 0.7f, 1.0f)

    @Volatile
    var mode: Mode = Mode.IDLE
        set(value) {
            if (field != value) {
                field = value
                startTime = SystemClock.uptimeMillis()
                postInvalidateOnAnimation()
            }
        }

    private var targetRms: Float = 0f
    private var dampedRms: Float = 0f
    private var startTime: Long = SystemClock.uptimeMillis()

    fun setRms(rms: Float) {
        targetRms = rms.coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val density = resources.displayMetrics.density

        // Smooth exponential moving average for fluid audio response
        dampedRms = dampedRms * 0.7f + targetRms * 0.3f

        val elapsedSec = (SystemClock.uptimeMillis() - startTime) / 1000f

        when (mode) {
            Mode.IDLE, Mode.LISTENING, Mode.SPEAKING -> {
                drawGeminiWaveform(canvas, cx, cy, density, elapsedSec)
            }
            Mode.THINKING -> {
                drawGeminiSparkle(canvas, cx, cy, density, elapsedSec)
            }
        }

        // Keep animating smoothly at screen refresh rate
        postInvalidateOnAnimation()
    }

    /**
     * Draws the signature Google Gemini Live 3-to-4 bar dynamic audio waveform.
     */
    private fun drawGeminiWaveform(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        density: Float,
        elapsedSec: Float
    ) {
        // Base bar heights: center bar is tallest, outer bars are stepped
        // Matching Gemini Live logo: left=16dp, center-left=24dp, center-right=24dp, right=14dp
        val barCount = 4
        val barWidth = 4f * density
        val spacing = 7.5f * density
        val baseHeights = floatArrayOf(
            12f * density,
            24f * density,
            20f * density,
            10f * density
        )

        // Linear gradient applied vertically
        wavePaint.shader = LinearGradient(
            cx - 20f * density, cy - 18f * density,
            cx + 20f * density, cy + 18f * density,
            geminiColors,
            geminiPositions,
            Shader.TileMode.CLAMP
        )

        val totalWidth = (barCount - 1) * spacing
        val startX = cx - (totalWidth / 2f)

        for (i in 0 until barCount) {
            val baseH = baseHeights[i]
            val x = startX + i * spacing

            val currentHeight: Float
            when (mode) {
                Mode.IDLE -> {
                    // Gentle, breathing floating motion
                    val breathe = 1.0f + 0.12f * sin(elapsedSec * 2.2f + i * 0.7f)
                    currentHeight = baseH * breathe
                }
                Mode.LISTENING -> {
                    // Dynamic live audio response: energetic undulation driven by RMS
                    val wave = abs(sin(elapsedSec * 6.5f + i * 1.1f))
                    val audioBoost = dampedRms * 28f * density
                    currentHeight = (baseH * 0.7f + audioBoost * (0.6f + 0.4f * wave))
                        .coerceAtLeast(barWidth)
                }
                Mode.SPEAKING -> {
                    // Speech cadence rhythm
                    val wave = abs(sin(elapsedSec * 7.5f + i * 0.9f))
                    currentHeight = (baseH * (0.8f + 0.6f * wave))
                        .coerceAtLeast(barWidth)
                }
                else -> currentHeight = baseH
            }

            val left = x - barWidth / 2f
            val right = x + barWidth / 2f
            val top = cy - currentHeight / 2f
            val bottom = cy + currentHeight / 2f
            val r = barWidth / 2f

            canvas.drawRoundRect(left, top, right, bottom, r, r, wavePaint)
        }
    }

    /**
     * Draws the rotating, pulsing Google Gemini 4-point Sparkle star during thinking/loading.
     */
    private fun drawGeminiSparkle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        density: Float,
        elapsedSec: Float
    ) {
        canvas.save()

        // Rotating gradient sparkle
        val rotAngle = elapsedSec * 90f // 90 deg / second
        canvas.rotate(rotAngle, cx, cy)

        val pulse = 1.0f + 0.15f * sin(elapsedSec * 5f)
        val outerRadius = 13f * density * pulse
        val innerRadius = 3.5f * density * pulse

        sparklePaint.shader = LinearGradient(
            cx - outerRadius, cy - outerRadius,
            cx + outerRadius, cy + outerRadius,
            geminiColors,
            geminiPositions,
            Shader.TileMode.CLAMP
        )

        // Construct 4-point star path (Gemini sparkle geometry)
        val path = Path()
        for (i in 0 until 8) {
            val angle = i * Math.PI.toFloat() / 4f
            val r = if (i % 2 == 0) outerRadius else innerRadius
            val x = cx + cos(angle) * r
            val y = cy + sin(angle) * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        canvas.drawPath(path, sparklePaint)
        canvas.restore()
    }
}
