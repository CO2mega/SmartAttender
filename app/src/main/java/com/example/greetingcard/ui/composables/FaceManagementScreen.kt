package com.example.greetingcard.ui.composables

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.greetingcard.data.FaceEntity
import com.example.greetingcard.viewmodel.MainViewModel

@Composable
fun FaceManagementScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val faces by viewModel.faces.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var editingFace by remember { mutableStateOf<FaceEntity?>(null) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "人脸管理", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { editingFace = null; showDialog = true }) {
                Text("新增人脸")
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
            onDismiss = { showDialog = false; editingFace = null },
            onSave = { face ->
                viewModel.addOrUpdateFace(face) { showDialog = false; editingFace = null }
            }
        )
    }
}

@Composable
fun FaceEditDialog(initial: FaceEntity?, onDismiss: () -> Unit, onSave: (FaceEntity) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var nfc by remember { mutableStateOf(initial?.nfcId ?: "") }
    var feature by remember { mutableStateOf(initial?.faceFeature ?: "") }
    var nfcScanned by remember { mutableStateOf(false) }
    var faceScanned by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增人脸" else "编辑人脸") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("姓名") })
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    // 扫描人脸逻辑（此处可替换为实际扫描）
                    feature = "示例人脸特征"
                    faceScanned = true
                }) {
                    Text(if (faceScanned) "已扫描人脸" else "扫描人脸")
                }
                if (faceScanned) {
                    Text(text = "人脸特征: $feature", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    // 扫描NFC卡逻辑（此处可替换为实际扫描）
                    nfc = "示例NFC卡号"
                    nfcScanned = true
                }) {
                    Text(if (nfcScanned) "已扫描NFC卡" else "扫描NFC卡")
                }
                if (nfcScanned) {
                    Text(text = "NFC卡号: $nfc", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val face = FaceEntity(
                    id = initial?.id ?: 0L,
                    name = name,
                    faceFeature = feature,
                    nfcId = nfc
                )
                onSave(face)
            }, enabled = name.isNotBlank() && faceScanned && nfcScanned) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
