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
import com.example.greetingcard.ui.composables.FaceManagementScreen
import com.example.greetingcard.ui.composables.ExportScreen
import com.example.greetingcard.ui.theme.GreetingCardTheme
import com.example.greetingcard.viewmodel.MainViewModel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp

class AdminActivity : ComponentActivity() {
    // expose ViewModel as property so we can forward NFC intents
    private lateinit var mainViewModel: MainViewModel
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFilters: Array<IntentFilter>? = null
    private lateinit var techListsForNfc: Array<Array<String>>

    // ReaderCallback for enableReaderMode path
    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        try {
            val idBytes = tag.id
            val idHex = idBytes.joinToString(separator = "") { b -> "%02X".format(b) }
            try { Log.d("AdminActivity", "ReaderCallback detected tag id=$idHex") } catch (_: Exception) {}
            Handler(Looper.getMainLooper()).post {
                try { mainViewModel.onNfcScanResult(idHex) } catch (e: Exception) { Log.e("AdminActivity", "ReaderCallback delivery failed", e) }
            }
        } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainViewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // prepare NFC helper objects
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        // NOTE: do NOT create PendingIntent here. Create it in onResume when activity is foreground

        // we'll create pendingIntent in onResume using these flags
        intentFilters = arrayOf(IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED))
        techListsForNfc = arrayOf(
            arrayOf(android.nfc.tech.Ndef::class.java.name),
            arrayOf(android.nfc.tech.NfcA::class.java.name),
            arrayOf(android.nfc.tech.IsoDep::class.java.name)
        )

        setContent {
            GreetingCardTheme {
                AdminScreen(viewModel = mainViewModel)
            }
        }
    }

    @Suppress("ConstantConditionIf")
    override fun onResume() {
        super.onResume()
        Log.d("AdminActivity", "onResume called")
        try {
            if (nfcAdapter == null) {
                Log.w("AdminActivity", "Device does not support NFC or nfcAdapter is null")
            } else if (!nfcAdapter!!.isEnabled) {
                Log.w("AdminActivity", "NFC adapter present but disabled")
            }
            // enable foreground dispatch or ReaderMode so this activity receives tag intents while in front
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    // Prefer ReaderMode (no PendingIntent, callback delivered to this activity)
                    val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
                    nfcAdapter?.enableReaderMode(this, readerCallback, flags, null)
                    Log.d("AdminActivity", "enableReaderMode active")
                } else {
                    // fallback to foreground dispatch using PendingIntent
                    val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), piFlags)
                    nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, techListsForNfc)
                    Log.d("AdminActivity", "enableForegroundDispatch active (fallback)")
                }
            } catch (e: Exception) {
                Log.w("AdminActivity", "enableForegroundDispatch failed", e)
                try { nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, null) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w("AdminActivity", "NFC setup failed", e)
        }
    }

    override fun onPause() {
        Log.d("AdminActivity", "onPause called")
        super.onPause()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                try { nfcAdapter?.disableReaderMode(this) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        try { nfcAdapter?.disableForegroundDispatch(this) } catch (_: Exception) {}
        // clear PendingIntent to avoid it being used while activity is background
        try { pendingIntent?.cancel() } catch (_: Exception) {}
        pendingIntent = null
    }

    override fun onDestroy() {
        Log.d("AdminActivity", "onDestroy called")
        super.onDestroy()
        return
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // update stored intent so getIntent() reflects latest NFC intent
        try { setIntent(intent) } catch (_: Exception) {}
        try { Log.d("AdminActivity", "onNewIntent action=${intent.action}") } catch (_: Exception) {}
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
                            try { mainViewModel.onNfcScanResult(text) } catch (_: Exception) {}
                            if (!shownToast) {
                                try { Toast.makeText(this, "读取到卡片: $text", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                                shownToast = true
                            }
                        } catch (e: Exception) {
                            Log.w("AdminActivity", "Failed to parse NDEF record", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AdminActivity", "No NDEF messages or failed to read", e)
        }

        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        tag?.let {
            val idBytes = it.id
            val idHex = idBytes.joinToString(separator = "") { b -> "%02X".format(b) }
            try { mainViewModel.onNfcScanResult(idHex) } catch (e: Exception) { Log.e("AdminActivity", "Failed to deliver NFC result to ViewModel", e) }
            if (!shownToast) try { Toast.makeText(this, "读取到卡号: $idHex", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
            Log.d("AdminActivity", "Processed tag id: $idHex")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    var selectedPage by remember { mutableStateOf(0) }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("管理后台") },
            )
        }
    ) { innerPadding ->
        Column(modifier = modifier.padding(innerPadding).fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { selectedPage = 0 }) { Text("人脸管理") }
                Button(onClick = { selectedPage = 1 }) { Text("签到导出") }
            }
            when (selectedPage) {
                0 -> FaceManagementScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                1 -> ExportScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
