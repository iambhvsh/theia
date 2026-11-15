package `in`.iambhvsh.theia

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Authentic Google Assistant 4-Colored Soundwave Visualizer.
 *
 * Implements Google Assistant's signature motion design:
 * - IDLE: 4 resting dots with subtle breathing scale
 * - LISTENING: 4 vertical rounded capsule bars (Blue, Red, Yellow, Green) that elongate
 *   and bounce in a travelling sine wave dynamically driven by voice RMS energy
 * - THINKING: 4-dot rotating orbital circle
 * - SPEAKING: Equalizer voice cadence wave
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

    // Google Assistant Core Brand Colors: Blue, Red, Yellow, Green
    private val paintBlue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4285F4.toInt() }
    private val paintRed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEA4335.toInt() }
    private val paintYellow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFBBC05.toInt() }
    private val paintGreen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF34A853.toInt() }

    private val paints = arrayOf(paintBlue, paintRed, paintYellow, paintGreen)

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

        // Smooth exponential moving average for fluid, natural audio response
        dampedRms = dampedRms * 0.7f + targetRms * 0.3f

        val elapsedSec = (SystemClock.uptimeMillis() - startTime) / 1000f
        val dotRadius = 4f * density
        val dotSpacing = 16f * density

        when (mode) {
            Mode.IDLE -> {
                // 4 resting dots with subtle breathing scale
                val breathe = 1.0f + 0.08f * sin(elapsedSec * 2.5f)
                val r = dotRadius * breathe
                for (i in 0 until 4) {
                    val x = cx + (i - 1.5f) * dotSpacing
                    canvas.drawCircle(x, cy, r, paints[i])
                }
            }

            Mode.LISTENING -> {
                // Authentic Google Assistant vertical capsule sound bars
                val barWidth = 7f * density
                val baseHeight = barWidth
                // Bars stretch vertically based on sound energy + wave oscillation
                val maxExtraHeight = (6f + dampedRms * 26f) * density
                val freq = 2.4f

                for (i in 0 until 4) {
                    val phase = i * (Math.PI / 2.0).toFloat()
                    val wave = abs(sin(elapsedSec * (2f * Math.PI.toFloat() * freq) + phase))
                    val currentHeight = baseHeight + wave * maxExtraHeight
                    val x = cx + (i - 1.5f) * dotSpacing
                    val left = x - barWidth / 2f
                    val right = x + barWidth / 2f
                    val top = cy - currentHeight / 2f
                    val bottom = cy + currentHeight / 2f
                    val cornerR = barWidth / 2f

                    canvas.drawRoundRect(left, top, right, bottom, cornerR, cornerR, paints[i])
                }
            }

            Mode.THINKING -> {
                // Google Assistant rotating orbital circle
                val orbitRadius = 11f * density
                val rotSpeed = 3.6f
                for (i in 0 until 4) {
                    val angle = elapsedSec * rotSpeed + (i * Math.PI.toFloat() / 2f)
                    val x = cx + cos(angle) * orbitRadius
                    val y = cy + sin(angle) * orbitRadius
                    val pulse = 1.0f + 0.15f * sin(elapsedSec * 6f + i)
                    canvas.drawCircle(x, y, dotRadius * pulse, paints[i])
                }
            }

            Mode.SPEAKING -> {
                // Equalizer speech playback cadence wave
                val barWidth = 6.5f * density
                val baseHeight = barWidth
                val maxWave = 14f * density
                val freq = 3.2f

                for (i in 0 until 4) {
                    val phase = i * 0.9f
                    val wave = abs(sin(elapsedSec * freq * Math.PI.toFloat() + phase))
                    val currentHeight = baseHeight + wave * maxWave
                    val x = cx + (i - 1.5f) * dotSpacing
                    val left = x - barWidth / 2f
                    val right = x + barWidth / 2f
                    val top = cy - currentHeight / 2f
                    val bottom = cy + currentHeight / 2f
                    val cornerR = barWidth / 2f

                    canvas.drawRoundRect(left, top, right, bottom, cornerR, cornerR, paints[i])
                }
            }
        }

        // Keep animating smoothly at screen refresh rate
        postInvalidateOnAnimation()
    }
}
