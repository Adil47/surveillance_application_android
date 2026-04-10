package com.example.surveillanceapp.ui.live

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

@Composable
fun PhoneCameraPreview(
    modifier: Modifier = Modifier,
    onFrame: (Bitmap) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionGranted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
    }

    LaunchedEffect(Unit) {
        launcher.launch(Manifest.permission.CAMERA)
    }

    if (!permissionGranted) return

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    DisposableEffect(previewView, lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        val listener = Runnable {
            val provider = providerFuture.get()
            bindCamera(provider, lifecycleOwner, previewView, context, onFrame)
        }
        providerFuture.addListener(listener, executor)

        onDispose {
            runCatching {
                val provider = providerFuture.get()
                provider.unbindAll()
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { previewView },
    )
}

private fun bindCamera(
    provider: ProcessCameraProvider,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    context: Context,
    onFrame: (Bitmap) -> Unit,
) {
    val preview = Preview.Builder().build().apply {
        surfaceProvider = previewView.surfaceProvider
    }
    val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
    analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { image ->
        val bmp = imageProxyToBitmap(image)
        image.close()
        if (bmp != null) onFrame(bmp)
    }
    val selector = CameraSelector.DEFAULT_BACK_CAMERA

    provider.unbindAll()
    provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
}

private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    if (image.format != ImageFormat.YUV_420_888) return null
    val nv21 = yuv420888ToNv21(image) ?: return null
    val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
    val out = ByteArrayOutputStream()
    yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
    val bytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

private fun yuv420888ToNv21(image: ImageProxy): ByteArray? {
    val yPlane = image.planes.getOrNull(0) ?: return null
    val uPlane = image.planes.getOrNull(1) ?: return null
    val vPlane = image.planes.getOrNull(2) ?: return null
    val ySize = image.width * image.height
    val uvSize = ySize / 2
    val out = ByteArray(ySize + uvSize)

    copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, image.width, image.height, out, 0, 1)
    // NV21 expects interleaved VU.
    copyPlane(vPlane.buffer, vPlane.rowStride, vPlane.pixelStride, image.width / 2, image.height / 2, out, ySize, 2)
    copyPlane(uPlane.buffer, uPlane.rowStride, uPlane.pixelStride, image.width / 2, image.height / 2, out, ySize + 1, 2)
    return out
}

private fun copyPlane(
    buffer: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    width: Int,
    height: Int,
    out: ByteArray,
    outOffset: Int,
    outPixelStride: Int,
) {
    val data = ByteArray(buffer.remaining())
    buffer.get(data)
    var outputPos = outOffset
    for (row in 0 until height) {
        val rowStart = row * rowStride
        for (col in 0 until width) {
            out[outputPos] = data[rowStart + col * pixelStride]
            outputPos += outPixelStride
        }
    }
}
