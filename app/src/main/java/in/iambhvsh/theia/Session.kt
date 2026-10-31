package `in`.iambhvsh.theia

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class Sessions : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = Session(this)
}

// Wake gesture lands here: open Theia's screen and start listening.
class Session(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startAssistantActivity(Intent(context, Screen::class.java).putExtra("autolisten", true))
        hide()
    }
}
