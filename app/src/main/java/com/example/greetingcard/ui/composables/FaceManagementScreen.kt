package com.example.greetingcard.ui.composables

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import com.example.greetingcard.data.FaceEntity
import com.example.greetingcard.ml.FaceEmbedder
import com.example.greetingcard.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

@Composable
fun FaceManagementScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val faces by viewModel.faces.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var editingFace by remember { mutableStateOf<FaceEntity?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "人脸管理", style = MaterialTheme.typography.titleLarge)
            Row {
                Button(onClick = { editingFace = null; showDialog = true }) {
                    Text("新增人脸")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = { showClearConfirm = true }) {
                    Text("一键清空数据库", color = MaterialTheme.colorScheme.onError)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(faces) { face ->
                Card(modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(text = "姓名: ${face.name}")
                            Text(text = "NFC: ${face.nfcId}")
                        }
                        Row {
                            TextButton(onClick = { editingFace = face; showDialog = true }) { Text("编辑") }
                            TextButton(onClick = { viewModel.deleteFace(face) }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        FaceEditDialog(
            initial = editingFace,
            viewModel = viewModel,
            onDismiss = { showDialog = false; editingFace = null },
            onSave = { face ->
                viewModel.addOrUpdateFace(face) { showDialog = false; editingFace = null }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("确认清空数据库") },
            text = { Text("此操作会删除所有人脸和签到记录，仅用于测试，确定要继续吗？") },
            confirmButton = {
                Button(onClick = {
                    showClearConfirm = false
                    // call ViewModel to clear DB and show toast when done
                    viewModel.clearDatabase {
                        try { android.widget.Toast.makeText(context, "数据库已清空", android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                        // refresh local UI state
                        try { viewModel.refreshData() } catch (_: Exception) {}
                    }
                }) { Text("确认") }
            },
            dismissButton = { Button(onClick = { showClearConfirm = false }) { Text("取消") } }
        )
    }
}

@Composable
fun FaceEditDialog(initial: FaceEntity?, viewModel: MainViewModel, onDismiss: () -> Unit, onSave: (FaceEntity) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var nfc by remember { mutableStateOf(initial?.nfcId ?: "") }
    var feature by remember { mutableStateOf(initial?.faceFeature ?: "") }
    var faceScanned by remember { mutableStateOf(false) }
    // Only auto-open camera when creating a new face (initial == null). When editing, do not
    // immediately show the camera; instead display existing image/feature if present.
    var showCamera by remember { mutableStateOf(initial == null) }
    val context = LocalContext.current
    // Note: do NOT auto-fill immediately when a scan arrives. We will consume the cached
    // last scan only when the user explicitly taps the "读卡" button below.
    var detectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    // Track whether the current NFC is already taken by another face
    var nfcOwnerId by remember { mutableStateOf<Long?>(null) }
    var nfcOwnerName by remember { mutableStateOf<String?>(null) }

    // Helper to normalize NFC ID to uppercase hex without separators
    fun normalizeNfc(id: String): String = id.trim().replace(Regex("[^0-9A-Fa-f]"), "").uppercase()

    // If we are editing an existing face that already has an NFC, check DB once at dialog open
    LaunchedEffect(initial?.nfcId) {
        val idVal = initial?.nfcId
        if (!idVal.isNullOrBlank()) {
            try {
                val existing = viewModel.faceDao.getFaceByNfcId(idVal)
                if (existing != null) {
                    nfcOwnerId = existing.id
                    nfcOwnerName = existing.name
                } else {
                    nfcOwnerId = null
                    nfcOwnerName = null
                }
            } catch (_: Exception) {
                // ignore
            }
        }
    }
    // Form validation: require name, nfc and either a captured face feature or an image
    // Also require that the NFC is not owned by another face (or is owned by the current editing face)
    val isFormValid by remember(name, nfc, feature, detectedBitmap, nfcOwnerId) {
        derivedStateOf {
            val ownsOrFree = nfcOwnerId == null || nfcOwnerId == (initial?.id ?: 0L)
            name.isNotBlank() && nfc.isNotBlank() && (feature.isNotBlank() || detectedBitmap != null) && ownsOrFree
        }
    }

    // If editing an existing face, populate detectedBitmap and feature so UI shows existing capture
    LaunchedEffect(initial) {
        val facePath = initial?.faceImagePath
        val faceFeat = initial?.faceFeature
        if (!facePath.isNullOrBlank() || !faceFeat.isNullOrBlank()) {
            // load bitmap from path if available
            try {
                val bmp = if (!facePath.isNullOrBlank()) android.graphics.BitmapFactory.decodeFile(facePath) else null
                detectedBitmap = bmp
            } catch (_: Exception) {
                detectedBitmap = null
            }
            feature = faceFeat ?: ""
            // mark as scanned so UI shows existing data without opening camera
            faceScanned = feature.isNotBlank() || detectedBitmap != null
            // ensure camera remains hidden until user explicitly requests re-capture
            showCamera = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增人脸" else "编辑人脸") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("姓名") })
                Spacer(modifier = Modifier.height(8.dp))
                // NFC field: read-only text + read-button
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = nfc, onValueChange = { }, label = { Text("NFC卡号") }, modifier = Modifier.weight(1f), enabled = false)
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        // First try to consume a cached NFC scan saved in the ViewModel
                        val cached = try { viewModel.lastNfcScan.value } catch (_: Exception) { null }
                        if (!cached.isNullOrBlank()) {
                            // Use cached value and clear it
                            scope.launch {
                                val norm = normalizeNfc(cached)
                                nfc = norm
                                // Check if another face already uses this NFC and store owner info
                                try {
                                    val existing = viewModel.faceDao.getFaceByNfcId(norm)
                                    if (existing != null) {
                                        nfcOwnerId = existing.id
                                        nfcOwnerName = existing.name
                                        if (existing.id != (initial?.id ?: 0L)) {
                                            try { android.widget.Toast.makeText(context, "卡号已被用户 ${existing.name} 使用", android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                                        } else {
                                            // it's the same record we are editing
                                            nfcOwnerId = existing.id
                                            nfcOwnerName = existing.name
                                            try { android.widget.Toast.makeText(context, "已填写卡号: $norm", android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                                        }
                                    } else {
                                        nfcOwnerId = null
                                        nfcOwnerName = null
                                        try { android.widget.Toast.makeText(context, "已填写卡号: $norm", android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                                    }
                                } catch (_: Exception) {
                                    nfcOwnerId = null
                                    nfcOwnerName = null
                                }
                                try { viewModel.clearLastNfcScan() } catch (_: Exception) {}
                            }
                        } else {
                            // No cached value -> start an on-demand NFC scan and fill when result arrives
                            viewModel.startNfcScan { result ->
                                scope.launch {
                                    val norm = normalizeNfc(result)
                                    nfc = norm
                                    try {
                                        val existing = viewModel.faceDao.getFaceByNfcId(norm)
                                        if (existing != null) {
                                            nfcOwnerId = existing.id
                                            nfcOwnerName = existing.name
                                            if (existing.id != (initial?.id ?: 0L)) {
                                                try { android.widget.Toast.makeText(context, "卡号已被用户 ${existing.name} 使用", android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                                            }
                                        } else {
                                            nfcOwnerId = null
                                            nfcOwnerName = null
                                        }
                                    } catch (_: Exception) {
                                        nfcOwnerId = null
                                        nfcOwnerName = null
                                    }
                                }
                            }
                        }
                    }) {
                        Text("读卡")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                // Face capture area: show existing capture when available; otherwise provide a button
                if (!faceScanned) {
                    // Show a button to start camera capture on demand (do not auto-open when editing)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { showCamera = true }) {
                            Text("采集人脸")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "还未采集人脸特征，可点击采集", style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Show the camera dialog only when explicitly requested (showCamera true) and no face has been confirmed yet
                if (showCamera && !faceScanned) {
                    Text("请正对摄像头采集人脸特征：")
                    CameraCaptureDialog(onResult = { bmp, faceFeature ->
                        feature = faceFeature
                        detectedBitmap = bmp
                        faceScanned = true
                        showCamera = false
                    }, onDismiss = {
                        // when camera dialog dismissed without capture
                        showCamera = false
                    })
                }
                if (faceScanned) {
                    Text(text = "已采集人脸特征", style = MaterialTheme.typography.bodySmall)
                    detectedBitmap?.let { bmp ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "已采集人脸",
                            modifier = Modifier
                                .size(120.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Keep '重新采集' visible only when a capture exists
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { showCamera = true; faceScanned = false; feature = ""; detectedBitmap = null }, enabled = faceScanned) {
                            Text("重新采集")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        // Centralized Save button: enabled only when form is valid
                        Button(onClick = {
                            // central save logic with final DB check to avoid race
                            scope.launch {
                                // normalize NFC before final checks and save
                                val nfcFinal = normalizeNfc(nfc)
                                // final check: ensure NFC not taken by someone else
                                try {
                                    val existingNow = viewModel.faceDao.getFaceByNfcId(nfcFinal)
                                    if (existingNow != null && existingNow.id != (initial?.id ?: 0L)) {
                                        try { android.widget.Toast.makeText(context, "保存失败：卡号已被用户 ${existingNow.name} 使用", android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                                        // update inline owner info
                                        nfcOwnerId = existingNow.id
                                        nfcOwnerName = existingNow.name
                                        return@launch
                                    }
                                } catch (_: Exception) { /* ignore and continue */ }

                                saving = true
                                if (detectedBitmap != null) {
                                    val savedPath = try {
                                        val facesDir = File(context.filesDir, "faces")
                                        if (!facesDir.exists()) facesDir.mkdirs()
                                        val outFile = File(facesDir, "face_${System.currentTimeMillis()}.jpg")
                                        FileOutputStream(outFile).use { fos ->
                                            detectedBitmap!!.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                                        }
                                        outFile.absolutePath
                                    } catch (_: Exception) {
                                        null
                                    }
                                    // compute embedding if model available
                                    var embeddingBase64: String? = null
                                    try {
                                        val ok = FaceEmbedder.initialize(context, "mobile_face_net.tflite", 2)
                                        if (ok) {
                                          val emb = FaceEmbedder.getEmbedding(detectedBitmap!!)
                                          if (emb != null) embeddingBase64 = FaceEmbedder.floatArrayToBase64(emb)
                                        }
                                    } catch (_: Exception) {}
                                    saving = false
                                    if (savedPath != null) {
                                        val entity = FaceEntity(
                                            id = initial?.id ?: 0L,
                                            name = name,
                                            nfcId = nfcFinal,
                                            faceFeature = feature,
                                            faceImagePath = savedPath,
                                            embedding = embeddingBase64
                                        )
                                        onSave(entity)
                                    }
                                } else {
                                    // fallback: save without image
                                    saving = false
                                    val entity = FaceEntity(
                                        id = initial?.id ?: 0L,
                                        name = name,
                                        nfcId = nfcFinal,
                                        faceFeature = feature,
                                        faceImagePath = null,
                                        embedding = null
                                    )
                                    onSave(entity)
                                }
                            }
                        }, enabled = isFormValid && !saving) {
                            Text(if (saving) "保存中..." else "保存并关闭")
                        }
                    }
                    // Inline validation hint when form is incomplete or NFC is taken
                    if (!isFormValid) {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (nfcOwnerId != null && nfcOwnerId != (initial?.id ?: 0L)) {
                            Text(text = "卡号已被 ${nfcOwnerName ?: "其他用户"} 使用，无法保存", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(text = "信息不完整：姓名、卡号、及人脸数据均为必填", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Button(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun CameraCaptureDialog(onResult: (Bitmap?, String) -> Unit, onDismiss: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var latestFeature by remember { mutableStateOf("") }
    var showConfirm by remember { mutableStateOf(false) }

    // Make the camera dialog non-dismissible by back press or outside taps to avoid accidental
    // return/cancellation that could lose the captured face data when creating a new face.
    Dialog(
        onDismissRequest = { /* ignore default dismiss gestures; use explicit UI controls to cancel */ },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top-left exit button so user can explicitly close the camera dialog
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        TextButton(onClick = { try { onDismiss() } catch (_: Exception) {} }) {
                            Text("退出", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                CameraPreview(
                    lifecycleOwner = lifecycleOwner,
                    modifier = Modifier.fillMaxSize(),
                    onFaceDetected = { bitmap, feature ->
                        // CameraPreview may call on background thread; post updates to main looper
                        try {
                            Handler(Looper.getMainLooper()).post {
                                latestBitmap = bitmap
                                latestFeature = feature
                                showConfirm = true
                            }
                        } catch (_: Exception) {}
                    }
                )

                if (showConfirm) {
                    // Bottom confirmation bar
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                latestBitmap?.let { bmp ->
                                    Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(88.dp).clip(RoundedCornerShape(8.dp)))
                                    Spacer(modifier = Modifier.width(12.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "已检测到人脸，请确认录入", fontSize = 16.sp, color = Color.Black)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(text = "位置: $latestFeature", style = MaterialTheme.typography.bodySmall)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                                    TextButton(onClick = {
                                        // Confirm: return result and close dialog
                                        onResult(latestBitmap, latestFeature)
                                        showConfirm = false
                                    }) { Text("确认") }
                                    TextButton(onClick = {
                                        // Cancel: hide confirm bar and continue preview
                                        showConfirm = false
                                        latestBitmap = null
                                        latestFeature = ""
                                    }) { Text("取消") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
