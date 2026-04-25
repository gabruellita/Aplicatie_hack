package ro.pub.cs.system.eim.aplicatie_hack.service

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * Primește evenimentele DataClient de la ceas (fall events).
 *
 * WearableListenerService este pornit automat de sistemul Android când sosesc date
 * pe path-ul declarat în manifest — nu trebuie să ruleze permanent în background.
 * Sistemul îl pornește, noi procesăm, el se oprește singur.
 *
 * De ce DataClient pentru căderi (și nu MessageClient)?
 * DataClient este persistent: dacă telefonul și ceasul sunt deconectate temporar,
 * evenimentul se sincronizează la reconectare. O cădere NU trebuie să se piardă.
 */
class PhoneDataLayerService : WearableListenerService() {

    companion object {
        const val PATH_FALL_EVENT  = "/emergency/fall"
        const val PATH_FALL_CANCEL = "/emergency/fall_cancel"
        private const val TAG = "PhoneDataLayer"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.use { buffer ->
            buffer.filter { it.type == DataEvent.TYPE_CHANGED }
                .forEach { event ->
                    val path = event.dataItem.uri.path ?: return@forEach
                    Log.d(TAG, "Data changed: $path")

                    when (path) {
                        PATH_FALL_EVENT  -> handleFallConfirmed(event)
                        PATH_FALL_CANCEL -> Log.i(TAG, "Cădere anulată de utilizator")
                    }
                }
        }
    }

    private fun handleFallConfirmed(event: DataEvent) {
        val data = DataMapItem.fromDataItem(event.dataItem).dataMap
        if (!data.getBoolean("confirmed", false)) return

        Log.i(TAG, "Cădere confirmată de ceas — pornesc EmergencyResponseService")
        startService(
            Intent(this, EmergencyResponseService::class.java).apply {
                action = EmergencyResponseService.ACTION_FALL_CONFIRMED
                putExtra("fall_ts", data.getLong("ts"))
            }
        )
    }
}