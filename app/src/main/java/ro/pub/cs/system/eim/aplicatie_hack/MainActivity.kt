package ro.pub.cs.system.eim.aplicatie_hack

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ro.pub.cs.system.eim.aplicatie_hack.service.VisionForegroundService
import ro.pub.cs.system.eim.aplicatie_hack.ui.theme.Aplicatie_hackTheme
import ro.pub.cs.system.eim.aplicatie_hack.vision.SceneDescriptionManager

class MainActivity : ComponentActivity() {

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(VisionForegroundService.PREFS_NAME, MODE_PRIVATE)
    }

    private var isActive = mutableStateOf(false)
    private var showContactsDialog = mutableStateOf(false)
    private var popupMessage = mutableStateOf<String?>(null)
    private var isErrorMessage = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startVisionService()
    }

    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SceneDescriptionManager.ACTION_SHOW_MESSAGE) {
                popupMessage.value = intent.getStringExtra(SceneDescriptionManager.EXTRA_MESSAGE)
                isErrorMessage.value = intent.getBooleanExtra(SceneDescriptionManager.EXTRA_IS_ERROR, false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isActive.value = prefs.getBoolean(VisionForegroundService.KEY_RUNNING, false)
        enableEdgeToEdge()

        val filter = IntentFilter(SceneDescriptionManager.ACTION_SHOW_MESSAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(messageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(messageReceiver, filter)
        }

        setContent {
            Aplicatie_hackTheme {
                MainScreen(
                    isActive          = isActive.value,
                    showContactsDlg   = showContactsDialog.value,
                    popupMsg          = popupMessage.value,
                    isErrorMsg        = isErrorMessage.value,
                    onToggle          = { handleToggle() },
                    onDescribeScene   = { triggerDescription() },
                    onContactsOpen    = { showContactsDialog.value = true },
                    onContactsSave    = { numbers ->
                        prefs.edit()
                            .putStringSet("emergency_numbers", numbers.toSet())
                            .apply()
                        showContactsDialog.value = false
                    },
                    onContactsDismiss = { showContactsDialog.value = false },
                    onDismissPopup    = { popupMessage.value = null },
                    savedNumbers = prefs.getStringSet("emergency_numbers", emptySet())
                        ?.joinToString("\n") ?: ""
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(messageReceiver)
    }

    override fun onResume() {
        super.onResume()
        isActive.value = prefs.getBoolean(VisionForegroundService.KEY_RUNNING, false)
    }

    private fun handleToggle() {
        if (isActive.value) {
            stopVisionService()
            return
        }
        val needed = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val allGranted = needed.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (allGranted) startVisionService() else permissionLauncher.launch(needed)
    }

    private fun startVisionService() {
        startForegroundService(Intent(this, VisionForegroundService::class.java))
        isActive.value = true
    }

    private fun stopVisionService() {
        stopService(Intent(this, VisionForegroundService::class.java))
        isActive.value = false
    }

    private fun triggerDescription() {
        startService(Intent(this, VisionForegroundService::class.java).apply {
            action = VisionForegroundService.ACTION_DESCRIBE_SCENE
        })
    }
}

@Composable
private fun MainScreen(
    isActive: Boolean,
    showContactsDlg: Boolean,
    popupMsg: String?,
    isErrorMsg: Boolean,
    onToggle: () -> Unit,
    onDescribeScene: () -> Unit,
    onContactsOpen: () -> Unit,
    onContactsSave: (List<String>) -> Unit,
    onContactsDismiss: () -> Unit,
    onDismissPopup: () -> Unit,
    savedNumbers: String,
) {
    val green  = Color(0xFF00E676)
    val red    = Color(0xFFFF1744)
    val blue   = Color(0xFF1565C0)
    val bgColor = Color(0xFF0A0A0A)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (isActive) "SISTEM ACTIV" else "SISTEM OPRIT",
            color = if (isActive) green else red,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics {
                contentDescription = if (isActive) "Sistemul este activ" else "Sistemul este oprit"
            }
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = if (isActive) "Detectare obstacole în timp real" else "Apasă START pentru a activa",
            color = Color(0xFFAAAAAA),
            fontSize = 14.sp,
        )

        Spacer(Modifier.height(48.dp))

        Button(
            onClick  = onToggle,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .semantics {
                    contentDescription =
                        if (isActive) "Buton oprire sistem" else "Buton pornire sistem"
                },
            shape  = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isActive) red else green
            ),
        ) {
            Text(
                text       = if (isActive) "OPREȘTE" else "PORNEȘTE",
                color      = Color.Black,
                fontSize   = 20.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }

        Spacer(Modifier.height(20.dp))

        if (isActive) {
            Button(
                onClick  = onDescribeScene,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .semantics { contentDescription = "Buton descriere scenă curentă prin voce" },
                shape  = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = blue),
            ) {
                Text(
                    text     = "DESCRIE SCENA",
                    color    = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(20.dp))
        }

        OutlinedButton(
            onClick  = onContactsOpen,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics { contentDescription = "Configurare contacte de urgență" },
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text  = "CONTACTE URGENȚĂ",
                color = Color(0xFFCCCCCC),
                fontSize = 14.sp,
            )
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text  = "Triple-press volum jos = pornire/oprire rapidă",
            color = Color(0xFF666666),
            fontSize = 11.sp,
        )
    }

    if (showContactsDlg) {
        EmergencyContactsDialog(
            initial   = savedNumbers,
            onSave    = { text ->
                val numbers = text.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                onContactsSave(numbers)
            },
            onDismiss = onContactsDismiss,
        )
    }

    if (popupMsg != null) {
        InfoDialog(
            message = popupMsg,
            isError = isErrorMsg,
            onDismiss = onDismissPopup
        )
    }
}

@Composable
private fun InfoDialog(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit
) {
    // Închidere automată după 60 secunde dacă utilizatorul nu apasă manual
    LaunchedEffect(message) {
        delay(60_000L)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                text = if (isError) "EROARE" else "DESCRIERE SCENĂ",
                color = if (isError) Color.Red else Color.Blue,
                fontWeight = FontWeight.Bold
            ) 
        },
        text = {
            Text(
                text = message,
                fontSize = 16.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("ÎNCHIDE (X)", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun EmergencyContactsDialog(
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("Contacte de urgență") },
        text    = {
            Column {
                Text(
                    "Introdu numerele de telefon (unul per linie).\n" +
                    "Un SMS cu locația GPS va fi trimis automat dacă se detectează o cădere.",
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value         = text,
                    onValueChange = { text = it },
                    label         = { Text("Numere telefon") },
                    placeholder   = { Text("+40712345678\n+40798765432") },
                    modifier      = Modifier.fillMaxWidth(),
                    minLines      = 3,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("SALVEAZĂ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ANULEAZĂ") }
        }
    )
}