package `in`.iambhvsh.theia.audio

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class VoiceItem(
    val id: String,
    val label: String,
    val systemVoiceName: String,
    val requiresInternet: Boolean = systemVoiceName.endsWith("-network")
)

object Speaker {
    private const val TAG = "TheiaSpeaker"
    private const val PREFS = "groq_prefs"
    private const val KEY_PRESET = "voice_preset_id"

    val PRESETS = listOf(
        VoiceItem("emma", "Emma", "en-us-x-iog-network", requiresInternet = true),
        VoiceItem("ava", "Ava", "en-us-x-sfg-network", requiresInternet = true),
        VoiceItem("liam", "Liam", "en-us-x-tpd-network", requiresInternet = true),
        VoiceItem("oliver", "Oliver", "en-gb-x-rjs-network", requiresInternet = true),
        VoiceItem("chloe", "Chloe", "en-us-x-sfg-local", requiresInternet = false)
    )

    private var tts: TextToSpeech? = null
    @Volatile private var isReady = false
    private val initLatch = CountDownLatch(1)
    @Volatile private var activeLatch: CountDownLatch? = null
    @Volatile var isSpeaking: Boolean = false
        private set
    @Volatile var onSpeakingStateChanged: ((Boolean) -> Unit)? = null

    fun getPreset(ctx: Context): VoiceItem {
        val id = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PRESET, "emma") ?: "emma"
        val mappedId = when (id) {
            "emma" -> "emma"
            "ava", "female_warm" -> "ava"
            "liam", "male_warm", "male_deep", "noah", "lucas", "sophia" -> "liam"
            "oliver", "female_british" -> "oliver"
            "chloe", "chloe_offline", "offline_female", "leo_offline" -> "chloe"
            else -> "emma"
        }
        return PRESETS.firstOrNull { it.id == mappedId } ?: PRESETS[0]
    }

    fun setPreset(ctx: Context, presetId: String) {
        val p = PRESETS.firstOrNull { it.id == presetId } ?: PRESETS[0]
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRESET, p.id)
            .apply()

        tts?.let { applyVoice(it, p.systemVoiceName) }
    }

    @Synchronized
    fun load(ctx: Context) {
        if (tts != null) return
        Handler(Looper.getMainLooper()).post {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val t = tts
                    if (t != null) {
                        t.language = Locale.US
                        t.setSpeechRate(1.05f)
                        t.setPitch(1.0f)
                        val p = getPreset(ctx)
                        applyVoice(t, p.systemVoiceName)
                        isReady = true
                        Log.d(TAG, "Google System TTS initialized successfully with preset ${p.label}")
                    }
                } else {
                    Log.w(TAG, "Google System TTS init failed with status: $status")
                }
                initLatch.countDown()
            }
        }
    }

    private fun applyVoice(t: TextToSpeech, voiceName: String) {
        val voices = try { t.voices } catch (_: Throwable) { null } ?: return
        if (voices.isEmpty()) return

        val match = voices.firstOrNull { it.name.equals(voiceName, ignoreCase = true) }
        if (match != null) {
            t.voice = match
            Log.d(TAG, "Applied voice: ${match.name}")
            return
        }

        // If network voice not found, fall back to local version of same speaker
        if (voiceName.endsWith("-network")) {
            val localName = voiceName.removeSuffix("-network") + "-local"
            val localMatch = voices.firstOrNull { it.name.equals(localName, ignoreCase = true) }
            if (localMatch != null) {
                t.voice = localMatch
                Log.d(TAG, "Applied local speaker voice: ${localMatch.name}")
                return
            }
        }

        // Generic fallback: match any neural English voice or local voice
        val enVoices = voices.filter { it.locale.language.equals("en", ignoreCase = true) }
        val fallback = enVoices.firstOrNull { it.name.contains("network", ignoreCase = true) }
            ?: enVoices.firstOrNull()
        if (fallback != null) {
            t.voice = fallback
            Log.d(TAG, "Applied fallback voice: ${fallback.name}")
        }
    }

    /** Blocking. Never throws: a voice failure must not break actions. */
    fun speak(ctx: Context, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        load(ctx)
        if (!isReady) {
            try {
                initLatch.await(3, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {}
        }
        val t = tts
        if (t == null || !isReady) {
            Log.w(TAG, "TTS engine not ready")
            return
        }

        val clean = cleanText(trimmed)
        val utteranceId = "theia_${System.currentTimeMillis()}"
        val latch = CountDownLatch(1)
        activeLatch = latch

        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                isSpeaking = true
                onSpeakingStateChanged?.invoke(true)
                Log.d(TAG, "Playback started ($id)")
            }
            override fun onDone(id: String?) {
                isSpeaking = false
                onSpeakingStateChanged?.invoke(false)
                if (id == utteranceId) latch.countDown()
            }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(id: String?) {
                isSpeaking = false
                onSpeakingStateChanged?.invoke(false)
                if (id == utteranceId) latch.countDown()
            }
            override fun onError(id: String?, errorCode: Int) {
                isSpeaking = false
                onSpeakingStateChanged?.invoke(false)
                if (id == utteranceId) latch.countDown()
            }
        })

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }

        val startMs = System.currentTimeMillis()
        var res = t.speak(clean, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (res != TextToSpeech.SUCCESS && t.voice?.isNetworkConnectionRequired == true) {
            val localName = t.voice?.name?.removeSuffix("-network") + "-local"
            val localVoice = t.voices?.firstOrNull { it.name.equals(localName, ignoreCase = true) }
            if (localVoice != null) {
                t.voice = localVoice
                Log.w(TAG, "Network TTS failed, falling back to local: ${localVoice.name}")
                res = t.speak(clean, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            }
        }
        if (res == TextToSpeech.SUCCESS) {
            Log.d(TAG, "TTS queued in ${System.currentTimeMillis() - startMs} ms for '$clean'")
            try {
                latch.await(30, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {}
        } else {
            Log.w(TAG, "TTS speak returned error code: $res")
        }
    }

    private fun cleanText(text: String): String {
        return text
            .replace(Regex("[*#_`~]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun stop() {
        isSpeaking = false
        onSpeakingStateChanged?.invoke(false)
        activeLatch?.countDown()
        activeLatch = null
        try { tts?.stop() } catch (_: Throwable) {}
    }
}
