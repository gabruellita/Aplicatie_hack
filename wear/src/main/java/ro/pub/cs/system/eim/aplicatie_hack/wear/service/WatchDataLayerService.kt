package ro.pub.cs.system.eim.aplicatie_hack.wear.service

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import ro.pub.cs.system.eim.aplicatie_hack.model.HapticEvent
import ro.pub.cs.system.eim.aplicatie_hack.wear.haptic.HapticManager

/**
 * Primește mesaje haptic de la telefon și le redă imediat prin vibrație.
 *
 * WearableListenerService se trezește automat când sosește un mesaj pe /haptic —
 * nu consumă baterie când nu există mesaje.
 */
class WatchDataLayerService : WearableListenerService() {

    private val haptic by lazy { HapticManager(this) }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != "/haptic") return

        val eventName = String(event.data, Charsets.UTF_8)
        val hapticEvent = runCatching { HapticEvent.valueOf(eventName) }.getOrElse {
            Log.w("WatchDataLayer", "Eveniment haptic necunoscut: $eventName")
            return
        }

        Log.d("WatchDataLayer", "Haptic: $hapticEvent")
        haptic.play(hapticEvent)
    }
}