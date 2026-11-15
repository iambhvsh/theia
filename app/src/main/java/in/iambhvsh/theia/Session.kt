package `in`.iambhvsh.theia

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class Sessions : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = Session(this)
}

/**
 * Wake gesture and power button handler.
 * setUiEnabled(false) suppresses the default framework session window,
 * preventing any blank white box or starting border from flashing on screen.
 */
class Session(ctx: Context) : VoiceInteractionSession(ctx) {

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, Screen::class.java).apply {
            putExtra("autolisten", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startAssistantActivity(intent)
        hide()
    }
}
