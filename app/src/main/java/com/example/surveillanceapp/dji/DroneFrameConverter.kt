package com.example.surveillanceapp.dji

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import dji.v5.manager.interfaces.ICameraStreamManager
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Converts raw camera frames from [ICameraStreamManager.CameraFrameListener] into Bitmaps.
 *
 * Prefers **RGBA8888** for direct ARGB buffers. **NV21** uses a small JPEG bridge (ok for Step 2
 * verification; Step 3+ should feed TFLite tensors without full Bitmap round-trips).
 */
object DroneFrameConverter {

    fun toBitmap(
        data: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
        format: ICameraStreamManager.FrameFormat,
    ): Bitmap? {
        if (width <= 0 || height <= 0) return null
        return when (format) {
            ICameraStreamManager.FrameFormat.RGBA_8888 -> rgba8888ToBitmap(data, offset, width, height)
            ICameraStreamManager.FrameFormat.NV21 -> nv21ToBitmapViaJpeg(data, offset, length, width, height)
            else -> null
        }
    }

    private fun rgba8888ToBitmap(data: ByteArray, offset: Int, width: Int, height: Int): Bitmap? {
        val expectedBytes = width * height * 4
        if (offset + expectedBytes > data.size) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val buffer = ByteBuffer.wrap(data, offset, expectedBytes).order(ByteOrder.nativeOrder())
        bmp.copyPixelsFromBuffer(buffer)
        return bmp
    }

    private fun nv21ToBitmapViaJpeg(
        data: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
    ): Bitmap? {
        val expected = width * height * 3 / 2
        if (expected <= 0) return null
        val copyLen = min(length, expected).coerceAtMost(data.size - offset)
        if (copyLen <= 0) return null
        val yuv = ByteArray(expected)
        System.arraycopy(data, offset, yuv, 0, copyLen)
        val yuvImage = YuvImage(yuv, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
