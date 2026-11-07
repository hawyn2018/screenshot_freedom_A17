// SPDX-License-Identifier: AGPL-3.0-only
package net.hawyn.screenshotfreedoma17

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val AGPL_SOURCE_URL = "https://www.gnu.org/licenses/agpl-3.0.en.html"
private const val AGPL_CHINESE_ASSET = "ui-content/license/agpl-3.0-zh-CN.txt"
private const val AGPL_ENGLISH_ASSET = "ui-content/license/agpl-3.0-en.txt"
private const val NOTICE_ASSET = "ui-content/license/NOTICE.txt"
private val licenseParagraphBreak = Regex("\\r?\\n[ \\t]*\\r?\\n")

private data class LicenseDocuments(val chinese: List<String>?, val english: List<String>?, val notices: String?) {
    val complete: Boolean get() = chinese != null && english != null && notices != null
}

@Composable
internal fun AppLicensePage(
    insets: PaddingValues,
    scroll: LazyListState,
    heading: @Composable () -> Unit,
    onAgree: (() -> Unit)? = null,
    saving: Boolean = false,
    footer: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var showEnglish by rememberSaveable { mutableStateOf(false) }
    val documents by produceState<LicenseDocuments?>(null, context.assets) {
        value = withContext(Dispatchers.IO) {
            fun read(name: String) = runCatching {
                context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull()?.takeIf { it.isNotBlank() }
            fun paragraphs(name: String) = read(name)?.split(licenseParagraphBreak)?.filterNot { it.isBlank() }
            LicenseDocuments(paragraphs(AGPL_CHINESE_ASSET), paragraphs(AGPL_ENGLISH_ASSET), read(NOTICE_ASSET))
        }
    }
    val loaded = documents
    val selectedDocument = if (showEnglish) loaded?.english else loaded?.chinese

    PageList(insets, scroll, bottomPadding = 24.dp + insets.calculateBottomPadding()) {
        item(key = "info-heading") { heading() }
        item(key = "license-intro") {
            Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LicenseHeading("软件及组件许可说明")
                    LicenseBody("自由截图 A17 应用、系统模块及其项目源代码按 GNU Affero 通用公共许可证第 3 版（AGPLv3）发布。")
                    LicenseBody("第三方代码和依赖保留各自的版权与许可声明，适用各自的许可。")
                    LicenseBody("软件按现状提供。担保与责任限制见 AGPLv3 第 15–17 条及适用法律。")
                }
            }
        }
        item(key = "license-language") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LicenseHeading("GNU Affero 通用公共许可证")
                LicenseBody("AGPL v3.0 · 2007 年 11 月 19 日")
                LicenseBody("中文由 AI 依据英文原文翻译，为非官方译文；条款以英文原文为准。")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { showEnglish = false }, modifier = Modifier.weight(1f),
                        colors = if (showEnglish) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColorsPrimary(),
                        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 12.dp)) { Text("中文译文", fontSize = 13.sp) }
                    Button(onClick = { showEnglish = true }, modifier = Modifier.weight(1f),
                        colors = if (showEnglish) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 12.dp)) { Text("English 原文", fontSize = 13.sp) }
                }
            }
        }
        if (selectedDocument == null) {
            item(key = "license-load") {
                LicenseBody(if (loaded == null) "正在加载许可证…" else "许可证文件读取失败，可打开下方 GNU 官方原文。")
            }
        } else {
            itemsIndexed(selectedDocument, key = { index, _ -> "license-paragraph:$showEnglish:$index" }) { _, paragraph ->
                SelectionContainer { LicenseBody(paragraph) }
            }
        }
        item(key = "license-source") {
            TextButton("英文原文：GNU AGPL v3.0", onClick = {
                runCatching { uriHandler.openUri(AGPL_SOURCE_URL) }.onFailure {
                    Toast.makeText(context, "打开链接失败", Toast.LENGTH_SHORT).show()
                }
            })
        }
        item(key = "license-notices") {
            Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LicenseHeading("第三方许可与版权声明")
                    SelectionContainer {
                        LicenseBody(loaded?.notices?.trimEnd()
                            ?: if (loaded == null) "正在加载第三方声明…" else "第三方声明文件读取失败，请查看源码中的 NOTICE.txt。")
                    }
                }
            }
        }
        if (onAgree != null) {
            item(key = "license-agree") {
                Button(onClick = onAgree, enabled = loaded?.complete == true && !saving,
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(if (saving) "正在保存…" else "我同意")
                }
            }
        }
        item(key = "license-footer") { footer() }
    }
}

@Composable
private fun LicenseHeading(value: String) {
    Text(value, modifier = Modifier.semantics { heading() }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun LicenseBody(value: String) {
    Text(value, fontSize = 14.sp, lineHeight = 22.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}
