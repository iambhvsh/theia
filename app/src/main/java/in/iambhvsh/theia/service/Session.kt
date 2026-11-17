package `in`.iambhvsh.theia.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import android.view.View
import `in`.iambhvsh.theia.screens.Screen

class Sessions : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = Session(this)
}

/**
 * Handles system assistant sessions triggered via gesture or hardware button.
 */
class Session(ctx: Context) : VoiceInteractionSession(ctx) {

    override fun onCreate() {
        setUiEnabled(false)
        super.onCreate()
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        setUiEnabled(false)
        super.onPrepareShow(args, showFlags)
    }

    override fun onCreateContentView(): View? = null

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d("TheiaSession", "onShow triggered flags=$showFlags")
        val intent = Intent(context, Screen::class.java).apply {
            putExtra("autolisten", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startAssistantActivity(intent)
    }

    override fun onHide() {
        super.onHide()
        Log.d("TheiaSession", "onHide")
    }

    override fun onBackPressed() {
        super.onBackPressed()
        hide()
    }
}
