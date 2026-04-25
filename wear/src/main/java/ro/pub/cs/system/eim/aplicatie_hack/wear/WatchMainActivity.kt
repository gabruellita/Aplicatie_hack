package ro.pub.cs.system.eim.aplicatie_hack.wear

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import ro.pub.cs.system.eim.aplicatie_hack.wear.service.FallDetectionService

/**
 * Activity principal al ceasului — punct de intrare vizibil pentru utilizator.
 *
 * ## Responsabilități
 * 1. Pornește [FallDetectionService] ca foreground service la lansare.
 * 2. Detectează **triple-press** pe butonul fizic al coroanei (KEYCODE_STEM_1)
 *    și trimite mesaj MessageClient `/remote/launch` → telefonul aduce [MainActivity] în prim-plan.
 * 3. Afișează un ecran Compose care:
 *    - Reflectă starea de urmărire din [TrackingState] (actualizată de [WatchDataLayerService]).
 *    - Detectează **double-tap** (când tracking activ) → `/command/describe_scene` → telefon descrie scena.
 *    - Detectează **triple-tap pe ecran** → `/command/toggle_tracking` → pornire/oprire urmărire pe telefon.
 *
 * ## Separarea gesturilor fizice vs. ecran
 * ```
 * Buton fizic (coroană) triple-press → /remote/launch   (deschide app pe telefon)
 * Ecran triple-tap                   → /command/toggle_tracking (pornire/oprire cameră)
 * Ecran double-tap (tracking activ)  → /command/describe_scene  (descriere vocală)
 * ```
 *
 * ## Optimizare latență — cache nod
 * ID-ul nodului telefon este cacheuit după prima descoperire și reutilizat pentru toate
 * mesajele ulterioare. La eșec, cache-ul e invalidat și nodul e redescoperit o singură dată.
 */
class WatchMainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WatchMain"

        /** Fereastra de timp în care trebuie să se producă 3 prese pe butonul fizic. */
        private const val TRIPLE_PRESS_WINDOW_MS = 1_400L

        const val PATH_REMOTE_LAUNCH    = "/remote/launch"
        const val PATH_DESCRIBE_SCENE   = "/command/describe_scene"
        const val PATH_TOGGLE_TRACKING  = "/command/toggle_tracking"
    }

    private val activityScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Triple-press buton fizic ──────────────────────────────────────────────
    private var stemPressCount = 0
    private val stemHandler       = Handler(Looper.getMainLooper())
    private val stemResetRunnable = Runnable { stemPressCount = 0 }

    // ── Cache nod telefon (evită round-trip getConnectedNodes la fiecare mesaj) ──
    @Volatile private var cachedPhoneNodeId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startFallDetection()

        setContent {
            val isTrackingActive by TrackingState.isActive.collectAsState()
            WatchMainScreen(
                isTrackingActive = isTrackingActive,
                onDoubleTap      = { if (isTrackingActive) sendMessage(PATH_DESCRIBE_SCENE) },
                onTripleTap      = { sendMessage(PATH_TOGGLE_TRACKING) },
            )
        }
    }

    /**
     * Interceptează triple-press pe butonul fizic al coroanei (KEYCODE_STEM_1).
     *
     * Toate presele sunt consumate (`return true`) în fereastra de 1.4s pentru a
     * preveni comportamentul implicit de navigare înapoi. Aceasta este o decizie
     * conștientă: aplicația de accesibilitate menține ecranul activ permanent.
     *
     * La al 3-lea press → [sendMessage] cu [PATH_REMOTE_LAUNCH].
     * La expirarea ferestrei fără 3 prese → [stemResetRunnable] resetează contorul.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STEM_1) {
            stemPressCount++
            stemHandler.removeCallbacks(stemResetRunnable)

            if (stemPressCount >= 3) {
                stemPressCount = 0
                Log.d(TAG, "Triple-press fizic → lansare telefon")
                sendMessage(PATH_REMOTE_LAUNCH)
                return true
            }

            stemHandler.postDelayed(stemResetRunnable, TRIPLE_PRESS_WINDOW_MS)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startFallDetection() {
        startForegroundService(Intent(this, FallDetectionService::class.java))
    }

    /**
     * Trimite un mesaj MessageClient la telefonul asociat.
     *
     * Folosește [cachedPhoneNodeId] pentru a evita [NodeClient.getConnectedNodes] la fiecare apel.
     * La eșec, invalidează cache-ul și retrimite o singură dată cu nodul redescoperit.
     *
     * @param path Calea mesajului (ex. [PATH_REMOTE_LAUNCH], [PATH_TOGGLE_TRACKING]).
     */
    private fun sendMessage(path: String) {
        activityScope.launch {
            runCatching {
                val nodeId = cachedPhoneNodeId ?: discoverPhoneNode()
                    ?.also { cachedPhoneNodeId = it }
                    ?: run { Log.w(TAG, "Niciun telefon conectat"); return@runCatching }

                val result = runCatching {
                    Wearable.getMessageClient(this@WatchMainActivity)
                        .sendMessage(nodeId, path, ByteArray(0))
                        .await()
                }

                if (result.isFailure) {
                    // Node-ul din cache s-a deconectat, redescopăr
                    cachedPhoneNodeId = null
                    val freshNode = discoverPhoneNode()?.also { cachedPhoneNodeId = it }
                        ?: return@runCatching
                    Wearable.getMessageClient(this@WatchMainActivity)
                        .sendMessage(freshNode, path, ByteArray(0))
                        .await()
                }

                Log.d(TAG, "Mesaj trimis: $path")
            }.onFailure { Log.w(TAG, "sendMessage($path) eșuat: ${it.message}") }
        }
    }

    /** Returnează ID-ul primului telefon conectat, preferând nodurile nearby (BLE direct). */
    private suspend fun discoverPhoneNode(): String? {
        val nodes = Wearable.getNodeClient(this).connectedNodes.await()
        return (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
    }

    override fun onDestroy() {
        stemHandler.removeCallbacks(stemResetRunnable)
        activityScope.cancel()
        super.onDestroy()
    }
}

/**
 * Ecranul principal al ceasului.
 *
 * ## Gesturile ecranului
 * Implementăm un [TapCounter] custom deoarece [detectTapGestures] din Compose
 * nu oferă `onTripleTap` nativ. Strategia:
 *  - Tap 1: așteptăm 600ms — dacă nu vine alt tap, resetăm (single tap ignorat)
 *  - Tap 2: așteptăm 400ms — dacă nu vine tap 3, declanșăm double-tap
 *  - Tap 3: declanșăm imediat triple-tap (fără delay suplimentar)
 *
 * Notă: [detectTapGestures] fără `onDoubleTap` explicit → `onTap` fire **imediat** la
 * fiecare apăsare (nu după timeout-ul de distincție single/double). Asta ne permite
 * să implementăm propriul mecanism de numărare.
 *
 * @param isTrackingActive True când [VisionForegroundService] rulează pe telefon.
 * @param onDoubleTap Callback pentru descriere scenă (activ doar când tracking e pornit).
 * @param onTripleTap Callback pentru toggle tracking.
 */
@Composable
private fun WatchMainScreen(
    isTrackingActive: Boolean,
    onDoubleTap: () -> Unit,
    onTripleTap: () -> Unit,
) {
    val tapCounter = remember { TapCounter() }
    // rememberCoroutineScope() este legat de lifecycle-ul Composable-ului și se
    // anulează automat la ieșire — corect, spre deosebire de CoroutineScope() ad-hoc.
    val tapScope = androidx.compose.runtime.rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .padding(12.dp)
            .pointerInput(isTrackingActive) {
                // Fără onDoubleTap în detectTapGestures → onTap fire imediat, nu după timeout
                detectTapGestures(
                    onTap = {
                        tapCounter.handle(
                            scope       = tapScope,
                            onDoubleTap = onDoubleTap,
                            onTripleTap = onTripleTap,
                        )
                    }
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text       = "Guardian",
            color      = Color(0xFF00E676),
            fontSize   = 16.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text      = if (isTrackingActive) "Urmărire activă" else "Protecție activă",
            color     = if (isTrackingActive) Color(0xFF00E676) else Color(0xFF888888),
            fontSize  = 11.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(10.dp))

        if (isTrackingActive) {
            Text(
                text      = "2× tap → descriere scenă",
                color     = Color(0xFF444444),
                fontSize  = 9.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(3.dp))
        }

        Text(
            text      = "3× tap ecran → pornire/oprire",
            color     = Color(0xFF444444),
            fontSize  = 9.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(3.dp))

        Text(
            text      = "3× buton → lansare telefon",
            color     = Color(0xFF333333),
            fontSize  = 9.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Contor de tap-uri cu fereastră de timp, folosit pentru a distinge
 * double-tap (2 atingeri rapide) de triple-tap (3 atingeri rapide).
 *
 * Nu este un [androidx.compose.runtime.State] — nu triggerează recompunere —
 * deoarece numărul de tap-uri intermediar nu are relevanță vizuală pentru UI.
 * Este `remember`'d în Composable pentru a persista între recompuneri.
 *
 * Toate accesările sunt pe main thread (pointerInput + rememberCoroutineScope
 * au același dispatcher), deci nu necesită sincronizare.
 */
private class TapCounter {
    private var count = 0
    private var job: Job? = null

    /**
     * Procesează un tap și decide acțiunea pe baza contorului.
     *
     * @param scope Scope-ul primit din [androidx.compose.runtime.rememberCoroutineScope] —
     *              legat de lifecycle Composable, se anulează automat la dispărut din UI.
     * @param onDoubleTap Apelat dacă al 2-lea tap nu este urmat de al 3-lea în 400ms.
     * @param onTripleTap Apelat imediat la al 3-lea tap.
     */
    fun handle(
        scope: CoroutineScope,
        onDoubleTap: () -> Unit,
        onTripleTap: () -> Unit,
    ) {
        count++
        job?.cancel()

        when {
            count >= 3 -> {
                // Triple-tap confirmat — acționăm imediat
                count = 0
                onTripleTap()
            }
            count == 2 -> {
                // Așteptăm 400ms pentru un posibil al 3-lea tap
                job = scope.launch {
                    delay(400L)
                    if (count == 2) { count = 0; onDoubleTap() }
                }
            }
            else -> {
                // Primul tap — resetăm după 600ms dacă nu vine altul
                job = scope.launch {
                    delay(600L)
                    count = 0
                }
            }
        }
    }
}
