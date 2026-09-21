package com.subarabify.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.*
import com.subarabify.ui.theme.*
import com.subarabify.worker.SubArabifyWorker
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SubArabifyTheme {
                SubArabifyApp()
            }
        }
    }
}

// ─── Prefs helpers ──────────────────────────────────────────────────
private fun prefs(ctx: Context) =
    ctx.getSharedPreferences("subarabify_prefs", Context.MODE_PRIVATE)

private fun statusPrefs(ctx: Context) =
    ctx.getSharedPreferences("subarabify_status", Context.MODE_PRIVATE)

// ─── Data models ────────────────────────────────────────────────────
data class LogEntry(val fileName: String, val status: String, val timestamp: String)

data class MediaItem(val name: String, val status: String) // done, pending, skipped, error, translating, weak_source

// ─── Root composable ────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubArabifyApp() {
    val context = LocalContext.current
    var selectedFolderUri by remember {
        mutableStateOf(prefs(context).getString("folder_uri", null))
    }
    var selectedFolderName by remember {
        mutableStateOf(prefs(context).getString("folder_name", null))
    }
    var monitoringActive by remember {
        mutableStateOf(prefs(context).getBoolean("monitoring", false))
    }
    var scanInterval by remember {
        mutableStateOf(prefs(context).getInt("scan_interval", 60))
    }
    var translationLog by remember { mutableStateOf(loadLog(context)) }
    var mediaItems by remember { mutableStateOf(loadMediaItems(context)) }
    var skipSet by remember {
        mutableStateOf(prefs(context).getStringSet("skip_list", emptySet())?.toMutableSet() ?: mutableSetOf())
    }

    // Refresh data periodically
    LaunchedEffect(monitoringActive) {
        while (true) {
            kotlinx.coroutines.delay(5000)
            translationLog = loadLog(context)
            mediaItems = loadMediaItems(context)
        }
    }

    // SAF folder picker
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, flags)
            selectedFolderUri = it.toString()
            selectedFolderName = it.lastPathSegment ?: "Selected Folder"
            prefs(context).edit()
                .putString("folder_uri", it.toString())
                .putString("folder_name", selectedFolderName)
                .apply()
        }
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            CenterAlignedTopAppBar(
                title = { BrandText(size = 22) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            // ── Hero ──
            item { HeroBanner() }

            // ── Monitoring toggle ──
            item {
                MonitoringCard(
                    active = monitoringActive,
                    folderSelected = selectedFolderUri != null,
                    onToggle = {
                        if (selectedFolderUri == null) return@MonitoringCard
                        monitoringActive = !monitoringActive
                        prefs(context).edit().putBoolean("monitoring", monitoringActive).apply()
                        if (monitoringActive) {
                            scheduleWorker(context, selectedFolderUri!!, scanInterval)
                        } else {
                            cancelWorker(context)
                        }
                    }
                )
            }

            // ── Folder selector ──
            item {
                FolderCard(
                    folderName = selectedFolderName ?: "Not selected",
                    hasFolder = selectedFolderUri != null,
                    onSelectFolder = { folderPicker.launch(null) },
                )
            }

            // ── Actions: Scan Now / Next ──
            if (selectedFolderUri != null) {
                item {
                    val nextPending = mediaItems.firstOrNull {
                        it.status == "pending" || it.status == "error"
                    }
                    ActionRow(
                        onScanNow = {
                            runOneShot(
                                context, selectedFolderUri!!,
                                forceRetranslate = false,
                                targetBase = null,
                                oneOnly = false,
                            )
                            translationLog = loadLog(context)
                            mediaItems = loadMediaItems(context)
                        },
                        onNext = {
                            val target = nextPending?.name ?: return@ActionRow
                            runOneShot(
                                context, selectedFolderUri!!,
                                forceRetranslate = true,
                                targetBase = target,
                                oneOnly = true,
                            )
                            statusPrefs(context).edit()
                                .putString("status_$target", "translating")
                                .apply()
                            mediaItems = loadMediaItems(context)
                        },
                        nextLabel = nextPending?.name,
                        hasNext = nextPending != null,
                    )
                }
            }

            // ── Stats row ──
            item {
                val doneCount = mediaItems.count { it.status == "done" }
                val pendingCount = mediaItems.count {
                    it.status == "pending" || it.status == "translating" || it.status == "weak_source"
                }
                val skippedCount = mediaItems.count { it.status == "skipped" }
                StatsRow(
                    done = doneCount,
                    pending = pendingCount,
                    skipped = skippedCount,
                )
            }

            // ── Scan Interval ──
            item {
                IntervalCard(
                    interval = scanInterval,
                    onIntervalChange = { newVal ->
                        scanInterval = newVal
                        prefs(context).edit().putInt("scan_interval", newVal).apply()
                        if (monitoringActive && selectedFolderUri != null) {
                            scheduleWorker(context, selectedFolderUri!!, scanInterval)
                        }
                    },
                )
            }

            // ── Media Items Section ──
            if (mediaItems.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Media Library",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary,
                        )
                        Text(
                            "${mediaItems.size} items",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                }
                items(mediaItems.sortedBy {
                    when (it.status) {
                        "pending" -> 0; "weak_source" -> 1; "translating" -> 2
                        "error" -> 3; "done" -> 4; "skipped" -> 5; else -> 6
                    }
                }) { media ->
                    MediaItemCard(
                        item = media,
                        onSkip = {
                            skipSet.add(media.name)
                            prefs(context).edit()
                                .putStringSet("skip_list", skipSet)
                                .apply()
                            statusPrefs(context).edit()
                                .putString("status_${media.name}", "skipped")
                                .apply()
                            mediaItems = loadMediaItems(context)
                        },
                        onUnskip = {
                            skipSet.remove(media.name)
                            prefs(context).edit()
                                .putStringSet("skip_list", skipSet)
                                .apply()
                            statusPrefs(context).edit()
                                .putString("status_${media.name}", "pending")
                                .apply()
                            mediaItems = loadMediaItems(context)
                        },
                        onRedo = {
                            if (selectedFolderUri == null) return@MediaItemCard
                            statusPrefs(context).edit()
                                .putString("status_${media.name}", "translating")
                                .apply()
                            mediaItems = loadMediaItems(context)
                            runOneShot(
                                context, selectedFolderUri!!,
                                forceRetranslate = true,
                                targetBase = media.name,
                                oneOnly = true,
                            )
                        },
                    )
                }
            }

            // ── Activity Log ──
            item {
                Text(
                    "Recent Activity",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (translationLog.isEmpty()) {
                item { EmptyState() }
            } else {
                items(translationLog.takeLast(20).reversed()) { entry ->
                    LogEntryCard(entry)
                }
            }
        }
    }
}

// ─── Two-tone brand text ────────────────────────────────────────────
@Composable
fun BrandText(size: Int = 22) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = TextPrimary, fontSize = size.sp)) { append("Sub") }
            withStyle(SpanStyle(color = Gold500, fontSize = size.sp)) { append("Arabify") }
        },
        style = MaterialTheme.typography.headlineMedium,
    )
}

// ─── Hero Banner ────────────────────────────────────────────────────
@Composable
fun HeroBanner() {
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ), label = "glowAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(24.dp))
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Gold500.copy(alpha = glowAlpha), Color.Transparent),
                        center = Offset(size.width * 0.7f, size.height * 0.3f),
                        radius = size.width * 0.6f,
                    ),
                    radius = size.width * 0.6f,
                    center = Offset(size.width * 0.7f, size.height * 0.3f),
                )
            }
            .background(
                Brush.linearGradient(
                    colors = listOf(DarkCard, DarkCardHigh),
                    start = Offset.Zero,
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
                shape = RoundedCornerShape(24.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🎬", fontSize = 36.sp, modifier = Modifier.padding(bottom = 8.dp))
            Text(
                "Automatic Arabic Subtitles",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "On-device • Offline • Powered by ML Kit",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
    }
}

// ─── Monitoring Card ────────────────────────────────────────────────
@Composable
fun MonitoringCard(active: Boolean, folderSelected: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = if (active) BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.4f)) else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (active) SuccessGreen else TextMuted),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        if (active) "Monitoring Active" else "Monitoring Off",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (active) SuccessGreen else TextSecondary,
                    )
                    Text(
                        when {
                            !folderSelected -> "Select a folder first"
                            active -> "Scanning for new media…"
                            else -> "Tap to start watching"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
            }
            Switch(
                checked = active,
                onCheckedChange = { onToggle() },
                enabled = folderSelected,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = DarkBg,
                    checkedTrackColor = SuccessGreen,
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = DarkBorder,
                ),
            )
        }
    }
}

// ─── Folder Card ────────────────────────────────────────────────────
@Composable
fun FolderCard(folderName: String, hasFolder: Boolean, onSelectFolder: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        onClick = onSelectFolder,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(Gold500.copy(alpha = 0.2f), Gold700.copy(alpha = 0.1f)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Folder, contentDescription = null, tint = Gold500, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Media Library", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text(
                    if (hasFolder) folderName else "Tap to select your movies folder",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasFolder) Gold400 else TextMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = TextMuted)
        }
    }
}

// ─── Stats Row ──────────────────────────────────────────────────────
@Composable
fun StatsRow(done: Int, pending: Int, skipped: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatChip(Modifier.weight(1f), Icons.Outlined.CheckCircle, "Done", "$done", SuccessGreen)
        StatChip(Modifier.weight(1f), Icons.Outlined.Schedule, "Pending", "$pending", WarnAmber)
        StatChip(Modifier.weight(1f), Icons.Outlined.SkipNext, "Skipped", "$skipped", TextMuted)
    }
}

@Composable
fun StatChip(modifier: Modifier, icon: ImageVector, label: String, value: String, color: Color) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
    }
}

// ─── Interval Card ──────────────────────────────────────────────────
@Composable
fun IntervalCard(interval: Int, onIntervalChange: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Scan Interval", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text(
                    when {
                        interval < 60 -> "${interval} min"
                        interval == 60 -> "1 hour"
                        else -> "${interval / 60}h ${interval % 60}m"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = Gold400,
                )
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = interval.toFloat(),
                onValueChange = { onIntervalChange(it.toInt()) },
                valueRange = 15f..360f,
                steps = 22,
                colors = SliderDefaults.colors(
                    thumbColor = Gold500,
                    activeTrackColor = Gold500,
                    inactiveTrackColor = DarkBorder,
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("15m", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Text("6h", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
        }
    }
}

// ─── Action row: Scan Now + Next ────────────────────────────────────
@Composable
fun ActionRow(
    onScanNow: () -> Unit,
    onNext: () -> Unit,
    nextLabel: String?,
    hasNext: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onScanNow,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Gold500, contentColor = DarkBg),
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Scan Now", style = MaterialTheme.typography.labelLarge)
        }
        Button(
            onClick = onNext,
            enabled = hasNext,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = DarkCardHigh,
                contentColor = TextPrimary,
                disabledContainerColor = DarkCard,
                disabledContentColor = TextMuted,
            ),
        ) {
            Icon(Icons.Rounded.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (hasNext) "Next" else "Caught up",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
    if (hasNext && nextLabel != null) {
        Text(
            "Next up: $nextLabel",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ─── Media Item Card (skip / unskip / redo) ─────────────────────────
@Composable
fun MediaItemCard(
    item: MediaItem,
    onSkip: () -> Unit,
    onUnskip: () -> Unit,
    onRedo: () -> Unit,
) {
    val (statusColor, statusIcon) = when (item.status) {
        "done" -> SuccessGreen to Icons.Rounded.CheckCircle
        "translating" -> InfoCyan to Icons.Rounded.Sync
        "pending" -> WarnAmber to Icons.Rounded.HourglassTop
        "weak_source" -> WarnAmber to Icons.Rounded.Warning
        "skipped" -> TextMuted to Icons.Rounded.SkipNext
        "error" -> ErrorRose to Icons.Rounded.Error
        else -> TextMuted to Icons.Rounded.Help
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when (item.status) {
                        "done" -> "✓ Arabic .srt ready — tap redo to rebuild"
                        "pending" -> "No usable English .srt yet"
                        "weak_source" -> "English .srt too short (YTS promo?) — add a full .en.srt"
                        "skipped" -> "Manually skipped"
                        "translating" -> "Translation in progress…"
                        "error" -> "Failed — tap redo"
                        else -> item.status
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
            if (item.status == "done" || item.status == "error" || item.status == "weak_source") {
                IconButton(onClick = onRedo, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.Replay,
                        contentDescription = "Redo",
                        tint = Gold400,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (item.status != "done" && item.status != "translating") {
                IconButton(
                    onClick = { if (item.status == "skipped") onUnskip() else onSkip() },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        if (item.status == "skipped") Icons.Rounded.PlayArrow else Icons.Rounded.SkipNext,
                        contentDescription = if (item.status == "skipped") "Unskip" else "Skip",
                        tint = if (item.status == "skipped") Gold400 else TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

// ─── Empty state ────────────────────────────────────────────────────
@Composable
fun EmptyState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("📂", fontSize = 40.sp)
            Spacer(Modifier.height(12.dp))
            Text("No activity yet", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
            Spacer(Modifier.height(4.dp))
            Text(
                "Select a media folder and enable monitoring\nto start translating subtitles automatically",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ─── Log Entry Card ─────────────────────────────────────────────────
@Composable
fun LogEntryCard(entry: LogEntry) {
    val statusColor = when (entry.status) {
        "success" -> SuccessGreen; "error" -> ErrorRose; else -> WarnAmber
    }
    val statusIcon = when (entry.status) {
        "success" -> Icons.Rounded.CheckCircle; "error" -> Icons.Rounded.Error; else -> Icons.Rounded.HourglassTop
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = statusColor.copy(alpha = 0.12f),
            ) {
                Text(
                    entry.status.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ─── WorkManager scheduling ─────────────────────────────────────────
private fun scheduleWorker(context: Context, folderUri: String, intervalMinutes: Int) {
    val data = workDataOf("LIBRARY_FOLDER_URI" to folderUri)
    val request = PeriodicWorkRequestBuilder<SubArabifyWorker>(
        intervalMinutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES,
    )
        .setInputData(data)
        .setConstraints(
            Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
        )
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "subarabify_monitor",
        ExistingPeriodicWorkPolicy.UPDATE,
        request,
    )
    // Also kick an immediate scan so the user isn't stuck waiting 15+ minutes
    runOneShot(context, folderUri, forceRetranslate = false, targetBase = null, oneOnly = false)
}

private fun runOneShot(
    context: Context,
    folderUri: String,
    forceRetranslate: Boolean,
    targetBase: String?,
    oneOnly: Boolean,
) {
    val dataBuilder = androidx.work.Data.Builder()
        .putString("LIBRARY_FOLDER_URI", folderUri)
        .putBoolean("FORCE_RETRANSLATE", forceRetranslate)
        .putBoolean("ONE_ONLY", oneOnly)
    if (targetBase != null) {
        dataBuilder.putString("TARGET_BASE", targetBase)
    }
    val request = OneTimeWorkRequestBuilder<SubArabifyWorker>()
        .setInputData(dataBuilder.build())
        .build()
    WorkManager.getInstance(context).enqueue(request)
}

private fun cancelWorker(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork("subarabify_monitor")
}

// ─── Data loaders ───────────────────────────────────────────────────
private fun loadLog(context: Context): List<LogEntry> {
    val raw = prefs(context).getString("log_entries", "") ?: ""
    if (raw.isBlank()) return emptyList()
    val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    return raw.split("|||").windowed(3, 3, partialWindows = false).mapNotNull { parts ->
        try {
            val ts = parts[2].toLongOrNull() ?: return@mapNotNull null
            LogEntry(parts[0], parts[1], sdf.format(Date(ts)))
        } catch (_: Exception) { null }
    }
}

private fun loadMediaItems(context: Context): List<MediaItem> {
    val sp = statusPrefs(context)
    val all = sp.all
    return all.entries
        .filter { it.key.startsWith("status_") }
        .map { MediaItem(it.key.removePrefix("status_"), it.value.toString()) }
}
