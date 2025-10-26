package com.example.greetingcard.ui.composables

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.greetingcard.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ExportScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }

    val records by remember { viewModel.records }.collectAsState()
    val faces by remember { viewModel.faces }.collectAsState()
    val lastExportInfo by remember { viewModel.lastExportInfo }.collectAsState()

    // helper to check if a timestamp (ms) is today
    fun isToday(ts: Long): Boolean {
        val cal = Calendar.getInstance()
        val end = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        return ts in start..end
    }

    val todaysRecords = remember(records) { records.filter { isToday(it.timestamp) } }

    // Permission handling for legacy external storage (pre-Q)
    var pendingExportAction by remember { mutableStateOf<Int?>(null) } // 1=all,2=today
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingExportAction != null) {
            scope.launch {
                exporting = true
                try {
                    val file: java.io.File? = when (pendingExportAction) {
                        1 -> viewModel.exportSignInCsv(context.applicationContext)
                        2 -> {
                            val today = viewModel.getRecordsForToday()
                            viewModel.exportSignInCsv(context.applicationContext, today)
                        }
                        else -> null
                    }
                    exporting = false
                    pendingExportAction = null
                    if (file != null || !lastExportInfo.isNullOrBlank()) {
                        // Prefer showing the friendly lastExportInfo if available (it points to Downloads/attender or content URI)
                        val info = lastExportInfo ?: file?.absolutePath ?: "导出成功"
                        Toast.makeText(context, "导出成功: $info", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                    exporting = false
                    pendingExportAction = null
                    Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            pendingExportAction = null
            Toast.makeText(context, "需要存储权限以导出到下载目录", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "签到数据导出", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(12.dp))

        Text(text = "今日签到：${todaysRecords.size} 条", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))

        // list of today's sign-ins
        if (todaysRecords.isEmpty()) {
            Text(text = "今日暂无签到记录", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(todaysRecords) { rec ->
                    val name = faces.firstOrNull { it.id == rec.faceId }?.name ?: "未知"
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    val timeStr = try { sdf.format(Date(rec.timestamp)) } catch (_: Exception) { rec.timestamp.toString() }
                    Column(modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)) {
                        Text(text = "${name}  -  ${timeStr}", style = MaterialTheme.typography.bodyMedium)
                        Text(text = "卡号: ${rec.nfcId}", style = MaterialTheme.typography.bodySmall)
                    }
                    Divider()
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Show last export location if available
        lastExportInfo?.let { info ->
            Text(text = "上次导出位置: $info", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                if (exporting) return@Button
                // Check if we need storage permission on pre-Q
                val needsLegacyPermission = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                if (needsLegacyPermission) {
                    pendingExportAction = 1
                    storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    return@Button
                }

                exporting = true
                scope.launch {
                    val file = viewModel.exportSignInCsv(context.applicationContext)
                    exporting = false
                    if (file != null || !lastExportInfo.isNullOrBlank()) {
                        val info = lastExportInfo ?: file?.absolutePath ?: "导出成功"
                        Toast.makeText(context, "导出成功: $info", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }) {
                Text(if (exporting) "导出中..." else "导出为 CSV")
            }

            Button(onClick = {
                if (exporting) return@Button
                val needsLegacyPermission = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                if (needsLegacyPermission) {
                    pendingExportAction = 2
                    storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    return@Button
                }

                exporting = true
                scope.launch {
                    // Query DB for today's records to avoid stale UI cache
                    val todayRecords = try { viewModel.getRecordsForToday() } catch (_: Exception) { emptyList() }
                    val file = viewModel.exportSignInCsv(context.applicationContext, todayRecords)
                    exporting = false
                    if (file != null || !lastExportInfo.isNullOrBlank()) {
                        val info = lastExportInfo ?: file?.absolutePath ?: "导出成功"
                        Toast.makeText(context, "导出成功: $info", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }) {
                Text(if (exporting) "导出中..." else "导出当日数据")
            }
        }
    }
}
