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
 * Audio waveform and sparkle indicator view.
 * Displays a 3-bar waveform during listening/speaking and a 4-point star while thinking.
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
    private val sparklePath = Path()

    // Shimmer gradient palette: Blue -> Violet -> Coral -> Cyan
    private val gradientColors = intArrayOf(
        0xFF4285F4.toInt(), // Blue
        0xFF9B72CB.toInt(), // Violet
        0xFFD96570.toInt(), // Coral
        0xFF24C1E0.toInt()  // Cyan
    )
    private val gradientPositions = floatArrayOf(0.0f, 0.33f, 0.66f, 1.0f)

    private var waveShader: LinearGradient? = null
    private var sparkleShader: LinearGradient? = null

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val density = resources.displayMetrics.density
        val cx = w / 2f
        val cy = h / 2f
        waveShader = LinearGradient(
            cx - 15f * density, cy - 16f * density,
            cx + 15f * density, cy + 16f * density,
            gradientColors,
            gradientPositions,
            Shader.TileMode.CLAMP
        )
        val maxRadius = 16f * density
        sparkleShader = LinearGradient(
            cx - maxRadius, cy - maxRadius,
            cx + maxRadius, cy + maxRadius,
            gradientColors,
            gradientPositions,
            Shader.TileMode.CLAMP
        )
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
                drawWaveform(canvas, cx, cy, density, elapsedSec)
            }
            Mode.THINKING -> {
                drawSparkle(canvas, cx, cy, density, elapsedSec)
            }
        }

        if (visibility == VISIBLE) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawWaveform(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        density: Float,
        elapsedSec: Float
    ) {
        val barCount = 3
        val barWidth = 3.2f * density
        val spacing = 7.0f * density
        val baseHeights = floatArrayOf(
            15f * density,
            24f * density,
            11f * density
        )

        wavePaint.shader = waveShader
        wavePaint.alpha = if (mode == Mode.IDLE) 180 else 255

        val totalWidth = (barCount - 1) * spacing
        val startX = cx - (totalWidth / 2f)

        for (i in 0 until barCount) {
            val baseH = baseHeights[i]
            val x = startX + i * spacing

            val currentHeight: Float
            when (mode) {
                Mode.IDLE -> {
                    val breathe = 1.0f + 0.08f * sin(elapsedSec * 2.0f + i * 0.8f)
                    currentHeight = baseH * breathe
                }
                Mode.LISTENING -> {
                    val wave = abs(sin(elapsedSec * 6.5f + i * 1.2f))
                    val audioBoost = dampedRms * 28f * density
                    currentHeight = (baseH * 0.7f + audioBoost * (0.6f + 0.4f * wave))
                        .coerceAtLeast(barWidth)
                }
                Mode.SPEAKING -> {
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

    private fun drawSparkle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        density: Float,
        elapsedSec: Float
    ) {
        canvas.save()

        val rotAngle = elapsedSec * 90f
        canvas.rotate(rotAngle, cx, cy)

        val pulse = 1.0f + 0.15f * sin(elapsedSec * 5f)
        val outerRadius = 13f * density * pulse
        val innerRadius = 3.5f * density * pulse

        sparklePaint.shader = sparkleShader

        sparklePath.reset()
        for (i in 0 until 8) {
            val angle = i * Math.PI.toFloat() / 4f
            val r = if (i % 2 == 0) outerRadius else innerRadius
            val x = cx + cos(angle) * r
            val y = cy + sin(angle) * r
            if (i == 0) sparklePath.moveTo(x, y) else sparklePath.lineTo(x, y)
        }
        sparklePath.close()

        canvas.drawPath(sparklePath, sparklePaint)
        canvas.restore()
    }
}
