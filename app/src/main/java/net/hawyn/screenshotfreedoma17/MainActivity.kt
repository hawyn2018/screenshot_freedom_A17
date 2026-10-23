package net.hawyn.screenshotfreedoma17

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.*

/** Authors: Hawyn; Codex. The app is a controller and a test fixture, never a target-app hook. */
class MainActivity : ComponentActivity() {
    private var secureTestActive = false
    private var requestedTab by mutableIntStateOf(0)
    private var navigationSerial by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedTab = tabFrom(intent)
        val controller = CompatController(applicationContext)
        val preferences = UiPreferences(applicationContext)
        setContent { ScreenshotApp(this, controller, preferences, requestedTab, navigationSerial) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedTab = tabFrom(intent)
        navigationSerial++
    }

    private fun tabFrom(intent: Intent): Int = when {
        intent.getStringExtra("tab") == "secure" || intent.component?.className?.endsWith("SecureTestActivity") == true -> 1
        intent.getStringExtra("tab") == "skip" || intent.component?.className?.endsWith("SkipTestActivity") == true -> 2
        intent.getStringExtra("tab") == "theme" -> 3
        else -> 0
    }

    fun secureTest(active: Boolean) {
        secureTestActive = active
        if (active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onResume() {
        super.onResume()
        if (secureTestActive) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onDestroy()
    }
}

private const val ARBITRATION_DISABLED_LABEL = "系统采集仲裁已关闭，将允许所有采集工具"

private data class RuntimeStatus(
    val secure: Boolean = true,
    val skip: Boolean = true,
    val recordingSecure: Boolean = true,
    val recordingSkip: Boolean = true,
    val arbitrationEnabled: Boolean = false,
    val captureToolCount: Int = 0,
    val captureToolsInitialized: Boolean = false,
    val captureToolMode: String = CompatController.TOOL_MODE_BLACK,
    val captureToolMigrationNotice: String = "",
    val framework: Boolean = false,
    val frameworkLoaded: Boolean = false,
    val configurationSynced: Boolean = false,
    val native: Boolean = false,
    val nativeMatching: Boolean = false,
    val nativeKnown: Boolean = false,
    val nativeOperationOk: Boolean = false,
    val nativeCapabilityGap: Boolean = false,
    val recordingNative: Boolean = false,
    val recordingNativeMatching: Boolean = false,
    val recordingNativeKnown: Boolean = false,
    val recordingNativeSupported: Boolean = false,
    val recordingNativeOperationOk: Boolean = false,
    val message: String = "正在读取运行状态…",
    val frameworkDetails: String = "正在连接框架…",
    val nativeDetails: String = "正在查询系统组件…",
    val recordingNativeDetails: String = "正在查询录屏组件…",
    val recordingPipeline: String = "",
    val policyKnown: Boolean = false,
    val policyRunning: Boolean = false,
    val policyMatching: Boolean = false,
    val policyReady: Boolean = false,
    val policyPaused: Boolean = true,
    val policyBlockers: Int = -1,
    val policyDetails: String = "正在查询采集仲裁…",
)

private fun CompatController.runtimeStatus() = RuntimeStatus(
    secure = getSecureEnabled(), skip = getSkipEnabled(),
    recordingSecure = getRecordingSecureEnabled(), recordingSkip = getRecordingSkipEnabled(),
    arbitrationEnabled = getArbitrationEnabled(),
    captureToolCount = getCaptureToolCount(),
    captureToolsInitialized = areCaptureToolsInitialized(),
    captureToolMode = getCaptureToolMode(),
    captureToolMigrationNotice = getCaptureToolMigrationNotice(),
    framework = isFrameworkConnected(), frameworkLoaded = isFrameworkLoaded(),
    configurationSynced = isConfigurationSynced(), native = isNativeActive(), nativeKnown = isNativeKnown(),
    nativeMatching = isNativeMatching(),
    nativeOperationOk = isNativeOperationOk(),
    nativeCapabilityGap = hasNativeCapabilityGap(),
    recordingNative = isRecordingNativeActive(), recordingNativeKnown = isRecordingNativeKnown(),
    recordingNativeMatching = isRecordingNativeMatching(),
    recordingNativeSupported = isRecordingNativeSupported(), recordingNativeOperationOk = isRecordingNativeOperationOk(), message = getStatus(),
    frameworkDetails = getFrameworkStatus(), nativeDetails = getNativeDetails(),
    recordingNativeDetails = getRecordingNativeDetails(), recordingPipeline = getRecordingPipeline(),
    policyKnown = isPolicyKnown(), policyRunning = isPolicyRunning(), policyReady = isPolicyReady(), policyPaused = isPolicyPaused(),
    policyMatching = isPolicyMatching(),
    policyBlockers = getPolicyBlockers(), policyDetails = getPolicyDetails(),
)

private class SurfaceTestSession {
    var surface: SkipTestSurface? = null
    var frameObservation: SkipTestSurface.FrameObservation? = null
    var appliedSkip: Boolean? = null
    var appliedSeed: Int? = null

    fun release() {
        frameObservation?.cancel()
        frameObservation = null
        surface = null
    }
}

@Composable
private fun ScreenshotApp(activity: MainActivity, controller: CompatController, preferences: UiPreferences, initialTab: Int, navigationSerial: Int) {
    var appearance by remember { mutableStateOf(preferences.read()) }
    val pager = rememberPagerState(initialPage = initialTab, pageCount = { 4 })
    val selected = pager.currentPage
    val pagePosition = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 3f)
    val fullySettled by remember { derivedStateOf {
        !pager.isScrollInProgress && pager.settledPage == pager.currentPage && abs(pager.currentPageOffsetFraction) < .001f
    } }
    val scope = rememberCoroutineScope()
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val navigate: (Int) -> Unit = { page ->
        keyboard?.hide()
        navigationJob?.cancel()
        if (page != pager.currentPage || pager.isScrollInProgress || abs(pager.currentPageOffsetFraction) >= .001f) {
            navigationJob = scope.launch { pager.animateWithoutPrejump(page) }
        } else {
            navigationJob = null
        }
    }
    var status by remember { mutableStateOf(controller.runtimeStatus()) }
    var secureRequest by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(initialTab, navigationSerial) { if (navigationSerial > 0) navigate(initialTab) }
    LaunchedEffect(selected) { if (selected != 3) keyboard?.hide() }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler((selected != 0 || pager.isScrollInProgress) && !imeVisible) { navigate(0) }
    LaunchedEffect(activity, controller) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                controller.pollStatusAsync()
                status = controller.runtimeStatus()
                val matching = status.nativeMatching || status.recordingNativeMatching || status.policyMatching
                val waiting = !status.configurationSynced || !status.frameworkLoaded || !status.nativeKnown ||
                    !status.recordingNativeKnown || (status.policyRunning && !status.policyReady)
                delay(if (matching) 1000 else if (waiting) 2000 else 5000)
            }
        }
    }
    val secureActive = fullySettled && selected == 1 && secureRequest
    DisposableEffect(secureActive) {
        activity.secureTest(secureActive)
        onDispose { activity.secureTest(false) }
    }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val systemAccent = remember(configuration) {
        val id = context.resources.getIdentifier("system_accent1_500", "color", "android")
        if (id != 0) runCatching { Color(context.getColor(id)) }.getOrDefault(Color(0xFF3478F6)) else Color(0xFF3478F6)
    }
    val accent = if (appearance.followAccent) systemAccent else Color(appearance.accent)
    val mode = when (appearance.mode) {
        "light" -> if (appearance.monet) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
        "dark" -> if (appearance.monet) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
        else -> if (appearance.monet) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
    }
    val palette = runCatching { ThemePaletteStyle.valueOf(appearance.palette) }.getOrDefault(ThemePaletteStyle.TonalSpot)
    val colorSpec = runCatching { ThemeColorSpec.valueOf(appearance.colorSpec) }.getOrDefault(ThemeColorSpec.Spec2025)
    val theme = remember(mode, accent, palette, colorSpec) {
        ThemeController(
            colorSchemeMode = mode,
            keyColor = accent,
            paletteStyle = palette,
            colorSpec = colorSpec,
            lightColors = lightColorScheme(primary = accent, onPrimary = if (accent.luminance() > .5f) Color.Black else Color.White,
                primaryVariant = accent, primaryContainer = accent.copy(alpha = .12f)),
            darkColors = darkColorScheme(primary = accent, onPrimary = if (accent.luminance() > .5f) Color.Black else Color.White,
                primaryVariant = accent, primaryContainer = accent.copy(alpha = .24f)),
        )
    }
    val dark = when (appearance.mode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    SideEffect {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MiuixTheme(controller = theme) {
        val backdrop = if (isRuntimeShaderSupported()) rememberLayerBackdrop() else null
        val updateAppearance: (Appearance) -> Unit = { appearance = it; preferences.write(it) }
        Scaffold(
            containerColor = MiuixTheme.colorScheme.surface,
            bottomBar = { Box(Modifier.imePadding()) { AppNavigation(selected, pagePosition, navigate, appearance, backdrop, dark) } },
        ) { insets ->
            Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(MiuixTheme.colorScheme.surface)) {
                HorizontalPager(
                    state = pager,
                    beyondViewportPageCount = 1,
                    key = { it },
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                ) { tab ->
                    Box(Modifier.fillMaxSize().clipToBounds()) {
                        when (tab) {
                            1 -> SecurePage(insets, secureRequest, { secureRequest = it })
                            2 -> SkipPage(insets, status)
                            3 -> ThemePage(insets, appearance, updateAppearance, accent)
                            else -> HomePage(insets, status, controller) {
                                status = controller.runtimeStatus()
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun PagerState.animateWithoutPrejump(page: Int) {
    val destination = page.coerceIn(0, pageCount - 1)
    // animateScrollToPage pre-jumps for 0 <-> 3. Keep all four pages in one scroll mutation.
    scroll {
        updateTargetPage(destination)
        val distanceInPages = destination - currentPage - currentPageOffsetFraction
        val distance = distanceInPages * (layoutInfo.pageSize + layoutInfo.pageSpacing)
        var consumed = 0f
        animate(0f, distance, animationSpec = tween(
            durationMillis = (280 + abs(distanceInPages) * 80).roundToInt(),
            easing = FastOutSlowInEasing,
        )) { value, _ -> consumed += scrollBy(value - consumed) }
    }
}

@Composable
private fun AppNavigation(selected: Int, position: Float, select: (Int) -> Unit, appearance: Appearance, backdrop: LayerBackdrop?, dark: Boolean) {
    val labels = listOf("主页", "窗口测试", "图层测试", "主题")
    val descriptions = listOf("tab_home", "tab_secure", "tab_skip", "tab_theme")
    val icons = listOf(AppIcons.Home, AppIcons.Shield, AppIcons.Layers, AppIcons.Theme)
    val colors = NavigationBarDefaults.navigationBarItemColors(
        selectedContentColor = MiuixTheme.colorScheme.primary,
        unselectedContentColor = MiuixTheme.colorScheme.onSurface,
    )
    val glass = appearance.liquidGlass && backdrop != null
    val lowerSaturation = 1f + .35f * appearance.lowerGlassColorStrength
    if (appearance.floatingBar) {
        FloatingNavigationBar(
            color = if (glass) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
            cornerRadius = 30.dp,
            shadowElevation = 1.dp,
        ) {
            if (glass) {
                Box(Modifier.width(268.dp)) {
                    // The official bar adds 12dp on each side. A wider background is centered
                    // within this content slot, preserving its original 292dp outside bounds.
                    Box(Modifier.matchParentSize().requiredWidth(292.dp)
                        .textureBlur(
                            backdrop = backdrop,
                            shape = RoundedCornerShape(30.dp),
                            blurRadius = 22f,
                            colors = BlurColors(
                                blendColors = listOf(BlendColorEntry(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = lowerGlassSurfaceAlpha(.56f, appearance.lowerGlassTransparency)), BlurBlendMode.SrcOver)),
                                saturation = lowerSaturation,
                            ),
                            highlight = if (dark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight,
                        ))
                    // Resolve against the complete row height, including font-scaled labels.
                    BoxWithConstraints(Modifier.matchParentSize()) {
                        val density = LocalDensity.current
                        val insetPx = with(density) { 5.dp.roundToPx() }
                        val selectionHeight = with(density) { (constraints.maxHeight - insetPx * 2).coerceAtLeast(0).toDp() }
                        GlassSelectionPill(position, 75.dp, 70.dp, (-8.5).dp,
                            selectionHeight, 30.dp, Color(appearance.upperGlassColor), appearance.upperGlassTransparency, top = with(density) { insetPx.toDp() })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        labels.forEachIndexed { index, label ->
                            FloatingTab(index, selected, label, descriptions[index], icons[index], colors, select)
                        }
                    }
                }
            } else {
            labels.forEachIndexed { index, label ->
                FloatingTab(index, selected, label, descriptions[index], icons[index], colors, select)
            }
            }
        }
    } else if (glass) {
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.matchParentSize()
                .textureBlur(
                    backdrop = backdrop,
                    shape = RoundedCornerShape(0.dp),
                    blurRadius = 22f,
                    colors = BlurColors(blendColors = listOf(BlendColorEntry(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = lowerGlassSurfaceAlpha(.62f, appearance.lowerGlassTransparency)), BlurBlendMode.SrcOver)), saturation = lowerSaturation),
                    highlight = if (dark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight,
                ))
            // The native NavigationBar adds the bottom inset; the pill covers only its 64dp item row.
            BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp)) {
                val step = maxWidth / 4
                GlassSelectionPill(position, (step - 12.dp).coerceAtLeast(1.dp), step, 6.dp, 56.dp, 22.dp, Color(appearance.upperGlassColor), appearance.upperGlassTransparency)
            }
            NavigationBar(color = Color.Transparent, showDivider = false) {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(selected == index, { select(index) }, icons[index], label,
                        modifier = Modifier.semantics { contentDescription = descriptions[index] }, colors = colors)
                }
            }
        }
    } else {
        NavigationBar(showDivider = false) {
            labels.forEachIndexed { index, label ->
                NavigationBarItem(selected == index, { select(index) }, icons[index], label,
                    modifier = Modifier.semantics { contentDescription = descriptions[index] }, colors = colors)
            }
        }
    }
}

@Composable
private fun FloatingTab(index: Int, selected: Int, label: String, description: String, icon: ImageVector,
                        colors: NavigationBarItemColors, select: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(58.dp).padding(bottom = 8.dp)) {
        FloatingNavigationBarItem(selected == index, { select(index) }, icon, label,
            modifier = Modifier.semantics { contentDescription = description }, colors = colors)
        Text(label, fontSize = 10.sp, fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected == index) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun GlassSelectionPill(position: Float, width: Dp, step: Dp, start: Dp, height: Dp,
                               cornerRadius: Dp, color: Color, transparency: Float, top: Dp = 4.dp) {
    val shape = RoundedCornerShape(cornerRadius)
    val opacity = 1f - transparency.coerceIn(0f, 1f)
    // Relative offset follows the same start-to-end ordering as Row and HorizontalPager in RTL.
    // This node only draws; it owns no pointer input or clickable semantics.
    Box(Modifier.offset { IntOffset((start.toPx() + position.coerceIn(0f, 3f) * step.toPx()).roundToInt(), top.roundToPx()) }
        .size(width, height)
        .background(color.copy(alpha = opacity), shape))
}

@Composable
private fun PageList(insets: PaddingValues, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = insets.calculateTopPadding() + 26.dp, bottom = 154.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

@Composable
private fun PageHeading(eyebrow: String, title: String, subtitle: String, trailing: @Composable (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 2.dp)) {
        if (trailing == null) {
            Text(eyebrow, color = MiuixTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(eyebrow, color = MiuixTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                    modifier = Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                trailing()
            }
        }
        Text(title, fontSize = 31.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
        Caption(subtitle)
    }
}

@Composable
private fun HomePage(insets: PaddingValues, status: RuntimeStatus, controller: CompatController, settingsChanged: () -> Unit) {
    val context = LocalContext.current
    var showCaptureTools by remember { mutableStateOf(false) }
    if (showCaptureTools) CaptureToolPicker(controller.getCaptureToolPackages(),
        controller.getFlagSecureToolPackages(), controller.getSkipScreenshotToolPackages(),
        blackMode = status.captureToolMode == CompatController.TOOL_MODE_BLACK,
        dismiss = { showCaptureTools = false }, save = { packages, flagSecure, skipScreenshot ->
            controller.setCaptureToolMechanisms(packages, flagSecure, skipScreenshot)
            settingsChanged()
            showCaptureTools = false
            Toast.makeText(context, "工具名单已保存，请重新开始采集", Toast.LENGTH_SHORT).show()
        })
    PageList(insets) {
        item {
            PageHeading("SCREENSHOT FREEDOM", "自由截图A17", "DISABLE FLAG_SECURE+DISABLE SKIP_SCREENSHOT二合一彻底破除截图限制。") {
                Pill(BuildConfig.VERSION_NAME, MiuixTheme.colorScheme.primary)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(
                    Brush.linearGradient(listOf(MiuixTheme.colorScheme.primaryContainer, MiuixTheme.colorScheme.primary.copy(alpha = .06f))),
                ).padding(24.dp)) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val headlineSize = minOf(33f, maxWidth.value / (7.2f * LocalDensity.current.fontScale)).sp
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("清晰留存", modifier = Modifier.weight(1f), fontSize = headlineSize, lineHeight = headlineSize * 1.28f,
                                    fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
                                Icon(AppIcons.Capture, null, Modifier.size(48.dp), tint = MiuixTheme.colorScheme.primary.copy(alpha = .65f))
                            }
                            Text("每一个应用画面", fontSize = headlineSize, lineHeight = headlineSize * 1.28f,
                                fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
                            Spacer(Modifier.height(6.dp))
                            Caption("系统截屏与录屏兼容\n窗口保护与图层排除，分别掌控。")
                        }
                    }
                }
            }
        }
        item {
            Card(cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val matching = status.nativeMatching || status.recordingNativeMatching || (status.arbitrationEnabled && status.policyMatching)
                    val policyPaused = status.arbitrationEnabled && status.policyPaused
                    val policyMissing = status.arbitrationEnabled && !status.policyRunning
                    val paused = (!status.secure && !status.skip) || (status.arbitrationEnabled && status.policyReady && policyPaused)
                    val windowsReady = (!status.secure && !status.recordingSecure) || status.frameworkLoaded
                    val expectedSkip = status.skip && !policyPaused
                    val screenshotReady = status.nativeKnown && status.nativeOperationOk && status.native == expectedSkip
                    val recordingReady = status.recordingNativeKnown && status.recordingNativeOperationOk && status.recordingNative == expectedSkip &&
                        (!status.recordingSkip || status.recordingNativeSupported)
                    val policyReady = !status.arbitrationEnabled || (status.policyKnown && status.policyReady)
                    val ready = status.configurationSynced && windowsReady && screenshotReady && recordingReady && policyReady
                    Box(Modifier.size(9.dp).background(if (matching) Color(0xFFEDA345) else if (policyMissing || (ready && paused)) Color(0xFF8590A0) else if (ready) Color(0xFF26B889) else Color(0xFFEDA345), CircleShape))
                    Text(when {
                        matching -> "正在匹配兼容"
                        policyMissing -> "采集仲裁未运行"
                        ready && policyPaused -> "图层兼容按排除规则暂停"
                        ready && paused -> "截屏与录屏兼容已暂停"
                        ready -> "当前兼容设置已就绪"
                        status.nativeCapabilityGap && status.frameworkLoaded -> "窗口已加载 · 图层待适配"
                        else -> "运行状态待确认"
                    }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                Caption(if (!status.arbitrationEnabled && status.message == CompatController.ARBITRATION_NOT_RUNNING)
                    "当前设置已同步，录屏变更需停止并重新开始录屏" else status.message)
                Spacer(Modifier.height(16.dp))
                RuntimeLine("框架", status.frameworkDetails)
                Spacer(Modifier.height(12.dp))
                RuntimeLine("截图子层", status.nativeDetails)
                Spacer(Modifier.height(12.dp))
                RuntimeLine("录屏子层", status.recordingNativeDetails)
                Spacer(Modifier.height(12.dp))
                RuntimeLine("采集仲裁", if (!status.arbitrationEnabled) ARBITRATION_DISABLED_LABEL else status.policyDetails)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { controller.retryModuleUpgradeAsync(); controller.refreshAsync() }, modifier = Modifier.weight(1f), insideMargin = PaddingValues(horizontal = 10.dp, vertical = 12.dp)) { Text("刷新状态", fontSize = 13.sp) }
                    Button(onClick = { controller.installNativeAsync() }, modifier = Modifier.weight(1.45f), insideMargin = PaddingValues(horizontal = 10.dp, vertical = 12.dp)) { Text("安装/更新系统模块", fontSize = 13.sp) }
                }
            }
        }
        item {
            SectionLabel("兼容总开关")
            Spacer(Modifier.height(10.dp))
            Card(cornerRadius = 24.dp) {
                ToggleRow("解除窗口限制", "统一控制直接截图和投影采集的窗口兼容", status.secure, {
                    controller.setSecureEnabled(it); settingsChanged()
                    Toast.makeText(context, "设置已更新，请重新开始录屏或投影采集", Toast.LENGTH_SHORT).show()
                }, AppIcons.Shield, mechanism = "DISABLE_FLAG_SECURE")
                Rule()
                ToggleRow("阻止图层排除", "统一控制截图与录屏的子图层采集", status.skip, {
                    controller.setSkipEnabled(it); settingsChanged()
                }, AppIcons.Layers, mechanism = "DISABLE_SKIP_SCREENSHOT")
                Caption("开关表示启用设置，运行情况以上方实际状态为准。", Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp))
            }
        }
        item {
            Card(cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
                Text("采集工具名单", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Caption("选择负责采集的工具应用；不需要勾选被采集的应用。两种模式的名单与机制设置独立保存。")
                if (status.captureToolMigrationNotice.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Caption(status.captureToolMigrationNotice)
                    TextButton("已了解迁移说明", onClick = {
                        controller.acknowledgeCaptureToolMigration()
                        settingsChanged()
                    })
                }
                Spacer(Modifier.height(14.dp))
                ToggleRow("黑白名单仲裁", "开启时使用下方名单；关闭或组件未运行时允许所有采集工具。", status.arbitrationEnabled, {
                    controller.setArbitrationEnabled(it)
                    settingsChanged()
                    Toast.makeText(context, "仲裁设置已更新，请重新开始采集", Toast.LENGTH_SHORT).show()
                }, contentPadding = PaddingValues(vertical = 12.dp))
                Box(Modifier.fillMaxWidth().height(.5.dp).background(MiuixTheme.colorScheme.dividerLine))
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(CompatController.TOOL_MODE_BLACK to "黑名单", CompatController.TOOL_MODE_WHITE to "白名单").forEach { (mode, label) ->
                        OptionChip(label, status.captureToolMode == mode, Modifier.weight(1f), status.captureToolsInitialized) {
                            if (mode != status.captureToolMode) {
                                controller.setCaptureToolMode(mode)
                                settingsChanged()
                                Toast.makeText(context, "模式已切换，请重新开始采集", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Caption(if (status.captureToolMode == CompatController.TOOL_MODE_BLACK)
                    "默认兼容所有系统采集通道，勾选的工具才排除对应适配。"
                    else "只兼容已勾选并开启对应机制的工具，首次已选中识别到的系统工具。")
                Spacer(Modifier.height(12.dp))
                Button(onClick = { showCaptureTools = true }, enabled = status.captureToolsInitialized, modifier = Modifier.fillMaxWidth()
                    .semantics { contentDescription = "capture_tool_list" }) {
                    Text(if (status.captureToolsInitialized)
                        "${if (status.captureToolMode == CompatController.TOOL_MODE_BLACK) "排除工具" else "选择工具"} · 已选 ${status.captureToolCount} 个"
                        else "正在识别系统采集工具…")
                }
                Spacer(Modifier.height(12.dp))
                Caption("每个工具可分别设置 FLAG_SECURE 与 SKIP_SCREENSHOT，两项均用于截屏和录屏，名单沿用系统采集授权。仲裁开启且组件运行时，SKIP_SCREENSHOT 被排除的采集会暂停图层兼容；并发时排除优先。")
            }
        }
        item {
            Card(cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
                Text("作用于系统截屏与录屏链路", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Caption("在 LSPosed 中启用模块并选择推荐系统作用域。目标应用保持原有作用域，无需单独勾选。系统模块配置后按框架提示重载或重启。")
                Spacer(Modifier.height(12.dp))
                TextButton("复制诊断信息", onClick = {
                    val value = "自由截图A17 ${BuildConfig.VERSION_NAME}\nHawyn; Codex\n${status.message}\n" +
                        "截图：FLAG_SECURE=${status.secure} SKIP_SCREENSHOT=${status.skip}\n" +
                        "录屏：FLAG_SECURE=${status.recordingSecure} SKIP_SCREENSHOT=${status.recordingSkip}\n" +
                        "采集工具=${controller.getCaptureToolPackages().sorted().joinToString(",")}\n" +
                        "名单模式=${status.captureToolMode}\n" +
                        "黑白名单仲裁=${status.arbitrationEnabled} running=${status.policyRunning}\n" +
                        "FLAG_SECURE工具=${controller.getFlagSecureToolPackages().sorted().joinToString(",")}\n" +
                        "SKIP_SCREENSHOT工具=${controller.getSkipScreenshotToolPackages().sorted().joinToString(",")}\n" +
                        "配置同步=${status.configurationSynced}\n${status.frameworkDetails}\n截图子层：${status.nativeDetails}\n" +
                        "录屏子层：${status.recordingNativeDetails}\n录屏pipeline=${status.recordingPipeline}\n" +
                        "截图active=${status.native} known=${status.nativeKnown} ok=${status.nativeOperationOk}\n" +
                        "录屏active=${status.recordingNative} known=${status.recordingNativeKnown} supported=${status.recordingNativeSupported} ok=${status.recordingNativeOperationOk}" +
                        "\n采集仲裁：${status.policyDetails}\nready=${status.policyReady} paused=${status.policyPaused} blockers=${status.policyBlockers} known=${status.policyKnown}"
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("截图兼容诊断", value))
                    Toast.makeText(context, "诊断信息已复制", Toast.LENGTH_SHORT).show()
                })
            }
        }
        item { Footer() }
    }
}

@Composable
private fun SecurePage(insets: PaddingValues, requested: Boolean, setRequested: (Boolean) -> Unit) {
    var seed by rememberSaveable { mutableIntStateOf(1) }
    PageList(insets) {
        item { PageHeading("WINDOW CAPTURE", "窗口保护测试", "这一页主动设置真实 FLAG_SECURE。") }
        item {
            Card(cornerRadius = 24.dp) {
                ToggleRow("为此页设置窗口保护", if (requested) "设置 FLAG_SECURE，离开时清除" else "窗口保护已关闭，当前为对照状态",
                    requested, setRequested, AppIcons.Shield)
            }
        }
        item { ColorPattern("FLAG", seed) }
        item {
            Card(cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
                Text("检查保存后的截图", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Caption("使用系统组合键或截屏入口保存此页。保存的图中应完整出现四色、FLAG 字样与图案编号。结果以实际保存图片为准。")
                Spacer(Modifier.height(18.dp))
                Button(onClick = { seed++ }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("生成新图案") }
            }
        }
        item { Caption("此测试只设置窗口保护，不设置 SKIP_SCREENSHOT。两个测试互相独立。") }
    }
}

@Composable
private fun SkipPage(insets: PaddingValues, status: RuntimeStatus) {
    var requestSkip by rememberSaveable { mutableStateOf(true) }
    var seed by rememberSaveable { mutableIntStateOf(1) }
    var retryEpoch by remember { mutableIntStateOf(0) }
    val session = remember(retryEpoch) { SurfaceTestSession() }
    val currentSession = rememberUpdatedState(session)
    var result by remember(session) { mutableStateOf<SurfaceTestStatus?>(null) }
    var frameState by remember(session) { mutableStateOf(SkipTestSurface.FrameState(
        SkipTestSurface.FramePhase.WAITING_SURFACE, "正在等待当前真实测试图层创建",
    )) }
    DisposableEffect(session) { onDispose { session.release() } }
    val messageHeight = with(LocalDensity.current) { 63.sp.toDp() }
    val currentResult = result
    val failure = currentResult?.takeIf { !it.submitted }?.message
        ?: frameState.takeIf { it.phase == SkipTestSurface.FramePhase.FAILED }?.message
    val ready = currentResult?.submitted == true && frameState.phase == SkipTestSurface.FramePhase.READY && failure == null
    val displayMessage = when {
        failure != null -> failure
        ready -> "${currentResult?.message}\n当前真实绘制已同步；请核验保存的截图"
        else -> frameState.message
    }
    Column(
        modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(start = 22.dp, end = 22.dp, top = insets.calculateTopPadding() + 26.dp, bottom = 154.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        PageHeading("LAYER CAPTURE", "图层排除测试", "实际内容放在独立 SurfaceControlViewHost 子树。")
        Card(cornerRadius = 24.dp) {
            ToggleRow("请求排除此测试图层", "调用真实 SKIP_SCREENSHOT 事务，不设置窗口保护", requestSkip, { requestSkip = it }, AppIcons.Layers)
        }
        Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(24.dp))) {
            // Keep this real SurfaceView throughout pager/vertical movement. It follows the
            // AndroidView/View rendering position; no ordinary preview can mask its exclusion.
            key(session) {
                AndroidView(
                    factory = { context ->
                        lateinit var surface: SkipTestSurface
                        surface = SkipTestSurface(context) { value ->
                            if (currentSession.value === session && session.surface === surface) {
                                result = value
                                if (!value.submitted) {
                                    frameState = SkipTestSurface.FrameState(SkipTestSurface.FramePhase.FAILED, value.message)
                                    session.frameObservation?.cancel()
                                }
                                android.util.Log.d("SkipTestFrame", "surface=${System.identityHashCode(surface)} requestSubmitted=${value.submitted} layer=${value.layerId}")
                            }
                        }
                        session.surface = surface
                        SkipTestSurface.configureTestView(surface)
                        session.frameObservation = SkipTestSurface.observeFrameReadiness(surface) { state ->
                            if (currentSession.value === session && session.surface === surface) {
                                if (state.clearSubmission) result = null
                                frameState = state
                            }
                        }
                        surface
                    },
                    modifier = Modifier.fillMaxSize(),
                    onReset = null,
                    onRelease = { surface ->
                        if (session.surface === surface) {
                            session.release()
                            if (currentSession.value === session) {
                                result = null
                                frameState = SkipTestSurface.FrameState(SkipTestSurface.FramePhase.WAITING_SURFACE, "当前真实图层已释放")
                            }
                        }
                        surface.dispose()
                    },
                    update = { surface ->
                        val changed = session.appliedSkip != null && (session.appliedSkip != requestSkip || session.appliedSeed != seed)
                        surface.update(requestSkip, seed)
                        session.appliedSkip = requestSkip
                        session.appliedSeed = seed
                        if (changed) session.frameObservation?.requestRedraw()
                    },
                )
            }
        }
        Card(cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (failure != null) Color(0xFFD96565) else if (ready) Color(0xFF26B889) else Color(0xFFEDA345), CircleShape))
                Text("当前测试图层", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Text(displayMessage, modifier = Modifier.fillMaxWidth().height(messageHeight), fontSize = 13.sp, lineHeight = 21.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(currentResult?.layerId?.let { "当前测试图层 #$it" } ?: "当前测试图层 · 等待创建", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(14.dp))
            Caption(if (status.native && status.skip) "阻止排除已启用。请用系统截屏确认四色 SKIP 图案是否完整保留。" else "先保留排除请求并截屏作为对照，再开启主页的图层兼容并重复测试。")
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { seed++ }, modifier = Modifier.weight(1.4f), colors = ButtonDefaults.buttonColorsPrimary(), insideMargin = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) { Text("生成新图案", fontSize = 13.sp) }
                Button(onClick = { retryEpoch++ }, modifier = Modifier.weight(1f), insideMargin = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) { Text("重建图层", fontSize = 13.sp) }
            }
        }
        Caption("左右切页和上下滚动均保留真实测试图层，不叠加普通图案预览。绿色表示本次请求与真实绘制已同步；实际效果以保存的截图为准。")
    }
}

@Composable
private fun ThemePage(insets: PaddingValues, value: Appearance, update: (Appearance) -> Unit, accent: Color) {
    var colorInput by remember(value.accent) { mutableStateOf("%06X".format(value.accent and 0xFFFFFF)) }
    var colorError by remember { mutableStateOf(false) }
    val supportsGlass = isRuntimeShaderSupported()
    val styles = listOf(
        "TonalSpot" to "柔和", "Neutral" to "中性", "Vibrant" to "鲜明", "Expressive" to "表现力",
        "Rainbow" to "彩虹", "FruitSalad" to "缤纷", "Monochrome" to "单色", "Fidelity" to "保真", "Content" to "内容",
    )
    PageList(insets) {
        item { PageHeading("MAKE IT YOURS", "主题与外观", "每一处调整，都会即时应用并保存。") }
        item {
            Card(cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
                Text("外观模式", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key, label) ->
                        OptionChip(label, value.mode == key, Modifier.weight(1f)) { update(value.copy(mode = key)) }
                    }
                }
                Spacer(Modifier.height(20.dp))
                PalettePreview(accent)
            }
        }
        item {
            SectionLabel("导航与质感")
            Spacer(Modifier.height(10.dp))
            Card(cornerRadius = 24.dp) {
                ToggleRow("悬浮底栏", "四个页面始终可从底栏切换", value.floatingBar, { update(value.copy(floatingBar = it)) }, AppIcons.Home)
                Rule()
                ToggleRow("液态玻璃", when {
                    !supportsGlass -> "当前设备未提供所需的实时渲染能力"
                    else -> "两种底栏均使用实时背景模糊与滑动纯色选中层"
                }, value.liquidGlass, { update(value.copy(liquidGlass = it)) }, AppIcons.Theme, supportsGlass)
                Rule()
                GlassAppearanceSlider("下层玻璃透明度", value.lowerGlassTransparency, 0f..1f,
                    "越高越通透，50%为默认。仅液态玻璃启用时生效。", value.liquidGlass && supportsGlass) {
                    update(value.copy(lowerGlassTransparency = it))
                }
                Rule()
                GlassAppearanceSlider("下层色彩强度", value.lowerGlassColorStrength, 0f..2f,
                    "越高越鲜明，100%为默认。跟随当前主题色彩。", value.liquidGlass && supportsGlass) {
                    update(value.copy(lowerGlassColorStrength = it))
                }
                Rule()
                GlassAppearanceSlider("上层透明度", value.upperGlassTransparency, 0f..1f,
                    "默认93%；越高越透明，100%仅显示下层。", value.liquidGlass && supportsGlass) {
                    update(value.copy(upperGlassTransparency = it))
                }
                Rule()
                UpperLayerColorPicker(value.upperGlassColor, value.liquidGlass && supportsGlass) {
                    update(value.copy(upperGlassColor = it))
                }
            }
        }
        item {
            SectionLabel("颜色")
            Spacer(Modifier.height(10.dp))
            Card(cornerRadius = 24.dp) {
                ToggleRow("Monet 动态配色", "按强调色生成完整界面调色板", value.monet, { update(value.copy(monet = it)) }, AppIcons.Theme)
                Rule()
                ToggleRow("强调色跟随系统", "从系统强调色取色，也可改用自选颜色", value.followAccent, { update(value.copy(followAccent = it)) })
                AnimatedVisibility(!value.followAccent) {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            listOf(0xFF3478F6, 0xFF02A88B, 0xFF885ADD, 0xFFF17843, 0xFFD34A81, 0xFF596B7C).forEach { argb ->
                                val color = Color(argb)
                                Box(Modifier.size(34.dp).clip(CircleShape).background(color)
                                    .border(if (value.accent == argb) 3.dp else 0.dp, MiuixTheme.colorScheme.onSurface, CircleShape)
                                    .clickable { update(value.copy(accent = argb)) })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BasicTextField(
                                value = colorInput,
                                onValueChange = { colorInput = it.removePrefix("#").take(6).uppercase(); colorError = false },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 16.sp, fontFamily = FontFamily.Monospace, color = MiuixTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MiuixTheme.colorScheme.surface).padding(14.dp),
                                decorationBox = { field -> Row { Text("#", fontSize = 16.sp); field() } },
                            )
                            Button(onClick = {
                                val parsed = colorInput.takeIf { it.matches(Regex("[0-9A-Fa-f]{6}")) }?.toLongOrNull(16)
                                if (parsed == null) colorError = true else update(value.copy(accent = 0xFF000000 or parsed))
                            }) { Text("应用") }
                        }
                        if (colorError) Text("请输入 6 位十六进制颜色", color = MiuixTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Card(cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
                Text("Monet 色彩风格", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Caption(if (value.monet) "跟随系统或自选强调色均使用所选风格。" else "开启 Monet 后应用以下色彩风格与标准。")
                Spacer(Modifier.height(16.dp))
                styles.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        row.forEach { (key, title) -> OptionChip(title, value.palette == key, Modifier.weight(1f), value.monet) { update(value.copy(palette = key)) } }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("色彩标准", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionChip("2021", value.colorSpec == "Spec2021", Modifier.weight(1f), value.monet) { update(value.copy(colorSpec = "Spec2021")) }
                    OptionChip("2025", value.colorSpec == "Spec2025", Modifier.weight(1f), value.monet) { update(value.copy(colorSpec = "Spec2025")) }
                }
                Spacer(Modifier.height(12.dp))
                Caption("2025 标准适用于柔和、中性、鲜明与表现力风格；其他风格使用 2021 标准。")
            }
        }
        item { Footer() }
    }
}

@Composable
private fun UpperLayerColorPicker(color: Long, enabled: Boolean, choose: (Long) -> Unit) {
    var input by remember(color) { mutableStateOf("%06X".format(color and 0xFFFFFF)) }
    var error by remember { mutableStateOf(false) }
    val swatches = listOf(
        "黑色" to 0xFF000000, "白色" to 0xFFFFFFFF, "蓝色" to 0xFF3478F6, "绿色" to 0xFF02A88B,
        "紫色" to 0xFF885ADD, "橙色" to 0xFFF17843, "红色" to 0xFFD34A81, "灰色" to 0xFF596B7C,
    )
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("上层颜色", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Caption("独立纯色，默认黑色；与 Monet 和强调色分别保存。")
        swatches.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { (name, argb) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val selected = color == argb
                        Box(Modifier.size(36.dp).clip(CircleShape).background(Color(argb))
                            .border(if (selected) 3.dp else 1.dp,
                                if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.dividerLine, CircleShape)
                            .clickable(enabled = enabled) { error = false; choose(argb) }
                            .semantics { contentDescription = "上层颜色$name" })
                        Text(name, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicTextField(
                value = input,
                onValueChange = { input = it.removePrefix("#").take(6).uppercase(); error = false },
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, fontFamily = FontFamily.Monospace, color = MiuixTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MiuixTheme.colorScheme.surface).padding(14.dp)
                    .semantics { contentDescription = "上层颜色HEX" },
                decorationBox = { field -> Row { Text("#", fontSize = 16.sp); field() } },
            )
            Button(enabled = enabled, onClick = {
                val parsed = input.takeIf { it.matches(Regex("[0-9A-Fa-f]{6}")) }?.toLongOrNull(16)
                if (parsed == null) error = true else { error = false; choose(0xFF000000 or parsed) }
            }) { Text("应用") }
        }
        if (error) Text("请输入 6 位十六进制颜色", color = MiuixTheme.colorScheme.error, fontSize = 12.sp)
    }
}

@Composable
private fun GlassAppearanceSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>,
                                  description: String, enabled: Boolean, change: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("${(value * 100f).roundToInt()}%", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(6.dp))
        Caption(description)
        Spacer(Modifier.height(12.dp))
        Slider(value = value, onValueChange = change, valueRange = range, enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = title })
    }
}

@Composable
private fun Caption(value: String, modifier: Modifier = Modifier) {
    Text(value, modifier = modifier, fontSize = 13.sp, lineHeight = 21.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun SectionLabel(value: String) {
    Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(start = 6.dp))
}

@Composable
private fun Rule() { Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(.5.dp).background(MiuixTheme.colorScheme.dividerLine)) }

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, toggle: (Boolean) -> Unit, icon: ImageVector? = null, enabled: Boolean = true,
                      mechanism: String? = null, contentPadding: PaddingValues = PaddingValues(20.dp)) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { toggle(!checked) }.padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (icon != null) Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (mechanism != null) BoxWithConstraints(Modifier.fillMaxWidth()) {
                val mechanismSize = minOf(13f, maxWidth.value / (mechanism.length * .63f * LocalDensity.current.fontScale)).sp
                Text(mechanism, fontSize = mechanismSize, maxLines = 1, softWrap = false,
                    fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.primary)
            }
            Caption(description)
        }
        Switch(checked = checked, onCheckedChange = toggle, enabled = enabled)
    }
}

@Composable
private fun OptionChip(title: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, choose: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(13.dp)).background(if (selected) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surface)
        .clickable(enabled = enabled, onClick = choose).padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(title, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center)
    }
}

@Composable
private fun RuntimeLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Caption(value)
    }
}

@Composable
private fun Pill(value: String, color: Color) {
    Text(value, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(color.copy(alpha = .1f)).padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
private fun ColorPattern(word: String, seed: Int, modifier: Modifier = Modifier, preview: Boolean = false) {
    val colors = listOf(Color(0xFF397CF6), Color(0xFF26BA96), Color(0xFFFFAB46), Color(0xFF9D73F3))
    Column(modifier.clip(RoundedCornerShape(26.dp)).background(Color(0xFF15243C)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (preview) "滑动预览  /  图案 $seed" else "真实窗口保护  /  图案 $seed", color = Color.White, fontSize = 12.sp)
        word.toList().chunked(2).forEachIndexed { row, letters ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                letters.forEachIndexed { column, letter ->
                    Box(Modifier.weight(1f).height(110.dp).clip(RoundedCornerShape(16.dp)).background(colors[(row * 2 + column + seed - 1) % 4]), contentAlignment = Alignment.Center) {
                        Text(letter.toString(), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Text(if (preview) "页面停稳后创建真实 SKIP_SCREENSHOT 图层" else "四色、字母与编号应完整保存在截图中", color = Color(0xFFAFC3E1), fontSize = 11.sp)
    }
}

@Composable
private fun PalettePreview(accent: Color) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.surface).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(accent), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Capture, null, Modifier.size(25.dp), tint = if (accent.luminance() > .5f) Color.Black else Color.White)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("你的配色，立即呈现", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Caption("#%06X".format(accent.toArgb() and 0xFFFFFF))
        }
        listOf(colors.primary, colors.secondary, colors.tertiaryContainer).forEach {
            Box(Modifier.padding(start = 5.dp).size(12.dp).background(it, CircleShape))
        }
    }
}

@Composable
private fun Footer() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val githubUrl = "https://github.com/hawyn2018/screenshot_freedom_A17"
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("自由截图A17  ${BuildConfig.VERSION_NAME}\n作者：Hawyn; Codex", fontSize = 11.sp, lineHeight = 19.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
        Text(githubUrl, fontSize = 11.sp, lineHeight = 19.sp, color = MiuixTheme.colorScheme.primary,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().clickable {
                runCatching { uriHandler.openUri(githubUrl) }.onFailure {
                    Toast.makeText(context, "未找到可打开链接的应用", Toast.LENGTH_SHORT).show()
                }
            }.padding(vertical = 4.dp))
    }
}

private object AppIcons {
    private fun vector(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
                strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round, pathBuilder = draw)
        }.build()
    val Home = vector("Home") {
        moveTo(3f, 10.5f); lineTo(12f, 3f); lineTo(21f, 10.5f)
        moveTo(5f, 9f); lineTo(5f, 20f); lineTo(10f, 20f); lineTo(10f, 14f); lineTo(14f, 14f); lineTo(14f, 20f); lineTo(19f, 20f); lineTo(19f, 9f)
    }
    val Shield = vector("Shield") {
        moveTo(12f, 3f); lineTo(20f, 6f); lineTo(20f, 12f); curveTo(20f, 17f, 16f, 20f, 12f, 22f); curveTo(8f, 20f, 4f, 17f, 4f, 12f); lineTo(4f, 6f); close()
        moveTo(8.5f, 12f); lineTo(11f, 14.5f); lineTo(16f, 9.5f)
    }
    val Layers = vector("Layers") {
        moveTo(3f, 8f); lineTo(12f, 3f); lineTo(21f, 8f); lineTo(12f, 13f); close()
        moveTo(3f, 13f); lineTo(12f, 18f); lineTo(21f, 13f)
        moveTo(3f, 18f); lineTo(12f, 23f); lineTo(21f, 18f)
    }
    val Theme = vector("Theme") {
        moveTo(12f, 4f); curveTo(6f, 4f, 3f, 8f, 3f, 12f); curveTo(3f, 17f, 7f, 21f, 12f, 21f)
        curveTo(16f, 21f, 12f, 16f, 16f, 15f); lineTo(18f, 15f); curveTo(23f, 15f, 23f, 4f, 12f, 4f); close()
        moveTo(7f, 10f); lineTo(7.2f, 10f); moveTo(11f, 7.5f); lineTo(11.2f, 7.5f); moveTo(16f, 9f); lineTo(16.2f, 9f)
    }
    val Capture = vector("Capture") {
        moveTo(9f, 3f); lineTo(4f, 3f); lineTo(4f, 8f); moveTo(15f, 3f); lineTo(20f, 3f); lineTo(20f, 8f)
        moveTo(4f, 16f); lineTo(4f, 21f); lineTo(9f, 21f); moveTo(20f, 16f); lineTo(20f, 21f); lineTo(15f, 21f)
        moveTo(7f, 15f); lineTo(10f, 11f); lineTo(13f, 14f); lineTo(16f, 10f); lineTo(18f, 15f)
    }
}
