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
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.subarabify.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val mediaExtensions = listOf("mp4", "mkv", "avi", "m4v")
    
    private var achievedCount by mutableStateOf(0)
    private var missingCount by mutableStateOf(0)
    private var skippedCount by mutableStateOf(0)
    private var selectedFolder by mutableStateOf<String?>(null)
    private var selectedRawPath by mutableStateOf<String>("/storage/emulated/0/Movies")
    private var itemList = mutableStateListOf<MediaItemStatus>()
    private var isScanning by mutableStateOf(false)
    private var showSetupDialog by mutableStateOf(false)
    private var selectedFolderUri by mutableStateOf<Uri?>(null)

    data class MediaItemStatus(
        val name: String, 
        val isAchieved: Boolean, 
        val folderName: String,
        var isSkipped: Boolean = false
    )

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val pathSegment = uri.lastPathSegment ?: ""
                selectedFolder = pathSegment
                selectedFolderUri = uri
                
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
            SubArabifyTheme {
                val context = LocalContext.current
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "ساب أرابيفاي",
                                        color = Gold500,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 24.sp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Gold500.copy(alpha = 0.15f),
                                    ) {
                                        Text(
                                            "v2.0.5",
                                            color = Gold500,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            },
                            actions = {
                                IconButton(onClick = { showSetupDialog = true }) {
                                    Icon(Icons.Default.Settings, contentDescription = "إعداد تيرمكس", tint = TextSecondary)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
                        )
                    }
                ) { paddingValues ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        color = DarkBg
                    ) {
                        DashboardScreen(context)
                        
                        if (showSetupDialog) {
                            TermuxSetupDialog(onDismiss = { showSetupDialog = false }, rawPath = selectedRawPath)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun DashboardScreen(context: Context) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Folder select button ──
            Button(
                onClick = { 
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    folderPickerLauncher.launch(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Gold500),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Text("📂  اختيار مجلد الوسائط", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = DarkBg)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Tracking path ──
            if (selectedFolder != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(SuccessGreen, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "قيد التتبع: $selectedRawPath",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── Scanning state ──
            if (isScanning) {
                Spacer(modifier = Modifier.height(48.dp))
                CircularProgressIndicator(color = Gold500, strokeWidth = 3.dp, modifier = Modifier.size(48.dp))
                Text("جاري فحص المجلدات والحلقات...", color = TextMuted, modifier = Modifier.padding(top = 16.dp))
            } else {
                // ── Stats row ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard("مكتمل ✅", achievedCount, SuccessGreen, SuccessGreen.copy(alpha = 0.12f), modifier = Modifier.weight(1f))
                    StatCard("مفقود 🔴", missingCount, ErrorRose, ErrorRose.copy(alpha = 0.12f), modifier = Modifier.weight(1f))
                    StatCard("متخطى ⏭️", skippedCount, WarnAmber, WarnAmber.copy(alpha = 0.12f), modifier = Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Action buttons row ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Launch Termux Engine
                    Button(
                        onClick = { launchTermuxEngine(context) },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = DarkBg, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تشغيل المحرك", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DarkBg)
                    }

                    // Rescan button
                    OutlinedButton(
                        onClick = { selectedFolderUri?.let { scanFolder(it) } },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = InfoCyan, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إعادة الفحص", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = InfoCyan)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = DarkBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                // ── Media items list ──
                if (itemList.isEmpty()) {
                    Spacer(modifier = Modifier.height(48.dp))
                    Text("اختر مجلد الوسائط لبدء الفحص", color = TextMuted, fontSize = 16.sp)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        val grouped = itemList.groupBy { it.folderName }
                        grouped.forEach { (folder, folderItems) ->
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("📁", fontSize = 16.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = folder,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = DarkCardHigh
                                    ) {
                                        Text(
                                            "${folderItems.size} ملف",
                                            color = TextMuted,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            items(folderItems) { item ->
                                MediaItemRow(item)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun launchTermuxEngine(context: Context) {
        // Try to send the command directly to Termux via RUN_COMMAND intent
        try {
            val runIntent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "cd ~/SubArabify && node subarabify.js --media \"$selectedRawPath\""))
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            }
            context.startService(runIntent)
            Toast.makeText(context, "🚀 تم إرسال الأمر إلى تيرمكس", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // Fallback: just open Termux app
            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.termux")
            if (launchIntent != null) {
                // Copy the command to clipboard for easy paste
                val cmd = "cd ~/SubArabify && node subarabify.js --media \"$selectedRawPath\""
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("SubArabify", cmd))
                context.startActivity(launchIntent)
                Toast.makeText(context, "📋 تم نسخ الأمر — الصقه في تيرمكس", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "❌ الرجاء تثبيت تيرمكس أولاً", Toast.LENGTH_SHORT).show()
                showSetupDialog = true
            }
        }
    }

    @Composable
    fun TermuxSetupDialog(onDismiss: () -> Unit, rawPath: String) {
        val context = LocalContext.current
        
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = DarkSurface,
            shape = RoundedCornerShape(20.dp),
            title = { 
                Text("⚙️ دليل إعداد تيرمكس", color = Gold500, fontWeight = FontWeight.Bold, fontSize = 20.sp) 
            },
            text = {
                LazyColumn {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = WarnAmber.copy(alpha = 0.1f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Info, contentDescription = null, tint = WarnAmber, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("⚠️ هام", fontWeight = FontWeight.Bold, color = TextPrimary)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "لا تقم بتثبيت تيرمكس من متجر بلاي. قم بتثبيته من F-Droid.",
                                    color = TextSecondary, fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))
                                        context.startActivity(intent)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = InfoCyan)
                                ) {
                                    Text("تحميل تيرمكس من F-Droid", color = DarkBg, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    
                    item { SetupStep("1️⃣  منح إذن التخزين", "termux-setup-storage", context) }
                    item { SetupStep("2️⃣  تثبيت التبعيات", "pkg update && pkg install nodejs ffmpeg git -y", context) }
                    item { SetupStep("3️⃣  تحميل المشروع", "git clone https://github.com/muxd22-alt/SubArabify.git && cd SubArabify && npm install", context) }
                    item { SetupStep("4️⃣  تشغيل الأتمتة", "cd ~/SubArabify && node subarabify.js --media \"$rawPath\"", context) }
                }
            },
            confirmButton = {
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold500)
                ) {
                    Text("إغلاق", color = DarkBg, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    @Composable
    fun SetupStep(title: String, command: String, context: Context) {
        Column(modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBg, RoundedCornerShape(10.dp))
                    .border(1.dp, DarkBorder, RoundedCornerShape(10.dp))
                    .clickable { 
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Termux Command", command)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "✅ تم نسخ الأمر!", Toast.LENGTH_SHORT).show()
                    }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    command,
                    color = SuccessGreen,
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Gold500.copy(alpha = 0.2f)
                ) {
                    Text("نسخ", color = Gold500, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
        }
    }

    @Composable
    fun StatCard(title: String, count: Int, accentColor: Color, bgColor: Color, modifier: Modifier = Modifier) {
        Card(
            modifier = modifier.height(90.dp),
            colors = CardDefaults.cardColors(containerColor = bgColor),
            shape = RoundedCornerShape(14.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(count.toString(), color = accentColor, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(title, color = accentColor.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    fun MediaItemRow(item: MediaItemStatus) {
        val context = LocalContext.current
        val index = itemList.indexOf(item)
        val isSkipped = item.isSkipped
        
        val statusColor = when {
            item.isAchieved -> SuccessGreen
            isSkipped -> WarnAmber
            else -> ErrorRose
        }
        val statusLabel = when {
            item.isAchieved -> "مكتمل"
            isSkipped -> "متخطى"
            else -> "مفقود"
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(14.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status indicator dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(statusColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))

                // File name + status
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.name,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        statusLabel,
                        color = statusColor.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal
                    )
                }

                // Action buttons: Skip / Unskip + Start
                if (!item.isAchieved) {
                    // Skip / Unskip toggle
                    IconButton(
                        onClick = {
                            if (index >= 0) {
                                val updated = itemList[index].copy(isSkipped = !isSkipped)
                                itemList[index] = updated
                                recalcCounts()
                            }
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            if (isSkipped) Icons.Default.Refresh else Icons.Default.Close,
                            contentDescription = if (isSkipped) "إلغاء التخطي" else "تخطي",
                            tint = if (isSkipped) InfoCyan else WarnAmber,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Start translation for this specific file
                    if (!isSkipped) {
                        IconButton(
                            onClick = { startSingleTranslation(context, item) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "ترجمة",
                                tint = SuccessGreen,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                } else {
                    // Already done checkmark
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "مكتمل",
                        tint = SuccessGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    private fun startSingleTranslation(context: Context, item: MediaItemStatus) {
        try {
            val runIntent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(
                    "-c",
                    "cd ~/SubArabify && node -e \"" +
                    "const s = require('./subarabify.js');" +
                    "\" --media \"$selectedRawPath\" 2>&1"
                ))
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            }
            context.startService(runIntent)
            Toast.makeText(context, "🚀 جاري ترجمة: ${item.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // Fallback: copy command + open Termux
            val cmd = "cd ~/SubArabify && node subarabify.js --media \"$selectedRawPath\""
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("SubArabify", cmd))
            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.termux")
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                Toast.makeText(context, "📋 تم نسخ الأمر — الصقه في تيرمكس", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "❌ الرجاء تثبيت تيرمكس أولاً", Toast.LENGTH_SHORT).show()
                showSetupDialog = true
            }
        }
    }

    private fun recalcCounts() {
        achievedCount = itemList.count { it.isAchieved }
        missingCount = itemList.count { !it.isAchieved && !it.isSkipped }
        skippedCount = itemList.count { it.isSkipped }
    }

    private fun scanFolder(uri: Uri) {
        isScanning = true
        itemList.clear()
        achievedCount = 0
        missingCount = 0
        skippedCount = 0

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
                itemList.addAll(newItems.sortedWith(compareBy({ it.isAchieved }, { it.folderName }, { it.name })))
                achievedCount = achieved
                missingCount = missing
                skippedCount = 0
                isScanning = false
            }
        }
    }
}
