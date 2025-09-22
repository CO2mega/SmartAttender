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
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import android.util.Log
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import com.example.greetingcard.ui.composables.CameraView
import com.example.greetingcard.ui.composables.FaceManagementScreen
import com.example.greetingcard.ui.composables.ExportScreen
import com.example.greetingcard.ui.theme.GreetingCardTheme
import com.example.greetingcard.viewmodel.MainViewModel
import java.io.File

class MainActivity : AppCompatActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFilters: Array<IntentFilter>? = null
    private val nfcIdState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 设置全屏（使用 WindowInsetsControllerCompat，替代已弃用的 FLAG_FULLSCREEN）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_MUTABLE
        )
        val nfcIntentFilter = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        intentFilters = arrayOf(nfcIntentFilter)

        // Use Activity ViewModelProvider to get MainViewModel instance (avoid unresolved compose viewModel)
        val activityViewModel: MainViewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setContent {
            GreetingCardTheme {
                var dragCount by remember { mutableStateOf(0) }
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                var selectedPage by remember { mutableStateOf(0) } // 0=Camera,1=FaceMgmt,2=Export
                // use the activity-scoped viewmodel inside Compose
                val mainViewModel = activityViewModel
                val adminUnlocked by mainViewModel.adminUnlocked.collectAsState()
                var lastFaceFeature by remember { mutableStateOf<String?>(null) }
                var lastNfcId by remember { mutableStateOf<String?>(null) }
                var toastMessage by remember { mutableStateOf("") }
                // NFC卡号状态 synchronization: observe nfcIdState.value so Compose recomposes when NFC tag arrives
                val observedNfcId = nfcIdState.value
                LaunchedEffect(observedNfcId) {
                    if (observedNfcId != null) {
                        lastNfcId = observedNfcId
                        if (lastFaceFeature != null) {
                            mainViewModel.matchFaceAndNfc(lastFaceFeature!!, observedNfcId) { matched ->
                                toastMessage = if (matched) "签到成功" else "人卡不匹配"
                            }
                        }
                    }
                }
                // react to admin unlock events from ViewModel (triggered by biometric success)
                LaunchedEffect(adminUnlocked) {
                    if (adminUnlocked) {
                        Log.d("GreetingCard", "adminUnlocked observed")
                        // debug: notify observed
                        try { Toast.makeText(this@MainActivity, "adminUnlocked observed", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                        // ensure biometric dialog is dismissed before opening
                        delay(600)
                        selectedPage = 1
                        try {
                            drawerState.open()
                            Log.d("GreetingCard", "drawer opened programmatically")
                        } catch (e: Exception) {
                            Log.e("GreetingCard", "drawer open failed", e)
                            try { Toast.makeText(this@MainActivity, "drawer open failed: ${'$'}{e.message}", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                        }
                        // reset flag
                        mainViewModel.setAdminUnlocked(false)
                    }
                }
                // 侧边栏内容 (use ModalDrawerSheet so drawer UI renders correctly)
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
                            detectHorizontalDragGestures { change, dragAmount ->
                                if (dragAmount > 50) {
                                    dragCount++
                                    if (dragCount >= 3) {
                                        // 指纹识别弹窗，认证成功后通过 ViewModel 发出解锁事件
                                        showFingerprintDialog {
                                            // Start AdminActivity after successful biometric auth
                                            try {
                                                val intent = Intent(this@MainActivity, AdminActivity::class.java)
                                                startActivity(intent)
                                            } catch (e: Exception) {
                                                Log.e("GreetingCard", "Failed to start AdminActivity", e)
                                                // fallback: set ViewModel flag
                                                mainViewModel.setAdminUnlocked(true)
                                            }
                                        }
                                        dragCount = 0
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->
                        when (selectedPage) {
                            0 -> {
                                CameraView(
                                    viewModel = mainViewModel,
                                    modifier = Modifier.padding(innerPadding),
                                    onFaceRecognized = { faceFeature ->
                                        lastFaceFeature = faceFeature
                                        if (lastNfcId != null) {
                                            mainViewModel.matchFaceAndNfc(faceFeature, lastNfcId!!) { matched ->
                                                toastMessage = if (matched) "签到成功" else "人卡不匹配"
                                            }
                                        }
                                    }
                                )
                            }
                            1 -> {
                                // Face management
                                FaceManagementScreen(viewModel = mainViewModel, modifier = Modifier.padding(innerPadding))
                            }
                            2 -> {
                                ExportScreen(viewModel = mainViewModel, modifier = Modifier.padding(innerPadding))
                            }
                        }
                        // NFC 读卡逻辑
                        if (toastMessage.isNotEmpty()) {
                            Toast.makeText(this@MainActivity, toastMessage, Toast.LENGTH_SHORT).show()
                            toastMessage = ""
                        }
                        // TODO: 其他页面逻辑
                    }
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
            runOnUiThread {
                try {
                    Toast.makeText(this, "App crashed: ${'$'}{throwable.message}", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {}
            }
            // rethrow to allow system to handle (optional). We won't rethrow to allow manual inspection.
        }
    }

    private fun showFingerprintDialog(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        // Ensure we pass a FragmentActivity to the BiometricPrompt constructor
        val fragmentActivity = this as androidx.fragment.app.FragmentActivity
        val biometricPrompt = BiometricPrompt(fragmentActivity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    // Ensure UI work runs on the main thread and give a visible confirmation
                    runOnUiThread {
                        Log.d("GreetingCard", "Biometric success callback invoked")
                        try { Toast.makeText(this@MainActivity, "指纹认证通过", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                        try { onSuccess() } catch (e: Exception) { Log.e("GreetingCard", "onSuccess lambda failed", e) }
                    }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(this@MainActivity, "指纹认证失败: $errString", Toast.LENGTH_SHORT).show()
                }
            })
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("指纹认证")
            .setSubtitle("请验证指纹以进入管理页面")
            .setNegativeButtonText("取消")
            .build()
        biometricPrompt.authenticate(promptInfo)
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        tag?.let {
            val idBytes = it.id
            val idHex = idBytes.joinToString(separator = "") { b -> "%02X".format(b) }
            nfcIdState.value = idHex
        }
    }
}
