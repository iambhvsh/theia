package `in`.iambhvsh.theia.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.SweepGradient
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * Screen perimeter activation glow rendered via AGSL RuntimeShader (Android 13+).
 * Computes boundary distance using a rounded-box signed distance field.
 */
class Shimmer(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    // --- AGSL Shader Definition (Android 13+ / API 33+) ---
    companion object {
        private const val GEMINI_AGSL = """
            uniform float2 iResolution;
            uniform float4 iCornerRadii; // topRight, bottomRight, topLeft, bottomLeft (in px)
            uniform float iTime;
            uniform float iIntensity;     // 0.0 (hidden) -> 1.0 (visible)

            // Gradient palette
            const float3 COLOR_BLUE   = float3(0.259, 0.522, 0.957);
            const float3 COLOR_VIOLET = float3(0.608, 0.447, 0.796);
            const float3 COLOR_CORAL  = float3(0.851, 0.396, 0.439);
            const float3 COLOR_CYAN   = float3(0.141, 0.757, 0.878);

            // Inigo Quilez Signed Distance Function for a rounded rectangle with per-corner radii
            float sdRoundBox(float2 p, float2 b, float4 r) {
                r.xy = (p.x > 0.0) ? r.xy : r.zw;
                r.x  = (p.y > 0.0) ? r.x  : r.y;
                float2 q = abs(p) - b + r.x;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r.x;
            }

            half4 main(float2 fragCoord) {
                float2 halfRes = iResolution * 0.5;
                float2 centeredCoord = fragCoord - halfRes;

                // Distance inside screen boundary
                float distFromEdge = -sdRoundBox(centeredCoord, halfRes, iCornerRadii);

                // Thicker glow near bottom
                float verticalBias = smoothstep(0.0, 1.0, fragCoord.y / iResolution.y);

                float angle = atan(centeredCoord.y, centeredCoord.x);

                // Wave distortion
                float wave1 = sin(angle * 3.0 + iTime * 2.4) * 0.5 + 0.5;
                float wave2 = cos(angle * 5.0 - iTime * 1.7) * 0.5 + 0.5;
                float organicWave = (wave1 * 0.65 + wave2 * 0.35);

                // Dynamic glow thickness
                float minDim = min(iResolution.x, iResolution.y);
                float baseThickness = minDim * mix(0.024, 0.052, verticalBias);
                float totalThickness = (baseThickness + (minDim * 0.016 * organicWave));

                // Falloff toward center
                float d = max(distFromEdge, 0.0);
                float retract = max(pow(iIntensity, 1.6), 0.001);
                float coreGlow = exp(-d / max(totalThickness * 0.35 * retract, 0.8));
                float softBloom = exp(-d / max(totalThickness * 1.45 * retract, 1.5)) * (iIntensity * iIntensity);
                float combinedGlow = (coreGlow * 0.88 + softBloom * 0.44) * iIntensity;

                // Color sweep around perimeter
                float phase = fract((angle / 6.2831853) + (iTime * 0.18));
                float3 color = mix(COLOR_BLUE, COLOR_VIOLET, smoothstep(0.00, 0.25, phase));
                color = mix(color, COLOR_CORAL,  smoothstep(0.25, 0.50, phase));
                color = mix(color, COLOR_CYAN,   smoothstep(0.50, 0.75, phase));
                color = mix(color, COLOR_BLUE,   smoothstep(0.75, 1.00, phase));

                // Edge highlight
                color = mix(color, float3(1.0, 1.0, 1.0), coreGlow * 0.38 * iIntensity);

                float alpha = clamp(combinedGlow, 0.0, 1.0);
                return half4(half3(color * alpha), half(alpha));
            }
        """
    }

    private var runtimeShader: RuntimeShader? = null
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Legacy fallback fields for API < 33
    private val fallbackPath = Path()
    private val fallbackMeasure = PathMeasure()
    private var fallbackShader: SweepGradient? = null
    private val fallbackMatrix = Matrix()
    private val fallbackAmbientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fallbackHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fallbackCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    // Dynamic State
    var intensity: Float = 0f
        private set

    private var timeSeconds: Float = 0f
    private var loopAnimator: ValueAnimator? = null
    private var intensityAnimator: ValueAnimator? = null

    var onSettleCallback: (() -> Unit)? = null
    private var settleFired = false

    private val autoStopRunnable = Runnable {
        stopShimmer()
    }

    init {
        isClickable = false
        isFocusable = false
        visibility = GONE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                runtimeShader = RuntimeShader(GEMINI_AGSL)
                shaderPaint.shader = runtimeShader
            } catch (e: Throwable) {
                android.util.Log.e("TheiaShimmer", "RuntimeShader compile failed, falling back: ${e.message}")
                runtimeShader = null
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        if (runtimeShader == null) {
            // Build legacy fallback path and gradient
            fallbackPath.reset()
            val corner = 40f * density
            val inset = 1f * density
            fallbackPath.addRoundRect(RectF(inset, inset, w - inset, h - inset), corner, corner, Path.Direction.CW)
            fallbackMeasure.setPath(fallbackPath, false)
            val colors = intArrayOf(
                0xFF4285F4.toInt(), 0xFF9B72CB.toInt(), 0xFFD96570.toInt(),
                0xFF24C1E0.toInt(), 0xFF4285F4.toInt()
            )
            fallbackShader = SweepGradient(w / 2f, h / 2f, colors, null)
            fallbackAmbientPaint.shader = fallbackShader
            fallbackHaloPaint.shader = fallbackShader
            fallbackCorePaint.shader = fallbackShader
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (intensity <= 0.002f || width <= 0 || height <= 0) return

        val rs = runtimeShader
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && rs != null) {
            // Extract exact hardware corner radii from WindowInsets (API 31+)
            val insets = rootWindowInsets
            val defaultRadius = 42f * density
            val tr = insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius?.toFloat() ?: defaultRadius
            val br = insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius?.toFloat() ?: defaultRadius
            val tl = insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: defaultRadius
            val bl = insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius?.toFloat() ?: defaultRadius

            rs.setFloatUniform("iResolution", width.toFloat(), height.toFloat())
            rs.setFloatUniform("iCornerRadii", tr, br, tl, bl)
            rs.setFloatUniform("iTime", timeSeconds)
            rs.setFloatUniform("iIntensity", intensity)

            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shaderPaint)
        } else {
            // Legacy rendering fallback
            val fb = fallbackShader ?: return
            fallbackMatrix.setRotate(timeSeconds * 60f, width / 2f, height / 2f)
            fb.setLocalMatrix(fallbackMatrix)

            fallbackAmbientPaint.strokeWidth = 32f * density
            fallbackAmbientPaint.alpha = (intensity * 60).toInt().coerceIn(0, 255)
            canvas.drawPath(fallbackPath, fallbackAmbientPaint)

            fallbackHaloPaint.strokeWidth = 14f * density
            fallbackHaloPaint.alpha = (intensity * 140).toInt().coerceIn(0, 255)
            canvas.drawPath(fallbackPath, fallbackHaloPaint)

            fallbackCorePaint.strokeWidth = 4f * density
            fallbackCorePaint.alpha = (intensity * 240).toInt().coerceIn(0, 255)
            canvas.drawPath(fallbackPath, fallbackCorePaint)
        }
    }

    fun startShimmer() {
        stopShimmer()
        removeCallbacks(autoStopRunnable)
        settleFired = false
        visibility = VISIBLE
        timeSeconds = 0f

        // Hide after 2 seconds
        postDelayed(autoStopRunnable, 2000L)

        loopAnimator = ValueAnimator.ofFloat(0f, 62.83185f).apply {
            duration = 20000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = null // Linear
            addUpdateListener {
                timeSeconds += 0.016f
                invalidate()
            }
        }
        loopAnimator?.start()

        // Fade in
        intensityAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320L
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                intensity = va.animatedValue as Float
                if (va.animatedFraction >= 0.75f && !settleFired) {
                    settleFired = true
                    onSettleCallback?.invoke()
                }
                invalidate()
            }
        }
        intensityAnimator?.start()
    }

    fun stopShimmer() {
        removeCallbacks(autoStopRunnable)
        intensityAnimator?.cancel()
        val current = intensity
        if (current <= 0.01f) {
            intensity = 0f
            visibility = GONE
            loopAnimator?.cancel()
            loopAnimator = null
            return
        }

        intensityAnimator = ValueAnimator.ofFloat(current, 0f).apply {
            duration = 320L
            interpolator = AccelerateInterpolator(1.4f)
            addUpdateListener { va ->
                intensity = va.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    intensity = 0f
                    visibility = GONE
                    loopAnimator?.cancel()
                    loopAnimator = null
                }
            })
        }
        intensityAnimator?.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(autoStopRunnable)
        loopAnimator?.cancel()
        loopAnimator = null
        intensityAnimator?.cancel()
    }
}
