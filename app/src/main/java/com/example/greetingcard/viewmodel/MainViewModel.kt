package com.example.greetingcard.viewmodel

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.greetingcard.data.AppDatabase
import com.example.greetingcard.data.FaceEntity
import com.example.greetingcard.data.SignInRecord
import com.example.greetingcard.ml.FaceEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.lang.StringBuilder

// UI state for camera permission and rationale
data class CameraUiState(
    val cameraPermissionGranted: Boolean = false,
    val showPermissionRationale: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "greeting_card_db"
    ).fallbackToDestructiveMigration().build()
    val faceDao = db.faceDao()
    val signInDao = db.signInDao()

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    // Expose faces and records as state flows for UI
    private val _faces = MutableStateFlow<List<FaceEntity>>(emptyList())
    val faces: StateFlow<List<FaceEntity>> = _faces.asStateFlow()

    private val _records = MutableStateFlow<List<SignInRecord>>(emptyList())
    val records: StateFlow<List<SignInRecord>> = _records.asStateFlow()

    init {
        // Load initial data
        viewModelScope.launch {
            _faces.value = faceDao.getAllFaces()
            _records.value = signInDao.getAllRecords()
            // Normalize existing NFC IDs once on startup so scan format matches stored format
            try { normalizeStoredNfcIds() } catch (_: Exception) {}
        }
    }

    private fun normalizeStoredNfcIds() {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val list = faceDao.getAllFaces()
                var updated = false
                val re = Regex("[^0-9A-Fa-f]")
                for (f in list) {
                    val orig = f.nfcId
                    val norm = orig.trim().replace(re, "").uppercase()
                    if (norm.isNotBlank() && norm != orig) {
                        try {
                            faceDao.updateFace(f.copy(nfcId = norm))
                            updated = true
                        } catch (_: Exception) {}
                    }
                }
                if (updated) {
                    try { _faces.value = faceDao.getAllFaces() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            _faces.value = faceDao.getAllFaces()
            _records.value = signInDao.getAllRecords()
        }
    }

    fun addOrUpdateFace(face: FaceEntity, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            if (face.id == 0L) {
                faceDao.insertFace(face)
            } else {
                faceDao.updateFace(face)
            }
            _faces.value = faceDao.getAllFaces()
            onComplete?.invoke()
        }
    }

    fun deleteFace(face: FaceEntity, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            faceDao.deleteFace(face)
            _faces.value = faceDao.getAllFaces()
            onComplete?.invoke()
        }
    }

    // Export sign-in CSV. Made suspend to avoid blocking the main thread; performs DB + file IO on IO dispatcher.
    suspend fun exportSignInCsv(context: Context): File? {
        return try {
            // Get records on IO
            val recordsList = withContext(Dispatchers.IO) { signInDao.getAllRecords() }
            val sb = StringBuilder()
            sb.append("faceId,nfcId,timestamp,isSigned\n")
            for (r in recordsList) {
                sb.append("${'$'}{r.faceId},${'$'}{r.nfcId},${'$'}{r.timestamp},${'$'}{r.isSigned}\n")
            }

            val bytes = sb.toString().toByteArray()
            val appCtx = context.applicationContext

            // For Android Q+ use MediaStore to place file into Downloads/SmartAttender (scoped storage compatible)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val resolver = appCtx.contentResolver
                val displayName = "sign_in_export.csv"
                val relPath = Environment.DIRECTORY_DOWNLOADS + "/SmartAttender"
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = try { resolver.insert(collection, values) } catch (_: Exception) { null }
                if (uri != null) {
                    // write on IO
                    withContext(Dispatchers.IO) {
                        resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    }
                    values.clear()
                    values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    try { resolver.update(uri, values, null, null) } catch (_: Exception) {}
                    // Try to return a File object pointing to the absolute path if possible (not always available); otherwise return a File under app cache
                    return try {
                        val cacheFile = File(appCtx.cacheDir, "sign_in_export.csv")
                        withContext(Dispatchers.IO) { FileOutputStream(cacheFile).use { it.write(bytes) } }
                        cacheFile
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    // insert failed -> fall back to legacy path below
                }
            }

            // Fallback for pre-Q or if MediaStore insert failed: attempt legacy public downloads directory
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadsDir, "SmartAttender")
            if (!targetDir.exists()) targetDir.mkdirs()
            val outFile = File(targetDir, "sign_in_export.csv")
            withContext(Dispatchers.IO) { FileOutputStream(outFile).use { it.write(bytes) } }
            outFile
        } catch (_: Exception) {
            null
        }
    }

    // For testing: clear all faces and sign-in records from the database
    fun clearDatabase(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            try {
                // Delete all faces
                try {
                    val facesList = faceDao.getAllFaces()
                    for (f in facesList) {
                        try { faceDao.deleteFace(f) } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                // Delete all sign-in records
                try {
                    val recs = signInDao.getAllRecords()
                    for (r in recs) {
                        try { signInDao.deleteRecord(r) } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                // Refresh cached flows
                try { _faces.value = faceDao.getAllFaces() } catch (_: Exception) { _faces.value = emptyList() }
                try { _records.value = signInDao.getAllRecords() } catch (_: Exception) { _records.value = emptyList() }
            } catch (_: Exception) {
            }
            onComplete?.invoke()
        }
    }

    fun onCameraPermissionGranted() {
        _uiState.update { it.copy(cameraPermissionGranted = true, showPermissionRationale = false) }
    }

    fun onCameraPermissionDenied(showRationale: Boolean) {
        _uiState.update { it.copy(cameraPermissionGranted = false, showPermissionRationale = showRationale) }
    }

    // 修改：回调返回匹配到的 FaceEntity（若有）以及相似度/可信度（Float，范围 0..1）
    fun matchFaceAndNfc(faceFeature: String, nfcId: String, onResult: (FaceEntity?, Float) -> Unit) {
        viewModelScope.launch {
            val face = faceDao.getFaceByNfcId(nfcId)
            val matched = face != null && face.faceFeature == faceFeature
            if (matched) {
                // face is non-null here because matched == true
                signInDao.insertRecord(
                    SignInRecord(
                        faceId = face!!.id,
                        nfcId = nfcId,
                        timestamp = System.currentTimeMillis(),
                        isSigned = true
                    )
                )
            }
            withContext(Dispatchers.Main) {
                if (matched && face != null) onResult(face, 1.0f) else onResult(null, 0f)
            }
        }
    }

    // Compute embedding from Bitmap using FaceEmbedder (runs on background thread)
    suspend fun computeEmbeddingFromBitmap(bitmap: android.graphics.Bitmap): FloatArray? {
        return withContext(Dispatchers.Default) {
            try {
                val ctx = getApplication<Application>().applicationContext
                val ok = FaceEmbedder.initialize(ctx, "mobile_face_net.tflite", 2)
                if (!ok) return@withContext null
                FaceEmbedder.getEmbedding(bitmap)
            } catch (e: Exception) {
                null
            }
        }
    }

    // Match embedding with stored embedding for the given NFC id. 回调返回匹配的 FaceEntity（或 null）和相似度
    fun matchEmbeddingAndNfc(embedding: FloatArray, nfcId: String, onResult: (FaceEntity?, Float) -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val face = faceDao.getFaceByNfcId(nfcId)
                var sim = 0f
                val matched = if (face?.embedding != null) {
                    val stored = FaceEmbedder.base64ToFloatArray(face.embedding!!)
                    if (stored != null) {
                        sim = FaceEmbedder.cosineSimilarity(embedding, stored)
                        // threshold tuned for ArcFace-like model on controlled capture; choose 0.65 as default
                        sim >= 0.65f
                    } else false
                } else false
                // NOTE: do NOT insert a record here. Return the match result to caller so the caller
                // can decide when and with what timestamp to create the sign-in record (important to
                // preserve the face-detected time as the sign-in timestamp).
                withContext(Dispatchers.Main) {
                    if (matched && face != null) onResult(face, sim) else onResult(null, sim)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(null, 0f) }
            }
        }
    }

    // Match embedding against all stored embeddings (no NFC). Returns matched FaceEntity via callback和相似度。
    // 注意：不在这里写入签到记录，让上层在 NFC 到来后统一调用 createSignInRecord，从而实现“先脸再卡”。
    fun matchEmbedding(embedding: FloatArray, onResult: (FaceEntity?, Float) -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val facesList = faceDao.getAllFaces()
                var best: FaceEntity? = null
                var bestSim = -1f
                for (f in facesList) {
                    try {
                        val embB64 = f.embedding ?: continue
                        val stored = FaceEmbedder.base64ToFloatArray(embB64) ?: continue
                        val sim = FaceEmbedder.cosineSimilarity(embedding, stored)
                        if (sim > bestSim) {
                            bestSim = sim
                            best = f
                        }
                    } catch (_: Exception) {
                        continue
                    }
                }
                // threshold for match
                val threshold = 0.65f
                val matched = if (best != null && bestSim >= threshold) best else null
                // 不写入记录，交由上层在 NFC 到来后调用 createSignInRecord
                withContext(Dispatchers.Main) {
                    if (matched != null) onResult(matched, bestSim) else onResult(null, bestSim)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(null, 0f) }
            }
        }
    }

    // Admin unlock flow: used when biometric succeeds to signal Compose UI
    private val _adminUnlocked = MutableStateFlow(false)
    val adminUnlocked: StateFlow<Boolean> = _adminUnlocked.asStateFlow()

    fun setAdminUnlocked(value: Boolean) {
        _adminUnlocked.value = value
    }

    // 新增：用于请求人脸和NFC采集
    private val _faceScanRequest = MutableStateFlow<((String) -> Unit)?>(null)
    val faceScanRequest: StateFlow<((String) -> Unit)?> = _faceScanRequest.asStateFlow()
    private val _nfcScanRequest = MutableStateFlow<((String) -> Unit)?>(null)
    val nfcScanRequest: StateFlow<((String) -> Unit)?> = _nfcScanRequest.asStateFlow()

    // 新增：最近一次扫描到的 NFC 内容（用于 UI 自动填充）
    private val _lastNfcScan = MutableStateFlow<String?>(null)
    val lastNfcScan: StateFlow<String?> = _lastNfcScan.asStateFlow()

    // 新增：最近扫描到的 NFC 队列（有序，尾部为最新），用于“静默识别”
    private val _nfcQueue = MutableStateFlow<List<String>>(emptyList())
    val nfcQueue: StateFlow<List<String>> = _nfcQueue.asStateFlow()

    // 新增：最近一次扫描的时间戳（毫秒），可用于 UI 显示或与数据库对比
    private val _lastNfcScanTime = MutableStateFlow<Long?>(null)
    val lastNfcScanTime: StateFlow<Long?> = _lastNfcScanTime.asStateFlow()

    // 规范化卡号：去分隔符并转大写十六进制
    private fun normalizeNfc(id: String): String = id.trim().replace(Regex("[^0-9A-Fa-f]"), "").uppercase()

    // 入队 NFC 扫描，最多保留最近 10 条；保持向后兼容更新 lastNfcScan；若有一次性回调也会投递
    fun enqueueNfcScan(rawId: String) {
        val norm = normalizeNfc(rawId)
        // 更新最近一次（兼容管理页面）
        _lastNfcScan.value = norm
        // 记录最近一次扫描时间
        _lastNfcScanTime.value = System.currentTimeMillis()
        // 入队（尾部最新）并限制容量
        _nfcQueue.update { old ->
            val next = old + norm
            if (next.size > 10) next.takeLast(10) else next
        }
        // 若注册了一次性回调，优先回调并清空回调
        _nfcScanRequest.value?.let { cb ->
            try { cb(norm) } catch (_: Exception) {}
            _nfcScanRequest.value = null
        }
    }

    // 返回指定 faceId + nfcId 的最新签到时间（毫秒），异步回调
    fun getLastSignInTimestamp(faceId: Long, nfcId: String, onComplete: (Long?) -> Unit) {
        viewModelScope.launch {
            try {
                val rec = signInDao.getRecord(faceId, nfcId)
                withContext(Dispatchers.Main) { onComplete(rec?.timestamp) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onComplete(null) }
            }
        }
    }

    // 判断最近一次签到是否在指定时间窗口内（毫秒），用于去重；回调返回(Pair(isRecent,lastTimestamp))
    fun isRecentSignIn(faceId: Long, nfcId: String, withinMs: Long = 10_000L, onComplete: (Boolean, Long?) -> Unit) {
        getLastSignInTimestamp(faceId, nfcId) { lastTs ->
            if (lastTs == null) {
                onComplete(false, null)
            } else {
                val now = System.currentTimeMillis()
                val isRecent = (now - lastTs) <= withinMs
                onComplete(isRecent, lastTs)
            }
        }
    }

    // 出队：弹出最近的一条 NFC（尾部）
    fun popLatestNfc(): String? {
        val cur = _nfcQueue.value
        if (cur.isEmpty()) return null
        val last = cur.last()
        _nfcQueue.value = cur.dropLast(1)
        return last
    }

    fun clearNfcQueue() {
        _nfcQueue.value = emptyList()
    }

    fun startFaceScan(callback: (String) -> Unit) {
        _faceScanRequest.value = callback
    }
    fun startNfcScan(callback: (String) -> Unit) {
        // Register one-shot callback
        _nfcScanRequest.value = callback
        try { android.util.Log.d("MainViewModel", "startNfcScan registered callback") } catch (_: Exception) {}
        // If we already have a cached last NFC scan, deliver it immediately to avoid race
        val cached = _lastNfcScan.value
        if (!cached.isNullOrBlank()) {
            try {
                // deliver on main thread
                viewModelScope.launch(Dispatchers.Main) {
                    try {
                        callback(cached)
                    } catch (_: Exception) {}
                    // clear the cached value after delivery
                    _lastNfcScan.value = null
                    // clear the one-off callback slot as it's consumed
                    _nfcScanRequest.value = null
                    try { android.util.Log.d("MainViewModel", "startNfcScan delivered cached NFC: $cached") } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }
    // UI采集到结果后调用
    fun onFaceScanResult(result: String) {
        _faceScanRequest.value?.invoke(result)
        _faceScanRequest.value = null
    }
    fun onNfcScanResult(result: String) {
        try { android.util.Log.d("MainViewModel", "onNfcScanResult received(raw): $result (callbackExists=${_nfcScanRequest.value != null})") } catch (_: Exception) {}
        // 统一走入队逻辑（含规范化、lastNfcScan 更新、一次性回调派发）
        try {
            enqueueNfcScan(result)
            try { android.util.Log.d("MainViewModel", "onNfcScanResult enqueued and updated lastNfcScan") } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    // Allow UI to clear the last NFC scan after it's been consumed (prevents repeated autofill)
    fun clearLastNfcScan() {
        _lastNfcScan.value = null
    }

    // Create a sign-in record for a faceId with associated nfcId.
    // This runs on a background coroutine and refreshes the cached records when done.
    // Accepts an optional timestamp (ms). If provided, it will be used as the sign-in time
    // (useful when face scan happened before NFC and that timestamp should be preserved).
    fun createSignInRecord(faceId: Long, nfcId: String, timestampMs: Long? = null, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val ts = timestampMs ?: System.currentTimeMillis()
                signInDao.insertRecord(
                    SignInRecord(
                        faceId = faceId,
                        nfcId = nfcId,
                        timestamp = ts,
                        isSigned = true
                    )
                )
                _records.value = signInDao.getAllRecords()
            } catch (_: Exception) {
                // ignore insertion errors for now
            }
            onComplete?.invoke()
        }
    }

    // Camera active state for controlling camera lifecycle
    private val _cameraActive = MutableStateFlow(true)
    val cameraActive: StateFlow<Boolean> = _cameraActive.asStateFlow()

    fun setCameraActive(active: Boolean) {
        _cameraActive.value = active
    }
}
