// SPDX-License-Identifier: AGPL-3.0-only
package net.hawyn.screenshotfreedoma17

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal class LicenseConsent(context: Context) {
    private val preferences = context.applicationContext.createDeviceProtectedStorageContext()
        .getSharedPreferences("open_source_license", Context.MODE_PRIVATE)

    fun accepted(): Boolean = runCatching { preferences.getBoolean(LICENSE_ID, false) }.getOrDefault(false)

    fun accept(): Boolean {
        if (accepted()) return true
        var saved = false
        try {
            saved = preferences.edit().putBoolean(LICENSE_ID, true).commit()
            return saved
        } finally {
            if (!saved) preferences.edit().remove(LICENSE_ID).apply()
        }
    }

    private companion object { const val LICENSE_ID = "AGPL-3.0" }
}

@Composable
internal fun LicenseConsentPrompt(saving: Boolean, error: Boolean, readFull: () -> Unit,
                                   agree: () -> Unit, exit: () -> Unit, modifier: Modifier,
                                   footer: @Composable () -> Unit) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .86f
    val frostedTint = MiuixTheme.colorScheme.surface.copy(alpha = if (Build.VERSION.SDK_INT >= 31) .30f else .88f)
    // Share the activity window so predictive Back can reveal this prompt before committing.
    Box(modifier.background(frostedTint).safeDrawingPadding(),
        contentAlignment = Alignment.Center) {
        Card(modifier = Modifier.padding(horizontal = 18.dp).widthIn(max = 520.dp).fillMaxWidth()
            .semantics { paneTitle = "开源许可证" },
            cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
            Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("开源许可证", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                Text("自由截图 A17 的项目代码依据 GNU Affero 通用公共许可证第 3 版（AGPLv3）发布。第三方组件继续适用各自的许可。",
                    fontSize = 14.sp, lineHeight = 21.sp)
                Text("本软件按现状提供，完整条款请阅读许可证。此确认不附加使用条款。",
                    fontSize = 13.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                footer()
                Button(onClick = readFull, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("阅读完整许可证") }
                if (error) Text("同意状态保存失败，请重试。", fontSize = 13.sp, color = MiuixTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = exit, enabled = !saving, modifier = Modifier.weight(1f)) { Text("退出") }
                    Button(onClick = agree, enabled = !saving, modifier = Modifier.weight(1f)) {
                        Text(if (saving) "正在保存…" else "同意")
                    }
                }
            }
        }
    }
}
