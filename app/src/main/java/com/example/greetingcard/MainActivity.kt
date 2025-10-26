package com.example.greetingcard

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.core.content.ContextCompat
import android.util.Log
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.camera.lifecycle.ProcessCameraProvider
import com.example.greetingcard.ui.composables.CameraPreview
import com.example.greetingcard.ui.composables.FaceManagementScreen
 import com.example.greetingcard.ui.composables.ExportScreen
import com.example.greetingcard.ui.theme.GreetingCardTheme
import com.example.greetingcard.viewmodel.MainViewModel
import com.example.greetingcard.ml.FaceEmbedder
import java.io.File
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import android.Manifest
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.greetingcard.data.FaceEntity
import android.graphics.BitmapFactory
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.lifecycle.lifecycleScope

class MainActivity : AppCompatActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFilters: Array<IntentFilter>? = null
    private lateinit var techListsForNfc: Array<Array<String>>
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private var cameraPermissionGranted: Boolean = false

    // make ViewModel an activity property so onNewIntent can call onNfcScanResult
    private lateinit var mainViewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // create MainViewModel here so Activity can observe scan requests
        mainViewModel = ViewModelProvider(this@MainActivity)[MainViewModel::class.java]

        // collect NFC-scan request to optionally show UI hint (we don't need pending callback in Activity now)
        lifecycleScope.launch {
            mainViewModel.nfcScanRequest.collect { cb ->
                if (cb != null) {
                    Log.d("MainActivity", "NFC scan requested - waiting for card")
                    try { android.widget.Toast.makeText(this@MainActivity, "等待刷卡以完成签到", android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                }
            }
        }

        // Try to initialize embedding model early so we can see logs at startup
        try {
            val ok = FaceEmbedder.initialize(applicationContext, "mobile_face_net.tflite", 2)
            if (ok) {
                Log.i("GreetingCard", "FaceEmbedder initialized at startup")
            } else {
                Log.w("GreetingCard", "FaceEmbedder not initialized (model missing or failed)")
            }
        } catch (e: Exception) {
            Log.e("GreetingCard", "FaceEmbedder init error", e)
        }

        // Register permission launcher early
        cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraPermissionGranted = granted
            if (!granted) {
                try { android.widget.Toast.makeText(this, "请授予摄像头权限以采集人脸", android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
            }
        }
        // Request camera permission if not already granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            cameraPermissionGranted = true
        }

        // Fullscreen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        enableEdgeToEdge()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        // Do not create PendingIntent here to avoid it being used while the activity is background.
        // We'll create the PendingIntent in onResume and cancel it in onPause.
         // Use ACTION_TAG_DISCOVERED and (optionally) NDEF to catch all tags
        val filterTag = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        val filters = mutableListOf<IntentFilter>()
        filters.add(filterTag)
        try {
            val filterNdef = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
            filterNdef.addDataType("*/*")
            filters.add(filterNdef)
        } catch (_: Exception) { }
        intentFilters = filters.toTypedArray()

        val techListsForDispatch = arrayOf(
            arrayOf(android.nfc.tech.Ndef::class.java.name),
            arrayOf(android.nfc.tech.NfcA::class.java.name),
            arrayOf(android.nfc.tech.IsoDep::class.java.name)
        )
        this.techListsForNfc = techListsForDispatch

        setContent {
            GreetingCardTheme {
                val mainViewModelInside: MainViewModel = mainViewModel
                var dragCount by remember { mutableStateOf(0) }
                var startAdminAuth by remember { mutableStateOf(false) }
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                var selectedPage by rememberSaveable { mutableStateOf(0) }

                // removed lastFaceFeature; not used in sign-in flow
                var lastDetectedBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

                // matched face to show in overlay card (used instead of Toast)
                var matchedFace by remember { mutableStateOf<FaceEntity?>(null) }
                var matchedConfidence by remember { mutableStateOf<Float?>(null) }
                var matchedBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                // Timestamp (ms) when the current matched face was detected. Use this as the canonical sign-in time.
                var faceDetectedTime by remember { mutableStateOf<Long?>(null) }

                // Sign-in UI state for the currently matched face
                var matchedSignInSuccess by remember { mutableStateOf(false) }
                var matchedSignInTime by remember { mutableStateOf<Long?>(null) }

                // Track whether we're currently awaiting an NFC scan for a particular face
                val awaitingNfcForFaceId = remember { mutableStateOf<Long?>(null) }

                // allow card-first window: pending NFC value to be used when a face is matched within 30s
                var pendingCardForFace by remember { mutableStateOf<String?>(null) }
                // store last computed embedding so we can re-check embedding+NFC when card-first path occurs
                var lastEmbedding by remember { mutableStateOf<FloatArray?>(null) }

                // avoid processing multiple frames concurrently
                var isProcessing by remember { mutableStateOf(false) }

                // Observe NFC queue for silent scans
                val nfcQueue by mainViewModelInside.nfcQueue.collectAsState()
                val lastNfc by mainViewModelInside.lastNfcScan.collectAsState()

                // New: observe sign-in records and faces to show homepage prompt when CardScanActivity creates a record
                val records by mainViewModelInside.records.collectAsState()
                val faces by mainViewModelInside.faces.collectAsState()
                // lastSeenRecordTs used only to deduplicate toast notifications
                var lastSeenRecordTs by rememberSaveable { mutableStateOf<Long?>(null) }

                LaunchedEffect(records) {
                    if (records.isNotEmpty()) {
                        val latest = records.maxByOrNull { it.timestamp }
                        if (latest != null && latest.timestamp != lastSeenRecordTs) {
                            lastSeenRecordTs = latest.timestamp
                            val latestSignInFaceName = faces.firstOrNull { it.id == latest.faceId }?.name ?: "用户"
                            // show a toast on home page to indicate clock-in time (no overlay)
                            try {
                                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                val formatted = try { sdf.format(java.util.Date(latest.timestamp)) } catch (_: Exception) { "" }
                                android.widget.Toast.makeText(this@MainActivity, "打卡成功：${latestSignInFaceName} $formatted", android.widget.Toast.LENGTH_LONG).show()
                            } catch (_: Exception) {}
                        }
                    }
                }

                // If a card is scanned while on camera page and no face matched - start 30s window to allow face to be scanned
                LaunchedEffect(lastNfc, selectedPage, matchedFace) {
                    if (selectedPage == 0 && !lastNfc.isNullOrBlank() && matchedFace == null) {
                        val n = lastNfc
                        pendingCardForFace = n
                        try { android.widget.Toast.makeText(this@MainActivity, "读取到卡片: $n，30秒内刷脸完成签到", android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception){}
                        // clear lastNfcScan after capturing into pending state so admin/management UI isn't affected
                        try { mainViewModelInside.clearLastNfcScan() } catch (_: Exception) {}
                        // Allow 30s for face to be matched
                        launch {
                            delay(30_000)
                            if (pendingCardForFace == n) {
                                pendingCardForFace = null
                                try { android.widget.Toast.makeText(this@MainActivity, "刷脸超时(30s)，请重新刷卡或人脸", android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                            }
                        }
                    }
                }

                // react to admin unlock events from ViewModel
                LaunchedEffect(mainViewModel.adminUnlocked) {
                    if (mainViewModel.adminUnlocked.value) {
                        Log.d("GreetingCard", "adminUnlocked observed")
                        try { android.widget.Toast.makeText(this@MainActivity, "adminUnlocked observed", android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                        delay(600)
                        selectedPage = 1
                        try { drawerState.open() } catch (e: Exception) { Log.e("GreetingCard", "drawer open failed", e) }
                        mainViewModel.setAdminUnlocked(false)
                    }
                }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet {
                            Column {
                                NavigationDrawerItem(
                                    label = { Text("Camera") },
                                    selected = selectedPage == 0,
                                    onClick = { scope.launch { selectedPage = 0; drawerState.close() } }
                                )
                                NavigationDrawerItem(
                                    label = { Text("人脸管理") },
                                    selected = selectedPage == 1,
                                    onClick = { scope.launch { selectedPage = 1; drawerState.close() } }
                                )
                                NavigationDrawerItem(
                                    label = { Text("签到数据导出") },
                                    selected = selectedPage == 2,
                                    onClick = { scope.launch { selectedPage = 2; drawerState.close() } }
                                )
                            }
                        }
                    }
                ) {
                    Scaffold(modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { _, dragAmount ->
                                if (dragAmount > 50) {
                                    dragCount++
                                    if (dragCount >= 3) {
                                        startAdminAuth = true
                                        dragCount = 0
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->

                        when (selectedPage) {
                            0 -> {
                                CameraPreview(
                                    lifecycleOwner = this@MainActivity,
                                    modifier = Modifier.padding(innerPadding),
                                    onFaceDetected = { bitmap, _ ->
                                        // no need to store feature string
                                        scope.launch { lastDetectedBitmap = bitmap }

                                        if (isProcessing) return@CameraPreview
                                        if (bitmap == null) return@CameraPreview

                                        isProcessing = true
                                        scope.launch {
                                            try {
                                                val emb = mainViewModelInside.computeEmbeddingFromBitmap(bitmap)
                                                // store embedding so we can use it if card-first path happens
                                                lastEmbedding = emb
                                                if (emb != null) {
                                                    mainViewModelInside.matchEmbedding(emb) { face, confidence ->
                                                        if (face != null) {
                                                            matchedFace = face
                                                            matchedConfidence = confidence
                                                            matchedBitmap = lastDetectedBitmap
                                                            // record when face was detected (canonical sign-in time)
                                                            faceDetectedTime = System.currentTimeMillis()
                                                        }
                                                    }
                                                } else {
                                                    Log.w("MainActivity", "computeEmbeddingFromBitmap returned null")
                                                }
                                            } catch (e: Exception) {
                                                Log.e("MainActivity", "Error computing embedding/matching", e)
                                            } finally {
                                                delay(200)
                                                isProcessing = false
                                            }
                                        }
                                    }
                                )

                                // Bottom card: show when matchedFace is non-null. Apply navigationBarsPadding so card sits above system nav bar.
                                matchedFace?.let { mf ->
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp)
                                                .navigationBarsPadding(),
                                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Row(modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                val bmpToShow = matchedBitmap ?: try { mf.faceImagePath?.let { BitmapFactory.decodeFile(it) } } catch (_: Exception) { null }
                                                if (bmpToShow != null) {
                                                    Image(bitmap = bmpToShow.asImageBitmap(), contentDescription = "matched", modifier = Modifier.size(88.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(text = mf.name, style = MaterialTheme.typography.titleMedium)
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    val conf = matchedConfidence ?: 0f
                                                    Text(text = "可信度: ${"%.2f".format(conf * 100)}%", style = MaterialTheme.typography.bodyMedium)
                                                    // Show NFC prompt or success under the confidence
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    if (matchedSignInSuccess && matchedSignInTime != null) {
                                                        // format time
                                                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                                        val formatted = try { sdf.format(java.util.Date(matchedSignInTime!!)) } catch (_: Exception) { "" }
                                                        Text(text = "刷卡成功: $formatted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                                    } else if (awaitingNfcForFaceId.value == mf.id) {
                                                        Text(text = "请刷卡完成签到", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    TextButton(onClick = { matchedFace = null; matchedConfidence = null; matchedBitmap = null }) {
                                                        Text("关闭")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // 当匹配到人脸后：若已缓存有 NFC，直接记账；否则注册一次性 NFC 等待刷卡
                                LaunchedEffect(matchedFace?.id) {
                                    matchedSignInSuccess = false
                                    matchedSignInTime = null

                                    val mfLocal = matchedFace
                                    if (mfLocal != null) {
                                        // 把刷卡流程交给专门的 CardScanActivity 来处理（该 Activity 不会使用摄像头，仅负责 NFC 扫描与匹配）
                                        try {
                                            val normalizedExpected = fun(id: String?): String { return id?.trim()?.uppercase().orEmpty() }
                                            val intent = Intent(this@MainActivity, CardScanActivity::class.java).apply {
                                                putExtra("faceId", mfLocal.id)
                                                putExtra("faceName", mfLocal.name)
                                                putExtra("expectedNfc", normalizedExpected(mfLocal.nfcId))
                                                // 如果已有 embedding（来自刚刚计算的 embedding），也一并传入以便 CardScanActivity 做更严格的匹配
                                                lastEmbedding?.let { putExtra("embedding", it) }
                                                // pass the face-detected timestamp so CardScanActivity can use it for the record
                                                putExtra("faceDetectedTs", faceDetectedTime ?: System.currentTimeMillis())
                                            }
                                            try { startActivity(intent) } catch (e: Exception) { Log.e("MainActivity", "Failed to start CardScanActivity", e) }
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "Error launching CardScanActivity", e)
                                        }

                                        // 清理 pending 状态，CardScanActivity 会负责后续记录写入与提示
                                        pendingCardForFace = null
                                        awaitingNfcForFaceId.value = null
                                    }
                                }

                                // Whenever queue updates and we have a matched face, consume latest silently
                                LaunchedEffect(nfcQueue, matchedFace?.id) {
                                    val mfLocal = matchedFace ?: return@LaunchedEffect
                                    if (nfcQueue.isEmpty()) return@LaunchedEffect
                                    fun normalized(id: String?) = id?.trim()?.uppercase().orEmpty()
                                    val nNowRaw = mainViewModelInside.popLatestNfc() ?: return@LaunchedEffect
                                    val nNow = normalized(nNowRaw)
                                    // expected NFC value for the currently matched face (normalized)
                                    val expectedNfcForFace = normalized(mfLocal.nfcId)
                                    try {
                                        val existing = try { mainViewModel.faceDao.getFaceByNfcId(nNow) } catch (_: Exception) { null }
                                        if (existing != null && existing.id != mfLocal.id) {
                                            try { android.widget.Toast.makeText(this@MainActivity, "刷卡失败：卡号已被 ${existing.name} 使用", android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                                        } else if (nNow != expectedNfcForFace) {
                                            try { android.widget.Toast.makeText(this@MainActivity, "刷卡失败：卡号不匹配，当前用户绑定为 $expectedNfcForFace", android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                                        } else {
                                            // Avoid duplicate sign-ins: check last sign-in timestamp
                                            mainViewModel.isRecentSignIn(mfLocal.id, nNow, withinMs = 10_000L) { isRecent, lastTs ->
                                                scope.launch {
                                                    if (isRecent) {
                                                        // Inform user and show last sign-in time
                                                        val formatted = try {
                                                            lastTs?.let {
                                                                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                                                sdf.format(java.util.Date(it))
                                                            } ?: ""
                                                        } catch (_: Exception) { "" }
                                                        try { android.widget.Toast.makeText(this@MainActivity, "重复签到：上次签到时间 $formatted", android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                                                    } else {
                                                        try {
                                                            // Use the face-detected timestamp if available so the stored timestamp reflects when face was seen
                                                            val ts = faceDetectedTime ?: System.currentTimeMillis()
                                                            mainViewModel.createSignInRecord(mfLocal.id, nNow, timestampMs = ts) {
                                                                scope.launch {
                                                                    matchedSignInSuccess = true
                                                                    matchedSignInTime = ts
                                                                    awaitingNfcForFaceId.value = null
                                                                    try { android.widget.Toast.makeText(this@MainActivity, "签到成功：${mfLocal.name}", android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                                                                }
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.e("MainActivity", "Failed to create sign-in record", e)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Error consuming NFC queue", e)
                                    } finally {
                                        try { mainViewModel.clearLastNfcScan() } catch (_: Exception) {}
                                    }
                                }
                            }
                            1 -> {
                                FaceManagementScreen(viewModel = mainViewModelInside, modifier = Modifier.padding(innerPadding))
                            }
                            2 -> {
                                ExportScreen(viewModel = mainViewModelInside, modifier = Modifier.padding(innerPadding))
                            }
                        }
                    }
                }

                // When gesture requested admin auth, show biometric prompt and on success unbind/start AdminActivity
                LaunchedEffect(startAdminAuth) {
                    if (startAdminAuth) {
                        startAdminAuth = false
                        try {
                            showFingerprintDialog {
                                // onSuccess -> deactivate camera, unbind then launch AdminActivity
                                lifecycleScope.launch {
                                    try {
                                        val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(this@MainActivity).get() }
                                        try { mainViewModel.setCameraActive(false) } catch (_: Exception) {}
                                        try {
                                            provider.unbindAll()
                                            Log.d("MainActivity", "Provider.unbindAll() completed before AdminActivity launch")
                                        } catch (e: Exception) { Log.w("MainActivity", "provider.unbindAll() failed before AdminActivity", e) }
                                    } catch (e: Exception) { Log.w("MainActivity", "Failed to unbind camera before AdminActivity", e) }
                                    try { delay(120) } catch (_: Exception) {}
                                    try {
                                        val intent = Intent(this@MainActivity, AdminActivity::class.java)
                                        startActivity(intent)
                                    } catch (e: Exception) {
                                        Log.e("GreetingCard", "Failed to start AdminActivity", e)
                                        mainViewModel.setAdminUnlocked(true)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error launching admin auth", e)
                        }
                    }
                }

                // Ensure camera use cases are unbound when switching away from camera page to avoid Surface/BufferQueue races
                LaunchedEffect(selectedPage) {
                    if (selectedPage != 0) {
                        try {
                            withContext(Dispatchers.IO) {
                                try {
                                    val provider = ProcessCameraProvider.getInstance(this@MainActivity).get()
                                    provider.unbindAll()
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Failed to unbind camera on page switch", e)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "LaunchedEffect unbindAll failed", e)
                        }
                    }
                }

                // Temporary global uncaught exception handler to capture startup crashes and write stacktrace to cache
                Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                    try {
                        val traceFile = File(cacheDir, "last_crash.txt")
                        traceFile.writeText(throwable.stackTraceToString())
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try { mainViewModel.setCameraActive(true) } catch (_: Exception) {}
        // NOTE: Do NOT enable NFC reader mode or foreground dispatch on the home/main activity.
        // NFC scans are handled in CardScanActivity (the dedicated scan UI). Keeping the home
        // activity from registering NFC prevents accidental simultaneous readers and avoids
        // confusing the UX where the home would react to cards.
        try {
            val adapter = nfcAdapter
            if (adapter == null) {
                Log.w("MainActivity", "Device does not support NFC or nfcAdapter is null")
            } else if (!adapter.isEnabled) {
                Log.w("MainActivity", "NFC adapter present but disabled")
            } else {
                // Intentionally skipping enableReaderMode/enableForegroundDispatch here.
                Log.d("MainActivity", "Home activity NFC listening disabled (CardScanActivity handles scans)")
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "NFC setup check failed", e)
        }
    }

    override fun onPause() {
        super.onPause()
        // Disable foreground dispatch to avoid leaks/duplicates
        try {
            // Try to disable reader mode if it was enabled; safe to call on all supported SDKs
            try { nfcAdapter?.disableReaderMode(this) } catch (_: Exception) {}
        } catch (_: Exception) {}
        try { nfcAdapter?.disableForegroundDispatch(this) } catch (_: Exception) {}
        // Cancel and clear PendingIntent so it won't be used when activity is background
        try { pendingIntent?.cancel() } catch (_: Exception) {}
        pendingIntent = null
    }

    override fun onNewIntent(intent: Intent) {
        @Suppress("DEPRECATION")
        super.onNewIntent(intent)
        // Ensure Activity holds the latest intent reference
        try { setIntent(intent) } catch (_: Exception) {}
        // Log intent action for debugging
        try { Log.d("MainActivity", "onNewIntent action=${intent.action} extras=${intent.extras?.keySet()?.joinToString()}") } catch (_: Exception) {}

        // Try to parse NDEF messages first (same as AdminActivity)
        var shownToast = false
        try {
            @Suppress("DEPRECATION")
            val rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            try { Log.d("MainActivity", "rawMsgs present=${rawMsgs != null} rawMsgsCount=${rawMsgs?.size ?: 0}") } catch (_: Exception) {}
            if (rawMsgs != null && rawMsgs.isNotEmpty()) {
                val msgs = rawMsgs.mapNotNull { it as? android.nfc.NdefMessage }
                for ((mi, m) in msgs.withIndex()) {
                    try { Log.d("MainActivity", "Processing NdefMessage[$mi] records=${m.records.size}") } catch (_: Exception) {}
                    for ((ri, rec) in m.records.withIndex()) {
                        try {
                            val payload = rec.payload ?: continue
                            val text = try {
                                if (payload.isNotEmpty()) {
                                    val status = payload[0].toInt()
                                    val isUtf8 = (status and 0x80) == 0
                                    val langLen = status and 0x3F
                                    val textBytes = payload.copyOfRange(1 + langLen, payload.size)
                                    String(textBytes, if (isUtf8) Charsets.UTF_8 else Charsets.UTF_16)
                                } else ""
                            } catch (_: Exception) { String(payload) }
                            try { Log.d("MainActivity", "Parsed NDEF record[$mi][$ri] text=$text") } catch (_: Exception) {}
                            try { mainViewModel.onNfcScanResult(text) } catch (_: Exception) {}
                            if (!shownToast) {
                                try { android.widget.Toast.makeText(this, "读取到卡片: $text", android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                                shownToast = true
                            }
                        } catch (e: Exception) {
                            Log.w("MainActivity", "Failed to parse NDEF record", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "No NDEF messages or failed to read", e)
        }

        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        tag?.let {
            val idBytes = it.id
            val idHex = idBytes.joinToString(separator = "") { b -> "%02X".format(b) }
            try {
                Log.d("MainActivity", "onNewIntent tag idHex=$idHex -> delivering to ViewModel")
                mainViewModel.onNfcScanResult(idHex)
                Log.d("MainActivity", "onNewIntent NFC id: $idHex -> enqueued")
             } catch (e: Exception) {
                 Log.e("MainActivity", "Failed to deliver NFC result to ViewModel", e)
             }
             if (!shownToast) try { android.widget.Toast.makeText(this, "读取到卡号: $idHex", android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
         }
    }

    private fun showFingerprintDialog(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val fragmentActivity = this as androidx.fragment.app.FragmentActivity
        val biometricPrompt = BiometricPrompt(fragmentActivity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    runOnUiThread {
                        Log.d("GreetingCard", "Biometric success callback invoked")
                        try { android.widget.Toast.makeText(this@MainActivity, "指纹认证通过", android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                        try { onSuccess() } catch (e: Exception) { Log.e("GreetingCard", "onSuccess lambda failed", e) }
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    try { android.widget.Toast.makeText(this@MainActivity, "指纹认证失败: $errString", android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                }
            })
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("指纹认证")
            .setSubtitle("请验证指纹以进入管理页面")
            .setNegativeButtonText("取消")
            .build()
        biometricPrompt.authenticate(promptInfo)
    }
}
