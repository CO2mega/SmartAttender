package com.example.greetingcard

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.example.greetingcard.ui.theme.GreetingCardTheme
import com.example.greetingcard.viewmodel.MainViewModel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

// Activity that handles NFC scanning after face recognition succeeded.
// It does NOT use the camera; only listens for NFC tags and performs matching using MainViewModel.
class CardScanActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFilters: Array<IntentFilter>? = null
    private lateinit var techListsForNfc: Array<Array<String>>
    private lateinit var mainViewModel: MainViewModel

    private var faceId: Long = 0L
    private var faceName: String = ""
    private var expectedNfc: String = ""
    private var embedding: FloatArray? = null
    private var faceDetectedTs: Long? = null

    // ReaderCallback for enableReaderMode
    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        try {
            val idBytes = tag.id
            val idHex = idBytes.joinToString(separator = "") { b -> "%02X".format(b) }
            try { Log.d("CardScanActivity", "ReaderCallback detected tag id=$idHex") } catch (_: Exception) {}
            Handler(Looper.getMainLooper()).post {
                onNfcDetected(idHex)
            }
        } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainViewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // read intent extras
        try {
            faceId = intent.getLongExtra("faceId", 0L)
            faceName = intent.getStringExtra("faceName") ?: ""
            expectedNfc = intent.getStringExtra("expectedNfc") ?: ""
            embedding = intent.getFloatArrayExtra("embedding")
            faceDetectedTs = intent.getLongExtra("faceDetectedTs", 0L).takeIf { it > 0L }
        } catch (_: Exception) {}

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        intentFilters = arrayOf(IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED))
        techListsForNfc = arrayOf(
            arrayOf(android.nfc.tech.Ndef::class.java.name),
            arrayOf(android.nfc.tech.NfcA::class.java.name),
            arrayOf(android.nfc.tech.IsoDep::class.java.name)
        )

        setContent {
            GreetingCardTheme {
                CardScanScreen(faceName = faceName, expectedNfc = expectedNfc)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            if (nfcAdapter == null) {
                try { Toast.makeText(this, "设备不支持 NFC", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                return
            }
            if (!nfcAdapter!!.isEnabled) {
                try { Toast.makeText(this, "请在系统设置中开启 NFC", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
                try { nfcAdapter?.enableReaderMode(this, readerCallback, flags, null) } catch (e: Exception) { Log.w("CardScanActivity", "enableReaderMode failed", e); enableForegroundDispatchFallback() }
            } else {
                enableForegroundDispatchFallback()
            }
        } catch (e: Exception) {
            Log.w("CardScanActivity", "NFC setup failed", e)
        }
    }

    private fun enableForegroundDispatchFallback() {
        try {
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), piFlags)
            nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, techListsForNfc)
        } catch (e: Exception) {
            Log.w("CardScanActivity", "enableForegroundDispatch fallback failed", e)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                try { nfcAdapter?.disableReaderMode(this) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        try { nfcAdapter?.disableForegroundDispatch(this) } catch (_: Exception) {}
        try { pendingIntent?.cancel() } catch (_: Exception) {}
        pendingIntent = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        try { setIntent(intent) } catch (_: Exception) {}
        // try NDEF first
        var shownToast = false
        try {
            @Suppress("DEPRECATION")
            val rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            if (rawMsgs != null && rawMsgs.isNotEmpty()) {
                val msgs = rawMsgs.mapNotNull { it as? android.nfc.NdefMessage }
                for (m in msgs) {
                    for (rec in m.records) {
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
                            onNfcDetected(text)
                            if (!shownToast) {
                                try { Toast.makeText(this, "读取到卡片: $text", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                                shownToast = true
                            }
                        } catch (e: Exception) {
                            Log.w("CardScanActivity", "Failed to parse NDEF record", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("CardScanActivity", "No NDEF messages or failed to read", e)
        }

        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        tag?.let {
            val idBytes = it.id
            val idHex = idBytes.joinToString(separator = "") { b -> "%02X".format(b) }
            try { onNfcDetected(idHex) } catch (e: Exception) { Log.e("CardScanActivity", "Failed to handle NFC in onNewIntent", e) }
            try { if (!shownToast) Toast.makeText(this, "读取到卡号: $idHex", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
    }

    private fun onNfcDetected(rawId: String) {
        val norm = rawId.trim().replace(Regex("[^0-9A-Fa-f]"), "").uppercase()
        // If embedding is provided, use embedding+NFC match for stronger verification
        if (embedding != null) {
            try {
                mainViewModel.matchEmbeddingAndNfc(embedding!!, norm) { faceMatched, sim ->
                    runOnUiThread {
                        if (faceMatched == null) {
                            try { Toast.makeText(this, "刷卡失败：人脸与卡片不匹配 (相似度=${String.format(Locale.getDefault(), "%.2f", sim)})", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                        } else if (faceMatched.id != faceId) {
                            try { Toast.makeText(this, "刷卡失败：卡号已被 ${faceMatched.name} 使用", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                        } else {
                            // Before inserting, check for recent sign-in to avoid duplicates
                            try {
                                mainViewModel.isRecentSignIn(faceId, norm, withinMs = 10_000L) { isRecent, lastTs ->
                                    runOnUiThread {
                                        if (isRecent) {
                                            val formatted = try {
                                                lastTs?.let {
                                                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                                    sdf.format(java.util.Date(it))
                                                } ?: ""
                                            } catch (_: Exception) { "" }
                                            try { Toast.makeText(this, "重复签到：上次签到时间 $formatted", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                                            finish()
                                        } else {
                                            try {
                                                val ts = faceDetectedTs ?: System.currentTimeMillis()
                                                mainViewModel.createSignInRecord(faceId, norm, timestampMs = ts) {
                                                    runOnUiThread {
                                                        try { Toast.makeText(this, "签到成功：${faceName}", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                                                        finish()
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.e("CardScanActivity", "Failed to create sign-in record", e)
                                                finish()
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("CardScanActivity", "Error checking recent sign-in", e)
                                finish()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CardScanActivity", "Error matching embedding+NFC", e)
            }
        } else {
            // No embedding available: fall back to simple NFC==expectedNfc check
            try {
                val expected = expectedNfc.trim().uppercase()
                // DAO call is suspend - run it on IO dispatcher
                lifecycleScope.launch {
                    val existing = try { withContext(Dispatchers.IO) { mainViewModel.faceDao.getFaceByNfcId(norm) } } catch (_: Exception) { null }
                    if (existing != null && existing.id != faceId) {
                        try { Toast.makeText(this@CardScanActivity, "刷卡失败：卡号已被 ${existing.name} 使用", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                    } else if (expected.isNotBlank() && norm != expected) {
                        try { Toast.makeText(this@CardScanActivity, "刷卡失败：卡号不匹配，当前用户绑定为 $expected", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                    } else {
                        // success: create record
                        try {
                            // Before inserting, check for recent sign-in to avoid duplicates
                            try {
                                mainViewModel.isRecentSignIn(faceId, norm, withinMs = 10_000L) { isRecent, lastTs ->
                                    runOnUiThread {
                                        if (isRecent) {
                                            val formatted = try {
                                                lastTs?.let {
                                                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                                    sdf.format(java.util.Date(it))
                                                } ?: ""
                                            } catch (_: Exception) { "" }
                                            try { Toast.makeText(this@CardScanActivity, "重复签到：上次签到时间 $formatted", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                                            finish()
                                        } else {
                                            try {
                                                mainViewModel.createSignInRecord(faceId, norm) {
                                                    runOnUiThread {
                                                        try { Toast.makeText(this@CardScanActivity, "签到成功：${faceName}", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                                                        finish()
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.e("CardScanActivity", "Failed to create sign-in record", e)
                                                finish()
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("CardScanActivity", "Error checking recent sign-in", e)
                            }
                        } catch (e: Exception) {
                            Log.e("CardScanActivity", "Failed to create sign-in record", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CardScanActivity", "Error during NFC handling", e)
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardScanScreen(faceName: String, expectedNfc: String, modifier: Modifier = Modifier) {
    var statusText by remember { mutableStateOf("请刷卡以完成签到") }
    Scaffold(topBar = { TopAppBar(title = { Text("刷卡确认") }) }) { innerPadding ->
        Column(modifier = modifier.padding(innerPadding).fillMaxSize().padding(16.dp)) {
            Text(text = "人脸: $faceName", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = if (expectedNfc.isNotBlank()) "期望卡号: $expectedNfc" else "(未绑定卡号)")
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = statusText)
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = { statusText = "等待刷卡..." }) {
                Text("开始等待刷卡")
            }
        }
    }
}
