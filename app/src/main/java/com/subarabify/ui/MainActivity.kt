package com.subarabify.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val mediaExtensions = listOf("mp4", "mkv", "avi", "m4v")
    
    private var achievedCount by mutableStateOf(0)
    private var missingCount by mutableStateOf(0)
    private var selectedFolder by mutableStateOf<String?>(null)
    private var selectedRawPath by mutableStateOf<String>("/storage/emulated/0/Movies")
    private var itemList = mutableStateListOf<MediaItemStatus>()
    private var isScanning by mutableStateOf(false)
    private var showSetupDialog by mutableStateOf(false)

    data class MediaItemStatus(val name: String, val isAchieved: Boolean, val folderName: String)

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val pathSegment = uri.lastPathSegment ?: ""
                selectedFolder = pathSegment
                
                // Convert primary:Movies to /storage/emulated/0/Movies for Termux
                if (pathSegment.startsWith("primary:")) {
                    selectedRawPath = "/storage/emulated/0/" + pathSegment.removePrefix("primary:")
                } else {
                    selectedRawPath = "/storage/emulated/0/" + pathSegment.substringAfter(":")
                }
                
                scanFolder(uri)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("SubArabify Helper", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold) },
                            actions = {
                                IconButton(onClick = { showSetupDialog = true }) {
                                    Icon(Icons.Default.Settings, contentDescription = "Setup Termux", tint = Color.White)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E))
                        )
                    }
                ) { paddingValues ->
                    Surface(
                        modifier = Modifier.fillMaxSize().padding(paddingValues),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        DashboardScreen()
                        
                        if (showSetupDialog) {
                            TermuxSetupDialog(onDismiss = { showSetupDialog = false }, rawPath = selectedRawPath)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun DashboardScreen() {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = { 
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    folderPickerLauncher.launch(intent)
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
            ) {
                Text("Select Media Folder", fontSize = 16.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (selectedFolder != null) {
                Text("Tracking: $selectedRawPath", fontSize = 13.sp, color = Color.Gray, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (isScanning) {
                CircularProgressIndicator(color = Color(0xFF4CAF50))
                Text("Scanning folders & episodes...", color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatCard("Achieved", achievedCount, Color(0xFF4CAF50))
                    StatCard("Missing", missingCount, Color(0xFFF44336))
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    val grouped = itemList.groupBy { it.folderName }
                    grouped.forEach { (folder, items) ->
                        item {
                            Text(
                                text = folder,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        items(items) { item ->
                            MediaItemRow(item)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun TermuxSetupDialog(onDismiss: () -> Unit, rawPath: String) {
        val context = LocalContext.current
        
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Termux Setup Guide", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("IMPORTANT", fontWeight = FontWeight.Bold, color = Color.White)
                                }
                                Text(
                                    "Do NOT install Termux from Google Play. Install the F-Droid or GitHub version.",
                                    color = Color.LightGray, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)
                                )
                                Button(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier.padding(top = 8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B))
                                ) {
                                    Text("Get Termux from F-Droid")
                                }
                            }
                        }
                    }
                    
                    item { SetupStep("1. Grant Storage Access", "termux-setup-storage", context) }
                    item { SetupStep("2. Install Dependencies", "pkg update && pkg install nodejs ffmpeg -y", context) }
                    item { SetupStep("3. Clone SubArabify", "git clone https://github.com/muxd22-alt/SubArabify.git && cd SubArabify && npm install", context) }
                    item { SetupStep("4. Run Automation (Uses Selected Folder)", "node subarabify.js --media \"$rawPath\"", context) }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Close", color = Color(0xFF4CAF50)) }
            }
        )
    }

    @Composable
    fun SetupStep(title: String, command: String, context: Context) {
        Column(modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black, RoundedCornerShape(8.dp))
                    .clickable { 
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Termux Command", command)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Command copied!", Toast.LENGTH_SHORT).show()
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(command, color = Color(0xFF00FF00), fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.weight(1f))
                Text("COPY", color = Color(0xFF1976D2), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    fun StatCard(title: String, count: Int, color: Color) {
        Card(
            modifier = Modifier.size(130.dp, 90.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(count.toString(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }

    @Composable
    fun MediaItemRow(item: MediaItemStatus) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 8.dp)
                .background(Color(0xFF222222), RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(if (item.isAchieved) Color(0xFF4CAF50) else Color(0xFFF44336), RoundedCornerShape(5.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(item.name, color = Color.LightGray, fontSize = 13.sp)
        }
    }

    private fun scanFolder(uri: Uri) {
        isScanning = true
        itemList.clear()
        achievedCount = 0
        missingCount = 0

        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        scope.launch {
            val rootFolder = DocumentFile.fromTreeUri(this@MainActivity, uri)
            val allFiles = mutableListOf<DocumentFile>()
            
            fun scanDocumentFile(folder: DocumentFile?) {
                folder?.listFiles()?.forEach { file ->
                    if (file.isDirectory) {
                        scanDocumentFile(file)
                    } else {
                        allFiles.add(file)
                    }
                }
            }

            scanDocumentFile(rootFolder)

            val mediaFiles = allFiles.filter { file ->
                val ext = file.name?.substringAfterLast('.', "")?.lowercase() ?: ""
                mediaExtensions.contains(ext)
            }

            val newItems = mutableListOf<MediaItemStatus>()
            var achieved = 0
            var missing = 0

            mediaFiles.forEach { videoFile ->
                val nameWithoutExt = videoFile.name?.substringBeforeLast('.') ?: ""
                val expectedSrtName = "$nameWithoutExt.SubArabify.ar.srt"
                
                val folder = videoFile.parentFile
                val subtitleExists = folder?.findFile(expectedSrtName) != null || 
                                     allFiles.any { it.name == expectedSrtName }
                
                if (subtitleExists) achieved++ else missing++
                newItems.add(MediaItemStatus(nameWithoutExt, subtitleExists, folder?.name ?: "Root"))
            }

            withContext(Dispatchers.Main) {
                // Sort missing first, then group by folder alphabetically
                itemList.addAll(newItems.sortedWith(compareBy({ it.isAchieved }, { it.folderName }, { it.name })))
                achievedCount = achieved
                missingCount = missing
                isScanning = false
            }
        }
    }
}

