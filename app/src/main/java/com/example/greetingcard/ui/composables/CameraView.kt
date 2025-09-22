package com.example.greetingcard.ui.composables

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleOwner
import androidx.camera.core.ExperimentalGetImage
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.greetingcard.viewmodel.MainViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.security.MessageDigest
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class, androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun CameraView(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onFaceRecognized: ((String) -> Unit)? = null // 新增人脸识别回调
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    // Camera permission state
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.onCameraPermissionGranted()
        } else {
            viewModel.onCameraPermissionDenied(cameraPermissionState.status.shouldShowRationale)
        }
    }

    LaunchedEffect(Unit) { // Request permission when the composable enters composition
        if (cameraPermissionState.status != PermissionStatus.Granted) {
            launcher.launch(Manifest.permission.CAMERA)
        } else {
            viewModel.onCameraPermissionGranted() // Already granted
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (uiState.cameraPermissionGranted) {
            // AndroidView to host CameraX PreviewView
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    // Initialize CameraX here using the previewView and lifecycleOwner
                    setupCamera(ctx, previewView, lifecycleOwner, onFaceRecognized)
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
            // You can add overlays here for face detection boxes, etc.
            Text("Camera Preview Area", Modifier.align(Alignment.Center))
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    if (uiState.showPermissionRationale) {
                        "Camera permission is needed to scan faces. Please grant the permission."
                    } else {
                        "Camera permission denied. Please enable it in app settings."
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    if (uiState.showPermissionRationale) {
                        launcher.launch(Manifest.permission.CAMERA)
                    } else {
                        // Open app settings
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = Uri.fromParts("package", context.packageName, null)
                        context.startActivity(intent)
                    }
                }) {
                    Text(if (uiState.showPermissionRationale) "Grant Permission" else "Open Settings")
                }
            }
        }
    }
}

// Minimal CameraX + ML Kit setup: binds front camera preview and image analysis
@androidx.camera.core.ExperimentalGetImage
private fun setupCamera(
    context: Context,
    previewView: PreviewView,
    lifecycleOwner: LifecycleOwner,
    onFaceRecognized: ((String) -> Unit)?
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    val cameraExecutor = Executors.newSingleThreadExecutor()

    val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()
    val detector = FaceDetection.getClient(options)

    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()

        val preview = Preview.Builder().build().also { p ->
            p.setSurfaceProvider(previewView.surfaceProvider)
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy: ImageProxy ->
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                try {
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
                    detector.process(inputImage)
                        .addOnSuccessListener { faces ->
                            if (faces.isNotEmpty()) {
                                val f = faces[0]
                                // Build a simple deterministic feature string from face properties
                                val featureBase = "${f.boundingBox.left},${f.boundingBox.top},${f.boundingBox.width()},${f.boundingBox.height()},${f.headEulerAngleY},${f.headEulerAngleZ},$rotationDegrees"
                                val featureHash = sha256(featureBase)
                                onFaceRecognized?.invoke(featureHash)
                            }
                        }
                        .addOnFailureListener {
                            // ignore
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } catch (_: Exception) {
                    imageProxy.close()
                }
            } else {
                imageProxy.close()
            }
        }

        try {
            cameraProvider.unbindAll()
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
        } catch (_: Exception) {
            // ignore bind errors for now
        }
    }, ContextCompat.getMainExecutor(context))
}

private fun sha256(input: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
