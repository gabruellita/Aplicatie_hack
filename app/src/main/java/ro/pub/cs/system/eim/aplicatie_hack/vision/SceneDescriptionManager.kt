package ro.pub.cs.system.eim.aplicatie_hack.vision

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ro.pub.cs.system.eim.aplicatie_hack.BuildConfig
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Trimite frame-ul curent la Gemini 2.0 Flash și redă descrierea vocal + afișare pop-up.
 */
class SceneDescriptionManager(private val context: Context) {

    companion object {
        const val TAG = "SceneDesc"
        const val ACTION_SHOW_MESSAGE = "ro.pub.cs.system.eim.aplicatie_hack.SHOW_MESSAGE"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_IS_ERROR = "extra_is_error"
        
        // Model stabil: gemini-2.0-flash
        private const val MODEL = "gemini-2.5-flash"
        private const val BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
        private const val COOLDOWN_MS = 6_000L
        private const val MAX_DIM_PX = 1024 // Rezoluție ușor mai mare pentru detalii mai bune

        private const val PROMPT = """
            Ești asistentul personal al unui nevăzător. 
            Analizează imaginea și oferă o descriere a scenei (interior sau exterior) în maxim 50 de cuvinte.
            Prioritizează elementele esențiale: obstacole, persoane, obiecte importante sau contextul general.
            Fii clar, direct și răspunde în limba română.
        """
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastCallAt = 0L

    init {
        tts = TextToSpeech(context) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (ttsReady) {
                val result = tts?.setLanguage(Locale("ro", "RO"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Limba română nu este suportată pe acest dispozitiv.")
                    tts?.language = Locale.ENGLISH
                }
                tts?.setSpeechRate(1.0f) // Viteză normală pentru descrieri mai lungi
            }
        }
    }

    fun describe(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastCallAt < COOLDOWN_MS) {
            speak("Vă rugăm să așteptați câteva secunde între analize.")
            return
        }
        lastCallAt = now
        speak("Analizez scena. Vă rog să așteptați.")

        scope.launch {
            try {
                val result = callGemini(bitmap)
                withContext(Dispatchers.Main) {
                    when (result) {
                        is GeminiResult.Success -> {
                            broadcastMessage(result.text, false)
                            speak(result.text)
                        }
                        is GeminiResult.Error -> {
                            val errorMsg = result.message
                            speak("Eroare la analiză. Detalii în fereastra de pe ecran.")
                            copyToClipboard(errorMsg)
                            broadcastMessage("EROARE API:\n$errorMsg", true)
                        }
                    }
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown error"
                Log.e(TAG, "Eroare în coroutină: $errorMsg")
                withContext(Dispatchers.Main) {
                    speak("Eroare de sistem la procesarea imaginii.")
                    broadcastMessage("EROARE SISTEM:\n$errorMsg", true)
                }
            }
        }
    }

    private fun broadcastMessage(text: String, isError: Boolean) {
        val intent = Intent(ACTION_SHOW_MESSAGE).apply {
            putExtra(EXTRA_MESSAGE, text)
            putExtra(EXTRA_IS_ERROR, isError)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Gemini Error Log", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun speak(text: String) {
        if (ttsReady) {
            // Pentru texte lungi, folosim un mod care nu blochează coada dacă e necesar
            // dar aici păstrăm QUEUE_FLUSH pentru a anula "Analizez" când vine rezultatul
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "guardian")
        } else {
            Log.w(TAG, "TTS not ready. Tried to speak: $text")
        }
    }

    private sealed class GeminiResult {
        data class Success(val text: String) : GeminiResult()
        data class Error(val message: String) : GeminiResult()
    }

    private fun callGemini(bitmap: Bitmap): GeminiResult {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isNullOrBlank()) {
            return GeminiResult.Error("API Key is missing in local.properties")
        }

        val imgB64 = encodeImage(bitmap)
        val bodyJson = buildRequestJson(imgB64)

        return try {
            val request = Request.Builder()
                .url("$BASE_URL?key=$apiKey")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                
                if (!response.isSuccessful) {
                    val msg = "HTTP ${response.code}: $responseBody"
                    Log.e(TAG, msg)
                    return GeminiResult.Error(msg)
                }

                if (responseBody == null) return GeminiResult.Error("Empty response body")

                val json = JsonParser.parseString(responseBody).asJsonObject
                val candidates = json.getAsJsonArray("candidates")
                
                if (candidates == null || candidates.size() == 0) {
                    Log.w(TAG, "Safety filter block: $responseBody")
                    return GeminiResult.Error("Conținut blocat de filtrele de siguranță. Body: $responseBody")
                }

                val text = candidates[0].asJsonObject
                    .getAsJsonObject("content")
                    ?.getAsJsonArray("parts")
                    ?.get(0)?.asJsonObject
                    ?.get("text")?.asString

                if (text != null) {
                    GeminiResult.Success(text)
                } else {
                    GeminiResult.Error("Nu s-a putut extrage textul din răspuns. Body: $responseBody")
                }
            }
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Network error: $msg")
            GeminiResult.Error(msg)
        }
    }

    private fun buildRequestJson(imageB64: String): String {
        val partText = JsonObject().apply { addProperty("text", PROMPT.trimIndent()) }
        val partImage = JsonObject().apply {
            val inlineData = JsonObject().apply {
                addProperty("mime_type", "image/jpeg")
                addProperty("data", imageB64)
            }
            add("inline_data", inlineData)
        }

        val partsArray = JsonArray().apply {
            add(partText)
            add(partImage)
        }

        val contentObj = JsonObject().apply { add("parts", partsArray) }
        val contentsArray = JsonArray().apply { add(contentObj) }

        val generationConfig = JsonObject().apply {
            // Am crescut limita de tokeni pentru a primi descrieri complete
            addProperty("maxOutputTokens", 1024)
            addProperty("temperature", 0.2)
        }

        return JsonObject().apply {
            add("contents", contentsArray)
            add("generationConfig", generationConfig)
        }.toString()
    }

    private fun encodeImage(bitmap: Bitmap): String {
        val scale = MAX_DIM_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else bitmap

        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
