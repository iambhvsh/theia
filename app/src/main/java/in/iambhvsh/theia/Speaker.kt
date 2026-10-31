package `in`.iambhvsh.theia

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

// Theia's voice: Kitten TTS nano (Apache-2.0) via sherpa-onnx
object Speaker {
    private const val SID = 1  // expr-voice-2-f (female). Other female ids: 3, 5, 7
    private const val SPEED = 1.0f

    private var tts: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var cancelled = false

    @Synchronized
    fun load(ctx: Context): OfflineTts {
        tts?.let { return it }
        val dir = File(ctx.filesDir, "kitten")
        if (!File(dir, "model.fp16.onnx").exists()) {
            val tmp = File(ctx.filesDir, "kitten.tmp")
            tmp.deleteRecursively()
            copyAssets(ctx.assets, "kitten", tmp)
            dir.deleteRecursively()
            tmp.renameTo(dir)
        }
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kitten = OfflineTtsKittenModelConfig(
                    model = "$dir/model.fp16.onnx",
                    voices = "$dir/voices.bin",
                    tokens = "$dir/tokens.txt",
                    dataDir = "$dir/espeak-ng-data",
                ),
                numThreads = 2,
            )
        )
        return OfflineTts(config = cfg).also { tts = it }
    }

    private fun copyAssets(am: AssetManager, path: String, dst: File) {
        val kids = am.list(path) ?: emptyArray()
        if (kids.isEmpty()) {
            dst.parentFile?.mkdirs()
            am.open(path).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        } else {
            dst.mkdirs()
            kids.forEach { copyAssets(am, "$path/$it", File(dst, it)) }
        }
    }

    /** Blocking. Never throws: a voice failure must not break actions. */
    fun speak(ctx: Context, text: String) {
        if (text.isBlank()) return
        try {
            val t = load(ctx)
            cancelled = false
            val audio = t.generate(text.take(400), SID, SPEED)
            if (!cancelled) play(audio.samples, audio.sampleRate)
        } catch (e: Throwable) {
            Log.w("Theia", "speak failed", e)
        }
    }

    fun stop() {
        cancelled = true
        try { track?.stop() } catch (_: Throwable) {}
    }

    private fun play(s: FloatArray, sr: Int) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sr)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(s.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = t
        try {
            t.write(s, 0, s.size, AudioTrack.WRITE_BLOCKING)
            t.play()
            val deadline = System.currentTimeMillis() + (s.size * 1000L / sr) + 2000
            while (!cancelled && System.currentTimeMillis() < deadline &&
                t.playState == AudioTrack.PLAYSTATE_PLAYING && t.playbackHeadPosition < s.size) {
                Thread.sleep(20)
            }
        } finally {
            try { t.release() } catch (_: Throwable) {}
            track = null
        }
    }
}
