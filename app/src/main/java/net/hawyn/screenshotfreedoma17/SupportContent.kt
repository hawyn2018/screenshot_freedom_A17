// SPDX-License-Identifier: AGPL-3.0-only
package net.hawyn.screenshotfreedoma17

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading as accessibilityHeading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AppSupportPage(
    insets: PaddingValues,
    scroll: LazyListState,
    heading: @Composable () -> Unit,
    footer: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 640.dp
        PageList(insets, scroll, bottomPadding = 24.dp + insets.calculateBottomPadding()) {
            item(key = "info-heading") { heading() }
            item {
                Card(cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
                    Text("作者研发不易", color = MiuixTheme.colorScheme.primary,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("感谢您的支持", modifier = Modifier.semantics { accessibilityHeading() },
                        fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("您的支持是我马不停蹄研发的动力", color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp)
                }
            }
            if (wide) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SupportQrImage("微信支付支持二维码", R.drawable.support_wechat, 1688f / 2332f, Modifier.weight(1f))
                        SupportQrImage("支付宝支持二维码", R.drawable.support_alipay, 1182f / 1772f, Modifier.weight(1f))
                    }
                }
            } else {
                item { SupportQrImage("微信支付支持二维码", R.drawable.support_wechat, 1688f / 2332f, Modifier) }
                item { SupportQrImage("支付宝支持二维码", R.drawable.support_alipay, 1182f / 1772f, Modifier) }
            }
            item { footer() }
        }
    }
}

@Composable
private fun SupportQrImage(description: String, image: Int, aspect: Float, modifier: Modifier) {
    // Make the image itself the rounded surface, with no separate frame or inset.
    Image(
        painter = painterResource(image),
        contentDescription = description,
        modifier = modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(24.dp)),
        contentScale = ContentScale.Fit,
    )
}
