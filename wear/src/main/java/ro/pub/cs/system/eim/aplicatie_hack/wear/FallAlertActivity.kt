package ro.pub.cs.system.eim.aplicatie_hack.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import ro.pub.cs.system.eim.aplicatie_hack.wear.service.FallDetectionService

/**
 * Apare automat pe ecranul ceasului (și pe ecranul de blocare) când se detectează o cădere.
 * Utilizatorul are CANCEL_WINDOW_MS secunde să apese "SUNT BINE".
 * Dacă nu reacționează, FallDetectionService trimite evenimentul la telefon → SMS.
 *
 * Design accesibil:
 *  - Buton circular MARE (80dp) în centrul ecranului rotund al ceasului
 *  - Countdown vizibil deasupra butonului
 *  - Contrast maxim: roșu/alb pe negru
 */
class FallAlertActivity : ComponentActivity() {

    private val countdownMs get() =
        intent.getLongExtra("countdown_ms", 30_000L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FallAlertScreen(
                totalSeconds = (countdownMs / 1000).toInt(),
                onCancel     = {
                    startService(
                        Intent(this, FallDetectionService::class.java).apply {
                            action = FallDetectionService.ACTION_CANCEL
                        }
                    )
                    finish()
                }
            )
        }
    }
}

@Composable
private fun FallAlertScreen(totalSeconds: Int, onCancel: () -> Unit) {
    var remaining by remember { mutableIntStateOf(totalSeconds) }

    // Countdown ticker
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1_000L)
            remaining--
        }
        // La 0 nu mai facem nimic — FallDetectionService se ocupă de notificare
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A0000)), // fundal roșu închis — urgență vizuală
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment  = Alignment.CenterHorizontally,
            verticalArrangement  = Arrangement.Center,
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text      = "ALERTĂ CĂDERE",
                color     = Color(0xFFFF4444),
                fontSize  = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text     = "${remaining}s",
                color    = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
            )

            Spacer(Modifier.height(12.dp))

            // Buton MARE — acoperă cea mai mare parte a ecranului rotund
            Button(
                onClick  = onCancel,
                modifier = Modifier.size(80.dp),
                shape    = CircleShape,
                colors   = ButtonDefaults.buttonColors(
                    backgroundColor = Color(0xFF00C853)
                ),
            ) {
                Text(
                    text      = "OK",
                    color     = Color.Black,
                    fontSize  = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign  = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text      = "Sunt bine",
                color     = Color(0xFFAAAAAA),
                fontSize  = 11.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}