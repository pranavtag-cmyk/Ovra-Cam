package com.ovra.cam

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity(), CameraEngine.Listener {

    private lateinit var previewView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var btnSwitch: Button
    private lateinit var btnTorch: Button
    private lateinit var btnFocus: Button
    private lateinit var btnDim: Button
    private lateinit var dimOverlay: View

    private val main = Handler(Looper.getMainLooper())
    private lateinit var engine: CameraEngine
    private lateinit var server: StreamServer
    private lateinit var beacon: Beacon

    private var surfaceReady = false
    private var wantFront = false
    private var wantRes = "1080p"
    private var dimmed = false

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
        override fun onDisplayChanged(displayId: Int) { updateDisplayRotation() }
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
        btnFocus = findViewById(R.id.btnFocus)
        btnDim = findViewById(R.id.btnDim)
        dimOverlay = findViewById(R.id.dimOverlay)

        val prefs = getPreferences(MODE_PRIVATE)
        wantFront = prefs.getBoolean("front", false)
        wantRes = prefs.getString("res", "1080p") ?: "1080p"

        engine = CameraEngine(this, this)
        server = StreamServer(PORT, ::onCommand,
            onClientChanged = { connected, address -> main.post { onClientChanged(connected, address) } },
            onNeedKeyframe = { engine.requestKeyframe() })
        server.start()
        beacon = Beacon(deviceName, PORT)
        beacon.start()

        btnSwitch.setOnClickListener { switchCamera() }
        btnTorch.setOnClickListener { engine.setTorch(!engine.torch) }
        btnFocus.setOnClickListener { if (engine.focusLocked) engine.autoFocus() else engine.lockFocus() }
        btnDim.setOnClickListener { setDimmed(true) }
        dimOverlay.setOnClickListener { setDimmed(false) }
        previewView.setOnClickListener { engine.refocus() }

        previewView.holder.setFixedSize(1920, 1080)
        previewView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                startCameraIfReady()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                engine.stop()
            }
        })
        findViewById<View>(android.R.id.content).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreview() }

        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, main)
        updateDisplayRotation()

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        }

        updateInfo()
        main.postDelayed(statusTick, 5000)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        server.stop()
        beacon.stop()
        engine.release()
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

    /** Keep the preview 16:9 and centred. */
    private fun fitPreview() {
        val root = findViewById<View>(android.R.id.content)
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        var pw = w
        var ph = w * 9 / 16
        if (ph > h) { ph = h; pw = h * 16 / 9 }
        val lp = previewView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != pw || lp.height != ph) {
            lp.width = pw
            lp.height = ph
            previewView.layoutParams = lp
        }
    }

    @Suppress("DEPRECATION")
    private fun updateDisplayRotation() {
        val r = (if (Build.VERSION.SDK_INT >= 30) display?.rotation else windowManager.defaultDisplay.rotation) ?: Surface.ROTATION_90
        engine.displayRotationDegrees = when (r) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasCameraPermission()) startCameraIfReady()
        else {
            statusText.text = "Camera permission needed"
            Toast.makeText(this, "Ovra Cam needs camera access to work as a webcam", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCameraIfReady() {
        if (!surfaceReady || !hasCameraPermission()) return
        engine.start(previewView.holder.surface, wantFront, wantRes)
    }

    private fun switchCamera() {
        wantFront = !engine.front
        savePrefs()
        startCameraIfReady()
    }

    private fun savePrefs() {
        getPreferences(MODE_PRIVATE).edit().putBoolean("front", wantFront).putString("res", wantRes).apply()
    }

    private fun setDimmed(on: Boolean) {
        dimmed = on
        dimOverlay.visibility = if (on) View.VISIBLE else View.GONE
        val lp = window.attributes
        lp.screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    // ---------------- CameraEngine.Listener ----------------

    override fun onVideo(data: ByteArray, rotation: Int, flags: Int) {
        server.sendVideo(data, rotation, flags)
    }

    override fun onStateChanged() {
        main.post {
            btnTorch.isEnabled = engine.hasFlash
            btnTorch.alpha = if (engine.hasFlash) 1f else 0.4f
            btnTorch.text = if (engine.torch) "Light on" else "Light off"
            btnFocus.text = if (engine.focusLocked) "Focus: locked" else "Focus: auto"
            sendStatus()
        }
    }

    override fun onError(message: String) {
        main.post {
            statusText.text = message
            statusText.setTextColor(0xFFFF6B6B.toInt())
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- PC link ----------------

    private fun onCommand(cmd: JSONObject) {
        main.post {
            when (cmd.optString("cmd")) {
                "switch" -> switchCamera()
                "front" -> if (!engine.front) switchCamera()
                "back" -> if (engine.front) switchCamera()
                "torch" -> engine.setTorch(cmd.optBoolean("on"))
                "keyframe" -> engine.requestKeyframe()
                "res" -> {
                    val v = cmd.optString("value")
                    if (v in listOf("720p", "1080p", "4k") && v != engine.resolution) {
                        wantRes = v
                        savePrefs()
                        startCameraIfReady()
                    } else sendStatus()
                }
                "focus" -> when (cmd.optString("mode")) {
                    "lock" -> engine.lockFocus()
                    "auto" -> engine.autoFocus()
                    "refocus" -> engine.refocus()
                    "manual" -> engine.setManualFocus(cmd.optDouble("value", 0.0))
                }
                "hello" -> sendStatus()
            }
        }
    }

    private fun onClientChanged(connected: Boolean, address: String?) {
        if (connected) {
            val via = if (address == "127.0.0.1" || address == "::1" || address?.contains("127.0.0.1") == true) "USB" else "Wi-Fi"
            statusText.text = "Streaming to PC · $via"
            statusText.setTextColor(0xFF3DDC97.toInt())
            server.primeWithConfig(engine.config, engine.rotation, if (engine.front) 1 else 0)
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
            .put("camera", if (engine.front) "front" else "back")
            .put("torch", engine.torch)
            .put("hasTorch", engine.hasFlash)
            .put("res", engine.resolution)
            .put("resolutions", JSONArray(engine.supportedResolutions()))
            .put("battery", battery())
            .put("focusLocked", engine.focusLocked)
            .put("focus", engine.focusValue)
            .put("canManualFocus", engine.canManualFocus)
        server.sendStatus(json)
    }

    private val statusTick = object : Runnable {
        override fun run() {
            if (server.hasClient) sendStatus()
            updateInfo()
            main.postDelayed(this, 3000)
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
