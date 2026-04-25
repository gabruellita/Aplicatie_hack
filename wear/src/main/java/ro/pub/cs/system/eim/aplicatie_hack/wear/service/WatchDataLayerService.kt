package ro.pub.cs.system.eim.aplicatie_hack.wear.service

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import ro.pub.cs.system.eim.aplicatie_hack.model.HapticEvent
import ro.pub.cs.system.eim.aplicatie_hack.wear.TrackingState
import ro.pub.cs.system.eim.aplicatie_hack.wear.haptic.HapticManager

/**
 * Ascultă două tipuri de evenimente de la telefon:
 *  1. MESSAGE_RECEIVED pe /haptic → redă imediat pattern haptic
 *  2. DATA_CHANGED pe /tracking/state → actualizează TrackingState (folosit de
 *     WatchMainActivity pentru a activa/dezactiva double-tap → describe scene)
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

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.use { buffer ->
            buffer.filter { it.type == DataEvent.TYPE_CHANGED }
                .forEach { event ->
                    val path = event.dataItem.uri.path ?: return@forEach
                    if (path == "/tracking/state") {
                        val active = DataMapItem.fromDataItem(event.dataItem)
                            .dataMap.getBoolean("active", false)
                        TrackingState.update(active)
                        Log.d("WatchDataLayer", "Tracking state actualizat: $active")
                    }
                }
        }
    }
}
