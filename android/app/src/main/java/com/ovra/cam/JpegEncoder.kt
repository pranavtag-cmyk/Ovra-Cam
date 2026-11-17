package com.ovra.cam

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/** YUV_420_888 -> NV21 -> JPEG, reusing buffers between frames. */
class JpegEncoder {
    private var nv21 = ByteArray(0)
    private var rowV = ByteArray(0)
    private var rowU = ByteArray(0)
    private val out = ByteArrayOutputStream(512 * 1024)

    fun encode(image: ImageProxy, quality: Int): ByteArray {
        val w = image.width
        val h = image.height
        val size = w * h * 3 / 2
        if (nv21.size != size) nv21 = ByteArray(size)

        // Y
        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yRow = yPlane.rowStride
        var pos = 0
        if (yRow == w) {
            yBuf.position(0)
            yBuf.get(nv21, 0, w * h)
            pos = w * h
        } else {
            for (r in 0 until h) {
                yBuf.position(r * yRow)
                yBuf.get(nv21, pos, w)
                pos += w
            }
        }

        // VU interleaved
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uvRow = vPlane.rowStride
        val pix = vPlane.pixelStride
        val cw = w / 2
        val ch = h / 2
        val rowLen = (cw - 1) * pix + 1
        if (rowV.size < rowLen) { rowV = ByteArray(rowLen); rowU = ByteArray(rowLen) }
        for (r in 0 until ch) {
            val start = r * uvRow
            vBuf.position(start)
            vBuf.get(rowV, 0, minOf(rowLen, vBuf.remaining()))
            uBuf.position(r * uPlane.rowStride)
            uBuf.get(rowU, 0, minOf(rowLen, uBuf.remaining()))
            var i = 0
            var c = 0
            while (c < cw) {
                nv21[pos++] = rowV[i]
                nv21[pos++] = rowU[i]
                i += pix
                c++
            }
        }

        out.reset()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, out)
        return out.toByteArray()
    }
}
