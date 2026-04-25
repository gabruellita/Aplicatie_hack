package ro.pub.cs.system.eim.aplicatie_hack.wearable

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import ro.pub.cs.system.eim.aplicatie_hack.model.HapticEvent

/**
 * Trimite comenzi haptic de la telefon la ceas prin Wearable MessageClient.
 *
 * De ce MessageClient și nu DataClient?
 * MessageClient = fire-and-forget, fără persistență.
 * Dacă ceasul e deconectat 300ms, mesajul vechi e oricum expirat (nu vrem să redăm
 * un pattern de obstacol vechi după reconectare). DataClient persistă și redă la
 * reconectare — greșit pentru feedback haptic în timp real.
 * DataClient rămâne corect pentru fall events (trebuie să nu se piardă niciodată).
 */
class WearableMessenger(private val context: Context) {

    companion object {
        const val PATH_HAPTIC = "/haptic"
    }

    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient    = Wearable.getNodeClient(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun send(event: HapticEvent) {
        scope.launch {
            runCatching {
                val watch = nodeClient.connectedNodes.await()
                    .firstOrNull { it.isNearby }
                    ?: nodeClient.connectedNodes.await().firstOrNull()
                    ?: return@runCatching

                messageClient.sendMessage(
                    watch.id,
                    PATH_HAPTIC,
                    event.name.toByteArray(Charsets.UTF_8)
                ).await()
            }.onFailure {
                Log.w("WearableMessenger", "Send failed: ${it.message}")
            }
        }
    }
}