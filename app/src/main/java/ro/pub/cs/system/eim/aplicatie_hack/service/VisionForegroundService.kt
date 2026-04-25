package ro.pub.cs.system.eim.aplicatie_hack.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import ro.pub.cs.system.eim.aplicatie_hack.MainActivity
import ro.pub.cs.system.eim.aplicatie_hack.model.HapticEvent
import ro.pub.cs.system.eim.aplicatie_hack.vision.BoundingBoxFilter
import ro.pub.cs.system.eim.aplicatie_hack.vision.SceneDescriptionManager
import ro.pub.cs.system.eim.aplicatie_hack.vision.TrafficLightColor
import ro.pub.cs.system.eim.aplicatie_hack.vision.TrafficLightDetector
import ro.pub.cs.system.eim.aplicatie_hack.vision.UrgencyLevel
import ro.pub.cs.system.eim.aplicatie_hack.wearable.WearableMessenger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Serviciu foreground principal al telefonului.
 *
 * Extinde LifecycleService (implementează LifecycleOwner) pentru CameraX binding.
 * Rulează continuu cât sistemul este activ; gestionează:
 *  - Captura cameră → inferență ML Kit → filtrare bbox → haptic pe ceas
 *  - Frame skipping adaptiv pentru economie baterie
 *  - Stocare ultimului frame pentru SceneDescriptionManager
 */
class VisionForegroundService : LifecycleService() {

    companion object {
        const val ACTION_DESCRIBE_SCENE = "action.DESCRIBE_SCENE"
        const val PREFS_NAME  = "guardian_prefs"
        const val KEY_RUNNING = "service_running"

        private const val NOTIF_ID      = 1001
        private const val CHANNEL_ID    = "vision_channel"
        private const val TAG           = "VisionService"

        // Ultimul frame capturat — accesat de SceneDescriptionManager la cerere
        val lastFrame = AtomicReference<Bitmap?>(null)
    }

    private val cameraExecutor    = Executors.newSingleThreadExecutor()
    private lateinit var detector : ObjectDetector
    private lateinit var bboxFilter: BoundingBoxFilter
    private lateinit var tlDetector: TrafficLightDetector
    private lateinit var messenger : WearableMessenger
    private lateinit var sceneDesc : SceneDescriptionManager

    // Stare adaptivă inferență
    private var frameCounter = 0
    private var skipEvery    = 3   // 30fps display → 10fps inferență la start

    // Debounce haptic — nu trimitem spam la ceas
    private var lastEvent     : HapticEvent? = null
    private var lastEventTime = 0L
    private val DEBOUNCE_MS   = 900L

    override fun onCreate() {
        super.onCreate()
        bboxFilter = BoundingBoxFilter()
        tlDetector = TrafficLightDetector()
        messenger  = WearableMessenger(this)
        sceneDesc  = SceneDescriptionManager(this)
        initDetector()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_DESCRIBE_SCENE) {
            lastFrame.get()?.let { sceneDesc.describe(it) }
            return START_STICKY
        }

        startForeground(NOTIF_ID, buildNotification())
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(KEY_RUNNING, true).apply()
        startCamera()
        return START_STICKY
    }

    private fun initDetector() {
        val opts = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE) // optimizat pentru video continuu
            .enableMultipleObjects()
            .enableClassification()
            .build()
        detector = ObjectDetection.getClient(opts)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor, ::analyzeFrame)

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            }.onFailure { Log.e(TAG, "Camera bind failed: ${it.message}") }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        frameCounter++
        if (frameCounter % skipEvery != 0) { proxy.close(); return }

        val mediaImage = proxy.image ?: run { proxy.close(); return }
        val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)

        detector.process(input)
            .addOnSuccessListener { objects ->
                val imgH = proxy.height.toFloat()
                val imgW = proxy.width.toFloat()

                // Stocăm ultimul frame pentru descriere scenă la cerere
                runCatching { lastFrame.set(proxy.toBitmap()) }

                // 1. Filtrare obstacole upper-body
                val threats = bboxFilter.filter(objects, imgH, imgW)

                // 2. Detecție semafor pe obiectele clasificate ca "Traffic light"
                val trafficEvent = objects
                    .filter { o -> o.labels.any { it.text == "Traffic light" && it.confidence > 0.65f } }
                    .firstNotNullOfOrNull { o ->
                        val bmp = lastFrame.get() ?: return@firstNotNullOfOrNull null
                        when (tlDetector.detectColor(bmp, o.boundingBox)) {
                            TrafficLightColor.GREEN -> HapticEvent.TRAFFIC_LIGHT_GREEN
                            TrafficLightColor.RED   -> HapticEvent.TRAFFIC_LIGHT_RED
                            else -> null
                        }
                    }

                val finalEvent = trafficEvent ?: when {
                    threats.any { it.urgency == UrgencyLevel.IMMINENT }    -> HapticEvent.DANGER_IMMINENT
                    threats.any { it.urgency == UrgencyLevel.APPROACHING } -> HapticEvent.OBSTACLE_APPROACHING
                    threats.any { it.urgency == UrgencyLevel.DISTANT }     -> HapticEvent.OBSTACLE_DISTANT
                    else -> null
                }

                // Debounce + trimitere la ceas
                finalEvent?.let { event ->
                    val now = System.currentTimeMillis()
                    if (event != lastEvent || now - lastEventTime > DEBOUNCE_MS) {
                        messenger.send(event)
                        lastEvent     = event
                        lastEventTime = now
                        // Pericol iminent → creștem rata de inferență temporar
                        skipEvery = if (event == HapticEvent.DANGER_IMMINENT) 1 else 3
                    }
                } ?: run {
                    // Scenă liberă → reducem rata (economie baterie)
                    if (skipEvery < 6) skipEvery++
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun buildNotification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Guardian Vision", NotificationManager.IMPORTANCE_LOW)
                .also { it.description = "Asistentul vizual este activ" }
        )

        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Guardian Vision activ")
            .setContentText("Detectare obstacole în timp real")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        detector.close()
        sceneDesc.shutdown()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(KEY_RUNNING, false).apply()
        super.onDestroy()
    }
}