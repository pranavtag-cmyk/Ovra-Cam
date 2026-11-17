package com.ovra.cam

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var btnSwitch: Button
    private lateinit var btnTorch: Button
    private lateinit var btnDim: Button
    private lateinit var dimOverlay: View

    private val main = Handler(Looper.getMainLooper())
    private lateinit var analysisExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null

    private var useFront = false
    private var torchOn = false
    private var res = "720p"
    private var dimmed = false
    private var lastFrameNs = 0L
    private val encoder = JpegEncoder()

    private lateinit var server: StreamServer
    private lateinit var beacon: Beacon
    private var pcAddress: String? = null

    private val deviceName: String by lazy {
        val user = try { Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) } catch (_: Exception) { null }
        if (!user.isNullOrBlank()) user
        else {
            val m = Build.MODEL ?: "Android"
            val brand = (Build.MANUFACTURER ?: "").replaceFirstChar { it.uppercase() }
            if (m.startsWith(brand, ignoreCase = true)) m else "$brand $m"
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val rotation = previewView.display?.rotation ?: return
            analysis?.targetRotation = rotation
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        previewView = findViewById(R.id.preview)
        statusText = findViewById(R.id.status)
        infoText = findViewById(R.id.info)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnTorch = findViewById(R.id.btnTorch)
        btnDim = findViewById(R.id.btnDim)
        dimOverlay = findViewById(R.id.dimOverlay)

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        analysisExecutor = Executors.newSingleThreadExecutor()

        btnSwitch.setOnClickListener { switchCamera() }
        btnTorch.setOnClickListener { setTorch(!torchOn) }
        btnDim.setOnClickListener { setDimmed(true) }
        dimOverlay.setOnClickListener { setDimmed(false) }
        setupTapToFocus()

        server = StreamServer(PORT, ::onCommand) { connected, address ->
            main.post { onClientChanged(connected, address) }
        }
        server.start()
        beacon = Beacon(deviceName, PORT)
        beacon.start()

        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, main)

        if (hasCameraPermission()) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)

        updateInfo()
        main.postDelayed(statusTick, 5000)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        server.stop()
        beacon.stop()
        analysisExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        updateInfo()
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasCameraPermission()) startCamera()
        else {
            statusText.text = "Camera permission needed"
            Toast.makeText(this, "Ovra Cam needs camera access to work as a webcam", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- camera ----------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun targetSize(): Size = if (res == "1080p") Size(1920, 1080) else Size(1280, 720)

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!provider.hasCamera(selector)) {
            useFront = !useFront
        }
        val finalSelector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(targetSize(), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build())
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val rotation = previewView.display?.rotation ?: windowManager.defaultDisplay.rotation
        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetRotation(rotation)
            .build()
        imageAnalysis.setAnalyzer(analysisExecutor) { image ->
            try {
                val now = System.nanoTime()
                // only encode when a PC is connected, and cap at ~30 fps
                if (server.hasClient && now - lastFrameNs >= 31_000_000L) {
                    lastFrameNs = now
                    val quality = if (res == "1080p") 75 else 80
                    val jpeg = encoder.encode(image, quality)
                    val flags = if (useFront) 1 else 0
                    server.sendFrame(jpeg, image.imageInfo.rotationDegrees, flags)
                }
            } catch (_: Exception) {
            } finally {
                image.close()
            }
        }
        analysis = imageAnalysis

        try {
            camera = provider.bindToLifecycle(this, finalSelector, preview, imageAnalysis)
            torchOn = false
            updateTorchUi()
            sendStatus()
        } catch (e: Exception) {
            statusText.text = "Camera error: ${e.message}"
        }
    }

    private fun switchCamera() {
        useFront = !useFront
        bindCamera()
    }

    private fun setTorch(on: Boolean) {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) return
        cam.cameraControl.enableTorch(on)
        torchOn = on
        updateTorchUi()
        sendStatus()
    }

    private fun updateTorchUi() {
        val has = camera?.cameraInfo?.hasFlashUnit() == true
        btnTorch.isEnabled = has
        btnTorch.alpha = if (has) 1f else 0.4f
        btnTorch.text = if (torchOn) "Light on" else "Light off"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToFocus() {
        previewView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                v.performClick()
            }
            true
        }
    }

    private fun setDimmed(on: Boolean) {
        dimmed = on
        dimOverlay.visibility = if (on) View.VISIBLE else View.GONE
        val lp = window.attributes
        lp.screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    // ---------------- PC link ----------------

    private fun onCommand(cmd: JSONObject) {
        main.post {
            when (cmd.optString("cmd")) {
                "switch" -> switchCamera()
                "torch" -> setTorch(cmd.optBoolean("on"))
                "res" -> {
                    val v = cmd.optString("value")
                    if ((v == "720p" || v == "1080p") && v != res) {
                        res = v
                        bindCamera()
                    } else sendStatus()
                }
                "front" -> { if (!useFront) switchCamera() }
                "back" -> { if (useFront) switchCamera() }
                "hello" -> sendStatus()
            }
        }
    }

    private fun onClientChanged(connected: Boolean, address: String?) {
        pcAddress = if (connected) address else null
        if (connected) {
            val via = if (address == "127.0.0.1" || address == "::1" || address?.startsWith("::ffff:127.") == true) "USB" else "Wi-Fi"
            statusText.text = "Streaming to PC · $via"
            statusText.setTextColor(0xFF3DDC97.toInt())
            sendStatus()
        } else {
            statusText.text = "Waiting for your PC"
            statusText.setTextColor(0xFFFFB547.toInt())
            if (dimmed) setDimmed(false)
        }
    }

    private fun battery(): Int = try {
        (getSystemService(BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) { -1 }

    private fun sendStatus() {
        if (!::server.isInitialized) return
        val json = JSONObject()
            .put("device", deviceName)
            .put("camera", if (useFront) "front" else "back")
            .put("torch", torchOn)
            .put("hasTorch", camera?.cameraInfo?.hasFlashUnit() == true)
            .put("res", res)
            .put("battery", battery())
        server.sendStatus(json)
    }

    private val statusTick = object : Runnable {
        override fun run() {
            if (server.hasClient) sendStatus()
            updateInfo()
            main.postDelayed(this, 5000)
        }
    }

    private fun updateInfo() {
        val ips = Beacon.localAddresses()
        infoText.text = if (ips.isEmpty()) "USB: turn on USB debugging · Wi-Fi: not connected"
        else "Wi-Fi IP: ${ips.first()} · or plug in USB"
    }

    companion object {
        const val PORT = 5150
    }
}
