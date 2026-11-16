package `in`.iambhvsh.theia

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

object Recorder {
    private const val TAG = "TheiaRecorder"
    private const val SR = 16000
    private const val DEFAULT_START_THRESH = 0.0080f // Robust speech onset threshold (rejects room hum & breath)
    private const val END_CHUNKS = 6                // 100 ms each -> 600 ms silence ends capture (ultra-responsive real-time)
    private const val WAIT_CHUNKS = 70              // 7 s initial wait before giving up
    private const val PRE_ROLL_CHUNKS = 5           // Keep ~500 ms of audio before speech trigger
    private const val TRAILING_PAD_CHUNKS = 2       // Retain ~200 ms padding at end of speech to prevent clipping

    @Volatile
    private var isCancelled = false

    fun stop() {
        isCancelled = true
    }

    /** Returns 16 kHz mono float PCM, or empty if no speech. Blocks. */
    @SuppressLint("MissingPermission")
    fun capture(
        maxSec: Int = 15,
        onRmsUpdate: ((Float) -> Unit)? = null,
        onSpeechDetected: (() -> Unit)? = null
    ): FloatArray {
        isCancelled = false
        val chunk = SR / 10 // 1600 samples (100 ms)

        val minBuf = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, chunk * 16)

        // Prefer VOICE_RECOGNITION for hardware AGC/noise suppression, fallback to MIC
        var rec: AudioRecord? = null
        for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            try {
                val candidate = AudioRecord(
                    source, SR, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    rec = candidate
                    Log.d(TAG, "AudioRecord initialized with source=$source")
                    break
                }
                candidate.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize source=$source", e)
            }
        }

        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord could not be initialized")
            return FloatArray(0)
        }

        val out = ArrayList<Float>()
        val preRoll = ArrayDeque<FloatArray>()
        val buf = ShortArray(chunk)
        val floatBuf = FloatArray(chunk)

        var started = false
        var speechChunks = 0
        var quiet = 0
        var waited = 0
        var ambientNoise = 0.0020f
        var ambientCount = 0
        var startThresh = DEFAULT_START_THRESH
        var continueThresh = 0.0022f
        var consecutiveSpeech = 0

        rec.startRecording()
        try {
            while (out.size + chunk <= SR * maxSec) {
                if (isCancelled) {
                    Log.d(TAG, "Audio capture cancelled")
                    return FloatArray(0)
                }
                val r = rec.read(buf, 0, chunk, AudioRecord.READ_BLOCKING)
                if (isCancelled) {
                    Log.d(TAG, "Audio capture cancelled after read")
                    return FloatArray(0)
                }
                if (r <= 0) {
                    Log.w(TAG, "AudioRecord.read returned $r")
                    break
                }

                // Convert 16-bit PCM to float [-1.0, 1.0] and compute RMS
                var sumSq = 0.0
                for (i in 0 until r) {
                    val s = buf[i] / 32768.0f
                    floatBuf[i] = s
                    sumSq += s * s
                }
                val rms = sqrt(sumSq / r).toFloat()
                onRmsUpdate?.invoke((rms * 15f).coerceIn(0f, 1f))

                // Dynamically track ambient noise baseline before speech triggers
                if (!started && rms < 0.012f) {
                    if (ambientCount == 0) {
                        ambientNoise = rms
                        ambientCount = 1
                    } else {
                        ambientNoise = ambientNoise * 0.85f + rms * 0.15f
                        ambientCount++
                    }
                    startThresh = maxOf(DEFAULT_START_THRESH, minOf(0.025f, ambientNoise * 2.8f))
                    continueThresh = maxOf(0.0035f, minOf(0.012f, ambientNoise * 1.8f))
                }

                if (!started) {
                    // Waiting for speech to start
                    if (rms >= startThresh) {
                        started = true
                        Log.d(TAG, "Speech started! rms=$rms >= startThresh=$startThresh (continueThresh=$continueThresh)")
                        onSpeechDetected?.invoke()
                        // Include full pre-roll to prevent cutting off the first syllable
                        for (p in preRoll) {
                            for (s in p) out.add(s)
                        }
                        preRoll.clear()
                        for (i in 0 until r) out.add(floatBuf[i])
                        speechChunks++
                        quiet = 0
                        consecutiveSpeech = 1
                    } else {
                        // Store rolling pre-roll buffer
                        if (preRoll.size >= PRE_ROLL_CHUNKS) {
                            preRoll.removeFirst()
                        }
                        preRoll.addLast(floatBuf.copyOf(r))

                        if (++waited >= WAIT_CHUNKS) {
                            Log.d(TAG, "Wait timed out ($waited chunks). Max RMS was $rms, thresh=$startThresh")
                            return FloatArray(0)
                        }
                    }
                } else {
                    // Speech has already started
                    for (i in 0 until r) out.add(floatBuf[i])

                    val isDefiniteSpeech = rms >= startThresh
                    val isPossibleSpeech = rms >= continueThresh

                    if (isDefiniteSpeech) {
                        speechChunks++
                        quiet = 0
                        consecutiveSpeech++
                    } else if (isPossibleSpeech) {
                        if (quiet == 0) {
                            // Direct continuation of active speech
                            speechChunks++
                            consecutiveSpeech++
                        } else {
                            // Energy during an existing silence pause (breath / transient ambient noise)
                            consecutiveSpeech++
                            if (consecutiveSpeech >= 2) {
                                // Sustained speech resumed
                                quiet = 0
                                speechChunks += consecutiveSpeech
                            } else {
                                // Isolated non-speech transient; continue counting silence
                                quiet++
                            }
                        }
                    } else {
                        // True quiet silence
                        consecutiveSpeech = 0
                        quiet++
                    }

                    if (quiet >= END_CHUNKS) {
                        Log.d(TAG, "Speech finished after $speechChunks active chunks. Total samples=${out.size}")
                        break
                    }
                }
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }

        if (!started || out.isEmpty() || speechChunks < 4) {
            Log.d(TAG, "Insufficient speech: started=$started, speechChunks=$speechChunks, samples=${out.size}")
            return FloatArray(0)
        }

        // Trim excess trailing silence
        val excessSilenceChunks = maxOf(0, quiet - TRAILING_PAD_CHUNKS)
        val trimSamples = excessSilenceChunks * chunk
        val finalSize = maxOf(chunk * 2, out.size - trimSamples)

        // Peak normalization: scale audio to nominal speech amplitude (~0.85 peak)
        val pcm = FloatArray(finalSize)
        var peak = 0.0f
        for (i in 0 until finalSize) {
            val v = out[i]
            pcm[i] = v
            val a = abs(v)
            if (a > peak) peak = a
        }

        // Reject room silence/noise: real human speech into a phone microphone has peak >= 0.035
        if (peak < 0.035f) {
            Log.d(TAG, "Audio rejected as ambient silence/noise: raw peak=$peak < 0.035")
            return FloatArray(0)
        }

        val targetPeak = 0.85f
        val gain = (targetPeak / peak).coerceIn(1.0f, 8.0f)
        Log.d(TAG, "Normalizing PCM: raw peak=$peak, applying gain=$gain -> target peak=$targetPeak")
        for (i in pcm.indices) {
            pcm[i] = (pcm[i] * gain).coerceIn(-1.0f, 1.0f)
        }

        Log.d(TAG, "Final audio: ${pcm.size} samples (${pcm.size / SR.toFloat()}s)")
        return pcm
    }
}
