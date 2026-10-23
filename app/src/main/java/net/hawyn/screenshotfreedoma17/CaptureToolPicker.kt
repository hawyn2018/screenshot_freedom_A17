package net.hawyn.screenshotfreedoma17

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.Collator
import java.util.Locale

private data class CaptureToolEntry(val packageName: String, val label: String, val icon: ImageBitmap?, val system: Boolean)

/** Package discovery and icon decoding run off the UI thread. No app process is started. */
@Composable
internal fun CaptureToolPicker(initial: Set<String>, initialFlagSecure: Set<String>, initialSkipScreenshot: Set<String>,
                               blackMode: Boolean, dismiss: () -> Unit, save: (Set<String>, Set<String>, Set<String>) -> Unit) {
    val context = LocalContext.current.applicationContext
    var selected by remember { mutableStateOf(initial.toSet()) }
    var flagSecure by remember { mutableStateOf(initialFlagSecure.intersect(initial)) }
    var skipScreenshot by remember { mutableStateOf(initialSkipScreenshot.intersect(initial)) }
    var query by remember { mutableStateOf("") }
    var selectedOnly by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<CaptureToolEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(context, reload) {
        loading = true
        failure = null
        try {
            entries = withContext(Dispatchers.IO) { loadCaptureTools(context, initial) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = "应用列表读取失败：${error.javaClass.simpleName}"
        } finally {
            loading = false
        }
    }
    val visible = remember(entries, query, selectedOnly, selected) {
        entries.filter { entry ->
            (!selectedOnly || entry.packageName in selected) &&
                (query.isBlank() || entry.label.contains(query, true) || entry.packageName.contains(query, true))
        }
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(modifier = Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 14.dp)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
            cornerRadius = 28.dp, insideMargin = PaddingValues(0.dp)) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(if (blackMode) "采集工具黑名单" else "采集工具白名单", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(if (blackMode) "默认兼容所有具有系统采集通道的工具。勾选后分别排除两种机制；首次勾选两项都排除。"
                        else "只兼容已勾选并开启对应机制的工具。首次勾选时两项开启；系统工具已自动识别。",
                        fontSize = 12.sp, lineHeight = 19.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text("FLAG_SECURE 控制窗口保护，SKIP_SCREENSHOT 控制图层排除；两项均覆盖截屏与录屏。排除 SKIP_SCREENSHOT 的工具采集期间，图层兼容按排除优先规则暂停。",
                        fontSize = 12.sp, lineHeight = 19.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    BasicTextField(value = query, onValueChange = { query = it.take(160) }, singleLine = true,
                        textStyle = TextStyle(fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(MiuixTheme.colorScheme.surface).padding(14.dp)
                            .semantics { contentDescription = "搜索采集工具" },
                        decorationBox = { field ->
                            Box {
                                if (query.isEmpty()) Text("搜索应用名称或包名", fontSize = 15.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                field()
                            }
                        })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 ${selected.size} 个", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(if (selectedOnly) "显示全部" else "仅看已选", onClick = { selectedOnly = !selectedOnly })
                        TextButton("清空", onClick = { selected = emptySet(); flagSecure = emptySet(); skipScreenshot = emptySet() })
                    }
                }
                when {
                    loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("正在读取应用与图标…", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    failure != null -> Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(failure.orEmpty(), fontSize = 13.sp, color = MiuixTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { reload++ }) { Text("重试") }
                    }
                    visible.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(if (selectedOnly) "暂无符合条件的已选工具" else "没有匹配的应用", fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(visible, key = { it.packageName }) { entry ->
                            val checked = entry.packageName in selected
                            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                            Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox) {
                                if (it) {
                                    selected = selected + entry.packageName
                                    flagSecure = flagSecure + entry.packageName
                                    skipScreenshot = skipScreenshot + entry.packageName
                                } else {
                                    selected = selected - entry.packageName
                                    flagSecure = flagSecure - entry.packageName
                                    skipScreenshot = skipScreenshot - entry.packageName
                                }
                            }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (entry.icon != null) Image(entry.icon, null, Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)))
                                else Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                                    .background(MiuixTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                    Text(entry.label.take(1), fontSize = 20.sp, color = MiuixTheme.colorScheme.primary)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(entry.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(entry.packageName + if (entry.system) " · 系统" else "", fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                Checkbox(state = if (checked) ToggleableState.On else ToggleableState.Off, onClick = null)
                            }
                            if (checked) Row(Modifier.fillMaxWidth().padding(start = 54.dp, top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("FLAG_SECURE", fontSize = 12.sp)
                                    Text(if (blackMode) "排除适配" else "允许适配", fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    Switch(checked = entry.packageName in flagSecure, onCheckedChange = {
                                        flagSecure = if (it) flagSecure + entry.packageName else flagSecure - entry.packageName
                                    }, modifier = Modifier.semantics { contentDescription = "${entry.label} ${if (blackMode) "排除 FLAG_SECURE" else "允许 FLAG_SECURE"}" })
                                }
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("SKIP_SCREENSHOT", fontSize = 12.sp)
                                    Text(if (blackMode) "排除适配" else "允许适配", fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    Switch(checked = entry.packageName in skipScreenshot, onCheckedChange = {
                                        skipScreenshot = if (it) skipScreenshot + entry.packageName else skipScreenshot - entry.packageName
                                    }, modifier = Modifier.semantics { contentDescription = "${entry.label} ${if (blackMode) "排除 SKIP_SCREENSHOT" else "允许 SKIP_SCREENSHOT"}" })
                                }
                            }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = dismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = { save(selected.toSet(), flagSecure.intersect(selected), skipScreenshot.intersect(selected)) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary()) { Text("保存 (${selected.size})") }
                }
            }
        }
    }
}

private fun loadCaptureTools(context: Context, retained: Set<String>): List<CaptureToolEntry> {
    val manager = context.packageManager
    val entries = manager.getInstalledApplications(0).asSequence().map { app ->
            val label = runCatching { app.loadLabel(manager).toString() }.getOrDefault(app.packageName)
            val icon = runCatching { app.loadIcon(manager).toBitmap(84, 84).asImageBitmap() }.getOrNull()
            CaptureToolEntry(app.packageName, label, icon,
                app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
        }.toMutableList()
    val installed = entries.mapTo(HashSet()) { it.packageName }
    retained.filterNot { it in installed }.forEach { entries += CaptureToolEntry(it, "已保存的工具", null, false) }
    val collator = Collator.getInstance(Locale.getDefault())
    return entries.sortedWith { first, second ->
        when {
            first.packageName in retained && second.packageName !in retained -> -1
            first.packageName !in retained && second.packageName in retained -> 1
            else -> collator.compare(first.label, second.label).takeIf { it != 0 }
                ?: first.packageName.compareTo(second.packageName)
        }
    }
}
