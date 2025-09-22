package com.example.greetingcard.ui.composables

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.greetingcard.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun ExportScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "签到数据导出", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = {
            if (exporting) return@Button
            exporting = true
            scope.launch {
                val file = viewModel.exportSignInCsv(context.applicationContext)
                exporting = false
                if (file != null) {
                    Toast.makeText(context, "导出成功: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                    // Optionally show share intent
                    try {
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
                        // share intent can be launched from Activity context; here we just notify
                    } catch (e: Exception) {
                        // ignore
                    }
                } else {
                    Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
                }
            }
        }) {
            Text(if (exporting) "导出中..." else "导出为 CSV")
        }
    }
}

