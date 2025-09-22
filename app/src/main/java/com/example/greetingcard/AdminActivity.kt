package com.example.greetingcard

import android.os.Bundle
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mainViewModel: MainViewModel = ViewModelProvider(this)[MainViewModel::class.java]
        setContent {
            GreetingCardTheme {
                AdminScreen(viewModel = mainViewModel)
            }
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
