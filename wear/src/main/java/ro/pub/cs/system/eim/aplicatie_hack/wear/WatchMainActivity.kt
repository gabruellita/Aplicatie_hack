package ro.pub.cs.system.eim.aplicatie_hack.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
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
import ro.pub.cs.system.eim.aplicatie_hack.wear.service.FallDetectionService

/**
 * Activity principal al ceasului.
 * Pornește automat FallDetectionService când aplicația de pe ceas este lansată.
 * UI minimal: status + buton pentru test manual.
 */
class WatchMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startFallDetection()

        setContent {
            WatchMainScreen(onTestFall = { simulateFall() })
        }
    }

    private fun startFallDetection() {
        startForegroundService(Intent(this, FallDetectionService::class.java))
    }

    private fun simulateFall() {
        // Declanșare manuală pentru test — util în demo
        startActivity(
            Intent(this, FallAlertActivity::class.java).apply {
                putExtra("countdown_ms", 10_000L) // 10s pentru test
            }
        )
    }
}

@Composable
private fun WatchMainScreen(onTestFall: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text      = "Guardian",
            color     = Color(0xFF00E676),
            fontSize  = 16.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text     = "Protecție activă",
            color    = Color(0xFF888888),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick  = onTestFall,
            modifier = Modifier.size(56.dp),
            shape    = CircleShape,
            colors   = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1565C0)),
        ) {
            Text(
                text     = "TEST",
                color    = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text     = "Simulare alertă",
            color    = Color(0xFF555555),
            fontSize = 10.sp,
        )
    }
}