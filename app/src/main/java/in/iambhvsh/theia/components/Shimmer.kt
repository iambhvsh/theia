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
 * Shimmer: Production AGSL GPU RuntimeShader recreation of Google Gemini's
 * authentic hands-free activation overlay and screen perimeter glow.
 *
 * Combines 2D Rounded-Box Signed Distance Field (SDF) geometry, hardware display
 * corner radius extraction, liquid-light wave turbulence, real-time voice amplitude
 * reactivity, and exponential dual-layer light bloom.
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
            uniform float iAudioLevel;    // 0.0 (silence) -> 1.0 (loud speech)
            uniform float iThinking;      // 0.0 (listening) -> 1.0 (processing state)

            // Authentic Google Gemini Palette
            const float3 GEMINI_BLUE   = float3(0.259, 0.522, 0.957); // #4285F4
            const float3 GEMINI_VIOLET = float3(0.608, 0.447, 0.796); // #9B72CB
            const float3 GEMINI_CORAL  = float3(0.851, 0.396, 0.439); // #D96570
            const float3 GEMINI_CYAN   = float3(0.141, 0.757, 0.878); // #24C1E0

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

                // Exact distance inside the curved screen boundary (0.0 at bezel, positive inward)
                float distFromEdge = -sdRoundBox(centeredCoord, halfRes, iCornerRadii);

                // Normalized vertical bias: thicker glow near bottom prompt sheet (0.0 top -> 1.0 bottom)
                float verticalBias = smoothstep(0.0, 1.0, fragCoord.y / iResolution.y);

                // Polar angle around screen center for seamless orbital wrapping
                float angle = atan(centeredCoord.y, centeredCoord.x);

                // Multi-harmonic liquid light wave distortion
                float speed = mix(2.4, 6.5, iThinking);
                float wave1 = sin(angle * 3.0 + iTime * speed) * 0.5 + 0.5;
                float wave2 = cos(angle * 5.0 - iTime * (speed * 0.7)) * 0.5 + 0.5;
                float organicWave = (wave1 * 0.65 + wave2 * 0.35);

                // Dynamic glow thickness in pixels driven by bottom anchor + voice reactivity
                float minDim = min(iResolution.x, iResolution.y);
                float baseThickness = minDim * mix(0.024, 0.052, verticalBias);
                float audioBoost = minDim * 0.038 * iAudioLevel * organicWave;
                float totalThickness = (baseThickness + audioBoost + (minDim * 0.016 * organicWave));

                // Dual-layer exponential light falloff with edge retraction ("suction" effect into the bezel)
                float d = max(distFromEdge, 0.0);
                float retract = max(pow(iIntensity, 1.6), 0.001);
                float coreGlow = exp(-d / max(totalThickness * 0.35 * retract, 0.8));
                float softBloom = exp(-d / max(totalThickness * 1.45 * retract, 1.5)) * (iIntensity * iIntensity);
                float combinedGlow = (coreGlow * 0.88 + softBloom * 0.44) * iIntensity;

                // Smooth 4-color orbital sweep around the perimeter
                float phase = fract((angle / 6.2831853) + (iTime * mix(0.18, 0.55, iThinking)));
                float3 color = mix(GEMINI_BLUE, GEMINI_VIOLET, smoothstep(0.00, 0.25, phase));
                color = mix(color, GEMINI_CORAL,  smoothstep(0.25, 0.50, phase));
                color = mix(color, GEMINI_CYAN,   smoothstep(0.50, 0.75, phase));
                color = mix(color, GEMINI_BLUE,   smoothstep(0.75, 1.00, phase));

                // Subtle white-hot core highlight right along the physical glass edge
                color = mix(color, float3(1.0, 1.0, 1.0), coreGlow * 0.38 * iIntensity);

                float alpha = clamp(combinedGlow, 0.0, 1.0);
                // Premultiplied alpha output for clean additive blending
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
    var audioLevel: Float = 0f
        private set
    var isThinking: Boolean = false
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
            val tr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius?.toFloat() ?: (42f * density)
            } else 42f * density
            val br = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius?.toFloat() ?: (42f * density)
            } else 42f * density
            val tl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: (42f * density)
            } else 42f * density
            val bl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius?.toFloat() ?: (42f * density)
            } else 42f * density

            rs.setFloatUniform("iResolution", width.toFloat(), height.toFloat())
            rs.setFloatUniform("iCornerRadii", tr, br, tl, bl)
            rs.setFloatUniform("iTime", timeSeconds)
            rs.setFloatUniform("iIntensity", intensity)
            rs.setFloatUniform("iAudioLevel", audioLevel.coerceIn(0f, 1f))
            rs.setFloatUniform("iThinking", if (isThinking) 1f else 0f)

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

    /**
     * Activates the Gemini Edge Glow with a high-energy trigger surge,
     * settling into audio-reactive breathing, and cleanly auto-hiding after 2 seconds.
     */
    fun startShimmer() {
        stopShimmer()
        removeCallbacks(autoStopRunnable)
        settleFired = false
        visibility = VISIBLE
        timeSeconds = 0f
        audioLevel = 0f
        isThinking = false

        // Automatically hide and release GPU after 2 seconds from activation
        postDelayed(autoStopRunnable, 2000L)

        // Continuous high-performance time driver (60 - 120 FPS)
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

        // Three-Phase State Choreography:
        // 1. Trigger Surge: rapid inward expansion (0ms -> 300ms)
        // 2. Settles to perimeter breathing
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

    /**
     * Smoothly modulates glow thickness in real-time with microphone RMS energy
     * while active during the initial activation window.
     */
    fun setAudioLevel(normalizedLevel: Float) {
        if (visibility != VISIBLE || intensity <= 0.01f) return
        val target = normalizedLevel.coerceIn(0f, 1f)
        audioLevel = (audioLevel * 0.6f) + (target * 0.4f)
        invalidate()
    }

    /**
     * Updates thinking mode if the gradient is active.
     */
    fun setThinking(thinking: Boolean) {
        isThinking = thinking
        if (visibility == VISIBLE && intensity > 0.01f) {
            invalidate()
        }
    }

    /**
     * Gracefully fades out the edge glow with an accelerating edge suction effect
     * that retracts the light right into the bezel, releasing the GPU loop.
     */
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
