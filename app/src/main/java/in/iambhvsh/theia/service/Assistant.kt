package `in`.iambhvsh.theia.service

import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

// Registered as the default digital assistant. The system binds this on the wake gesture.
class Assistant : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // Disable AssistStructure and screenshot disclosure flash (white border box)
        disabledShowContext = VoiceInteractionSession.SHOW_WITH_ASSIST or
            VoiceInteractionSession.SHOW_WITH_SCREENSHOT
    }
}
