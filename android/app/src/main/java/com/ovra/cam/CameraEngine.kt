package com.ovra.cam

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface

/**
 * Camera2 capture straight into the phone's hardware H.264 encoder (up to 4K), plus an on-screen preview.
 * Encoded output is Annex B (start codes), handed to [Listener.onVideo].
 */
class CameraEngine(private val context: Context, private val listener: Listener) {

    interface Listener {
        /** flags: 1 = front camera, 2 = keyframe, 4 = codec config (SPS/PPS) */
        fun onVideo(data: ByteArray, rotation: Int, flags: Int)
        fun onStateChanged()
        fun onError(message: String)
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val camThread = HandlerThread("ovra-camera").apply { start() }
    private val camHandler = Handler(camThread.looper)
    private val encThread = HandlerThread("ovra-encoder").apply { start() }
    private val encHandler = Handler(encThread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var codec: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var previewSurface: Surface? = null
    private var generation = 0

    // current settings / state (read by the UI)
    @Volatile var front = false; private set
    @Volatile var resolution = "1080p"; private set
    @Volatile var torch = false; private set
    @Volatile var hasFlash = false; private set
    @Volatile var focusLocked = false; private set
    @Volatile var canManualFocus = false; private set
    @Volatile var focusValue = 0.0; private set   // 0 = infinity .. 1 = closest
    @Volatile var displayRotationDegrees = 0
    @Volatile var config: ByteArray? = null; private set

    private var sensorOrientation = 90
    private var minFocusDistance = 0f
    private var lastFocusDistance = 0f
    private var manualDistance = -1f      // >= 0 when locked with a manual distance
    private var refocusing = false
    private var refocusDeadline = 0L
    private var fpsRange: Range<Int>? = null

    val rotation: Int
        get() = if (front) (sensorOrientation + displayRotationDegrees) % 360
        else (sensorOrientation - displayRotationDegrees + 360) % 360

    // ---------------- capabilities ----------------

    private fun cameraIdFor(wantFront: Boolean): String? {
        val facing = if (wantFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
        }
    }

    private fun encoderSupports(w: Int, h: Int): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities.areSizeAndRateSupported(w, h, 30.0)
        }
    } catch (_: Exception) { false }

    private val resCache = HashMap<Boolean, List<String>>()

    fun supportedResolutions(wantFront: Boolean = front): List<String> =
        synchronized(resCache) { resCache.getOrPut(wantFront) { querySupported(wantFront) } }

    private fun querySupported(wantFront: Boolean): List<String> {
        val id = cameraIdFor(wantFront) ?: return listOf("720p")
        val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return listOf("720p")
        val sizes = map.getOutputSizes(MediaCodec::class.java)?.toSet() ?: emptySet()
        return RES.filter { (_, s) -> sizes.contains(s) && encoderSupports(s.width, s.height) }.map { it.first }
            .ifEmpty { listOf("720p") }
    }

    // ---------------- start / stop ----------------

    fun start(preview: Surface?, wantFront: Boolean, res: String) {
        camHandler.post { startInternal(preview, wantFront, res) }
    }

    fun stop() {
        camHandler.post { stopInternal() }
    }

    fun release() {
        camHandler.post {
            stopInternal()
            camThread.quitSafely()
            encThread.quitSafely()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startInternal(preview: Surface?, wantFront: Boolean, res: String) {
        stopInternal()
        val gen = ++generation
        try {
            var id = cameraIdFor(wantFront)
            var isFront = wantFront
            if (id == null) { id = cameraIdFor(!wantFront); isFront = !wantFront }
            if (id == null) { listener.onError("No camera found"); return }

            val ch = manager.getCameraCharacteristics(id)
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            minFocusDistance = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            canManualFocus = minFocusDistance > 0f && caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
            fpsRange = ranges.filter { it.upper == 30 }.maxByOrNull { it.lower } ?: ranges.maxByOrNull { it.upper }

            val supported = supportedResolutions(isFront)
            val chosen = if (supported.contains(res)) res else if (supported.contains("1080p")) "1080p" else supported.first()
            val size = RES.first { it.first == chosen }.second

            front = isFront
            resolution = chosen
            torch = false
            focusLocked = false
            manualDistance = -1f
            config = null

            val c = createEncoder(size, chosen)
            codec = c
            encoderSurface = c.createInputSurface()
            c.start()
            previewSurface = preview

            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    if (gen != generation) { cam.close(); return }
                    device = cam
                    createSession(cam, gen)
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close(); if (device === cam) device = null }
                override fun onError(cam: CameraDevice, error: Int) {
                    cam.close()
                    if (device === cam) device = null
                    listener.onError("Camera error $error")
                }
            }, camHandler)
            listener.onStateChanged()
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            listener.onError(e.message ?: "Camera failed to start")
        }
    }

    private fun createEncoder(size: Size, res: String): MediaCodec {
        val bitrate = when (res) { "4k" -> 35_000_000; "1080p" -> 12_000_000; else -> 6_000_000 }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "encoder error", e)
            }
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buf.get(data)
                        val frontFlag = if (front) 1 else 0
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            config = data
                            listener.onVideo(data, rotation, 4 or frontFlag)
                        } else {
                            val key = if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) 2 else 0
                            listener.onVideo(data, rotation, key or frontFlag)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (_: IllegalStateException) {
                    // codec was stopped while we were reading
                }
            }
        }, encHandler)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return c
    }

    @Suppress("DEPRECATION")
    private fun createSession(cam: CameraDevice, gen: Int) {
        val targets = listOfNotNull(previewSurface?.takeIf { it.isValid }, encoderSurface)
        try {
            cam.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != generation) { s.close(); return }
                    session = s
                    applyRequest()
                    listener.onStateChanged()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    listener.onError("This camera can't stream at $resolution - try a lower quality")
                }
            }, camHandler)
        } catch (e: Exception) {
            listener.onError(e.message ?: "Camera session failed")
        }
    }

    private fun stopInternal() {
        generation++
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { encoderSurface?.release() } catch (_: Exception) {}
        encoderSurface = null
    }

    // ---------------- capture request ----------------

    private fun buildRequest(): CaptureRequest.Builder? {
        val cam = device ?: return null
        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        previewSurface?.takeIf { it.isValid }?.let { b.addTarget(it) }
        encoderSurface?.let { b.addTarget(it) }
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        fpsRange?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        b.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)

        when {
            refocusing -> b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            focusLocked && canManualFocus && manualDistance >= 0f -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualDistance)
            }
            focusLocked -> b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO) // stays put after a trigger
            else -> b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        return b
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let {
                lastFocusDistance = it
                if (!focusLocked || manualDistance < 0f) {
                    focusValue = if (minFocusDistance > 0f) (it / minFocusDistance).toDouble().coerceIn(0.0, 1.0) else 0.0
                }
            }
            if (refocusing) {
                val af = result.get(CaptureResult.CONTROL_AF_STATE)
                val done = af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                if (done || System.currentTimeMillis() > refocusDeadline) {
                    refocusing = false
                    lockAtCurrent()
                }
            }
        }
    }

    private fun applyRequest() {
        val s = session ?: return
        try {
            val b = buildRequest() ?: return
            s.setRepeatingRequest(b.build(), captureCallback, camHandler)
        } catch (e: Exception) {
            Log.w(TAG, "request failed: ${e.message}")
        }
    }

    // ---------------- controls ----------------

    fun setTorch(on: Boolean) = camHandler.post {
        if (!hasFlash) return@post
        torch = on
        applyRequest()
        listener.onStateChanged()
    }

    /** Lock focus where it is now. */
    fun lockFocus() = camHandler.post { lockAtCurrent() }

    private fun lockAtCurrent() {
        focusLocked = true
        manualDistance = if (canManualFocus) lastFocusDistance.coerceAtLeast(0f) else -1f
        if (canManualFocus && minFocusDistance > 0f) focusValue = (manualDistance / minFocusDistance).toDouble().coerceIn(0.0, 1.0)
        applyRequest()
        listener.onStateChanged()
    }

    fun autoFocus() = camHandler.post {
        focusLocked = false
        manualDistance = -1f
        refocusing = false
        applyRequest()
        listener.onStateChanged()
    }

    /** value: 0 = far (infinity) .. 1 = closest. */
    fun setManualFocus(value: Double) = camHandler.post {
        if (!canManualFocus) return@post
        focusLocked = true
        refocusing = false
        focusValue = value.coerceIn(0.0, 1.0)
        manualDistance = (focusValue * minFocusDistance).toFloat()
        applyRequest()
        listener.onStateChanged()
    }

    /** Focus once on the centre, then lock. */
    fun refocus() = camHandler.post {
        val s = session ?: return@post
        try {
            refocusing = true
            refocusDeadline = System.currentTimeMillis() + 2500
            applyRequest()
            val b = buildRequest() ?: return@post
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), captureCallback, camHandler)
        } catch (e: Exception) {
            refocusing = false
        }
    }

    fun requestKeyframe() = encHandler.post {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "OvraCamera"
        val RES = listOf(
            "720p" to Size(1280, 720),
            "1080p" to Size(1920, 1080),
            "4k" to Size(3840, 2160),
        )
    }
}
