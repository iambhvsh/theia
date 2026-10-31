package `in`.iambhvsh.theia

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

object Recorder {
    private const val TAG = "TheiaRecorder"
    private const val SR = 16000
    private const val DEFAULT_START_THRESH = 0.0032f // Very sensitive speech onset threshold
    private const val END_CHUNKS = 22               // 100 ms each -> 2.2 s silence ends capture (natural pauses)
    private const val WAIT_CHUNKS = 70              // 7 s initial wait before giving up
    private const val PRE_ROLL_CHUNKS = 5           // Keep ~500 ms of audio before speech trigger

    /** Returns 16 kHz mono float PCM, or empty if no speech. Blocks. */
    @SuppressLint("MissingPermission")
    fun capture(maxSec: Int = 15, onSpeechDetected: (() -> Unit)? = null): FloatArray {
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
        var ambientSum = 0.0
        var ambientCount = 0
        var startThresh = DEFAULT_START_THRESH
        var continueThresh = 0.0016f

        rec.startRecording()
        try {
            while (out.size + chunk <= SR * maxSec) {
                val r = rec.read(buf, 0, chunk, AudioRecord.READ_BLOCKING)
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
                val rms = Math.sqrt(sumSq / r).toFloat()

                // Baseline ambient noise over first 3 chunks (300 ms)
                if (ambientCount < 3) {
                    ambientSum += rms
                    ambientCount++
                    val avgAmbient = (ambientSum / ambientCount).toFloat()
                    startThresh = maxOf(DEFAULT_START_THRESH, minOf(0.010f, avgAmbient * 2.0f))
                    continueThresh = maxOf(0.0015f, minOf(0.004f, avgAmbient * 1.1f))
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

                    if (rms >= continueThresh) {
                        // Still speaking or voice energy present
                        speechChunks++
                        quiet = 0
                    } else {
                        // True quiet silence
                        if (++quiet >= END_CHUNKS) {
                            Log.d(TAG, "Speech finished after $speechChunks active chunks. Total samples=${out.size}")
                            break
                        }
                    }
                }
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }

        if (!started || out.isEmpty() || speechChunks < 2) {
            Log.d(TAG, "Insufficient speech: started=$started, speechChunks=$speechChunks, samples=${out.size}")
            return FloatArray(0)
        }

        // Peak normalization: scale audio to nominal speech amplitude (~0.85 peak)
        val pcm = out.toFloatArray()
        var peak = 0.0f
        for (v in pcm) {
            val a = Math.abs(v)
            if (a > peak) peak = a
        }

        if (peak > 0.003f) {
            val targetPeak = 0.85f
            val gain = (targetPeak / peak).coerceIn(1.0f, 35.0f)
            Log.d(TAG, "Normalizing PCM: raw peak=$peak, applying gain=$gain -> target peak=$targetPeak")
            for (i in pcm.indices) {
                pcm[i] = (pcm[i] * gain).coerceIn(-1.0f, 1.0f)
            }
        }

        Log.d(TAG, "Final audio: ${pcm.size} samples (${pcm.size / SR.toFloat()}s)")
        return pcm
    }
}
