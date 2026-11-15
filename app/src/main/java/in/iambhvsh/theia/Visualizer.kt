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
 * Animated Google Assistant 4-dots visualizer view.
 * Reengineered from Google Assistant's signature motion design:
 * - IDLE: 4 resting dots with subtle breathing scale
 * - LISTENING: Dynamic sine wave oscillation reacting to voice RMS energy
 * - THINKING: 4-dot rotating orbital circle
 * - SPEAKING: Cadence wave animation
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

        // Smoothly interpolate RMS for fluid motion
        dampedRms = dampedRms * 0.75f + targetRms * 0.25f

        val elapsedSec = (SystemClock.uptimeMillis() - startTime) / 1000f
        val baseRadius = 5f * density
        val dotSpacing = 14f * density

        when (mode) {
            Mode.IDLE -> {
                // 4 resting dots with gentle breathing pulse
                val breathe = 1.0f + 0.08f * sin(elapsedSec * 2.5f)
                val r = baseRadius * breathe
                for (i in 0 until 4) {
                    val x = cx + (i - 1.5f) * dotSpacing
                    canvas.drawCircle(x, cy, r, paints[i])
                }
            }

            Mode.LISTENING -> {
                // Google Assistant signature 4-dot bouncing sine wave
                val freq = 2.4f // Cycles per second
                val maxWaveAmp = (8f + dampedRms * 16f) * density
                val currentRadius = baseRadius + (dampedRms * 2.5f * density)

                for (i in 0 until 4) {
                    // 90 degree phase shift between dots creates the fluid wave
                    val phase = i * (Math.PI / 2.0).toFloat()
                    val wave = sin(elapsedSec * (2f * Math.PI.toFloat() * freq) + phase)
                    val x = cx + (i - 1.5f) * dotSpacing
                    val y = cy - (wave * maxWaveAmp)

                    canvas.drawCircle(x, y, currentRadius, paints[i])
                }
            }

            Mode.THINKING -> {
                // Google Assistant rotating orbital circle
                val orbitRadius = 12f * density
                val rotSpeed = 3.8f // Radians per sec
                for (i in 0 until 4) {
                    val angle = elapsedSec * rotSpeed + (i * Math.PI.toFloat() / 2f)
                    val x = cx + cos(angle) * orbitRadius
                    val y = cy + sin(angle) * orbitRadius
                    val pulse = 1.0f + 0.15f * sin(elapsedSec * 6f + i)
                    canvas.drawCircle(x, y, baseRadius * pulse, paints[i])
                }
            }

            Mode.SPEAKING -> {
                // Audio speech cadence wave
                val freq = 3.2f
                for (i in 0 until 4) {
                    val phase = i * 0.9f
                    val wave = abs(sin(elapsedSec * freq * Math.PI.toFloat() + phase))
                    val x = cx + (i - 1.5f) * dotSpacing
                    val yOffset = (wave - 0.5f) * 10f * density
                    val r = baseRadius * (0.85f + 0.35f * wave)
                    canvas.drawCircle(x, cy + yOffset, r, paints[i])
                }
            }
        }

        // Keep animating smoothly at screen refresh rate
        postInvalidateOnAnimation()
    }
}
