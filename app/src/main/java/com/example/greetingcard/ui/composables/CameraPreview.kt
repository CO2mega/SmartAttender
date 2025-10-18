package com.example.greetingcard.ui.composables

import android.Manifest
import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.util.Log
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import android.graphics.Matrix
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.camera.view.PreviewView

// Toggle to enable/disable verbose camera preview logs (set true to debug)
private const val CAMERA_PREVIEW_VERBOSE = false

@Composable
fun CameraPreview(
    lifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier,
    onFaceDetected: (Bitmap?, String) -> Unit
) {
    val context = LocalContext.current
    // permission handling
    val initialPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(initialPermission) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    if (!hasPermission) {
        // Show simple UI prompting for camera permission
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("应用需要摄像头权限以采集人脸")
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("请求摄像头权限")
                }
            }
        }
        return
    }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var lastFeature by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var bindAttempt by remember { mutableStateOf(0) }
    // face-stability tracking (thread-safe atomics)
    val stableFrames = remember { AtomicInteger(0) }
    val lastBoundingBox = remember { AtomicReference<Rect?>(null) }
    val lastDetectionMs = remember { AtomicLong(0L) }
    val requiredStableFrames = 6 // 连续稳定帧数门槛，可调整
    val minIntervalBetweenDetectionsMs = 1500L // 触发间隔，防止短时间重复
    // debug state to show frames received in UI
    val frameCounterState = remember { mutableStateOf(0) }

    // Hold refs so we can clean up
    val imageAnalyzerRef = remember { mutableStateOf<ImageAnalysis?>(null) }
    val detectorRef = remember { mutableStateOf<com.google.mlkit.vision.face.FaceDetector?>(null) }
    val previewViewRef = remember { mutableStateOf<androidx.camera.view.PreviewView?>(null) }
    // 当前绑定的用例引用（只解绑这两个，避免 unbindAll 导致竞态）
    val currentPreviewRef = remember { mutableStateOf<Preview?>(null) }
    val currentAnalyzerRef = remember { mutableStateOf<ImageAnalysis?>(null) }

    // Lifecycle observer: rebind camera on ON_RESUME; unbind on ON_PAUSE
    var resumeTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { resumeTick++ }
                Lifecycle.Event.ON_PAUSE -> {
                    try {
                        val provider = cameraProviderFuture.get()
                        val p = currentPreviewRef.value
                        val a = currentAnalyzerRef.value
                        if (p != null && a != null) provider.unbind(p, a) else if (p != null) provider.unbind(p) else if (a != null) provider.unbind(a)
                    } catch (_: Exception) {}
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Clean up executor when this composable leaves composition（仅在整体离开时关闭）
    DisposableEffect(Unit) {
        onDispose {
            try { executor.shutdown() } catch (_: Exception) {}
        }
    }

    Box(modifier = modifier) {
        // Debug overlay: show frame count and last detected feature
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text(text = "frames=${frameCounterState.value} feature=${lastFeature}", color = androidx.compose.ui.graphics.Color.White)
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    // 使用 TextureView 实现，减少某些机型 SurfaceView 的销毁/重建带来的用例抖动
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                previewView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                previewViewRef.value = previewView
                previewView
            },
            update = { previewView ->
                previewViewRef.value = previewView
            }
        )

        if (errorMessage != null) {
            // overlay error with retry
            Box(modifier = Modifier
                .fillMaxSize()
                .padding(16.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "摄像头启动失败: ${errorMessage}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { bindAttempt++ }) {
                        Text("重试")
                    }
                }
            }
        }
    }

    // Bind/unbind camera when lifecycleOwner, permission, retry changes, or on resume
    DisposableEffect(lifecycleOwner, hasPermission, bindAttempt, resumeTick) {
        var cameraProvider: ProcessCameraProvider?
        try {
            if (hasPermission) {
                cameraProvider = cameraProviderFuture.get()
                val previewView = previewViewRef.value
                if (previewView != null) {
                    // 如果之前有已绑定的用例，先精确解绑它们
                    try {
                        val prevP = currentPreviewRef.value
                        val prevA = currentAnalyzerRef.value
                        if (prevP != null && prevA != null) cameraProvider.unbind(prevP, prevA) else if (prevP != null) cameraProvider.unbind(prevP) else if (prevA != null) cameraProvider.unbind(prevA)
                    } catch (_: Exception) {}

                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                    val options = FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                        .setMinFaceSize(0.05f)
                        .build()
                    val detector = FaceDetection.getClient(options)
                    detectorRef.value = detector
                    var frameCounter = 0

                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setTargetResolution(Size(480, 640))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    try { previewView.display?.rotation?.let { imageAnalyzer.targetRotation = it } } catch (_: Exception) {}

                    imageAnalyzer.setAnalyzer(executor) { imageProxy: ImageProxy ->
                        frameCounter++
                        try { Handler(Looper.getMainLooper()).post { frameCounterState.value = frameCounter } } catch (_: Exception) {}
                        val rotation = imageProxy.imageInfo.rotationDegrees
                        // 使用 NV21 字节数组以避免 ExperimentalGetImage
                        val nv21 = yuv420888ToNv21(imageProxy)
                        if (nv21 == null) {
                            if (CAMERA_PREVIEW_VERBOSE) Log.w("CameraPreview", "NV21 conversion failed on frame $frameCounter")
                            try { imageProxy.close() } catch (_: Exception) {}
                            return@setAnalyzer
                        }
                        val inputImage = try {
                            InputImage.fromByteArray(
                                nv21,
                                imageProxy.width,
                                imageProxy.height,
                                rotation,
                                InputImage.IMAGE_FORMAT_NV21
                            )
                        } catch (_: Exception) {
                            Log.w("CameraPreview", "Failed to create InputImage from NV21")
                            try { imageProxy.close() } catch (_: Exception) {}
                            return@setAnalyzer
                        }
                        detector.process(inputImage)
                            .addOnSuccessListener { faces ->
                                if (CAMERA_PREVIEW_VERBOSE) Log.d("CameraPreview", "MLKit onSuccess, faces size=${faces.size} frame=${frameCounter} rot=${rotation} image=${imageProxy.width}x${imageProxy.height}")
                                if (faces.isNotEmpty()) {
                                    val face = faces[0]
                                    val currentRect = face.boundingBox

                                    // helper: compare two rects for stability (center move + size change)
                                    fun rectsAreSimilar(a: Rect, b: Rect): Boolean {
                                        val ax = (a.left + a.right) / 2f
                                        val ay = (a.top + a.bottom) / 2f
                                        val bx = (b.left + b.right) / 2f
                                        val by = (b.top + b.bottom) / 2f
                                        val dx = kotlin.math.abs(ax - bx)
                                        val dy = kotlin.math.abs(ay - by)
                                        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                        val norm = maxOf(a.width(), b.width()).toFloat().coerceAtLeast(1f)
                                        val moveRatio = dist / norm
                                        val wDiff = kotlin.math.abs(a.width() - b.width()) / norm
                                        val hDiff = kotlin.math.abs(a.height() - b.height()) / norm
                                        // thresholds: move <12% of face width, size changes <20%
                                        return moveRatio < 0.12f && wDiff < 0.20f && hDiff < 0.20f
                                    }

                                    val prevRect = lastBoundingBox.get()
                                    val isSimilar = prevRect?.let { rectsAreSimilar(it, currentRect) } ?: false
                                    if (isSimilar) {
                                        stableFrames.incrementAndGet()
                                    } else {
                                        stableFrames.set(1)
                                    }
                                    lastBoundingBox.set(currentRect)

                                    // Only trigger when face stable for required frames and enough time since last detection
                                    val now = System.currentTimeMillis()
                                    if (stableFrames.get() >= requiredStableFrames && (now - lastDetectionMs.get()) >= minIntervalBetweenDetectionsMs) {
                                        lastDetectionMs.set(now)
                                        stableFrames.set(0)
                                        val featureStr = "(${currentRect.left},${currentRect.top})-(${currentRect.right},${currentRect.bottom})"
                                        // update UI-observable lastFeature on main thread
                                        try { Handler(Looper.getMainLooper()).post { lastFeature = featureStr } } catch (_: Exception) {}

                                        // Proceed to capture/crop same as before, but use currentRect
                                        try {
                                            val pv = previewViewRef.value
                                            val rect = currentRect
                                            var bmpForCrop: Bitmap? = null
                                            var usedRect: Rect = rect
                                            try {
                                                bmpForCrop = nv21ToBitmap(nv21, imageProxy.width, imageProxy.height, rotation)
                                            } catch (_: Exception) { bmpForCrop = null }
                                            if (bmpForCrop == null) {
                                                val pvBmp = pv?.bitmap
                                                if (pvBmp != null) {
                                                    try {
                                                        val srcW = imageProxy.width.toFloat()
                                                        val srcH = imageProxy.height.toFloat()
                                                        val dstW = pvBmp.width.toFloat()
                                                        val dstH = pvBmp.height.toFloat()
                                                        val scaleX = dstW / srcW
                                                        val scaleY = dstH / srcH
                                                        val isFrontCamera = true
                                                        if (!isFrontCamera) {
                                                            usedRect = Rect(
                                                                (rect.left * scaleX).toInt(),
                                                                (rect.top * scaleY).toInt(),
                                                                (rect.right * scaleX).toInt(),
                                                                (rect.bottom * scaleY).toInt()
                                                            )
                                                        } else {
                                                            val scaledLeft = rect.left * scaleX
                                                            val scaledRight = rect.right * scaleX
                                                            val mappedLeft = (dstW - scaledRight).toInt()
                                                            val mappedRight = (dstW - scaledLeft).toInt()
                                                            usedRect = Rect(
                                                                mappedLeft,
                                                                (rect.top * scaleY).toInt(),
                                                                mappedRight,
                                                                (rect.bottom * scaleY).toInt()
                                                            )
                                                        }
                                                        bmpForCrop = pvBmp
                                                    } catch (_: Exception) {
                                                        Log.w("CameraPreview", "Failed to map bounding box to preview bitmap")
                                                        bmpForCrop = null
                                                    }
                                                }
                                            }

                                            if (bmpForCrop != null) {
                                                // add padding to bounding box to avoid tight crop (20% of max dimension)
                                                val left0 = usedRect.left.coerceAtLeast(0)
                                                val top0 = usedRect.top.coerceAtLeast(0)
                                                val right0 = usedRect.right.coerceAtMost(bmpForCrop.width)
                                                val bottom0 = usedRect.bottom.coerceAtMost(bmpForCrop.height)
                                                val w = (right0 - left0)
                                                val h = (bottom0 - top0)
                                                val pad = ((maxOf(w, h) * 0.20f).toInt()).coerceAtLeast(0)
                                                val left = (left0 - pad).coerceAtLeast(0)
                                                val top = (top0 - pad).coerceAtLeast(0)
                                                val right = (right0 + pad).coerceAtMost(bmpForCrop.width)
                                                val bottom = (bottom0 + pad).coerceAtMost(bmpForCrop.height)
                                                val ww = right - left
                                                val hh = bottom - top
                                                if (ww > 0 && hh > 0) {
                                                    var bitmap = Bitmap.createBitmap(bmpForCrop, left, top, ww, hh)
                                                    try {
                                                        // If using front camera, mirror horizontally so the saved/returned face appears natural
                                                        val isFrontCamera = true // This composable uses Default Front Camera
                                                        if (isFrontCamera) {
                                                            val matrix = Matrix().apply { preScale(-1f, 1f) }
                                                            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
                                                        }
                                                    } catch (_: Exception) {}
                                                    onFaceDetected(bitmap, featureStr)
                                                } else {
                                                    onFaceDetected(null, featureStr)
                                                }
                                            } else {
                                                // Preview bitmap not available; return detected feature only
                                                onFaceDetected(null, featureStr)
                                            }
                                        } catch (_: Exception) {
                                            Log.w("CameraPreview", "Failed to obtain preview bitmap")
                                            try { onFaceDetected(null, featureStr) } catch (_: Exception) {}
                                        }
                                    }
                                } else {
                                    // no faces: reset stability counters
                                    stableFrames.set(0)
                                    lastBoundingBox.set(null)
                                    // no faces: log more info occasionally
                                    if (CAMERA_PREVIEW_VERBOSE) {
                                        if (frameCounter % 15 == 0) Log.d("CameraPreview", "No faces detected (frame=${'$'}frameCounter) - rotation=${'$'}rotation size=${'$'}{imageProxy.width}x${'$'}{imageProxy.height}")
                                    }
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.w("CameraPreview", "MLKit detection failed", e)
                            }
                            .addOnCompleteListener {
                                try { imageProxy.close() } catch (_: Exception) {}
                            }
                     }

                    // 保存引用，供解绑使用
                    imageAnalyzerRef.value = imageAnalyzer
                    currentAnalyzerRef.value = imageAnalyzer

                    // 绑定用例（不再使用 unbindAll）
                    try {
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalyzer
                        )
                        currentPreviewRef.value = preview
                        errorMessage = null
                    } catch (e: Exception) {
                        errorMessage = e.message
                        Log.e("CameraPreview", "Use case binding failed", e)
                    }
                }
            }
        } catch (e: Exception) {
            errorMessage = e.message
            Log.e("CameraPreview", "Camera initialization failed", e)
        }

        onDispose {
            try { imageAnalyzerRef.value?.clearAnalyzer() } catch (_: Exception) {}
            try { detectorRef.value?.close() } catch (_: Exception) {}
            // 只解绑当前绑定的用例，避免误伤新绑定
            try {
                val provider = cameraProviderFuture.get()
                val p = currentPreviewRef.value
                val a = currentAnalyzerRef.value
                if (p != null && a != null) provider.unbind(p, a) else if (p != null) provider.unbind(p) else if (a != null) provider.unbind(a)
            } catch (_: Exception) {}
            // 清理引用；不要在这里关闭 executor（executor 生命周期由上面的 DisposableEffect(Unit) 管）
            imageAnalyzerRef.value = null
            currentAnalyzerRef.value = null
            currentPreviewRef.value = null
            detectorRef.value = null
            // 注意：不要把 previewViewRef 置空，避免下一次重绑时拿不到 SurfaceProvider
            // previewViewRef.value = null
        }
    }
}

private fun imageProxyToNV21(imageProxy: ImageProxy): ByteArray? {
    // 兼容旧名函数，内部委托到稳健实现
    return yuv420888ToNv21(imageProxy)
}

private fun yuv420888ToNv21(image: ImageProxy): ByteArray? {
    return try {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val ySize = width * height
        val uvSize = ySize / 2
        val out = ByteArray(ySize + uvSize)

        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()

        val yRowStride = yPlane.rowStride
        // U/V 的行步长与像素步长
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        // 拷贝 Y 分量（逐行，跳过每行末尾的 padding）
        var outIndex = 0
        if (yRowStride == width) {
            yBuffer.get(out, 0, ySize)
            outIndex = ySize
        } else {
            val yRow = ByteArray(yRowStride)
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(yRow, 0, yRowStride)
                System.arraycopy(yRow, 0, out, row * width, width)
            }
            outIndex = ySize
        }

        // 拷贝 VU 交错到 NV21（每 2x2 采样一次 U/V）
        var uvOutIndex = ySize
        val halfHeight = height / 2
        val halfWidth = width / 2
        // 逐像素读取（考虑 pixelStride）
        for (row in 0 until halfHeight) {
            for (col in 0 until halfWidth) {
                val uIndex = row * uRowStride + col * uPixelStride
                val vIndex = row * vRowStride + col * vPixelStride
                // NV21: V 在前 U 在后
                out[uvOutIndex++] = vBuffer.get(vIndex)
                out[uvOutIndex++] = uBuffer.get(uIndex)
            }
        }
        out
    } catch (_: Exception) {
        null
    }
}

private fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int, rotation: Int): Bitmap? {
    return try {
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), 85, out)
        val bytes = out.toByteArray()
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        bmp
    } catch (_: Exception) {
        null
    }
}
