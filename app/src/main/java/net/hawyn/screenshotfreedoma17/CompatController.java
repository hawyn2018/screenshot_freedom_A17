package net.hawyn.screenshotfreedoma17;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.HotReloadResult;

/** App-side configuration. No activity or target application is retained. */
public final class CompatController {
    public static final String PREF_GROUP = "screenshot_compat_v2";
    public static final String KEY_SECURE = "disable_flag_secure";
    public static final String KEY_SKIP = "disable_skip_screenshot";
    public static final String KEY_RECORD_SECURE = "disable_flag_secure_recording";
    public static final String KEY_RECORD_SKIP = "disable_skip_screenshot_recording";
    public static final String KEY_ARBITRATION_ENABLED = "arbitration_enabled";
    private static final boolean DEFAULT_ARBITRATION_ENABLED = false;
    public static final String KEY_CAPTURE_TOOLS = "capture_tool_packages";
    public static final String KEY_SCREENSHOT_TOOLS = "capture_tool_screenshot_packages";
    public static final String KEY_RECORDING_TOOLS = "capture_tool_recording_packages";
    public static final String KEY_FLAG_SECURE_TOOLS = "capture_tool_flag_secure_packages";
    public static final String KEY_SKIP_SCREENSHOT_TOOLS = "capture_tool_skip_screenshot_packages";
    public static final String KEY_TOOL_MODE = "capture_tool_mode";
    public static final String TOOL_MODE_WHITE = "WHITE";
    public static final String TOOL_MODE_BLACK = "BLACK";
    public static final String WHITE_TOOL_GROUP = "capture_whitelist_v3";
    public static final String BLACK_TOOL_GROUP = "capture_blacklist_v3";
    private static final String KEY_TOOL_DEFAULTS_READY = "capture_tool_defaults_v1";
    private static final String KEY_TOOL_MECHANISMS_READY = "capture_tool_mechanisms_v1";
    private static final String KEY_TOOL_MECHANISM_REVIEW = "capture_tool_mechanism_review_v1";
    private static final long REQUIRED_SYSTEM_VERSION = 26;
    private static final String MODULE_DIR = "/data/adb/modules/enable_screenshot17fix";
    private static final String TAG = "ScreenshotCompatUI";
    public static final String ARBITRATION_NOT_RUNNING = "系统采集仲裁未运行，将允许所有采集工具";
    public static final String COMPATIBILITY_MATCHING = "正在匹配兼容，请稍后";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ScreenshotCompat-config");
        t.setDaemon(true);
        return t;
    });
    // Local status files must remain readable while framework/package Binder
    // calls or an explicitly requested root installation occupy the config queue.
    private static final ExecutorService STATUS_WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ScreenshotCompat-status");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final AtomicBoolean REFRESH_PENDING = new AtomicBoolean();
    private static final AtomicBoolean FRAMEWORK_REFRESH_PENDING = new AtomicBoolean();
    private static final AtomicBoolean UPGRADE_RELOAD_REQUESTED = new AtomicBoolean();
    private static final AtomicInteger CONFIG_REVISION = new AtomicInteger();
    private static volatile XposedService framework;
    private static volatile Context context;
    private static volatile SharedPreferences local;
    private static volatile SharedPreferences whiteTools;
    private static volatile SharedPreferences blackTools;
    private static volatile String frameworkStatus = "等待 LSPosed 连接";
    private static volatile String nativeDetails = "等待系统组件状态";
    private static volatile String recordingNativeDetails = "等待录屏输出状态";
    private static volatile String recordingPipeline = "";
    private static volatile String policyDetails = "等待采集仲裁状态";
    private static volatile boolean policyRunning;
    private static volatile boolean policyMatching;
    private static volatile boolean policyKnown;
    private static volatile boolean policyReady;
    private static volatile boolean policyPaused;
    private static volatile int policyBlockers = -1;
    private static volatile String upgradeReloadDetails = "";
    private static volatile String operationStatus = "";
    private static volatile boolean nativeActive;
    private static volatile boolean nativeMatching;
    private static volatile boolean nativeKnown;
    private static volatile boolean nativeOperationOk;
    private static volatile boolean nativeCapabilityMissing;
    private static volatile boolean recordingNativeActive;
    private static volatile boolean recordingNativeMatching;
    private static volatile boolean recordingNativeKnown;
    private static volatile boolean recordingNativeOperationOk;
    private static volatile boolean recordingNativeSupported;
    private static volatile boolean recordingNativeCapabilityMissing;
    private static volatile boolean frameworkLoaded;
    private static volatile boolean configurationSynced;
    private static volatile String lastTargets = "";
    private static volatile long lastFrameworkStatusQuery = -15_000;
    private static final StatusFile NATIVE_STATUS_FILE = new StatusFile();
    private static final StatusFile RECORDING_STATUS_FILE = new StatusFile();
    private static final StatusFile POLICY_STATUS_FILE = new StatusFile();

    public CompatController(Context source) {
        synchronized (CompatController.class) {
            if (context == null) {
                context = source.getApplicationContext().createDeviceProtectedStorageContext();
                local = context.getSharedPreferences(PREF_GROUP, Context.MODE_PRIVATE);
                whiteTools = context.getSharedPreferences(WHITE_TOOL_GROUP, Context.MODE_PRIVATE);
                blackTools = context.getSharedPreferences(BLACK_TOOL_GROUP, Context.MODE_PRIVATE);
                // Seed only a missing preference. Existing initialized app data
                // retains the previous implicit ON default; a fresh install is OFF.
                if (!local.contains(KEY_ARBITRATION_ENABLED)) {
                    boolean existingSettings = !local.getAll().isEmpty()
                            || whiteTools.contains(KEY_TOOL_DEFAULTS_READY)
                            || blackTools.contains(KEY_TOOL_DEFAULTS_READY);
                    local.edit().putBoolean(KEY_ARBITRATION_ENABLED, existingSettings).apply();
                }
                WORKER.execute(() -> {
                    // Device-protected settings survive reboot. Publish them before
                    // first-run package discovery, which may wait for PackageManager.
                    try { writeArbitrationConfig(); writeNativeConfig(); writeRecordingNativeConfig(); } catch (Exception e) { report(e); }
                    requestStatusRefresh();
                    initializeCaptureTools();
                    // Preserve the existing screenshot values as the two master settings.
                    local.edit().putBoolean(KEY_RECORD_SECURE, local.getBoolean(KEY_SECURE, true))
                            .putBoolean(KEY_RECORD_SKIP, local.getBoolean(KEY_SKIP, true)).apply();
                });
            }
        }
        if (REGISTERED.compareAndSet(false, true)) {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override public void onServiceBind(XposedService service) {
                    framework = service;
                    WORKER.execute(() -> {
                        syncRemote();
                        updateFrameworkStatus();
                        reloadPreviousGeneration();
                    });
                }
                @Override public void onServiceDied(XposedService service) {
                    if (framework == service) {
                        framework = null;
                        frameworkLoaded = false;
                        configurationSynced = false;
                        frameworkStatus = "框架连接已断开";
                    }
                }
            });
        }
        refreshAsync();
    }

    public boolean getSecureEnabled() { return local.getBoolean(KEY_SECURE, true); }
    public boolean getSkipEnabled() { return local.getBoolean(KEY_SKIP, true); }
    public boolean getArbitrationEnabled() { return local.getBoolean(KEY_ARBITRATION_ENABLED, DEFAULT_ARBITRATION_ENABLED); }
    public boolean getRecordingSecureEnabled() { return getSecureEnabled(); }
    public boolean getRecordingSkipEnabled() { return getSkipEnabled(); }
    public Set<String> getCaptureToolPackages() {
        Set<String> saved = toolPreferences().getStringSet(KEY_CAPTURE_TOOLS, Collections.emptySet());
        return saved == null ? new HashSet<>() : new HashSet<>(saved);
    }
    public int getCaptureToolCount() {
        Set<String> saved = toolPreferences().getStringSet(KEY_CAPTURE_TOOLS, Collections.emptySet());
        return saved == null ? 0 : saved.size();
    }
    /** Historical purpose selections are retained as migration backups. */
    @Deprecated public Set<String> getScreenshotToolPackages() { return selectedToolSubset(KEY_SCREENSHOT_TOOLS); }
    @Deprecated public Set<String> getRecordingToolPackages() { return selectedToolSubset(KEY_RECORDING_TOOLS); }
    public Set<String> getFlagSecureToolPackages() {
        return mechanismSubset(toolPreferences(), KEY_FLAG_SECURE_TOOLS, TOOL_MODE_BLACK.equals(getCaptureToolMode()));
    }
    public Set<String> getSkipScreenshotToolPackages() {
        return mechanismSubset(toolPreferences(), KEY_SKIP_SCREENSHOT_TOOLS, TOOL_MODE_BLACK.equals(getCaptureToolMode()));
    }
    public boolean areCaptureToolsInitialized() { return whiteTools.getBoolean(KEY_TOOL_DEFAULTS_READY, false)
            && blackTools.getBoolean(KEY_TOOL_DEFAULTS_READY, false)
            && whiteTools.getBoolean(KEY_TOOL_MECHANISMS_READY, false)
            && blackTools.getBoolean(KEY_TOOL_MECHANISMS_READY, false); }
    public String getCaptureToolMigrationNotice() {
        boolean white = whiteTools.getBoolean(KEY_TOOL_MECHANISM_REVIEW, false);
        boolean black = blackTools.getBoolean(KEY_TOOL_MECHANISM_REVIEW, false);
        if (!white && !black) return "";
        return "名单现按 FLAG_SECURE 与 SKIP_SCREENSHOT 分别设置，两项均作用于截屏和录屏。旧配置中截屏、录屏不同的工具已保守迁移："
                + (black ? "黑名单两项均排除" : "") + (black && white ? "；" : "")
                + (white ? "白名单两项均暂未放行" : "") + "。请复核对应工具；原用途配置已保留。";
    }
    public void acknowledgeCaptureToolMigration() {
        whiteTools.edit().putBoolean(KEY_TOOL_MECHANISM_REVIEW, false).apply();
        blackTools.edit().putBoolean(KEY_TOOL_MECHANISM_REVIEW, false).apply();
    }
    public String getCaptureToolMode() { return TOOL_MODE_BLACK.equals(local.getString(KEY_TOOL_MODE, TOOL_MODE_BLACK))
            ? TOOL_MODE_BLACK : TOOL_MODE_WHITE; }

    private SharedPreferences toolPreferences() { return TOOL_MODE_BLACK.equals(getCaptureToolMode()) ? blackTools : whiteTools; }

    private Set<String> selectedToolSubset(String key) {
        Set<String> selected = getCaptureToolPackages();
        Set<String> subset = toolPreferences().getStringSet(key, selected);
        selected.retainAll(subset == null ? Collections.emptySet() : subset);
        return selected;
    }
    public boolean isFrameworkConnected() { return framework != null; }
    public boolean isFrameworkLoaded() { return frameworkLoaded; }
    public boolean isNativeActive() { return nativeActive; }
    public boolean isNativeMatching() { return nativeMatching; }
    public boolean isNativeKnown() { return nativeKnown; }
    public boolean isNativeOperationOk() { return nativeOperationOk; }
    public boolean isConfigurationSynced() { return configurationSynced; }
    public boolean isRecordingNativeActive() { return recordingNativeActive; }
    public boolean isRecordingNativeMatching() { return recordingNativeMatching; }
    public boolean isRecordingNativeKnown() { return recordingNativeKnown; }
    public boolean isRecordingNativeSupported() { return recordingNativeSupported; }
    public boolean isRecordingNativeOperationOk() { return recordingNativeOperationOk; }
    public boolean hasNativeCapabilityGap() { return nativeCapabilityMissing || recordingNativeCapabilityMissing; }
    public String getFrameworkStatus() { return frameworkStatus; }
    public String getNativeDetails() { return nativeDetails; }
    public String getRecordingNativeDetails() { return recordingNativeDetails; }
    public String getRecordingPipeline() { return recordingPipeline; }
    public String getPolicyDetails() { return isPolicyMatching() ? COMPATIBILITY_MATCHING
            : isPolicyRunning() ? policyDetails : ARBITRATION_NOT_RUNNING; }
    public boolean isPolicyRunning() { return getArbitrationEnabled() && policyRunning; }
    public boolean isPolicyMatching() { return getArbitrationEnabled()
            && (policyMatching || nativeMatching || recordingNativeMatching); }
    public boolean isPolicyKnown() { return policyKnown; }
    public boolean isPolicyReady() { return isPolicyRunning() && policyReady; }
    public boolean isPolicyPaused() { return isPolicyRunning() && policyPaused; }
    public int getPolicyBlockers() { return isPolicyRunning() ? policyBlockers : -1; }
    public String getStatus() {
        if (!operationStatus.isEmpty()) return operationStatus;
        if (isPolicyMatching()) return COMPATIBILITY_MATCHING;
        if (!isPolicyRunning()) return ARBITRATION_NOT_RUNNING;
        if ((getSecureEnabled() || getRecordingSecureEnabled()) && !frameworkLoaded)
            return "窗口兼容等待系统模块加载";
        if (!configurationSynced) return "开关设置等待框架同步，请查看运行状态";
        if (!policyKnown) return "采集仲裁实际状态待确认，请查看运行状态";
        if (!policyReady) return "采集仲裁等待系统模块握手；请结束已有采集后刷新";
        if (policyPaused) return policyBlockers > 0
                ? "排除工具正在采集，图层兼容按排除优先规则暂停"
                : "采集仲裁保守暂停图层兼容，请查看实际状态";
        if (hasNativeCapabilityGap()) return getSkipEnabled()
                ? "窗口模块与图层兼容分别工作：部分原生图层接口尚未适配，与 APK 证书无关"
                : "原生图层接口尚未适配，当前未启用该管线；窗口设置单独由框架处理";
        if (!nativeKnown) return "截图子层实际状态待确认，请查看运行状态";
        if (getSkipEnabled() && !nativeActive) return "截图 SKIP_SCREENSHOT 等待系统组件生效";
        if (!getSkipEnabled() && nativeActive) return nativeOperationOk
                ? "正在恢复子层排除规则" : "关闭尚未完成，系统仍在包含被排除子层";
        if (!nativeOperationOk) return "截图系统组件操作未完成，请查看运行状态";
        if (!recordingNativeKnown)
            return "录屏子层实际状态待确认，请安装或更新系统组件";
        if (getRecordingSkipEnabled() && !recordingNativeSupported)
            return "当前录屏接口尚未匹配，请查看录屏子层状态";
        if (getRecordingSkipEnabled() && !recordingNativeActive)
            return "录屏 SKIP_SCREENSHOT 等待系统组件生效";
        if (!getRecordingSkipEnabled() && recordingNativeActive) return recordingNativeOperationOk
                ? "正在恢复录屏子层排除规则" : "录屏关闭尚未完成，请查看实际状态";
        if (!recordingNativeOperationOk)
            return "录屏系统组件操作未完成，请查看运行状态";
        return "当前设置已同步；录屏变更需停止并重新开始录屏";
    }

    public void setSecureEnabled(boolean enabled) {
        local.edit().putBoolean(KEY_SECURE, enabled).putBoolean(KEY_RECORD_SECURE, enabled).apply();
        markConfigurationChanged();
        WORKER.execute(CompatController::syncRemote);
    }

    public void setSkipEnabled(boolean enabled) {
        local.edit().putBoolean(KEY_SKIP, enabled).putBoolean(KEY_RECORD_SKIP, enabled).apply();
        markConfigurationChanged();
        WORKER.execute(() -> {
            try {
                writeNativeConfig();
                writeRecordingNativeConfig();
                operationStatus = "";
            } catch (Exception e) { report(e); }
            syncRemote();
            requestStatusRefresh();
        });
    }

    public void setArbitrationEnabled(boolean enabled) {
        local.edit().putBoolean(KEY_ARBITRATION_ENABLED, enabled).apply();
        markConfigurationChanged();
        WORKER.execute(() -> {
            try {
                writeArbitrationConfig();
                operationStatus = "";
            } catch (Exception error) { report(error); }
            syncRemote();
            requestStatusRefresh();
        });
    }

    public void setRecordingSecureEnabled(boolean enabled) {
        setSecureEnabled(enabled);
    }

    public void setRecordingSkipEnabled(boolean enabled) {
        setSkipEnabled(enabled);
    }

    public void setCaptureToolPackages(Set<String> packages) {
        Set<String> added = new HashSet<>(packages);
        added.removeAll(getCaptureToolPackages());
        Set<String> flagSecure = getFlagSecureToolPackages();
        Set<String> skipScreenshot = getSkipScreenshotToolPackages();
        flagSecure.addAll(added);
        skipScreenshot.addAll(added);
        setCaptureToolMechanisms(packages, flagSecure, skipScreenshot);
    }

    /** Legacy purpose-based callers receive the same conservative migration as an upgrade. */
    @Deprecated
    public void setCaptureTools(Set<String> packages, Set<String> screenshotPackages, Set<String> recordingPackages) {
        Set<String> merged = legacyMechanismPackages(packages, screenshotPackages, recordingPackages,
                TOOL_MODE_BLACK.equals(getCaptureToolMode()));
        toolPreferences().edit().putStringSet(KEY_SCREENSHOT_TOOLS, new HashSet<>(screenshotPackages))
                .putStringSet(KEY_RECORDING_TOOLS, new HashSet<>(recordingPackages)).apply();
        setCaptureToolMechanisms(packages, merged, merged);
    }

    public void setCaptureToolMechanisms(Set<String> packages, Set<String> flagSecurePackages, Set<String> skipScreenshotPackages) {
        Set<String> selected = new HashSet<>();
        for (String name : packages) {
            if (name != null && !name.isEmpty() && name.length() <= 255
                    && name.equals(name.trim())) selected.add(name);
        }
        Set<String> flagSecure = new HashSet<>(flagSecurePackages);
        Set<String> skipScreenshot = new HashSet<>(skipScreenshotPackages);
        flagSecure.retainAll(selected);
        skipScreenshot.retainAll(selected);
        toolPreferences().edit().putStringSet(KEY_CAPTURE_TOOLS, selected)
                .putStringSet(KEY_FLAG_SECURE_TOOLS, flagSecure)
                .putStringSet(KEY_SKIP_SCREENSHOT_TOOLS, skipScreenshot)
                .putBoolean(KEY_TOOL_DEFAULTS_READY, true)
                .putBoolean(KEY_TOOL_MECHANISMS_READY, true).apply();
        markConfigurationChanged();
        WORKER.execute(CompatController::syncRemote);
    }

    public void setCaptureToolMode(String mode) {
        local.edit().putString(KEY_TOOL_MODE, TOOL_MODE_BLACK.equals(mode) ? TOOL_MODE_BLACK : TOOL_MODE_WHITE).apply();
        markConfigurationChanged();
        WORKER.execute(CompatController::syncRemote);
    }

    private static void markConfigurationChanged() {
        CONFIG_REVISION.incrementAndGet();
        configurationSynced = false;
    }

    public void applyAsync() {
        WORKER.execute(() -> {
            try { writeNativeConfig(); writeRecordingNativeConfig(); writeArbitrationConfig(); } catch (Exception e) { report(e); }
            syncRemote();
            updateFrameworkStatus();
            requestStatusRefresh();
        });
    }

    public void refreshAsync() {
        requestStatusRefresh();
        requestFrameworkRefresh(true);
    }

    /** Foreground polling is coalesced; full framework enumeration has its own cadence. */
    public void pollStatusAsync() {
        requestStatusRefresh();
        requestFrameworkRefresh(false);
    }

    private static void requestStatusRefresh() {
        if (!REFRESH_PENDING.compareAndSet(false, true)) return;
        STATUS_WORKER.execute(() -> {
            try {
                readNativeStatus();
                readRecordingNativeStatus();
                readPolicyStatus();
            } finally { REFRESH_PENDING.set(false); }
        });
    }

    private static void requestFrameworkRefresh(boolean force) {
        long now = SystemClock.elapsedRealtime();
        long interval = frameworkLoaded && configurationSynced ? 15_000 : 5_000;
        if (!force && now - lastFrameworkStatusQuery < interval) return;
        if (!FRAMEWORK_REFRESH_PENDING.compareAndSet(false, true)) return;
        WORKER.execute(() -> {
            try {
                if (!configurationSynced) syncRemote();
                updateFrameworkStatus();
            } finally {
                lastFrameworkStatusQuery = SystemClock.elapsedRealtime();
                FRAMEWORK_REFRESH_PENDING.set(false);
            }
        });
    }

    private static void syncRemote() {
        XposedService service = framework;
        if (service == null) { configurationSynced = false; return; }
        try {
            int revision = CONFIG_REVISION.get();
            String mode = TOOL_MODE_BLACK.equals(local.getString(KEY_TOOL_MODE, TOOL_MODE_BLACK))
                    ? TOOL_MODE_BLACK : TOOL_MODE_WHITE;
            boolean whiteSynced = syncToolGroup(service, WHITE_TOOL_GROUP, whiteTools);
            boolean blackSynced = syncToolGroup(service, BLACK_TOOL_GROUP, blackTools);
            if (!whiteSynced || !blackSynced) {
                configurationSynced = false;
                frameworkStatus = "名单同步未完成，模式尚未切换";
                return;
            }
            boolean success = service.getRemotePreferences(PREF_GROUP).edit()
                    .putBoolean(KEY_SECURE, local.getBoolean(KEY_SECURE, true))
                    .putBoolean(KEY_SKIP, local.getBoolean(KEY_SKIP, true))
                    .putBoolean(KEY_RECORD_SECURE, local.getBoolean(KEY_SECURE, true))
                    .putBoolean(KEY_RECORD_SKIP, local.getBoolean(KEY_SKIP, true))
                    .putBoolean(KEY_ARBITRATION_ENABLED, local.getBoolean(KEY_ARBITRATION_ENABLED, DEFAULT_ARBITRATION_ENABLED))
                    .putString(KEY_TOOL_MODE, mode).commit();
            configurationSynced = success && revision == CONFIG_REVISION.get() && framework == service;
            if (!success) frameworkStatus = "配置同步未完成，请刷新状态";
        } catch (Throwable e) {
            configurationSynced = false;
            frameworkStatus = "配置同步失败：" + e.getClass().getSimpleName();
            Log.w(TAG, "Remote preference sync", e);
        }
    }

    private static boolean syncToolGroup(XposedService service, String group, SharedPreferences source) throws Exception {
        Set<String> selected = new HashSet<>(source.getStringSet(KEY_CAPTURE_TOOLS, Collections.emptySet()));
        Set<String> screenshot = new HashSet<>(source.getStringSet(KEY_SCREENSHOT_TOOLS, selected));
        Set<String> recording = new HashSet<>(source.getStringSet(KEY_RECORDING_TOOLS, selected));
        boolean black = BLACK_TOOL_GROUP.equals(group);
        Set<String> flagSecure = mechanismSubset(source, KEY_FLAG_SECURE_TOOLS, black);
        Set<String> skipScreenshot = mechanismSubset(source, KEY_SKIP_SCREENSHOT_TOOLS, black);
        screenshot.retainAll(selected);
        recording.retainAll(selected);
        return service.getRemotePreferences(group).edit().putStringSet(KEY_CAPTURE_TOOLS, selected)
                .putStringSet(KEY_SCREENSHOT_TOOLS, screenshot)
                .putStringSet(KEY_RECORDING_TOOLS, recording)
                .putStringSet(KEY_FLAG_SECURE_TOOLS, flagSecure)
                .putStringSet(KEY_SKIP_SCREENSHOT_TOOLS, skipScreenshot)
                .putBoolean(KEY_TOOL_MECHANISMS_READY, true).commit();
    }

    private static void updateFrameworkStatus() {
        XposedService service = framework;
        if (service == null) {
            frameworkLoaded = false;
            frameworkStatus = "等待 LSPosed 连接，请启用模块并选择系统作用域";
            return;
        }
        try {
            String name = service.getFrameworkName();
            frameworkLoaded = false;
            boolean previousVersion = false;
            StringBuilder targets = new StringBuilder();
            for (var target : service.getRunningTargets()) {
                targets.append(target.getProcessName()).append('/').append(target.getPid())
                        .append(" uid=").append(target.getUid())
                        .append(" version=").append(target.getLoadedVersionCode())
                        .append(" state=").append(target.getState()).append(';');
                String process = target.getProcessName();
                if (target.getUid() == 1000 && ("system".equals(process)
                        || "system_server".equals(process) || "android".equals(process))) {
                    frameworkLoaded = target.getLoadedVersionCode() >= REQUIRED_SYSTEM_VERSION
                            && target.getState() == HookedTarget.State.UP_TO_DATE;
                    previousVersion = !frameworkLoaded;
                }
            }
            String details = targets.toString();
            if (!details.equals(lastTargets)) {
                lastTargets = details;
                Log.i(TAG, "Framework targets: " + details);
            }
            frameworkStatus = name + (frameworkLoaded ? " · 系统模块已加载"
                    : previousVersion ? " · 系统仍运行旧版，请重新加载模块或重启"
                    : " · 系统模块待加载，请确认系统作用域并重启");
            if (!frameworkLoaded && !upgradeReloadDetails.isEmpty()) frameworkStatus += " · " + upgradeReloadDetails;
        } catch (Throwable e) {
            frameworkLoaded = false;
            frameworkStatus = "框架已连接 · 加载状态查询失败";
            Log.w(TAG, "Framework status", e);
        }
    }

    private static void reloadPreviousGeneration() {
        XposedService service = framework;
        if (service == null || !UPGRADE_RELOAD_REQUESTED.compareAndSet(false, true)) return;
        try {
            for (HookedTarget target : service.getRunningTargets()) {
                if (target.getLoadedVersionCode() >= REQUIRED_SYSTEM_VERSION
                        && target.getState() == HookedTarget.State.UP_TO_DATE) continue;
                // A code upgrade only. Ordinary preference changes use remote preferences.
                frameworkStatus = "正在加载系统模块";
                service.hotReloadModule(target, null, (resultTarget, result) -> {
                    WORKER.execute(() -> {
                        if (result.status() == HotReloadResult.Status.FAILED && result.message() == null) {
                            upgradeReloadDetails = "请先结束采集，再点刷新状态重新加载";
                        } else if (result.status() != HotReloadResult.Status.SUCCEEDED) {
                            upgradeReloadDetails = "模块更新待处理：" + result.status();
                        } else upgradeReloadDetails = "";
                        updateFrameworkStatus();
                    });
                });
            }
        } catch (Throwable e) {
            frameworkStatus = "系统模块更新待重启后加载";
            Log.w(TAG, "Upgrade reload", e);
        }
    }

    /** Explicit UI refresh can retry a code upgrade after active capture sessions end. */
    public void retryModuleUpgradeAsync() {
        WORKER.execute(() -> {
            if (!frameworkLoaded) {
                UPGRADE_RELOAD_REQUESTED.set(false);
                reloadPreviousGeneration();
            }
        });
    }

    private static void initializeCaptureTools() {
        try {
            if (!whiteTools.getBoolean(KEY_TOOL_DEFAULTS_READY, false)) {
                Set<String> selected = new HashSet<>(local.getStringSet(KEY_CAPTURE_TOOLS, Collections.emptySet()));
                Set<String> screenshot = new HashSet<>(local.getStringSet(KEY_SCREENSHOT_TOOLS, selected));
                Set<String> recording = new HashSet<>(local.getStringSet(KEY_RECORDING_TOOLS, selected));
                Set<String> defaults = local.getBoolean(KEY_TOOL_DEFAULTS_READY, false)
                        ? Collections.emptySet() : discoverSystemCaptureTools();
                Set<String> newDefaults = new HashSet<>(defaults);
                newDefaults.removeAll(selected);
                selected.addAll(defaults);
                screenshot.addAll(newDefaults);
                recording.addAll(newDefaults);
                boolean saved = whiteTools.edit().putStringSet(KEY_CAPTURE_TOOLS, selected)
                        .putStringSet(KEY_SCREENSHOT_TOOLS, screenshot)
                        .putStringSet(KEY_RECORDING_TOOLS, recording)
                        .putBoolean(KEY_TOOL_DEFAULTS_READY, true).commit();
                if (!saved) throw new IllegalStateException("系统采集工具默认设置尚未保存");
                Log.i(TAG, "Default system capture tools: " + defaults);
            }
            if (!blackTools.getBoolean(KEY_TOOL_DEFAULTS_READY, false)) {
                boolean saved = blackTools.edit().putStringSet(KEY_CAPTURE_TOOLS, Collections.emptySet())
                        .putStringSet(KEY_SCREENSHOT_TOOLS, Collections.emptySet())
                        .putStringSet(KEY_RECORDING_TOOLS, Collections.emptySet())
                        .putBoolean(KEY_TOOL_DEFAULTS_READY, true).commit();
                if (!saved) throw new IllegalStateException("黑名单默认设置尚未保存");
            }
            migrateToolMechanisms(whiteTools, false);
            migrateToolMechanisms(blackTools, true);
        } catch (Exception error) { report(error); }
    }

    /** Purpose choices have no exact mechanism equivalent when they differ. */
    static Set<String> legacyMechanismPackages(Set<String> selected, Set<String> screenshot,
                                                Set<String> recording, boolean black) {
        Set<String> result = new HashSet<>(screenshot);
        if (black) result.addAll(recording); else result.retainAll(recording);
        result.retainAll(selected);
        return result;
    }

    private static Set<String> mechanismSubset(SharedPreferences source, String key, boolean black) {
        Set<String> selected = new HashSet<>(source.getStringSet(KEY_CAPTURE_TOOLS, Collections.emptySet()));
        if (source.contains(key)) {
            Set<String> result = new HashSet<>(source.getStringSet(key, Collections.emptySet()));
            result.retainAll(selected);
            return result;
        }
        return legacyMechanismPackages(selected,
                source.getStringSet(KEY_SCREENSHOT_TOOLS, selected),
                source.getStringSet(KEY_RECORDING_TOOLS, selected), black);
    }

    private static void migrateToolMechanisms(SharedPreferences source, boolean black) {
        if (source.getBoolean(KEY_TOOL_MECHANISMS_READY, false)) return;
        Set<String> selected = new HashSet<>(source.getStringSet(KEY_CAPTURE_TOOLS, Collections.emptySet()));
        Set<String> screenshot = new HashSet<>(source.getStringSet(KEY_SCREENSHOT_TOOLS, selected));
        Set<String> recording = new HashSet<>(source.getStringSet(KEY_RECORDING_TOOLS, selected));
        screenshot.retainAll(selected);
        recording.retainAll(selected);
        boolean mixed = !screenshot.equals(recording)
                && (!source.contains(KEY_FLAG_SECURE_TOOLS) || !source.contains(KEY_SKIP_SCREENSHOT_TOOLS));
        boolean saved = source.edit()
                .putStringSet(KEY_FLAG_SECURE_TOOLS, mechanismSubset(source, KEY_FLAG_SECURE_TOOLS, black))
                .putStringSet(KEY_SKIP_SCREENSHOT_TOOLS, mechanismSubset(source, KEY_SKIP_SCREENSHOT_TOOLS, black))
                .putBoolean(KEY_TOOL_MECHANISM_REVIEW, mixed || source.getBoolean(KEY_TOOL_MECHANISM_REVIEW, false))
                .putBoolean(KEY_TOOL_MECHANISMS_READY, true).commit();
        if (!saved) throw new IllegalStateException("采集工具机制设置尚未保存");
    }

    /** Capability discovery first; installed system-package hints cover OEM private entry points. */
    private static Set<String> discoverSystemCaptureTools() {
        Set<String> result = new HashSet<>();
        PackageManager manager = context.getPackageManager();
        try {
            int resource = context.getResources().getIdentifier("config_screenshotServiceComponent", "string", "android");
            if (resource != 0) {
                ComponentName component = ComponentName.unflattenFromString(context.getString(resource));
                if (component != null) addSystemCapturePackage(manager, result, component.getPackageName(), true);
            }
        } catch (RuntimeException error) { Log.w(TAG, "Screenshot component discovery", error); }
        for (String action : new String[]{"android.intent.action.SCREENSHOT", "android.intent.action.SCREEN_RECORD",
                "com.android.systemui.action.SCREENSHOT"}) {
            try {
                Intent intent = new Intent(action);
                for (ResolveInfo entry : manager.queryIntentServices(intent, 0)) {
                    if (entry.serviceInfo != null) addSystemCapturePackage(manager, result, entry.serviceInfo.packageName, true);
                }
                for (ResolveInfo entry : manager.queryIntentActivities(intent, 0)) {
                    if (entry.activityInfo != null) addSystemCapturePackage(manager, result, entry.activityInfo.packageName, true);
                }
                for (ResolveInfo entry : manager.queryBroadcastReceivers(intent, 0)) {
                    if (entry.activityInfo != null) addSystemCapturePackage(manager, result, entry.activityInfo.packageName, true);
                }
            } catch (RuntimeException error) { Log.w(TAG, "Capture action discovery: " + action, error); }
        }
        try {
            for (ResolveInfo entry : manager.queryIntentServices(
                    new Intent("android.service.quicksettings.action.QS_TILE"), 0)) {
                if (entry.serviceInfo == null) continue;
                String description = (entry.serviceInfo.name + " " + entry.loadLabel(manager))
                        .toLowerCase(Locale.ROOT);
                if (description.contains("screenshot") || description.contains("screenrecord")
                        || description.contains("screen_capture") || description.contains("screen_record")
                        || description.contains("screen capture") || description.contains("screen record")
                        || description.contains("截屏") || description.contains("截图")
                        || description.contains("录屏") || description.contains("屏幕录制")) {
                    addSystemCapturePackage(manager, result, entry.serviceInfo.packageName, true);
                }
            }
        } catch (RuntimeException error) { Log.w(TAG, "Capture tile discovery", error); }
        for (String name : new String[]{
                "com.android.systemui", "com.google.android.systemui",
                "com.oplus.screenshot", "com.oplus.screenrecorder",
                "com.coloros.screenshot", "com.coloros.screenrecorder",
                "com.oneplus.screenshot", "com.oneplus.screenrecord",
                "com.miui.screenshot", "com.miui.screenrecorder",
                "com.samsung.android.app.smartcapture", "com.samsung.android.app.screenrecorder",
                "com.huawei.screenshot", "com.huawei.screenrecorder",
                "com.hihonor.screenshot", "com.hihonor.HnMultiScreenShot", "com.hihonor.screenrecorder",
                "com.vivo.smartshot", "com.flyme.systemuiex", "org.lineageos.recorder"}) {
            addSystemCapturePackage(manager, result, name);
        }
        return result;
    }

    private static void addSystemCapturePackage(PackageManager manager, Set<String> result, String packageName) {
        addSystemCapturePackage(manager, result, packageName, false);
    }

    private static void addSystemCapturePackage(PackageManager manager, Set<String> result,
                                                String packageName, boolean resolvedComponent) {
        try {
            ApplicationInfo app = manager.getApplicationInfo(packageName, 0);
            boolean system = (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            boolean platformSigned = !system && resolvedComponent && app.enabled
                    && manager.checkSignatures("android", packageName) == PackageManager.SIGNATURE_MATCH;
            if (app.enabled && captureToolComponentTrusted(system, resolvedComponent, platformSigned)) {
                result.add(packageName);
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // Package hints are accepted only when an installed system application verifies them.
        } catch (RuntimeException error) { Log.w(TAG, "System capture package discovery: " + packageName, error); }
    }

    static boolean captureToolComponentTrusted(boolean system, boolean resolvedComponent, boolean platformSigned) {
        return system || (resolvedComponent && platformSigned);
    }

    private static void writeNativeConfig() throws Exception {
        writeBooleanConfig("sf17fix.conf", local.getBoolean(KEY_SKIP, true));
    }

    /** Keep the original single-value screenshot file compatible with earlier companions. */
    private static void writeRecordingNativeConfig() throws Exception {
        writeBooleanConfig("sf17fix-recording.conf", local.getBoolean(KEY_SKIP, true));
    }

    private static void writeArbitrationConfig() throws Exception {
        writeBooleanConfig("sf17fix-arbitration.conf", local.getBoolean(KEY_ARBITRATION_ENABLED, DEFAULT_ARBITRATION_ENABLED));
    }

    private static void writeBooleanConfig(String name, boolean enabled) throws Exception {
        File destination = new File(context.getFilesDir(), name);
        int digit = enabled ? '1' : '0';
        if (destination.isFile() && destination.length() == 2) {
            try (FileInputStream input = new FileInputStream(destination)) {
                var stat = Os.lstat(destination.getAbsolutePath());
                if (OsConstants.S_ISREG(stat.st_mode) && (stat.st_mode & 077) == 0 && stat.st_uid == android.os.Process.myUid()
                        && input.read() == digit && input.read() == '\n' && input.read() == -1) return;
            } catch (Exception ignored) { /* Replace unreadable old config atomically. */ }
        }
        File temporary = new File(context.getFilesDir(), name + ".new");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            Os.chmod(temporary.getAbsolutePath(), 0600);
            stream.write(digit);
            stream.write('\n');
            stream.getFD().sync();
        }
        Os.rename(temporary.getAbsolutePath(), destination.getAbsolutePath());
    }

    private static void readNativeStatus() {
        File file = new File(context.getFilesDir(), "sf17fix-status.json");
        nativeCapabilityMissing = false;
        nativeMatching = false;
        try {
            if (!file.isFile()) {
                nativeActive = false;
                nativeKnown = false;
                nativeOperationOk = false;
                nativeDetails = "系统组件尚未安装，或尚未启动";
                return;
            }
            long age = System.currentTimeMillis() - file.lastModified();
            JSONObject status = NATIVE_STATUS_FILE.read(file);
            boolean fresh = age >= -5_000 && age < 15_000;
            nativeMatching = statusMatching(status, "screenshot", age, 15_000);
            nativeCapabilityMissing = fresh && capabilityMissing(status);
            // A failed disable can leave an observed active patch in place. Keep
            // observation separate from operation success; never report that as off.
            nativeKnown = fresh && status.optBoolean("active_known");
            nativeActive = nativeKnown && status.optBoolean("active");
            nativeOperationOk = fresh && !nativeMatching && status.optBoolean("ok");
            if (!fresh) nativeDetails = "系统组件状态已过期，请安装或更新系统组件";
            else if (nativeMatching) nativeDetails = COMPATIBILITY_MATCHING;
            else if (nativeCapabilityMissing) nativeDetails = capabilityMessage("截图", status);
            else if (!status.optBoolean("supported")) nativeDetails = "截图原生接口待核对：" + status.optString("reason");
            else if (!status.optBoolean("ok")) nativeDetails = "系统组件待处理：" + status.optString("reason");
            else nativeDetails = nativeActive ? "系统截图已包含被排除子层" : "已恢复系统子层排除规则";
        } catch (Exception e) {
            nativeActive = false;
            nativeKnown = false;
            nativeOperationOk = false;
            nativeDetails = "系统组件状态读取失败：" + e.getClass().getSimpleName();
        }
    }

    private static void readRecordingNativeStatus() {
        File file = new File(context.getFilesDir(), "sf17fix-recording-status.json");
        recordingNativeCapabilityMissing = false;
        recordingNativeMatching = false;
        try {
            if (!file.isFile()) {
                recordingNativeActive = false;
                recordingNativeKnown = false;
                recordingNativeOperationOk = false;
                recordingNativeSupported = false;
                recordingPipeline = "";
                recordingNativeDetails = "录屏系统组件状态尚未就绪，请安装或更新系统组件";
                return;
            }
            long age = System.currentTimeMillis() - file.lastModified();
            JSONObject status = RECORDING_STATUS_FILE.read(file);
            boolean fresh = age >= -5_000 && age < 15_000;
            boolean schemaKnown = status.optInt("schema", -1) == 1;
            recordingPipeline = schemaKnown ? status.optString("pipeline", "") : "";
            boolean pipelineKnown = "recording".equals(recordingPipeline);
            recordingNativeMatching = statusMatching(status, "recording", age, 15_000);
            recordingNativeCapabilityMissing = fresh && schemaKnown && pipelineKnown && capabilityMissing(status);
            recordingNativeKnown = fresh && schemaKnown && pipelineKnown && status.optBoolean("active_known");
            recordingNativeActive = recordingNativeKnown && status.optBoolean("active");
            recordingNativeOperationOk = fresh && schemaKnown && pipelineKnown && !recordingNativeMatching && status.optBoolean("ok");
            recordingNativeSupported = fresh && schemaKnown && pipelineKnown && status.optBoolean("supported");
            if (!fresh) recordingNativeDetails = "录屏系统组件状态已过期，请安装或更新系统组件";
            else if (!schemaKnown) recordingNativeDetails = "录屏状态格式待更新，请安装新版系统组件";
            else if (!pipelineKnown) recordingNativeDetails = "录屏状态通道不匹配，请更新系统组件";
            else if (recordingNativeMatching) recordingNativeDetails = COMPATIBILITY_MATCHING;
            else if (recordingNativeCapabilityMissing) recordingNativeDetails = capabilityMessage("录屏", status);
            else if (!recordingNativeSupported) recordingNativeDetails = "录屏原生接口待核对：" + status.optString("reason");
            else if (!recordingNativeOperationOk) recordingNativeDetails = "录屏系统组件待处理：" + status.optString("reason");
            else if (!recordingNativeKnown) recordingNativeDetails = "录屏输出实际状态待确认：" + status.optString("reason");
            else recordingNativeDetails = recordingNativeActive
                    ? "录屏输出已包含被排除子层；请用实际视频验证"
                    : "已恢复录屏子层排除规则；请重新开始录屏";
        } catch (Exception e) {
            recordingNativeActive = false;
            recordingNativeKnown = false;
            recordingNativeOperationOk = false;
            recordingNativeSupported = false;
            recordingPipeline = "";
            recordingNativeDetails = "录屏状态读取失败：" + e.getClass().getSimpleName();
        }
    }

    private static boolean capabilityMissing(JSONObject status) {
        String reason = status.optString("reason");
        return Boolean.FALSE.equals(status.opt("supported")) && Boolean.FALSE.equals(status.opt("owned"))
                && (reason.equals("signature_not_found") || reason.equals("signature_ambiguous")
                || reason.matches("unsupported_[a-z0-9_]+"));
    }

    private static String capabilityMessage(String pipeline, JSONObject status) {
        String reason = status.optString("reason");
        String cause = reason.equals("signature_not_found") ? "未找到经过验证的系统原生代码模式"
                : reason.equals("signature_ambiguous") ? "系统原生代码存在多个候选，尚未确认唯一匹配"
                : "当前原生接口或格式尚未支持";
        return pipeline + "图层兼容未启用：" + cause + "；与 APK 签名或证书无关。诊断：" + reason;
    }

    private static void readPolicyStatus() {
        File file = new File(context.getFilesDir(), "sf17fix-policy-status.json");
        policyRunning = false;
        policyMatching = false;
        policyKnown = false;
        policyReady = false;
        policyPaused = false;
        policyBlockers = -1;
        policyDetails = ARBITRATION_NOT_RUNNING;
        if (!local.getBoolean(KEY_ARBITRATION_ENABLED, DEFAULT_ARBITRATION_ENABLED)) return;
        try {
            if (!file.isFile()) return;
            long age = System.currentTimeMillis() - file.lastModified();
            if (age < -5_000 || age >= 8_000) return;
            JSONObject status = POLICY_STATUS_FILE.read(file);
            policyMatching = statusMatching(status, "policy", age, 8_000);
            if (policyMatching) {
                policyRunning = Boolean.TRUE.equals(status.opt("running"));
                policyDetails = COMPATIBILITY_MATCHING;
                return;
            }
            if (!policyStatusRunning(status, age)) return;
            boolean ready = policyBoolean(status, "ready", "policy_ready");
            boolean paused = policyBoolean(status, "paused", "policy_paused");
            if (!(status.get("ok") instanceof Boolean ok)) throw new IllegalStateException("policy ok");
            int blockers = -1;
            if (status.has("blockers") || status.has("blocked_count")) {
                Object count = status.has("blockers") ? status.get("blockers") : status.get("blocked_count");
                if (!(count instanceof Integer number) || number < 0) throw new IllegalStateException("policy count");
                blockers = number;
                if (status.has("blockers") && status.has("blocked_count")
                        && !count.equals(status.get("blocked_count"))) throw new IllegalStateException("policy count aliases");
            }
            policyKnown = true;
            policyRunning = true;
            policyReady = ok && ready;
            policyPaused = paused;
            policyBlockers = blockers;
            if (!ok) policyDetails = "采集仲裁待处理：" + status.optString("reason");
            else if (!ready) policyDetails = "等待系统模块握手；请结束已有采集后刷新";
            else if (paused) policyDetails = "图层兼容按排除优先暂停" + (blockers >= 0 ? " · 排除会话 " + blockers : "");
            else policyDetails = "系统采集仲裁已就绪 · 无排除会话";
            if (Boolean.FALSE.equals(status.opt("screenshot_managed"))
                    && Boolean.FALSE.equals(status.opt("recording_managed"))) {
                policyDetails += " · 图层处理未匹配，原系统规则保留";
            } else if ("partial".equals(status.optString("coverage"))) policyDetails += " · 部分图层管线支持";
        } catch (Exception error) {
            policyDetails = ARBITRATION_NOT_RUNNING;
            Log.w(TAG, "Capture policy status unavailable", error);
        }
    }

    private static boolean policyStatusRunning(JSONObject status, long age) throws Exception {
        return age >= -5_000 && age < 8_000
                && status.get("schema") instanceof Integer schema && schema == 1
                && "policy".equals(status.get("pipeline"))
                && Boolean.TRUE.equals(status.get("running"));
    }

    private static boolean statusMatching(JSONObject status, String pipeline, long age, long maximumAge) {
        return age >= -5_000 && age < maximumAge
                && status.opt("schema") instanceof Integer schema && schema == 1
                && pipeline.equals(status.opt("pipeline"))
                && Boolean.TRUE.equals(status.opt("matching"));
    }

    /** Cache immutable parsed snapshots, while freshness is checked on every poll. */
    private static final class StatusFile {
        private long modified = Long.MIN_VALUE;
        private long length = -1;
        private long inode = -1;
        private String text;
        private JSONObject parsed;

        synchronized JSONObject read(File file) throws Exception {
            long currentModified = file.lastModified(), currentLength = file.length();
            long currentInode = Os.stat(file.getAbsolutePath()).st_ino;
            if (parsed != null && currentModified == modified && currentLength == length && currentInode == inode) return parsed;
            String next;
            try (InputStream input = new FileInputStream(file)) { next = readText(input, 65_536); }
            if (parsed == null || !next.equals(text)) parsed = new JSONObject(next);
            text = next;
            modified = currentModified;
            length = currentLength;
            inode = currentInode;
            return parsed;
        }
    }

    private static boolean policyBoolean(JSONObject status, String canonical, String alias) throws Exception {
        Object value = status.has(canonical) ? status.get(canonical) : status.get(alias);
        if (!(value instanceof Boolean result)) throw new IllegalStateException("policy boolean");
        if (status.has(canonical) && status.has(alias) && !value.equals(status.get(alias))) {
            throw new IllegalStateException("policy aliases");
        }
        return result;
    }

    /** One-time installation needs root; ordinary toggles use the private config file. */
    public void installNativeAsync() {
        operationStatus = "正在安装系统组件，请留意 Root 授权提示";
        WORKER.execute(() -> {
            Process process = null;
            try {
                File stage = new File(context.getFilesDir(), "native-installer");
                if (!stage.isDirectory() && !stage.mkdirs()) throw new IllegalStateException("staging directory");
                for (String item : new String[]{"module.prop", "service.sh", "uninstall.sh"}) {
                    copyAsset("root-component/" + item, new File(stage, item));
                }
                boolean found = false;
                for (String abi : Build.SUPPORTED_ABIS) {
                    try {
                        copyAsset("native/" + abi + "/libsf17fix.so", new File(stage, "sf17fix"));
                        found = true;
                        break;
                    } catch (java.io.FileNotFoundException ignored) { }
                }
                if (!found) throw new IllegalStateException("APK 未包含当前处理器架构的系统组件");
                writeNativeConfig();
                writeRecordingNativeConfig();
                writeArbitrationConfig();
                String mod = quote(MODULE_DIR), src = quote(stage.getAbsolutePath());
                String script = "set -eu\nMOD=" + mod + "\nSRC=" + src + "\n"
                        + "if [ -f \"$MOD/module.prop\" ]; then grep -qx 'id=enable_screenshot17fix' \"$MOD/module.prop\"; fi\n"
                        + "mkdir -p \"$MOD/bin\"\n"
                        + "fail_install() { echo \"SF17FIX_INSTALL_FAILED: $1\"; exit 1; }\n"
                        + "is_service() { [ -r \"/proc/$1/cmdline\" ] && tr '\\000' ' ' < \"/proc/$1/cmdline\" | grep -Fq \"$MOD/service.sh\"; }\n"
                        + "is_inactive() { printf '%s\\n' \"$1\" | grep -Eq '\"ok\"[[:space:]]*:[[:space:]]*true' && printf '%s\\n' \"$1\" | grep -Eq '\"active_known\"[[:space:]]*:[[:space:]]*true' && printf '%s\\n' \"$1\" | grep -Eq '\"active\"[[:space:]]*:[[:space:]]*false'; }\n"
                        + "capability_absent() { [ ! -e \"$2\" ] && [ ! -L \"$2\" ] && printf '%s\\n' \"$1\" | grep -Eq '\"supported\"[[:space:]]*:[[:space:]]*false' && printf '%s\\n' \"$1\" | grep -Eq '\"reason\"[[:space:]]*:[[:space:]]*\"(signature_not_found|signature_ambiguous|unsupported_[a-z0-9_]+)\"'; }\n"
                        + "if [ -f \"$MOD/daemon.pid\" ]; then\n"
                        + " oldpid=$(cat \"$MOD/daemon.pid\")\n"
                        + " case \"$oldpid\" in *[!0-9]*|'') ;; *)\n"
                        + "  if is_service \"$oldpid\"; then\n"
                        + "   kill -TERM \"$oldpid\" || fail_install 'old service termination failed'\n"
                        + "   waiting=0\n"
                        + "   while is_service \"$oldpid\"; do\n"
                        + "    [ \"$waiting\" -lt 450 ] || fail_install 'old service did not finish restoring its changes'\n"
                        + "    sleep 0.1\n"
                        + "    waiting=$((waiting + 1))\n"
                        + "   done\n"
                        + "  fi ;; esac\nfi\n"
                        + "STATE=/data/adb/enable_screenshot17fix/native-patch.state\n"
                        + "RECORD_STATE=/data/adb/enable_screenshot17fix/native-recording-patch.state\n"
                        + "if [ -x \"$MOD/bin/sf17fix\" ]; then\n"
                        + " screenshot_code=0\n"
                        + " restored=$(\"$MOD/bin/sf17fix\" disable 2>>\"$MOD/daemon.log\") || screenshot_code=$?\n"
                        + " if [ \"$screenshot_code\" = 0 ] && is_inactive \"$restored\"; then :\n"
                        + " elif capability_absent \"$restored\" \"$STATE\"; then :\n"
                        + " else fail_install 'old screenshot restoration is unconfirmed'; fi\n"
                        + " record_status=$(\"$MOD/bin/sf17fix\" record-status 2>>\"$MOD/daemon.log\") || true\n"
                        + " if printf '%s\\n' \"$record_status\" | grep -Eq '\"reason\"[[:space:]]*:[[:space:]]*\"usage\"'; then\n"
                        + "  [ ! -e \"$RECORD_STATE\" ] && [ ! -L \"$RECORD_STATE\" ] || fail_install 'recording ownership requires a compatible restore tool'\n"
                        + " else\n"
                        + "  record_code=0\n"
                        + "  restored=$(\"$MOD/bin/sf17fix\" record-disable 2>>\"$MOD/daemon.log\") || record_code=$?\n"
                        + "  if [ \"$record_code\" = 0 ] && is_inactive \"$restored\"; then :\n"
                        + "  elif capability_absent \"$restored\" \"$RECORD_STATE\"; then :\n"
                        + "  else fail_install 'old recording restoration is unconfirmed'; fi\n"
                        + " fi\n"
                        + "fi\n"
                        + "[ ! -e \"$STATE\" ] && [ ! -L \"$STATE\" ] && [ ! -e \"$RECORD_STATE\" ] && [ ! -L \"$RECORD_STATE\" ] || fail_install 'owned changes remain pending'\n"
                        + "rm -f \"$MOD/daemon.pid\"\n"
                        + "cp \"$SRC/sf17fix\" \"$MOD/bin/sf17fix.new\"\n"
                        + "cp \"$SRC/module.prop\" \"$MOD/module.prop.new\"\n"
                        + "cp \"$SRC/service.sh\" \"$MOD/service.sh.new\"\n"
                        + "cp \"$SRC/uninstall.sh\" \"$MOD/uninstall.sh.new\"\n"
                        + "chmod 755 \"$MOD/bin/sf17fix.new\" \"$MOD/service.sh.new\" \"$MOD/uninstall.sh.new\"\n"
                        + "chmod 644 \"$MOD/module.prop.new\"\n"
                        + "mv -f \"$MOD/bin/sf17fix.new\" \"$MOD/bin/sf17fix\"\n"
                        + "mv -f \"$MOD/module.prop.new\" \"$MOD/module.prop\"\n"
                        + "mv -f \"$MOD/service.sh.new\" \"$MOD/service.sh\"\n"
                        + "mv -f \"$MOD/uninstall.sh.new\" \"$MOD/uninstall.sh\"\n"
                        + "rm -f \"$MOD/disable\" \"$MOD/remove\"\n"
                        + "nohup sh \"$MOD/service.sh\" >/dev/null 2>&1 </dev/null &\n"
                        + "echo SF17FIX_INSTALL_OK\n";
                process = new ProcessBuilder("su", "-c", "sh").redirectErrorStream(true).start();
                try (var stream = process.getOutputStream()) {
                    stream.write(script.getBytes(StandardCharsets.UTF_8));
                }
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("Root 授权或安装等待超时");
                }
                String result;
                try (InputStream stream = process.getInputStream()) {
                    result = readText(stream, 65_536);
                }
                if (process.exitValue() != 0 || !result.contains("SF17FIX_INSTALL_OK")) {
                    String failure = "Root 未授权，或系统组件安装未完成";
                    for (String line : result.split("\\r?\\n")) {
                        if (line.startsWith("SF17FIX_INSTALL_FAILED:")) { failure = line; break; }
                    }
                    throw new IllegalStateException(failure);
                }
                operationStatus = "系统组件已安装，正在等待状态更新";
                Thread.sleep(2200);
                requestStatusRefresh();
                operationStatus = "";
            } catch (Throwable e) {
                report(e);
            } finally {
                if (process != null && process.isAlive()) process.destroyForcibly();
            }
        });
    }

    private static void copyAsset(String name, File destination) throws Exception {
        try (InputStream input = context.getAssets().open(name);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[16_384];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private static String readText(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4_096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IllegalStateException("status too large");
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static void report(Throwable error) {
        operationStatus = "操作未完成：" + (error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage());
        Log.w(TAG, "Configuration operation", error);
    }
}
