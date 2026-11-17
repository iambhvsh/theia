package `in`.iambhvsh.theia.components

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
 * Reengineered directly from Google Gemini Live (`ls_gemini_live`):
 * - 3-bar signature Gemini Live icon in dark theme (#C4C7C5 in IDLE)
 * - Dynamic undulating gradient waveform (#3084FF Electric Blue -> #7C4DFF Iris Purple -> #CD9AEC Lilac) during LISTENING & SPEAKING
 * - Rotating pulsing Gemini 4-point sparkle during THINKING
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

    // Gemini Brand Gradient: Blue -> Iris -> Lilac
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
     * Draws the signature Google Gemini Live 3-bar dynamic audio waveform.
     */
    private fun drawGeminiWaveform(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        density: Float,
        elapsedSec: Float
    ) {
        // 3 bars matching Google Gemini Live icon:
        // Left: 14dp, Center: 24dp, Right: 10dp
        val barCount = 3
        val barWidth = 3.2f * density
        val spacing = 7.0f * density
        val baseHeights = floatArrayOf(
            15f * density,
            24f * density,
            11f * density
        )

        if (mode == Mode.IDLE) {
            wavePaint.shader = null
            wavePaint.color = 0xFFC4C7C5.toInt() // Exact Gemini theme icon grey
        } else {
            // Linear gradient applied vertically
            wavePaint.shader = LinearGradient(
                cx - 15f * density, cy - 16f * density,
                cx + 15f * density, cy + 16f * density,
                geminiColors,
                geminiPositions,
                Shader.TileMode.CLAMP
            )
        }

        val totalWidth = (barCount - 1) * spacing
        val startX = cx - (totalWidth / 2f)

        for (i in 0 until barCount) {
            val baseH = baseHeights[i]
            val x = startX + i * spacing

            val currentHeight: Float
            when (mode) {
                Mode.IDLE -> {
                    // Gentle breathing motion
                    val breathe = 1.0f + 0.08f * sin(elapsedSec * 2.0f + i * 0.8f)
                    currentHeight = baseH * breathe
                }
                Mode.LISTENING -> {
                    // Dynamic live audio response: energetic undulation driven by RMS
                    val wave = abs(sin(elapsedSec * 6.5f + i * 1.2f))
                    val audioBoost = dampedRms * 28f * density
                    currentHeight = (baseH * 0.7f + audioBoost * (0.6f + 0.4f * wave))
                        .coerceAtLeast(barWidth)
                }
                Mode.SPEAKING -> {
                    // Speech cadence rhythm
                    val wave = abs(sin(elapsedSec * 7.5f + i * 1.0f))
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
