package `in`.iambhvsh.theia.service

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

// Placeholder required by the voice-interaction XML on some Android versions. Does nothing.
class Recognizer : RecognitionService() {
    override fun onStartListening(i: Intent?, c: Callback?) { c?.error(SpeechRecognizer.ERROR_CLIENT) }
    override fun onCancel(c: Callback?) {}
    override fun onStopListening(c: Callback?) {}
}
