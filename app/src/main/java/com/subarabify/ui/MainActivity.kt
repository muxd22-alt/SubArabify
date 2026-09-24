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
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0D1117))) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("مساعد ساب أرابيفاي", color = Color(0xFF00FF7F), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp) },
                            actions = {
                                IconButton(onClick = { showSetupDialog = true }) {
                                    Icon(Icons.Default.Settings, contentDescription = "إعداد تيرمكس", tint = Color.LightGray)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF161B22))
                        )
                    }
                ) { paddingValues ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0D1117))
                            .padding(paddingValues),
                        color = Color.Transparent
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
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = { 
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    folderPickerLauncher.launch(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
            ) {
                Text("اختيار مجلد الوسائط", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (selectedFolder != null) {
                Text("قيد التتبع: $selectedRawPath", fontSize = 14.sp, color = Color(0xFF8B949E), textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(24.dp))
            }

            if (isScanning) {
                CircularProgressIndicator(color = Color(0xFF00FF7F), strokeWidth = 4.dp)
                Text("جاري فحص المجلدات والحلقات...", color = Color(0xFF8B949E), modifier = Modifier.padding(top = 12.dp))
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StatCard(
                        "مكتمل", 
                        achievedCount, 
                        Color(0xFF3FB950), 
                        Color(0xFF238636).copy(alpha = 0.2f),
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        "مفقود", 
                        missingCount, 
                        Color(0xFFFF7B72), 
                        Color(0xFFDA3633).copy(alpha = 0.2f),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                Divider(color = Color(0xFF30363D), thickness = 1.dp)
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { 
                        val launchIntent = LocalContext.current.packageManager.getLaunchIntentForPackage("com.termux")
                        if (launchIntent != null) {
                            LocalContext.current.startActivity(launchIntent)
                        } else {
                            Toast.makeText(LocalContext.current, "الرجاء تثبيت تيرمكس أولاً", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD29922)),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                ) {
                    Text("تشغيل محرك الترجمة (تيرمكس)", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
                
                Spacer(modifier = Modifier.height(16.dp))

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
            title = { Text("دليل إعداد تيرمكس", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold) },
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
                                    Text("هام", fontWeight = FontWeight.Bold, color = Color.White)
                                }
                                Text(
                                    "لا تقم بتثبيت تيرمكس من متجر بلاي. قم بتثبيته من F-Droid.",
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
                                    Text("تحميل تيرمكس من F-Droid")
                                }
                            }
                        }
                    }
                    
                    item { SetupStep("1. منح إذن التخزين", "termux-setup-storage", context) }
                    item { SetupStep("2. تثبيت التبعيات", "pkg update && pkg install nodejs ffmpeg -y", context) }
                    item { SetupStep("3. تحميل المشروع", "git clone https://github.com/muxd22-alt/SubArabify.git && cd SubArabify && npm install", context) }
                    item { SetupStep("4. تشغيل الأتمتة (باستخدام المجلد المحدد)", "node subarabify.js --media \"$rawPath\"", context) }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("إغلاق", color = Color(0xFF4CAF50)) }
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
                        Toast.makeText(context, "تم نسخ الأمر!", Toast.LENGTH_SHORT).show()
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(command, color = Color(0xFF00FF00), fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.weight(1f))
                Text("نسخ", color = Color(0xFF1976D2), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    fun StatCard(title: String, count: Int, accentColor: Color, bgColor: Color, modifier: Modifier = Modifier) {
        Card(
            modifier = modifier.height(110.dp),
            colors = CardDefaults.cardColors(containerColor = bgColor),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, color = accentColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(count.toString(), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }

    @Composable
    fun MediaItemRow(item: MediaItemStatus) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 4.dp)
                .background(Color(0xFF161B22), RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(if (item.isAchieved) Color(0xFF3FB950) else Color(0xFFFF7B72), RoundedCornerShape(6.dp))
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(item.name, color = Color(0xFFC9D1D9), fontSize = 14.sp, fontWeight = FontWeight.Medium)
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

