package net.hawyn.screenshotfreedoma17;

import android.util.Log;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Screenshot and recording compatibility, selected by the members present at runtime.
 * The framework scope determines which processes receive this module.
 *
 * Authors: Hawyn; Codex
 */
public final class ScreenshotCompatModule extends XposedModule {
    private static final String TAG = "ScreenshotCompat";
    private static final int SAMPLE_LIMIT = 5;
    private static final String KEY_CAPTURE_TOOLS = "capture_tool_packages";
    private static final String KEY_SCREENSHOT_TOOLS = "capture_tool_screenshot_packages";
    private static final String KEY_RECORDING_TOOLS = "capture_tool_recording_packages";
    private static final String KEY_FLAG_SECURE_TOOLS = "capture_tool_flag_secure_packages";
    private static final String KEY_SKIP_SCREENSHOT_TOOLS = "capture_tool_skip_screenshot_packages";
    private static final String KEY_TOOL_MODE = "capture_tool_mode";
    private static final String[] CAPTURE_CLASSES = {
            "android.window.ScreenCaptureInternal",
            "android.window.ScreenCapture",
            "android.view.SurfaceControl"
    };
    private static final String[] BUFFER_CLASSES = {
            "android.window.ScreenCaptureInternal$ScreenshotHardwareBuffer",
            "android.window.ScreenCapture$ScreenshotHardwareBuffer",
            "android.view.SurfaceControl$ScreenshotHardwareBuffer",
            "com.oplus.screenshot.OplusScreenCapture$ScreenshotHardwareBuffer"
    };

    private final Set<Method> installed = ConcurrentHashMap.newKeySet();
    private final Set<String> installedHookIds = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedRuntimeErrors = ConcurrentHashMap.newKeySet();
    private final Set<Object> captureArgsInUse = Collections.newSetFromMap(new IdentityHashMap<>());
    private int activeScreenshotCallers;
    private boolean captureHooksRetiring;
    private volatile String processLabel = "unbound";
    private volatile ClassLoader hostClassLoader;
    private volatile boolean hostSystemServer;
    private volatile SharedPreferences settings;
    private volatile SharedPreferences whiteToolSettings;
    private volatile SharedPreferences blackToolSettings;
    private volatile boolean settingsErrorLogged;
    private volatile Context systemContext;
    private final ThreadLocal<CaptureCaller> screenshotCallerUid = new ThreadLocal<>();
    private final ThreadLocal<CapturePolicyBroker.LegacyDisplay> vendorDisplayRequest = new ThreadLocal<>();
    private final ThreadLocal<Boolean> vendorSecureDisplayCreation = new ThreadLocal<>();
    private final ThreadLocal<Boolean> originalWindowPolicyCheck = new ThreadLocal<>();
    private final DeferredCaptureIdentity<CaptureCaller> deferredScreenshotCallers = new DeferredCaptureIdentity<>(64);
    private final ThreadLocal<DeferredCaptureIdentity.Ticket<CaptureCaller>> screenshotRequest = new ThreadLocal<>();
    private final CapturePolicyBroker policyBroker = new CapturePolicyBroker((message, error) -> {
        if (error == null) info(message); else warn(message, error);
    });
    private volatile boolean displayDestructionTracked;
    private volatile boolean vendorSecureDisplayMarkerInstalled;
    private volatile WmsCaptureRelayProfile wmsCaptureRelay;
    private final AtomicInteger wmsRelaySamples = new AtomicInteger();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processLabel = param.getProcessName();
        hostSystemServer = param.isSystemServer();
        bindSettings();
        info("loaded process=" + processLabel + " api=" + getApiVersion());
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        processLabel = "system_server";
        hostSystemServer = true;
        hostClassLoader = param.getClassLoader();
        installCommon(hostClassLoader);
        installSystemServer(hostClassLoader);
        policyBroker.startReadiness(hostClassLoader);
        info("ready process=" + processLabel + " totalHooks=" + installed.size());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        ApplicationInfo app = param.getApplicationInfo();
        if (app == null || (app.flags & (ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) {
            info("system-only scope: skipped package=" + param.getPackageName());
            return;
        }
        processLabel = param.getPackageName();
        hostSystemServer = false;
        hostClassLoader = param.getClassLoader();
        installCommon(hostClassLoader);
        installRecorderSecureNotice(hostClassLoader);
        info("ready package=" + processLabel + " totalHooks=" + installed.size());
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        synchronized (captureArgsInUse) {
            if (!captureArgsInUse.isEmpty() || activeScreenshotCallers != 0
                    || deferredScreenshotCallers.hasPending() || !policyBroker.prepareReload()) {
                info("hot reload deferred: finish active screenshot/projection sessions before updating");
                return false;
            }
            captureHooksRetiring = true;
        }
        if (!policyBroker.stopReadiness()) {
            info("hot reload deferred: capture policy control thread is still stopping");
            return false;
        }
        // Object[], String, Boolean and the host loader are owned by the framework/runtime.
        // Do not transfer module instances, hookers, counters or CaptureFields across generations.
        param.setSavedInstanceState(new Object[]{
                "ScreenshotCompatState/1", processLabel, hostClassLoader, hostSystemServer
        });
        info("hot reload state saved hasHostLoader=" + (hostClassLoader != null)
                + " systemServer=" + hostSystemServer + " hooks=" + installed.size());
        // Active completion proxies and display leases veto reload above; no callback owner migrates.
        return true;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        processLabel = param.getProcessName();
        hostSystemServer = param.isSystemServer();
        // Read ids while the old handles are still valid, before same-id replacement.
        Map<XposedInterface.HookHandle, String> oldHooks = snapshotOldHooks(param);
        boolean restored = false;
        boolean completed = false;
        boolean cleaned;
        try {
            Object saved = param.getSavedInstanceState();
            if (saved instanceof Object[] state && state.length == 4
                    && "ScreenshotCompatState/1".equals(state[0])
                    && state[1] instanceof String label
                    && state[2] instanceof ClassLoader loader
                    && state[3] instanceof Boolean systemServer
                    && systemServer == hostSystemServer) {
                processLabel = label;
                hostClassLoader = loader;
                restored = true;
                installCommon(loader);
                installRecorderSecureNotice(loader);
                if (hostSystemServer) {
                    installSystemServer(loader);
                    policyBroker.startReadiness(loader);
                }
                completed = true;
            } else {
                info("hot reload skipped: missing or incompatible host-loader state; process restart required");
            }
        } catch (Throwable error) {
            warn("hot reload initialization failed; process restart required", error);
        } finally {
            cleaned = removeObsoleteHooks(oldHooks);
        }
        if (restored && completed && cleaned && !installedHookIds.isEmpty()) {
            info("hot reload ready systemServer=" + hostSystemServer
                    + " totalHooks=" + installedHookIds.size());
        } else if (restored && completed) {
            if (installedHookIds.isEmpty()) {
                info("hot reload finished with zero applicable hooks; no screenshot compatibility hooks active");
            } else {
                info("hot reload hook cleanup incomplete; process restart required");
            }
        }
    }

    private Map<XposedInterface.HookHandle, String> snapshotOldHooks(HotReloadedParam param) {
        Map<XposedInterface.HookHandle, String> snapshot = new IdentityHashMap<>();
        for (XposedInterface.HookHandle old : param.getOldHookHandles()) {
            try {
                snapshot.put(old, old.getId());
            } catch (Throwable error) {
                snapshot.put(old, null);
                warn("old hook id read failed; hook scheduled for removal", error);
            }
        }
        return snapshot;
    }

    private boolean removeObsoleteHooks(Map<XposedInterface.HookHandle, String> oldHooks) {
        int removed = 0;
        int replaced = 0;
        boolean complete = true;
        for (Map.Entry<XposedInterface.HookHandle, String> entry : oldHooks.entrySet()) {
            try {
                String id = entry.getValue();
                if (id != null && installedHookIds.contains(id)) {
                    // Installing the same executable/id atomically invalidated the old handle.
                    replaced++;
                } else {
                    entry.getKey().unhook();
                    removed++;
                }
            } catch (Throwable error) {
                complete = false;
                warn("old hook cleanup failed", error);
            }
        }
        info("hot reload old hooks replaced=" + replaced + " removed=" + removed);
        return complete;
    }

    private void installCommon(ClassLoader loader) {
        bindSettings();
        if (!hostSystemServer) installWmsCaptureRelay(loader);
        for (String name : CAPTURE_CLASSES) {
            Class<?> owner = probeClass(name, loader);
            if (owner == null) continue;
            installNativeCapture(owner);
            installCaptureCallbackDiagnostics(owner);
            installCaptureDiagnostics(owner);
        }
        for (String name : BUFFER_CLASSES) {
            Class<?> owner = probeClass(name, loader);
            if (owner == null) continue;
            installBufferHooks(owner);
        }
        Class<?> oplusCapture = probeClass("com.oplus.screenshot.OplusScreenCapture", loader);
        if (oplusCapture != null) installCaptureDiagnostics(oplusCapture);
        Class<?> oplusBuilder = probeClass(
                "com.oplus.screenshot.OplusScreenCapture$CaptureArgs$Builder", loader);
        if (oplusBuilder != null) installOplusUid(oplusBuilder);
        installBitmapSecureMetadata(loader);
    }

    /** The verified vendor wrapper is a metadata query; the original bitmap stays intact. */
    private void installBitmapSecureMetadata(ClassLoader loader) {
        Class<?> owner = probeClass("com.hihonor.android.graphics.BitmapEx", loader);
        if (owner == null) return;
        for (Method method : methods(owner)) {
            if (!method.getName().equals("isContainsSecureLayers") || !Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != boolean.class || method.getParameterCount() != 1
                    || !method.getParameterTypes()[0].getName().equals("android.graphics.Bitmap")) continue;
            install(method, chain -> {
                Object original = chain.proceed();
                if (hostSystemServer && screenshotCallerUid.get() == null) return original;
                return false;
            });
        }
    }

    /** Log only callback status/nullness; forward the same buffer and integer exactly once. */
    @SuppressWarnings("unchecked")
    private void installCaptureCallbackDiagnostics(Class<?> owner) {
        for (Method method : methods(owner)) {
            if (!method.getName().equals("nativeCreateScreenCaptureListener")
                    || !Modifier.isStatic(method.getModifiers()) || method.getReturnType() != long.class
                    || method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != java.util.function.ObjIntConsumer.class) continue;
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();
            AtomicInteger registrations = new AtomicInteger();
            installCapturePipeline(method, chain -> {
                if (registrations.get() >= SAMPLE_LIMIT * 2
                        || registrations.getAndIncrement() >= SAMPLE_LIMIT * 2) return chain.proceed();
                Object callback = chain.getArg(0);
                if (!(callback instanceof java.util.function.ObjIntConsumer<?>)) return chain.proceed();
                java.util.function.ObjIntConsumer<Object> original = (java.util.function.ObjIntConsumer<Object>) callback;
                java.util.function.ObjIntConsumer<Object> observed = (buffer, status) -> {
                    AtomicInteger count = status == 0 ? successes : errors;
                    if (count.incrementAndGet() <= SAMPLE_LIMIT) {
                        info(owner.getName() + " listener callback status=" + status + " bufferNull=" + (buffer == null));
                    }
                    original.accept(buffer, status);
                };
                Object[] replacement = chain.getArgs().toArray();
                replacement[0] = observed;
                return chain.proceed(replacement);
            });
        }
    }

    /** Only the verified recorder's dedicated secure-content notice, not SF monitoring. */
    private void installRecorderSecureNotice(ClassLoader loader) {
        if (hostSystemServer || !"com.oplus.screenrecorder".equals(processLabel)) return;
        try {
            Class<?> monitor = Class.forName(
                    "com.oplus.screenrecorder.floatwindow.monitor.q", false, loader);
            Class<?> quickToast = Class.forName(
                    "com.oplus.screenrecorder.floatwindow.view.r0", false, loader);
            Class<?> stringResources = Class.forName(
                    "com.oplus.screenrecorder.floatwindow.R$string", false, loader);
            Method notice = monitor.getDeclaredMethod("d", monitor);
            Method event = monitor.getDeclaredMethod("handleEvent", String.class);
            Method showResource = quickToast.getDeclaredMethod("o", int.class);
            Field resource = stringResources.getDeclaredField("record_secure_tips");
            int modifiers = notice.getModifiers();
            boolean hasToastField = false;
            Field contextMember = null;
            for (Field member : monitor.getDeclaredFields()) {
                if (!Modifier.isStatic(member.getModifiers()) && member.getType() == quickToast) hasToastField = true;
                if (!Modifier.isStatic(member.getModifiers()) && Context.class.isAssignableFrom(member.getType())) {
                    if (contextMember != null) {
                        info("recorder secure notice context is ambiguous; original notice retained");
                        return;
                    }
                    contextMember = member;
                }
            }
            if (!Modifier.isPrivate(modifiers) || !Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)
                    || notice.getReturnType() != void.class || event.getReturnType() != void.class
                    || Modifier.isStatic(event.getModifiers()) || showResource.getReturnType() != void.class
                    || Modifier.isStatic(showResource.getModifiers()) || !hasToastField || contextMember == null
                    || resource.getType() != int.class || !Modifier.isStatic(resource.getModifiers())) {
                info("recorder secure notice structure differs; original notice retained");
                return;
            }
            resource.setAccessible(true);
            int noticeResource = resource.getInt(null);
            if (noticeResource <= 0) {
                info("recorder secure notice resource is absent; original notice retained");
                return;
            }
            Field noticeContext = contextMember;
            noticeContext.setAccessible(true);
            if (!installed.add(notice)) return;
            try {
                AtomicInteger samples = new AtomicInteger();
                String id = "ScreenshotCompatRecorderNotice:" + notice.toGenericString();
                hook(notice).setId(id).intercept(chain -> {
                    // The FLAG mechanism applies to this recorder independently of SKIP policy.
                    // Read current remote preferences when the posted notice actually executes.
                    boolean toolAllowed = !arbitrationEnabled() || !policyBroker.arbitrationRunningForMetadata()
                            || captureToolAllowed(processLabel, KEY_FLAG_SECURE_TOOLS);
                    if (!recordingSecureEnabled() || !toolAllowed) {
                        return chain.proceed();
                    }
                    boolean verifiedResource = false;
                    try {
                        Context context = (Context) noticeContext.get(chain.getArg(0));
                        verifiedResource = context != null
                                && "record_secure_tips".equals(context.getResources().getResourceEntryName(noticeResource))
                                && "string".equals(context.getResources().getResourceTypeName(noticeResource));
                    } catch (Throwable error) {
                        runtimeError(notice, "recorder secure notice resource verification; original notice retained", error);
                    }
                    if (!verifiedResource) return chain.proceed();
                    if (samples.incrementAndGet() <= SAMPLE_LIMIT) info("recorder record_secure_tips notice suppressed");
                    return null;
                });
                installedHookIds.add(id);
                info("installed recorder record_secure_tips-only notice hook " + notice.toGenericString());
            } catch (Throwable error) {
                installed.remove(notice);
                warn("recorder secure notice hook failed; original notice retained", error);
            }
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException missing) {
            info("zero verified recorder secure notice matches; original notice retained");
        } catch (Throwable error) {
            warn("recorder secure notice probe failed; original notice retained", error);
        }
    }

    private void installNativeCapture(Class<?> owner) {
        int matched = 0;
        for (Method method : methods(owner)) {
            String name = method.getName();
            if (!name.equals("nativeCaptureDisplay") && !name.equals("nativeCaptureLayers")) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length < 2 || types[0].isPrimitive() || types[1] != long.class
                    || method.getReturnType() != int.class) {
                info("skip unsupported capture signature " + method.toGenericString());
                continue;
            }
            CaptureFields fields = captureFields(types[0], owner);
            if (fields.policy == null && fields.legacySecure == null) {
                info("zero compatible secure fields; keep screenshot arbitration for " + method.toGenericString());
            }
            matched++;
            AtomicInteger samples = new AtomicInteger();
            installCapturePipeline(method, chain -> {
                DeferredCaptureIdentity.Ticket<CaptureCaller> request = screenshotRequest.get();
                if (request != null) request.nativeInvoked = true;
                Object captureArgs = chain.getArg(0);
                synchronized (captureArgsInUse) {
                    if (!captureArgsInUse.add(captureArgs)) {
                        runtimeError(method, "capture args concurrently reused; capture denied", new IllegalStateException("captureArgs busy"));
                        throw new CapturePolicyFailure("Capture arguments are already in use", null);
                    }
                }
                try {
                    if (name.equals("nativeCaptureDisplay") && relayCaptureDisplay(method, owner,
                            captureArgs, (Long) chain.getArg(1))) return 0;
                    boolean[] flagAllowed = {true};
                    CapturePolicyBroker.Lease lease = null;
                    Object[] replacement;
                    try {
                        replacement = chain.getArgs().toArray();
                        CaptureCaller caller = screenshotIdentity();
                        lease = policyBroker.beginQualified("SCREENSHOT", () -> {
                            flagAllowed[0] = false;
                            flagAllowed[0] = screenshotToolAllowed(method, KEY_FLAG_SECURE_TOOLS);
                            return screenshotToolAllowed(method, KEY_SKIP_SCREENSHOT_TOOLS);
                        }, caller.uid);
                        CapturePolicyBroker.WrappedListener listener = policyBroker.wrapListener(owner,
                                (Long) replacement[1], lease);
                        replacement[1] = listener.pointer;
                    } catch (Throwable error) {
                        if (lease != null) lease.abandon();
                        lease = policyBroker.noOp("SCREENSHOT");
                        replacement = chain.getArgs().toArray();
                        runtimeError(method, "capture arbitration unavailable; original listener retained", error);
                    }
                    boolean windowAllowed = secureEnabled() && (!lease.arbitrating || flagAllowed[0]);
                    int sample = samples.incrementAndGet();
                    Integer originalPolicy = null;
                    Boolean originalLegacySecure = null;
                    if (windowAllowed) try {
                        Object args = captureArgs;
                        if (args == null) {
                            if (sample <= SAMPLE_LIMIT) info(name + " captureArgs=null; original call retained");
                        } else {
                            StringBuilder changed = sample <= SAMPLE_LIMIT ? new StringBuilder() : null;
                            if (fields.policy != null) {
                                int before = fields.policy.getInt(args);
                                originalPolicy = before;
                                fields.policy.setInt(args, fields.capturePolicy);
                                if (changed != null) changed.append("policy=").append(before)
                                        .append("->").append(fields.capturePolicy);
                            }
                            if (fields.legacySecure != null) {
                                boolean before = fields.legacySecure.getBoolean(args);
                                originalLegacySecure = before;
                                fields.legacySecure.setBoolean(args, true);
                                if (changed != null) changed.append(" legacySecure=").append(before).append("->true");
                            }
                            if (changed != null) info(method.getDeclaringClass().getName() + "." + name + " " + changed);
                        }
                    } catch (Throwable error) {
                        runtimeError(method, "capture argument update; original call retained", error);
                    }
                    // Never catch and replay proceed(): the original operation executes exactly once.
                    Object result;
                    try {
                        result = chain.proceed(replacement);
                    } catch (Throwable error) {
                        // Another hook may throw after JNI accepted an asynchronous capture.
                        // An unknown submission becomes an orphan blocker in the root broker.
                        lease.abandon();
                        runtimeError(method, "capture threw; completion lease retained", error);
                        throw error;
                    } finally {
                        // JNI copies capture policy synchronously. Restore reusable Java args,
                        // including on submission failure, without replaying the capture.
                        if (originalPolicy != null) try {
                            fields.policy.setInt(captureArgs, originalPolicy);
                        } catch (Throwable error) { runtimeError(method, "capture policy restoration failed", error); }
                        if (originalLegacySecure != null) try {
                            fields.legacySecure.setBoolean(captureArgs, originalLegacySecure);
                        } catch (Throwable error) { runtimeError(method, "legacy capture policy restoration failed", error); }
                    }
                    if (result instanceof Integer status && status != 0) {
                        lease.finishAsync(); // JNI explicitly rejected submission; no output is pending.
                        if (lease.arbitrating) throw new CaptureSubmissionFailure(status);
                    }
                    if (sample <= SAMPLE_LIMIT) logResult(method, result);
                    return result;
                } finally {
                    synchronized (captureArgsInUse) { captureArgsInUse.remove(captureArgs); }
                }
            });
        }
        if (matched == 0) info("zero native capture matches in " + owner.getName());
    }

    private CaptureFields captureFields(Class<?> argsType, Class<?> owner) {
        Field policy = probeField(argsType, "mSecureContentPolicy", int.class);
        Field legacy = probeField(argsType, "mCaptureSecureLayers", boolean.class);
        int capturePolicy = 1;
        if (policy != null) {
            Field constant = probeField(owner, "SECURE_CONTENT_POLICY_CAPTURE", int.class);
            if (constant == null) {
                Class<?> facade = probeClass("android.window.ScreenCapture", owner.getClassLoader());
                if (facade != null) constant = probeField(facade, "SECURE_CONTENT_POLICY_CAPTURE", int.class);
            }
            if (constant != null && Modifier.isStatic(constant.getModifiers())) {
                try {
                    capturePolicy = constant.getInt(null);
                } catch (Throwable error) {
                    warn("secure policy constant read failed; use capture policy=1", error);
                }
            } else {
                info("secure policy constant absent; use capture policy=1 for " + argsType.getName());
            }
        }
        return new CaptureFields(policy, legacy, capturePolicy);
    }

    private void installBufferHooks(Class<?> owner) {
        int secureMatches = 0;
        int diagnosticMatches = 0;
        for (Method method : methods(owner)) {
            if (method.getParameterCount() != 0) continue;
            if (method.getName().equals("containsSecureLayers") && method.getReturnType() == boolean.class) {
                secureMatches++;
                AtomicInteger samples = new AtomicInteger();
                install(method, chain -> {
                    Object original = chain.proceed();
                    if (samples.incrementAndGet() <= SAMPLE_LIMIT) {
                        info(method.getDeclaringClass().getName() + ".containsSecureLayers original=" + original + " returned=false");
                    }
                    return false;
                });
            } else if ((method.getName().equals("getHardwareBuffer") || method.getName().equals("asBitmap"))
                    && !method.getReturnType().isPrimitive()) {
                diagnosticMatches++;
                installResultDiagnostic(method);
            }
        }
        if (secureMatches == 0) info("zero containsSecureLayers matches in " + owner.getName());
        if (diagnosticMatches == 0) info("zero buffer diagnostics matches in " + owner.getName());
    }

    private void installCaptureDiagnostics(Class<?> owner) {
        int matched = 0;
        for (Method method : methods(owner)) {
            String name = method.getName();
            if (!name.equals("captureDisplay") && !name.equals("captureDisplayInternal")
                    && !name.equals("captureLayers")) continue;
            Class<?> resultType = method.getReturnType();
            if (resultType == void.class) continue;
            if (resultType == int.class || resultType.getSimpleName().equals("ScreenshotHardwareBuffer")) {
                matched++;
                installResultDiagnostic(method);
            }
        }
        if (matched == 0) info("zero capture result diagnostics matches in " + owner.getName());
    }

    private void installResultDiagnostic(Method method) {
        AtomicInteger samples = new AtomicInteger();
        installDiagnostic(method, chain -> {
            if (samples.get() >= SAMPLE_LIMIT) return chain.proceed();
            Object result = chain.proceed();
            if (samples.incrementAndGet() <= SAMPLE_LIMIT) logResult(method, result);
            return result;
        });
    }

    private void installOplusUid(Class<?> owner) {
        int matched = 0;
        for (Method method : methods(owner)) {
            if (!method.getName().equals("setUid") || method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != long.class) continue;
            matched++;
            AtomicInteger samples = new AtomicInteger();
            install(method, chain -> {
                Object[] replacement = null;
                try {
                    replacement = chain.getArgs().toArray();
                    replacement[0] = -1L;
                    if (samples.incrementAndGet() <= SAMPLE_LIMIT) {
                        info(owner.getName() + ".setUid(long) using Long(-1)");
                    }
                } catch (Throwable error) {
                    replacement = null;
                    runtimeError(method, "UID argument update; original call retained", error);
                }
                return replacement == null ? chain.proceed() : chain.proceed(replacement);
            });
        }
        if (matched == 0) info("zero setUid(long) matches in " + owner.getName());
    }

    private void installSystemServer(ClassLoader loader) {
        installDeferredScreenshotCallerTracking(loader);
        installTypedWindowManagerCallerTracking(loader);
        installScreenshotCallerTracking(loader);
        installAccessibilityWindowCallerTracking(loader);
        installAccessibilitySecureWindowCheck(loader);
        installVendorWindowProtectionCheck(loader);
        Class<?> longshot = probeClass("com.android.server.wm.OplusLongshotMainWindow", loader);
        if (longshot != null) {
            int matched = 0;
            for (Method method : methods(longshot)) {
                if (!method.getName().equals("hasSecure") || method.getParameterCount() != 0
                        || method.getReturnType() != boolean.class) continue;
                matched++;
                AtomicInteger samples = new AtomicInteger();
                install(method, chain -> {
                    Object original = chain.proceed();
                    if (samples.incrementAndGet() <= SAMPLE_LIMIT) {
                        info("OplusLongshotMainWindow.hasSecure original=" + original + " returned=false");
                    }
                    return false;
                });
            }
            if (matched == 0) info("zero hasSecure matches in " + longshot.getName());
        }
        installScreenshotWindowCheck(loader);
        installRecordingVirtualDisplays(loader);
        installVendorDisplayRecording(loader);
    }

    /** Intercept only the proven vendor Binder-to-DisplayControl route after its permission gate. */
    private void installVendorDisplayRecording(ClassLoader loader) {
        Class<?> vendor = probeClass("com.android.server.display.HwDisplayManagerServiceEx", loader);
        if (vendor == null) return;
        policyBroker.requireLegacyInventory();
        try {
            Class<?> displayControl = Class.forName("com.android.server.display.DisplayControl", false, loader);
            Class<?> factory = Class.forName("com.android.server.HwServiceExFactory", false, loader);
            Class<?> managerClass = Class.forName("com.android.server.am.HwActivityManagerServiceEx", false, loader);
            Class<?> processList = Class.forName("com.android.server.am.ProcessList", false, loader);
            Method create = VendorWindowProtectionProfile.requiredMethod(vendor, "createVirtualDisplay", android.os.IBinder.class, String.class, boolean.class);
            Method destroy = VendorWindowProtectionProfile.requiredMethod(vendor, "destroyVirtualDisplay", void.class, android.os.IBinder.class);
            Method lowerCreate = VendorWindowProtectionProfile.requiredMethod(displayControl, "createVirtualDisplay", android.os.IBinder.class, String.class, boolean.class);
            Method lowerDestroy = VendorWindowProtectionProfile.requiredMethod(displayControl, "destroyVirtualDisplay", void.class, android.os.IBinder.class);
            Method getManager = factory.getDeclaredMethod("getHwActivityManagerServiceEx");
            Method check = VendorWindowProtectionProfile.requiredMethod(managerClass, "checkScreenRecord", boolean.class);
            Method notify = VendorWindowProtectionProfile.requiredMethod(managerClass, "notifyScreenRecord", void.class, int.class, int.class);
            Method died = VendorWindowProtectionProfile.requiredMethod(processList, "dispatchProcessDied", void.class, int.class, int.class);
            if (Modifier.isStatic(create.getModifiers()) || Modifier.isStatic(destroy.getModifiers())
                    || !Modifier.isStatic(lowerCreate.getModifiers()) || !Modifier.isStatic(lowerDestroy.getModifiers())
                    || !Modifier.isStatic(getManager.getModifiers()) || check.getReturnType() != boolean.class
                    || check.getParameterCount() != 0 || notify.getReturnType() != void.class
                    || Modifier.isStatic(died.getModifiers())) throw new NoSuchMethodException("Vendor recording profile");
            getManager.setAccessible(true);
            check.setAccessible(true);
            notify.setAccessible(true);
            boolean deathInstalled = installRecording(died, chain -> {
                try { return chain.proceed(); }
                finally {
                    try { policyBroker.legacyOwnerDied((Integer) chain.getArg(0), (Integer) chain.getArg(1)); }
                    catch (Throwable error) { runtimeError(died, "vendor recording owner cleanup", error); }
                }
            });
            boolean destroyInstalled = installRecording(lowerDestroy, chain -> {
                CapturePolicyBroker.LegacyDisplay display = policyBroker.beginLegacyDestruction((android.os.IBinder) chain.getArg(0));
                if (display == null) return chain.proceed();
                try {
                    Object result = chain.proceed();
                    policyBroker.legacyDestroyed(display, loader);
                    return result;
                } catch (Throwable error) {
                    policyBroker.legacyDestructionUncertain(display);
                    throw error;
                }
            });
            boolean createInstalled = installRecording(lowerCreate, chain -> {
                CapturePolicyBroker.LegacyDisplay display = vendorDisplayRequest.get();
                if (display == null) return chain.proceed();
                if (!display.reachedFactory.compareAndSet(false, true)) throw new IllegalStateException("Nested vendor display factory");
                if (!deathInstalled || !destroyInstalled) {
                    if (policyBroker.arbitrationRunning()) throw new IllegalStateException("Vendor capture lifecycle is unavailable");
                    display.lease = policyBroker.noOp("PROJECTION");
                }
                if (display.dead.get()) throw new IllegalStateException("Capture owner has exited");
                try {
                    boolean[] flagAllowed = {true};
                    if (display.lease == null) display.lease = policyBroker.beginQualified("PROJECTION", () -> {
                        flagAllowed[0] = false;
                        flagAllowed[0] = screenshotToolAllowed(lowerCreate, KEY_FLAG_SECURE_TOOLS);
                        return screenshotToolAllowed(lowerCreate, KEY_SKIP_SCREENSHOT_TOOLS);
                    }, display.ownerUid);
                    Object[] replacement = chain.getArgs().toArray();
                    boolean before = (Boolean) replacement[1];
                    boolean windowAllowed = recordingSecureEnabled() && (!display.lease.arbitrating || flagAllowed[0]);
                    boolean after = before || windowAllowed;
                    replacement[1] = after;
                    replacement[0] = captureDisplayName((String) replacement[0],
                            vendorSecureDisplayMarkerInstalled && windowAllowed, after);
                    Object token;
                    display.nativeSubmitted.set(true);
                    try { token = chain.proceed(replacement); }
                    catch (Throwable error) { display.lease.abandon(); throw error; }
                    policyBroker.legacyCreated(display, (android.os.IBinder) token, loader);
                    info("vendor recording uid=" + display.ownerUid + " pid=" + display.ownerPid
                            + " secure=" + before + "->" + after + " resultNull=" + (token == null));
                    return token;
                } catch (Throwable error) {
                    if (display.lease != null) display.lease.abandon();
                    runtimeError(lowerCreate, "vendor recording creation is unconfirmed", error);
                    throw error;
                }
            });
            boolean entryInstalled = installRecording(create, chain -> {
                if (!beginCallerScope()) throw new CapturePolicyFailure("Capture module is being reloaded", null);
                CapturePolicyBroker.LegacyDisplay request = null;
                CapturePolicyBroker.LegacyDisplay previousRequest = vendorDisplayRequest.get();
                CaptureCaller previous = screenshotCallerUid.get();
                try {
                    if (previousRequest != null) throw new IllegalStateException("Nested vendor display creation");
                    request = policyBroker.legacyRequest(Binder.getCallingUid(), Binder.getCallingPid(), display -> {
                        // Call the same native destruction entry first. Its hook waits
                        // for SF commit before ending the lease; death is not completion.
                        if (display.token != null) lowerDestroy.invoke(null, display.token);
                        Object manager = getManager.invoke(null);
                        if (!managerClass.isInstance(manager)) throw new IllegalStateException("Vendor recording inventory is unavailable");
                        notify.invoke(manager, display.ownerUid, 0);
                    });
                    vendorDisplayRequest.set(request);
                    screenshotCallerUid.set(new CaptureCaller(request.ownerUid, null));
                    // The vendor's original signature/system permission checks and
                    // notification execute exactly once, with unchanged Binder identity.
                    Object result = chain.proceed();
                    if (result instanceof android.os.IBinder token && !request.reachedFactory.get()) {
                        // A changed vendor implementation bypassed the proven lower
                        // factory. Restore SKIP filtering before exposing its token.
                        request.reachedFactory.set(true);
                        request.nativeSubmitted.set(true);
                        if (request.lease == null) request.lease = policyBroker.begin("PROJECTION", false, request.ownerUid);
                        policyBroker.legacyCreated(request, token, loader);
                        info("vendor recording used an unrecognized lower path; original filtering retained");
                    }
                    return result;
                } finally {
                    if (previousRequest == null) vendorDisplayRequest.remove(); else vendorDisplayRequest.set(previousRequest);
                    if (previous == null) screenshotCallerUid.remove(); else screenshotCallerUid.set(previous);
                    if (request != null) policyBroker.legacyRequestReturned(request);
                    endCallerScope();
                }
            });
            if (deathInstalled && destroyInstalled && createInstalled && entryInstalled) {
                policyBroker.configureLegacyInventory(getManager, check);
                installVendorSecureDisplayMarker(displayControl);
                info("vendor recording profile installed; original permissions and output lifecycle retained");
            } else {
                info("vendor recording profile incomplete; policy readiness remains conservative");
            }
        } catch (Throwable error) {
            warn("vendor recording profile unavailable; policy readiness remains conservative", error);
        }
    }

    /** Use the device's existing capture-output classification without changing source layers. */
    private void installVendorSecureDisplayMarker(Class<?> displayControl) throws NoSuchMethodException {
        Method create = VendorWindowProtectionProfile.requiredMethod(displayControl, "createVirtualDisplay",
                android.os.IBinder.class, String.class, boolean.class, boolean.class, String.class, int.class, float.class);
        if (!Modifier.isStatic(create.getModifiers())) throw new NoSuchMethodException("Capture display factory must be static");
        AtomicInteger samples = new AtomicInteger();
        vendorSecureDisplayMarkerInstalled = installRecording(create, chain -> {
            String before = (String) chain.getArg(0);
            String after = captureDisplayName(before, Boolean.TRUE.equals(vendorSecureDisplayCreation.get()), (Boolean) chain.getArg(1));
            if (after == before) return chain.proceed();
            Object[] replacement = chain.getArgs().toArray();
            replacement[0] = after;
            if (samples.incrementAndGet() <= SAMPLE_LIMIT) info("qualified secure capture display classification applied");
            return chain.proceed(replacement);
        });
    }

    private static String captureDisplayName(String name, boolean qualified, boolean secure) {
        if (!qualified || !secure || name == null || name.isEmpty() || name.contains("MS_VIRTUAL_DISPLAY")) return name;
        return name + ":MS_VIRTUAL_DISPLAY";
    }

    private void installWmsCaptureRelay(ClassLoader loader) {
        try {
            // These typed vendor facades, rather than a brand/version check, identify
            // the display-capture path whose secure result needs the WMS layer route.
            Class<?> vendorControl = Class.forName("com.hihonor.android.hardware.display.HnVideoRecordControl", false, loader);
            Method vendorCreate = VendorWindowProtectionProfile.requiredMethod(vendorControl, "createDisplay",
                    IBinder.class, String.class, boolean.class);
            Class<?> bitmap = Class.forName("com.hihonor.android.graphics.BitmapEx", false, loader);
            Method metadata = VendorWindowProtectionProfile.requiredMethod(bitmap, "isContainsSecureLayers",
                    boolean.class, android.graphics.Bitmap.class);
            if (!Modifier.isStatic(vendorCreate.getModifiers()) || !Modifier.isStatic(metadata.getModifiers())) return;
            wmsCaptureRelay = new WmsCaptureRelayProfile(loader);
            info("typed system screenshot WMS relay available for equivalent internal-display requests");
        } catch (ClassNotFoundException absent) {
            // Other platform paths keep their existing capture implementation.
        } catch (Throwable error) {
            warn("WMS capture relay profile unavailable; original capture retained", error);
        }
    }

    /** Return true only after the one WMS invocation has started, even if its outcome is unknown. */
    private boolean relayCaptureDisplay(Method method, Class<?> owner, Object args, long nativeListener) {
        WmsCaptureRelayProfile profile = wmsCaptureRelay;
        if (hostSystemServer || profile == null || owner != profile.capture || !secureEnabled()
                || !screenshotToolGate(method, KEY_FLAG_SECURE_TOOLS)) return false;
        WmsCaptureRelayProfile.Prepared prepared;
        try { prepared = profile.prepare(args, nativeListener, policyBroker); }
        catch (Throwable error) {
            runtimeError(method, "WMS capture relay preparation; original capture retained", error);
            return false;
        }
        if (prepared == null) return false;
        try {
            // No local BEGIN: WMS retains the actual Binder caller, checks its
            // original permission, and owns the single native layer-capture lease.
            prepared.invoke();
            if (wmsRelaySamples.incrementAndGet() <= SAMPLE_LIMIT) info("captureDisplay forwarded once through original WMS display=0");
        } catch (Throwable error) {
            // The server may already have submitted its asynchronous request.
            // Never replay JNI or fabricate onError beside a possible real callback.
            runtimeError(method, "WMS capture relay outcome unconfirmed; original listener retained", error);
        } finally {
            try { prepared.release(); }
            catch (Throwable error) { runtimeError(method, "WMS capture argument-copy release", error); }
        }
        return true;
    }

    /** A narrow equivalence profile; all unproven orientation/overlay/video modes use the original path. */
    private static final class WmsCaptureRelayProfile {
        final Class<?> capture;
        final Class<?> captureArgs;
        final Class<?> displayArgs;
        final Class<?> listener;
        final Method defaultToken;
        final Method displayGlobal;
        final Method displayInfo;
        final Method activityThread;
        final Method systemContext;
        final Method service;
        final Method asWindow;
        final Method captureDisplay;
        final Method releaseArgs;
        final Field token;
        final Field width;
        final Field height;
        final Field crop;
        final Field scaleX;
        final Field scaleY;
        final Field installationOrientation;
        final Field includeOverlays;
        final Field captureVideo;
        final Field forRotation;
        final Field captureMode;
        final Field logicalWidth;
        final Field logicalHeight;
        final Field rotation;
        final Parcelable.Creator<?> creator;

        WmsCaptureRelayProfile(ClassLoader loader) throws Exception {
            capture = Class.forName("android.window.ScreenCaptureInternal", false, loader);
            captureArgs = Class.forName(capture.getName() + "$CaptureArgs", false, loader);
            displayArgs = Class.forName(capture.getName() + "$DisplayCaptureArgs", false, loader);
            listener = Class.forName(capture.getName() + "$ScreenCaptureListener", false, loader);
            if (displayArgs.getSuperclass() != captureArgs || !Parcelable.class.isAssignableFrom(captureArgs)) {
                throw new NoSuchMethodException("Capture Parcelable hierarchy");
            }
            if (displayArgs.getMethod("writeToParcel", Parcel.class, int.class).getDeclaringClass() != captureArgs) {
                throw new NoSuchMethodException("Capture Parcelable encoding");
            }
            Class<?> surfaceControl = Class.forName("android.view.SurfaceControl", false, loader);
            defaultToken = VendorWindowProtectionProfile.requiredMethod(surfaceControl, "getInternalDisplayToken", IBinder.class);
            Class<?> global = Class.forName("android.hardware.display.DisplayManagerGlobal", false, loader);
            Class<?> info = Class.forName("android.view.DisplayInfo", false, loader);
            displayGlobal = VendorWindowProtectionProfile.requiredMethod(global, "getInstance", global);
            displayInfo = VendorWindowProtectionProfile.requiredMethod(global, "getDisplayInfo", info, int.class);
            Class<?> thread = Class.forName("android.app.ActivityThread", false, loader);
            activityThread = VendorWindowProtectionProfile.requiredMethod(thread, "currentActivityThread", thread);
            systemContext = thread.getDeclaredMethod("getSystemContext");
            if (!Context.class.isAssignableFrom(systemContext.getReturnType()) || Modifier.isStatic(systemContext.getModifiers())) {
                throw new NoSuchMethodException("ActivityThread system context");
            }
            systemContext.setAccessible(true);
            Class<?> manager = Class.forName("android.os.ServiceManager", false, loader);
            service = VendorWindowProtectionProfile.requiredMethod(manager, "getService", IBinder.class, String.class);
            Class<?> window = Class.forName("android.view.IWindowManager", false, loader);
            Class<?> stub = Class.forName(window.getName() + "$Stub", false, loader);
            asWindow = VendorWindowProtectionProfile.requiredMethod(stub, "asInterface", window, IBinder.class);
            captureDisplay = VendorWindowProtectionProfile.requiredMethod(window, "captureDisplay", void.class,
                    int.class, captureArgs, listener);
            releaseArgs = VendorWindowProtectionProfile.requiredMethod(captureArgs, "release", void.class);
            if (!Modifier.isStatic(defaultToken.getModifiers()) || !Modifier.isStatic(displayGlobal.getModifiers())
                    || !Modifier.isStatic(activityThread.getModifiers()) || !Modifier.isStatic(service.getModifiers())
                    || !Modifier.isStatic(asWindow.getModifiers()) || Modifier.isStatic(captureDisplay.getModifiers())) {
                throw new NoSuchMethodException("Capture relay method roles");
            }
            token = VendorWindowProtectionProfile.requiredField(displayArgs, "mDisplayToken", IBinder.class);
            width = VendorWindowProtectionProfile.requiredField(displayArgs, "mWidth", int.class);
            height = VendorWindowProtectionProfile.requiredField(displayArgs, "mHeight", int.class);
            crop = VendorWindowProtectionProfile.requiredField(captureArgs, "mSourceCrop", Rect.class);
            scaleX = VendorWindowProtectionProfile.requiredField(captureArgs, "mFrameScaleX", float.class);
            scaleY = VendorWindowProtectionProfile.requiredField(captureArgs, "mFrameScaleY", float.class);
            installationOrientation = VendorWindowProtectionProfile.requiredField(captureArgs, "mUseDisplayInstallationOrientation", boolean.class);
            includeOverlays = VendorWindowProtectionProfile.requiredField(captureArgs, "mIncludeSystemOverlays", boolean.class);
            captureVideo = VendorWindowProtectionProfile.requiredField(captureArgs, "mCaptureVideo", boolean.class);
            forRotation = VendorWindowProtectionProfile.requiredField(captureArgs, "mForRotationFlag", boolean.class);
            captureMode = VendorWindowProtectionProfile.requiredField(captureArgs, "mCaptureMode", int.class);
            logicalWidth = VendorWindowProtectionProfile.requiredField(info, "logicalWidth", int.class);
            logicalHeight = VendorWindowProtectionProfile.requiredField(info, "logicalHeight", int.class);
            rotation = VendorWindowProtectionProfile.requiredField(info, "rotation", int.class);
            Field creatorField = captureArgs.getDeclaredField("CREATOR");
            creatorField.setAccessible(true);
            Object value = creatorField.get(null);
            if (!(value instanceof Parcelable.Creator<?> typedCreator)) throw new NoSuchFieldException("CaptureArgs Parcelable creator");
            creator = typedCreator;
        }

        Prepared prepare(Object original, long pointer, CapturePolicyBroker broker) throws Exception {
            if (!displayArgs.isInstance(original) || pointer == 0) return null;
            Object thread = activityThread.invoke(null);
            Context context = thread == null ? null : (Context) systemContext.invoke(thread);
            if (context == null || context.checkSelfPermission("android.permission.READ_FRAME_BUFFER") != PackageManager.PERMISSION_GRANTED) return null;
            IBinder requestedToken = (IBinder) token.get(original);
            IBinder internal = (IBinder) defaultToken.invoke(null);
            if (requestedToken == null || internal == null || !requestedToken.equals(internal)) return null;
            Object global = displayGlobal.invoke(null);
            Object info = displayInfo.invoke(global, 0);
            if (info == null) return null;
            int displayWidth = logicalWidth.getInt(info), displayHeight = logicalHeight.getInt(info);
            CaptureGeometry geometry = CaptureGeometry.resolve(displayWidth, displayHeight, rotation.getInt(info),
                    (Rect) crop.get(original), width.getInt(original), height.getInt(original),
                    scaleX.getFloat(original), scaleY.getFloat(original),
                    installationOrientation.getBoolean(original), includeOverlays.getBoolean(original),
                    captureVideo.getBoolean(original), forRotation.getBoolean(original), captureMode.getInt(original));
            if (geometry == null) return null;
            IBinder window = (IBinder) service.invoke(null, "window");
            if (window == null || !"android.view.IWindowManager".equals(window.getInterfaceDescriptor())) return null;
            Object target = asWindow.invoke(null, window);
            if (!captureDisplay.getDeclaringClass().isInstance(target)) return null;
            Object callback = broker.listenerFromPointer(capture, pointer);
            if (!listener.isInstance(callback)) return null;
            Object copy;
            Parcel parcel = Parcel.obtain();
            try {
                ((Parcelable) original).writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                copy = creator.createFromParcel(parcel);
            } finally { parcel.recycle(); }
            boolean retained = false;
            try {
                if (copy == null || copy.getClass() != captureArgs) return null;
                Rect copyCrop = (Rect) crop.get(copy);
                if (copyCrop == null) return null;
                copyCrop.set(geometry.crop);
                scaleX.setFloat(copy, geometry.scaleX);
                scaleY.setFloat(copy, geometry.scaleY);
                // Recheck the token and display geometry before the sole Binder call.
                Object freshInfo = displayInfo.invoke(global, 0);
                if (freshInfo == null || !requestedToken.equals(defaultToken.invoke(null))
                        || logicalWidth.getInt(freshInfo) != displayWidth || logicalHeight.getInt(freshInfo) != displayHeight
                        || rotation.getInt(freshInfo) != 0) return null;
                retained = true;
                return new Prepared(target, copy, callback);
            } finally {
                if (!retained && copy != null && captureArgs.isInstance(copy)) releaseArgs.invoke(copy);
            }
        }

        final class Prepared {
            final Object target;
            final Object args;
            final Object callback;
            Prepared(Object target, Object args, Object callback) { this.target = target; this.args = args; this.callback = callback; }
            void invoke() throws Exception { captureDisplay.invoke(target, 0, args, callback); }
            void release() throws Exception { releaseArgs.invoke(args); }
        }
    }

    private static final class CaptureGeometry {
        final Rect crop;
        final float scaleX;
        final float scaleY;
        CaptureGeometry(Rect crop, float scaleX, float scaleY) { this.crop = crop; this.scaleX = scaleX; this.scaleY = scaleY; }

        static CaptureGeometry resolve(int displayWidth, int displayHeight, int rotation, Rect source,
                                       int width, int height, float frameScaleX, float frameScaleY,
                                       boolean installation, boolean overlays, boolean video, boolean forRotation, int mode) {
            if (displayWidth <= 0 || displayHeight <= 0 || displayWidth >= 16384 || displayHeight >= 16384
                    || rotation != 0 || source == null || width <= 0 || height <= 0
                    || frameScaleX != 1f || frameScaleY != 1f || installation || overlays || video || forRotation || mode != 0) return null;
            Rect crop = new Rect(source);
            if (crop.isEmpty()) {
                if (crop.left != 0 || crop.top != 0 || crop.right != 0 || crop.bottom != 0) return null;
                crop.set(0, 0, displayWidth, displayHeight);
            }
            if (crop.left < 0 || crop.top < 0 || crop.right > displayWidth || crop.bottom > displayHeight) return null;
            // Explicit output sizes are proven inputs on this route. Keep the
            // vendor's unproven zero-size defaulting on its original JNI path.
            int outputWidth = width;
            int outputHeight = height;
            if (outputWidth <= 0 || outputHeight <= 0 || outputWidth >= 16384 || outputHeight >= 16384) return null;
            float scaleX = exactScale(crop.width(), outputWidth), scaleY = exactScale(crop.height(), outputHeight);
            return Float.isFinite(scaleX) && Float.isFinite(scaleY) ? new CaptureGeometry(crop, scaleX, scaleY) : null;
        }

        private static float exactScale(int source, int output) {
            if (source <= 0 || output <= 0) return Float.NaN;
            float scale = (float) output / source;
            // SurfaceFlinger truncates the float product to integer output pixels.
            // A bounded ULP correction keeps the requested size exact, or declines.
            for (int step = 0; step < 4 && (int) (source * scale) < output; step++) scale = Math.nextUp(scale);
            return scale > 0 && Float.isFinite(scale) && (int) (source * scale) == output ? scale : Float.NaN;
        }
    }

    /**
     * This adapter runs after DisplayManagerService has checked the capture request.
     * Change its creation flags, rather than only the SurfaceFlinger factory boolean,
     * so the display's framework metadata and secure output property remain consistent.
     * Existing displays retain their creation properties until recording is restarted.
     */
    private void installRecordingVirtualDisplays(ClassLoader loader) {
        Class<?> adapter = probeClass("com.android.server.display.VirtualDisplayAdapter", loader);
        if (adapter == null) return;
        installDisplayDestructionTracking(loader);
        Method contextGetter = recordingContextGetter(adapter);
        Field contextField = contextGetter == null ? probeField(adapter, "mContext", Context.class) : null;
        if (contextGetter == null && contextField == null) {
            info("zero recording owner verification context matches; running arbitration retains original window filtering");
        }
        int secureFlag = recordingSecureFlag(loader);
        int matched = 0;
        int hooked = 0;
        for (Method method : methods(adapter)) {
            if (!method.getName().equals("createVirtualDisplayLocked")) continue;
            RecordingArguments arguments = recordingArguments(method);
            if (arguments == null) {
                info("skip unsupported recording display signature " + method.toGenericString());
                continue;
            }
            matched++;
            AtomicInteger samples = new AtomicInteger();
            if (installRecording(method, chain -> {
                Boolean previousMarker = vendorSecureDisplayCreation.get();
                boolean callerScope = false;
                vendorSecureDisplayCreation.set(false);
                try {
                if (chain.getArg(arguments.projection) == null) return chain.proceed();
                if (!beginCallerScope()) throw new CapturePolicyFailure("Capture module is being reloaded", null);
                callerScope = true;
                CapturePolicyBroker.Lease lease;
                Object[] replacement;
                int before;
                int after;
                int ownerUid;
                String ownerPackage;
                boolean markerAllowed;
                try {
                    ownerUid = (Integer) chain.getArg(arguments.ownerUid);
                    ownerPackage = (String) chain.getArg(arguments.ownerPackage);
                    policyBroker.observeAdapter(chain.getThisObject());
                    replacement = chain.getArgs().toArray();
                    before = (Integer) replacement[arguments.flags];
                    boolean[] flagAllowed = {true};
                    int capturedOwnerUid = ownerUid;
                    String capturedOwnerPackage = ownerPackage;
                    lease = policyBroker.beginQualified("PROJECTION", () -> {
                        flagAllowed[0] = false;
                        flagAllowed[0] = recordingToolAllowed(chain.getThisObject(), contextGetter, contextField,
                                capturedOwnerUid, capturedOwnerPackage, KEY_FLAG_SECURE_TOOLS);
                        return recordingToolAllowed(chain.getThisObject(), contextGetter, contextField,
                                capturedOwnerUid, capturedOwnerPackage, KEY_SKIP_SCREENSHOT_TOOLS);
                    }, ownerUid);
                    if (lease.arbitrating && !displayDestructionTracked) {
                        lease.abandon();
                        lease = policyBroker.noOp("PROJECTION");
                    }
                    markerAllowed = recordingSecureEnabled() && (!lease.arbitrating || flagAllowed[0]);
                    after = markerAllowed ? before | secureFlag : before;
                    replacement[arguments.flags] = after;
                } catch (Throwable error) {
                    runtimeError(method, "projection arbitration unavailable; original creation retained", error);
                    ownerUid = (Integer) chain.getArg(arguments.ownerUid);
                    ownerPackage = (String) chain.getArg(arguments.ownerPackage);
                    replacement = chain.getArgs().toArray();
                    before = (Integer) replacement[arguments.flags];
                    markerAllowed = recordingSecureEnabled();
                    after = markerAllowed ? before | secureFlag : before;
                    replacement[arguments.flags] = after;
                    lease = policyBroker.noOp("PROJECTION");
                }
                Object result;
                vendorSecureDisplayCreation.set(vendorSecureDisplayMarkerInstalled && markerAllowed);
                try {
                    result = chain.proceed(replacement);
                } catch (Throwable error) {
                    lease.abandon();
                    runtimeError(method, "display creation threw; completion lease retained", error);
                    throw error;
                }
                if (result == null) policyBroker.finishAfterDisplayCommit(lease, loader);
                else if (lease.arbitrating) policyBroker.retainDisplay(result, lease);
                if (samples.incrementAndGet() <= SAMPLE_LIMIT) {
                    info("projection tool=" + ownerPackage + " uid=" + ownerUid
                            + " flags=0x" + Integer.toHexString(before) + "->0x" + Integer.toHexString(after)
                            + " resultNull=" + (result == null));
                }
                return result;
                } finally {
                    if (previousMarker == null) vendorSecureDisplayCreation.remove();
                    else vendorSecureDisplayCreation.set(previousMarker);
                    if (callerScope) endCallerScope();
                }
            })) hooked++;
        }
        if (matched == 0) info("zero compatible recording virtual display signatures in " + adapter.getName());
        info("recording virtual display hooks matched=" + matched + " installed=" + hooked
                + "; changes apply to newly created MediaProjection displays");
    }

    private void installDisplayDestructionTracking(ClassLoader loader) {
        Class<?> device = probeClass("com.android.server.display.VirtualDisplayAdapter$VirtualDisplayDevice", loader);
        if (device == null) return;
        int matched = 0;
        for (Method method : methods(device)) {
            if (!method.getName().equals("destroyLocked") || method.getReturnType() != void.class
                    || method.getParameterCount() != 1 || method.getParameterTypes()[0] != boolean.class) continue;
            matched++;
            if (!installed.add(method)) continue;
            try {
                String id = "ScreenshotCompatDisplayLease:" + method.toGenericString();
                hook(method).setId(id).intercept(chain -> {
                    Object result;
                    try { result = chain.proceed(); }
                    catch (Throwable error) {
                        policyBroker.displayDestructionUncertain(chain.getThisObject());
                        throw error;
                    }
                    policyBroker.displayDestroyed(chain.getThisObject(), loader);
                    return result;
                });
                installedHookIds.add(id);
                displayDestructionTracked = true;
                info("installed display destruction commit tracking " + method.toGenericString());
            } catch (Throwable error) {
                installed.remove(method);
                warn("display destruction tracking install failed", error);
            }
        }
        if (matched == 0) info("zero compatible virtual-display destruction methods");
    }

    /** Locate typed parameters; older overloads may place width/height/density before Surface. */
    private RecordingArguments recordingArguments(Method method) {
        if (method.getReturnType().isPrimitive()) return null;
        Class<?>[] types = method.getParameterTypes();
        int projection = -1;
        int surface = -1;
        for (int i = 0; i < types.length; i++) {
            if (types[i].getName().equals("android.media.projection.IMediaProjection")) {
                if (projection != -1) return null;
                projection = i;
            }
            if (types[i].getName().equals("android.view.Surface")) {
                if (surface != -1) return null;
                surface = i;
            }
        }
        if (projection == -1 || surface == -1 || surface + 1 >= types.length
                || types[surface + 1] != int.class || projection + 2 >= types.length
                || types[projection + 1] != int.class || types[projection + 2] != String.class) return null;
        return new RecordingArguments(projection, surface, surface + 1, projection + 1, projection + 2);
    }

    private Method recordingContextGetter(Class<?> adapter) {
        for (Class<?> cursor = adapter; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                Method getter = cursor.getDeclaredMethod("getContext");
                if (!Modifier.isStatic(getter.getModifiers())
                        && Context.class.isAssignableFrom(getter.getReturnType())) {
                    getter.setAccessible(true);
                    return getter;
                }
            } catch (NoSuchMethodException missing) {
                // The context accessor normally belongs to DisplayAdapter.
            } catch (Throwable error) {
                warn("recording adapter context accessor probe failed", error);
                return null;
            }
        }
        return null;
    }

    /** Preserve the original peer across synchronous providers which clear Binder identity. */
    private void installScreenshotCallerTracking(ClassLoader loader) {
        for (String name : new String[]{
                "com.android.server.wm.WindowManagerService",
                "com.android.server.wm.OplusWindowManagerServiceEnhance",
                "com.android.server.accessibility.AccessibilityServiceConnection",
                "com.android.server.accessibility.AbstractAccessibilityServiceConnection"}) {
            Class<?> owner = probeClass(name, loader);
            if (owner == null) continue;
            int matched = 0;
            for (Method method : methods(owner)) {
                String member = method.getName();
                if (!member.equals("captureDisplay") && !member.equals("captureLayers")
                        && !member.equals("screenCapture") && !member.equals("takeScreenshot")) continue;
                matched++;
                if (!installed.add(method)) continue;
                try {
                    String id = "ScreenshotCompatCaller:" + method.toGenericString();
                    hook(method).setId(id).intercept(chain -> {
                        if (!beginCallerScope()) throw new CapturePolicyFailure("Capture module is being reloaded", null);
                        try {
                            CaptureCaller previous = screenshotCallerUid.get();
                            if (previous == null) screenshotCallerUid.set(new CaptureCaller(Binder.getCallingUid(), null));
                            try {
                                return chain.proceed();
                            } finally {
                                if (previous == null) screenshotCallerUid.remove();
                                else screenshotCallerUid.set(previous);
                            }
                        } finally { endCallerScope(); }
                    });
                    installedHookIds.add(id);
                    info("installed screenshot caller tracking " + method.toGenericString());
                } catch (Throwable error) {
                    installed.remove(method);
                    warn("screenshot caller tracking install failed " + method.toGenericString(), error);
                }
            }
            if (matched == 0) info("zero screenshot caller tracking matches in " + name);
        }
    }

    /** Discover real Binder capture entries from their protocol types, not OEM method names. */
    private void installTypedWindowManagerCallerTracking(ClassLoader loader) {
        Class<?> windowManager = probeClass("com.android.server.wm.WindowManagerService", loader);
        Class<?> contract = probeClass("android.view.IWindowManager", loader);
        if (windowManager == null || contract == null || !contract.isInterface()
                || !contract.isAssignableFrom(windowManager)) return;
        int matched = 0;
        try {
            for (Method declaration : contract.getMethods()) {
                if (declaration.getReturnType() != void.class && declaration.getReturnType() != int.class) continue;
                Class<?> args = null;
                for (Class<?> type : declaration.getParameterTypes()) {
                    if (captureArgsType(type)) {
                        if (args != null) { args = null; break; }
                        args = type;
                    }
                }
                if (args == null) continue;
                int listeners = 0;
                for (Class<?> type : declaration.getParameterTypes()) if (captureListenerType(args, type)) listeners++;
                if (listeners != 1) continue;
                Method method = windowManager.getMethod(declaration.getName(), declaration.getParameterTypes());
                if (method.getReturnType() != declaration.getReturnType() || Modifier.isStatic(method.getModifiers())) continue;
                matched++;
                installCallerHook(method, chain -> {
                    if (!beginCallerScope()) throw new CapturePolicyFailure("Capture module is being reloaded", null);
                    try {
                        CaptureCaller previous = screenshotCallerUid.get();
                        if (previous == null) screenshotCallerUid.set(new CaptureCaller(Binder.getCallingUid(), null));
                        try { return chain.proceed(); }
                        finally {
                            if (previous == null) screenshotCallerUid.remove(); else screenshotCallerUid.set(previous);
                        }
                    } finally { endCallerScope(); }
                });
            }
        } catch (Throwable error) {
            warn("typed WindowManager capture entry discovery failed; named compatibility paths retained", error);
        }
        if (matched == 0) info("zero typed IWindowManager capture caller entries");
    }

    /** Preserve caller identity across the verified delayed exclude-layers capture path. */
    private void installDeferredScreenshotCallerTracking(ClassLoader loader) {
        Class<?> windowManager = probeClass("com.android.server.wm.WindowManagerService", loader);
        if (windowManager == null) return;
        List<Method> entries = new ArrayList<>();
        List<Method> callbacks = new ArrayList<>();
        for (Method method : methods(windowManager)) {
            Class<?>[] types = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class
                    || types.length != 4 || types[0] != int.class || !captureArgsType(types[1])) continue;
            if (method.getName().equals("captureDisplayExcludeLayers")
                    && captureListenerType(types[1], types[2]) && types[3] == String[].class) entries.add(method);
            if (method.getName().startsWith("lambda$captureDisplayExcludeLayers$")
                    && types[2] == String[].class && captureListenerType(types[1], types[3])) callbacks.add(method);
        }
        if (entries.size() != 1 || callbacks.size() != 1) {
            info("zero unique deferred screenshot caller path: entries=" + entries.size() + " callbacks=" + callbacks.size());
            return;
        }
        Method entry = entries.get(0);
        Method callback = callbacks.get(0);
        AtomicInteger missingSamples = new AtomicInteger();
        if (!installCallerHook(callback, chain -> {
            if (!beginCallerScope()) return null;
            try {
                DeferredCaptureIdentity.Ticket<CaptureCaller> ticket = deferredScreenshotCallers.take(chain.getArg(1), chain.getArg(3));
                if (ticket == null) {
                    if (missingSamples.incrementAndGet() <= SAMPLE_LIMIT) {
                        info("deferred screenshot denied: caller association missing or ambiguous; original listener.onError is not invoked");
                    }
                    return null;
                }
                CaptureCaller previous = screenshotCallerUid.get();
                DeferredCaptureIdentity.Ticket<CaptureCaller> previousRequest = screenshotRequest.get();
                screenshotCallerUid.set(ticket.caller);
                screenshotRequest.set(ticket);
                try {
                    return chain.proceed();
                } catch (CapturePolicyFailure | CaptureSubmissionFailure denied) {
                    // This callback runs on the WMS Handler. Report controlled capture failure
                    // without killing that thread or invoking native onError on a Binder proxy.
                    runtimeError(callback, "deferred screenshot failed closed", denied);
                    return null;
                } finally {
                    if (previous == null) screenshotCallerUid.remove(); else screenshotCallerUid.set(previous);
                    if (previousRequest == null) screenshotRequest.remove(); else screenshotRequest.set(previousRequest);
                }
            } finally { endCallerScope(); }
        })) return;
        AtomicInteger associationSamples = new AtomicInteger();
        installCallerHook(entry, chain -> {
            if (!beginCallerScope()) throw new CapturePolicyFailure("Capture module is being reloaded", null);
            try {
                CaptureCaller previous = screenshotCallerUid.get();
                CaptureCaller caller = previous != null ? previous : new CaptureCaller(Binder.getCallingUid(), null);
                DeferredCaptureIdentity.Ticket<CaptureCaller> previousRequest = screenshotRequest.get();
                DeferredCaptureIdentity.Ticket<CaptureCaller> ticket = deferredScreenshotCallers.record(chain.getArg(1), chain.getArg(2), caller);
                screenshotCallerUid.set(caller);
                if (ticket == null) screenshotRequest.remove(); else screenshotRequest.set(ticket);
                if (ticket == null && associationSamples.incrementAndGet() <= SAMPLE_LIMIT) {
                    info("deferred screenshot identity capacity/ambiguity: asynchronous capture will fail closed");
                }
                try {
                    // The original READ_FRAME_BUFFER permission check and scheduling stay intact.
                    return chain.proceed();
                } catch (Throwable error) {
                    if (ticket != null && !ticket.nativeInvoked) deferredScreenshotCallers.markUncertain(ticket);
                    throw error;
                } finally {
                    if (ticket != null && ticket.nativeInvoked) deferredScreenshotCallers.discard(ticket);
                    if (previous == null) screenshotCallerUid.remove(); else screenshotCallerUid.set(previous);
                    if (previousRequest == null) screenshotRequest.remove(); else screenshotRequest.set(previousRequest);
                }
            } finally { endCallerScope(); }
        });
    }

    private boolean installCallerHook(Method method, XposedInterface.Hooker hooker) {
        if (!installed.add(method)) return false;
        try {
            String id = "ScreenshotCompatCaller:" + method.toGenericString();
            hook(method).setId(id).intercept(hooker);
            installedHookIds.add(id);
            info("installed typed deferred screenshot caller tracking " + method.toGenericString());
            return true;
        } catch (Throwable error) {
            installed.remove(method);
            warn("deferred screenshot caller hook install failed", error);
            return false;
        }
    }

    private boolean beginCallerScope() {
        synchronized (captureArgsInUse) {
            if (captureHooksRetiring) return false;
            activeScreenshotCallers++;
            return true;
        }
    }

    private void endCallerScope() {
        synchronized (captureArgsInUse) { activeScreenshotCallers--; }
    }

    private static boolean captureArgsType(Class<?> type) {
        for (String owner : CAPTURE_CLASSES) if (type.getName().equals(owner + "$CaptureArgs")) return true;
        return false;
    }

    private static boolean captureListenerType(Class<?> args, Class<?> listener) {
        String owner = args.getName().substring(0, args.getName().length() - "$CaptureArgs".length());
        return listener.getName().equals(owner + "$ScreenCaptureListener");
    }

    private void installAccessibilityWindowCallerTracking(ClassLoader loader) {
        Class<?> connection = probeClass("com.android.server.accessibility.AbstractAccessibilityServiceConnection", loader);
        if (connection == null) return;
        Field serviceInfo = probeField(connection, "mAccessibilityServiceInfo", AccessibilityServiceInfo.class);
        if (serviceInfo == null) {
            info("zero bound accessibility-service identity fields; no window callback tracking installed");
            return;
        }
        Set<Class<?>> candidates = new HashSet<>();
        try {
            Collections.addAll(candidates, connection.getDeclaredClasses());
        } catch (Throwable error) { warn("accessibility callback class enumeration failed", error); }
        // Anonymous-class numbering varies with framework compilation. A name is
        // only a candidate; the callback superclass, signature and outer type must match.
        for (int index = 1; index <= 64; index++) {
            try {
                candidates.add(Class.forName(connection.getName() + "$" + index, false, loader));
            } catch (ClassNotFoundException missing) {
                // Unused anonymous-class numbers are expected.
            } catch (Throwable error) {
                warn("accessibility callback candidate probe failed index=" + index, error);
            }
        }
        int matched = 0;
        for (Class<?> candidate : candidates) {
            boolean callback = false;
            for (Class<?> parent = candidate.getSuperclass(); parent != null; parent = parent.getSuperclass()) {
                if (parent.getName().equals("android.view.accessibility.IWindowSurfaceInfoCallback$Stub")) {
                    callback = true;
                    break;
                }
            }
            if (!callback) continue;
            Field outer = null;
            try {
                for (Field field : candidate.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && connection.isAssignableFrom(field.getType())) {
                        if (outer != null) { outer = null; break; }
                        field.setAccessible(true);
                        outer = field;
                    }
                }
            } catch (Throwable error) { warn("accessibility callback outer-instance probe failed", error); }
            if (outer == null) continue;
            Field boundConnection = outer;
            for (Method method : methods(candidate)) {
                Class<?>[] types = method.getParameterTypes();
                if (!method.getName().equals("provideWindowSurfaceInfo") || method.getReturnType() != void.class
                        || types.length != 3 || types[0] != int.class || types[1] != int.class
                        || !types[2].getName().equals("android.view.SurfaceControl")) continue;
                matched++;
                if (!installed.add(method)) continue;
                try {
                    String id = "ScreenshotCompatAccessibilityCaller:" + method.toGenericString();
                    AtomicInteger samples = new AtomicInteger();
                    hook(method).setId(id).intercept(chain -> {
                        if (!beginCallerScope()) return null;
                        try {
                            CaptureCaller previous = screenshotCallerUid.get();
                            CaptureCaller caller = new CaptureCaller(-1, null);
                            try {
                                Object owner = boundConnection.get(chain.getThisObject());
                                AccessibilityServiceInfo info = (AccessibilityServiceInfo) serviceInfo.get(owner);
                                android.content.pm.ResolveInfo resolved = info == null ? null : info.getResolveInfo();
                                if (resolved != null && resolved.serviceInfo != null && resolved.serviceInfo.applicationInfo != null) {
                                    caller = new CaptureCaller(resolved.serviceInfo.applicationInfo.uid, resolved.serviceInfo.packageName);
                                }
                                if (samples.incrementAndGet() <= SAMPLE_LIMIT) {
                                    info("accessibility window capture boundToolUid=" + caller.uid + " package=" + caller.packageName);
                                }
                            } catch (Throwable error) {
                                runtimeError(method, "bound accessibility tool identity unavailable; original capture retained", error);
                            }
                            screenshotCallerUid.set(caller);
                            try {
                                // processUid in this callback belongs to the captured content.
                                return chain.proceed();
                            } finally {
                                if (previous == null) screenshotCallerUid.remove();
                                else screenshotCallerUid.set(previous);
                            }
                        } finally { endCallerScope(); }
                    });
                    installedHookIds.add(id);
                    info("installed bound accessibility-window caller tracking " + method.toGenericString());
                } catch (Throwable error) {
                    installed.remove(method);
                    warn("bound accessibility-window caller tracking install failed", error);
                }
            }
        }
        if (matched == 0) info("zero compatible bound accessibility-window callbacks");
    }

    private void installAccessibilitySecureWindowCheck(ClassLoader loader) {
        Class<?> connection = probeClass("com.android.server.accessibility.AbstractAccessibilityServiceConnection", loader);
        if (connection == null) return;
        int matched = 0;
        for (Method method : methods(connection)) {
            if (!method.getName().equals("canCaptureSecureLayers") || method.getReturnType() != boolean.class
                    || method.getParameterCount() != 0) continue;
            matched++;
            install(method, chain -> {
                // Accessibility capability/connection checks remain in the original caller.
                // This private helper only controls its secure-window content precheck.
                chain.proceed();
                return true;
            });
        }
        if (matched == 0) info("zero accessibility secure-window precheck matches");
    }

    private CaptureCaller screenshotIdentity() {
        if (!hostSystemServer) return new CaptureCaller(android.os.Process.myUid(), processLabel);
        CaptureCaller tracked = screenshotCallerUid.get();
        return tracked != null ? tracked : new CaptureCaller(Binder.getCallingUid(), null);
    }

    private boolean screenshotToolAllowed(Method method, String mechanismKey) {
        try {
            if (!hostSystemServer) {
                // Only system-app hosts reach this branch; application scope remains unchanged.
                return captureToolAllowed(processLabel, mechanismKey);
            }
            CaptureCaller tracked = screenshotCallerUid.get();
            int uid = tracked != null ? tracked.uid : Binder.getCallingUid();
            if (uid < 0) return false;
            Context context = screenshotSystemContext();
            if (context == null) return false;
            long identity = Binder.clearCallingIdentity();
            try {
                PackageManager manager = context.getPackageManager();
                String[] packages = manager.getPackagesForUid(uid);
                if (packages == null) return false;
                ToolPolicy policy = toolPolicy(mechanismKey);
                if (policy == null || packages.length == 0) return false;
                if (tracked != null && tracked.packageName != null) {
                    for (String packageName : packages) {
                        if (tracked.packageName.equals(packageName)) return policy.allows(packageName);
                    }
                    return false;
                }
                for (String packageName : packages) {
                    boolean allowed = policy.allows(packageName);
                    // A Binder uid can belong to several signed packages. Exclusion
                    // wins for that uid when no validated package argument is available.
                    if (policy.black && !allowed) return false;
                    if (!policy.black && allowed) return true;
                }
                return policy.black;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        } catch (Throwable error) {
            runtimeError(method, "screenshot tool identity check; original call retained", error);
            return false;
        }
    }

    private boolean screenshotToolGate(Method method, String mechanismKey) {
        return !arbitrationEnabled() || !policyBroker.arbitrationRunning()
                || screenshotToolAllowed(method, mechanismKey);
    }

    private boolean screenshotMetadataToolGate(Method method, String mechanismKey) {
        return !arbitrationEnabled() || !policyBroker.arbitrationRunningForMetadata()
                || screenshotToolAllowed(method, mechanismKey);
    }

    private Context screenshotSystemContext() throws Exception {
        Context cached = systemContext;
        if (cached != null) return cached;
        Class<?> thread = Class.forName("android.app.ActivityThread", false, hostClassLoader);
        Method current = thread.getDeclaredMethod("currentActivityThread");
        Method getContext = thread.getDeclaredMethod("getSystemContext");
        current.setAccessible(true);
        getContext.setAccessible(true);
        Object instance = current.invoke(null);
        if (instance == null) return null;
        cached = (Context) getContext.invoke(instance);
        systemContext = cached;
        return cached;
    }

    /** The adapter's owner is the collecting tool, never the app visible in its output. */
    private boolean recordingToolAllowed(Object adapter, Method getter, Field field,
                                         int ownerUid, String ownerPackage, String mechanismKey) throws Exception {
        if (adapter == null || ownerUid < 0 || ownerPackage == null || ownerPackage.isEmpty()
                || (getter == null && field == null)) return false;
        Context context = (Context) (getter != null ? getter.invoke(adapter) : field.get(adapter));
        if (context == null) return false;
        systemContext = context;
        // Only package lookup uses the host identity. Restore the caller before
        // the original adapter operation; this does not alter capture authorization.
        long identity = Binder.clearCallingIdentity();
        try {
            PackageManager manager = context.getPackageManager();
            String[] packages = manager.getPackagesForUid(ownerUid);
            boolean belongs = false;
            if (packages != null) {
                for (String name : packages) {
                    if (ownerPackage.equals(name)) { belongs = true; break; }
                }
            }
            if (!belongs) return false;
            return captureToolAllowed(ownerPackage, mechanismKey);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean captureToolAllowed(String packageName, String mechanismKey) {
        try {
            ToolPolicy policy = toolPolicy(mechanismKey);
            return policy != null && policy.allows(packageName);
        } catch (Throwable error) {
            if (loggedRuntimeErrors.add("capture-tool-preferences")) {
                warn("capture tool preferences unavailable; original capture behavior retained", error);
            }
            return false;
        }
    }

    private ToolPolicy toolPolicy(String mechanismKey) {
        SharedPreferences current = settings;
        if (current == null) return null;
        boolean black = "BLACK".equals(current.getString(KEY_TOOL_MODE, "BLACK"));
        SharedPreferences group = black ? blackToolSettings : whiteToolSettings;
        if (group == null) return null;
        Map<String, ?> values = group.getAll();
        Object selected = values.get(KEY_CAPTURE_TOOLS);
        if (selected != null && !(selected instanceof Set<?>)) throw new ClassCastException(KEY_CAPTURE_TOOLS);
        Set<?> selectedPackages = selected == null ? Collections.emptySet() : (Set<?>) selected;
        Object mechanisms = values.get(mechanismKey);
        if (!values.containsKey(mechanismKey)) {
            // During an app upgrade, remote preferences may still contain purpose
            // selections. Never reinterpret screenshot as FLAG or recording as SKIP.
            Object screenshot = values.containsKey(KEY_SCREENSHOT_TOOLS) ? values.get(KEY_SCREENSHOT_TOOLS) : selected;
            Object recording = values.containsKey(KEY_RECORDING_TOOLS) ? values.get(KEY_RECORDING_TOOLS) : selected;
            if (screenshot != null && !(screenshot instanceof Set<?>)) throw new ClassCastException(KEY_SCREENSHOT_TOOLS);
            if (recording != null && !(recording instanceof Set<?>)) throw new ClassCastException(KEY_RECORDING_TOOLS);
            Set<Object> merged = new HashSet<>(screenshot == null ? Collections.emptySet() : (Set<?>) screenshot);
            Set<?> other = recording == null ? Collections.emptySet() : (Set<?>) recording;
            if (black) merged.addAll(other); else merged.retainAll(other);
            merged.retainAll(selectedPackages);
            mechanisms = merged;
        }
        if (mechanisms != null && !(mechanisms instanceof Set<?>)) throw new ClassCastException(mechanismKey);
        return new ToolPolicy(black, selected == null ? Collections.emptySet() : (Set<?>) selected,
                mechanisms == null ? Collections.emptySet() : (Set<?>) mechanisms);
    }

    private int recordingSecureFlag(ClassLoader loader) {
        Class<?> manager = probeClass("android.hardware.display.DisplayManager", loader);
        Field field = manager == null ? null
                : probeField(manager, "VIRTUAL_DISPLAY_FLAG_SECURE", int.class);
        if (field != null && Modifier.isStatic(field.getModifiers())) {
            try {
                int value = field.getInt(null);
                if (value > 0 && Integer.bitCount(value) == 1) return value;
                info("unexpected secure virtual display flag=" + value + "; use public flag=4");
            } catch (Throwable error) {
                warn("secure virtual display flag read failed; use public flag=4", error);
            }
        } else {
            info("secure virtual display constant absent; use public flag=4");
        }
        return 4;
    }

    private void installScreenshotWindowCheck(ClassLoader loader) {
        Class<?> window = probeClass("com.android.server.wm.WindowState", loader);
        if (window == null) return;
        int matched = 0;
        for (Method method : methods(window)) {
            if (!method.getName().equals("isSecureLocked") || method.getParameterCount() != 0
                    || method.getReturnType() != boolean.class) continue;
            matched++;
            AtomicInteger samples = new AtomicInteger();
            install(method, chain -> {
                if (Boolean.TRUE.equals(originalWindowPolicyCheck.get())) return chain.proceed();
                boolean screenshotCaller = false;
                try {
                    screenshotCaller = isScreenshotCheck();
                } catch (Throwable error) {
                    runtimeError(method, "screenshot caller check; original call retained", error);
                }
                if (!screenshotCaller) return chain.proceed();
                Object original = chain.proceed();
                if (samples.incrementAndGet() <= SAMPLE_LIMIT) {
                    info("WindowState.isSecureLocked screenshot caller original=" + original + " returned=false");
                }
                return false;
            });
        }
        if (matched == 0) info("zero isSecureLocked matches in " + window.getName());
    }

    /** A verified vendor precheck reads FLAG_SECURE directly before the standard capture API. */
    private void installVendorWindowProtectionCheck(ClassLoader loader) {
        Class<?> implementation = probeClass("com.android.server.wm.WindowManagerServiceImpl", loader);
        if (implementation == null) return;
        try {
            Class<?> contract = Class.forName("com.android.server.wm.WindowManagerServiceStub", false, loader);
            Class<?> root = Class.forName("com.android.server.wm.RootWindowContainer", false, loader);
            if (!contract.isAssignableFrom(implementation)) {
                info("zero verified window protection implementation contracts");
                return;
            }
            Method method = VendorWindowProtectionProfile.requiredMethod(implementation,
                    "notAllowCaptureDisplay", boolean.class, root, int.class);
            if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
                info("zero public window protection check signatures");
                return;
            }
            VendorWindowProtectionProfile profile = new VendorWindowProtectionProfile(loader, implementation);
            AtomicInteger samples = new AtomicInteger();
            install(method, chain -> {
                // Only the tracked WMS capture request supplies a collecting-tool identity.
                if (screenshotCallerUid.get() == null) return chain.proceed();
                Boolean previous = originalWindowPolicyCheck.get();
                Object original;
                originalWindowPolicyCheck.set(true);
                try { original = chain.proceed(); }
                finally {
                    if (previous == null) originalWindowPolicyCheck.remove();
                    else originalWindowPolicyCheck.set(previous);
                }
                if (!Boolean.TRUE.equals(original)) return original;
                try {
                    if (profile.isFlagOnlyRejection(chain.getArg(0), (Integer) chain.getArg(1), chain.getThisObject())) {
                        if (samples.incrementAndGet() <= SAMPLE_LIMIT) info("window capture precheck FLAG-only rejection bypassed; other policies retained");
                        return false;
                    }
                } catch (Throwable error) {
                    runtimeError(method, "window protection reason verification; original rejection retained", error);
                }
                return original;
            });
        } catch (ReflectiveOperationException | LinkageError missing) {
            info("zero complete window protection reason profile; original check retained: " + missing.getClass().getSimpleName());
        } catch (Throwable error) {
            warn("window protection profile probe failed; original check retained", error);
        }
    }

    /** Read-only equivalent of the verified predicate with only the FLAG bit omitted. */
    private static final class VendorWindowProtectionProfile {
        final Class<?> windowType;
        final Class<?> predicateType;
        final Method getDisplay, forAllWindows, visible, hasSurface, onScreen, disableSecure;
        final Method owningPackage, owningUid, windowToken, sensitiveBlock, policyInstance, policyAllowed, ctsVerifier;
        final Field animator, attributes, flags, attributePackage, windowService, sensitivePackages, showUser;

        VendorWindowProtectionProfile(ClassLoader loader, Class<?> implementation) throws ReflectiveOperationException {
            Class<?> root = Class.forName("com.android.server.wm.RootWindowContainer", false, loader);
            Class<?> display = Class.forName("com.android.server.wm.DisplayContent", false, loader);
            Class<?> service = Class.forName("com.android.server.wm.WindowManagerService", false, loader);
            Class<?> animation = Class.forName("com.android.server.wm.WindowStateAnimator", false, loader);
            Class<?> attrs = Class.forName("android.view.WindowManager$LayoutParams", false, loader);
            Class<?> sensitive = Class.forName("com.android.server.wm.SensitiveContentPackages", false, loader);
            Class<?> policy = Class.forName("android.app.admin.DevicePolicyCache", false, loader);
            Class<?> binder = Class.forName("android.os.IBinder", false, loader);
            windowType = Class.forName("com.android.server.wm.WindowState", false, loader);
            predicateType = Class.forName("com.android.internal.util.ToBooleanFunction", false, loader);
            if (!predicateType.isInterface()) throw new NoSuchMethodException("window predicate interface");
            requiredMethod(predicateType, "apply", boolean.class, Object.class);
            getDisplay = requiredMethod(root, "getDisplayContent", display, int.class);
            forAllWindows = requiredMethod(display, "forAllWindows", boolean.class, predicateType, boolean.class);
            visible = requiredMethod(windowType, "isVisible", boolean.class);
            onScreen = requiredMethod(windowType, "isOnScreen", boolean.class);
            hasSurface = requiredMethod(animation, "hasSurface", boolean.class);
            disableSecure = requiredMethod(service, "getDisableSecureWindows", boolean.class);
            owningPackage = requiredMethod(windowType, "getOwningPackage", String.class);
            owningUid = requiredMethod(windowType, "getOwningUid", int.class);
            windowToken = requiredMethod(windowType, "getWindowToken", binder);
            sensitiveBlock = requiredMethod(sensitive, "shouldBlockScreenCaptureForApp", boolean.class, String.class, int.class, binder);
            policyInstance = requiredMethod(policy, "getInstance", policy);
            if (!Modifier.isStatic(policyInstance.getModifiers())) throw new NoSuchMethodException("static policy cache");
            policyAllowed = requiredMethod(policy, "isScreenCaptureAllowed", boolean.class, int.class);
            ctsVerifier = requiredMethod(implementation, "isCtsVerifier", boolean.class, String.class);
            animator = requiredField(windowType, "mWinAnimator", animation);
            attributes = requiredField(windowType, "mAttrs", attrs);
            flags = requiredField(attrs, "flags", int.class);
            attributePackage = requiredField(attrs, "packageName", String.class);
            windowService = requiredField(windowType, "mWmService", service);
            sensitivePackages = requiredField(service, "mSensitiveContentPackages", sensitive);
            showUser = requiredField(windowType, "mShowUserId", int.class);
        }

        boolean isFlagOnlyRejection(Object root, int displayId, Object implementation) throws Exception {
            Object display = getDisplay.invoke(root, displayId);
            if (display == null) return false;
            boolean[] result = new boolean[3]; // FLAG present, other policy blocks, verification failed.
            Throwable[] failure = new Throwable[1];
            Object predicate = Proxy.newProxyInstance(predicateType.getClassLoader(), new Class<?>[]{predicateType},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            if (method.getName().equals("toString")) return "ScreenshotCompatWindowPolicy";
                            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                            if (method.getName().equals("equals")) return proxy == args[0];
                        }
                        try {
                            if (!method.getName().equals("apply") || args == null || args.length != 1
                                    || !windowType.isInstance(args[0])) throw new IllegalStateException("unexpected window predicate");
                            Object window = args[0];
                            if (!Boolean.TRUE.equals(visible.invoke(window))
                                    || !Boolean.TRUE.equals(hasSurface.invoke(animator.get(window)))
                                    || !Boolean.TRUE.equals(onScreen.invoke(window))) return false;
                            Object attrs = attributes.get(window);
                            result[0] |= (flags.getInt(attrs) & 8192) != 0;
                            Object service = windowService.get(window);
                            boolean other = false;
                            if (!Boolean.TRUE.equals(disableSecure.invoke(service))) {
                                Object sensitive = sensitivePackages.get(service);
                                other = Boolean.TRUE.equals(sensitiveBlock.invoke(sensitive,
                                        owningPackage.invoke(window), owningUid.invoke(window), windowToken.invoke(window)));
                                if (!other) {
                                    Object cache = policyInstance.invoke(null);
                                    other = !Boolean.TRUE.equals(policyAllowed.invoke(cache, showUser.getInt(window)));
                                }
                            }
                            if (other && !Boolean.TRUE.equals(ctsVerifier.invoke(implementation, attributePackage.get(attrs)))) {
                                result[1] = true;
                                return true;
                            }
                            return false;
                        } catch (Throwable error) {
                            result[2] = true;
                            failure[0] = error;
                            return true;
                        }
                    });
            forAllWindows.invoke(display, predicate, false);
            if (failure[0] != null) throw new IllegalStateException("Window protection verification failed", failure[0]);
            return result[0] && !result[1] && !result[2];
        }

        static Method requiredMethod(Class<?> type, String name, Class<?> result, Class<?>... parameters) throws NoSuchMethodException {
            for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
                try {
                    Method method = cursor.getDeclaredMethod(name, parameters);
                    if (method.getReturnType() != result) throw new NoSuchMethodException(name + " return type");
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException missing) {
                    if (cursor == Object.class) throw missing;
                }
            }
            throw new NoSuchMethodException(type.getName() + "." + name);
        }

        static Field requiredField(Class<?> type, String name, Class<?> valueType) throws NoSuchFieldException {
            for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
                try {
                    Field field = cursor.getDeclaredField(name);
                    if (field.getType() != valueType) throw new NoSuchFieldException(name + " type");
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException missing) {
                    if (cursor == Object.class) throw missing;
                }
            }
            throw new NoSuchFieldException(type.getName() + "." + name);
        }
    }

    /** Keep secure Surface creation unchanged; only alter an identifiable screenshot check. */
    private static boolean isScreenshotCheck() {
        boolean screenshot = false;
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String owner = frame.getClassName();
            if (!owner.startsWith("com.android.server.wm.")) continue;
            String method = frame.getMethodName();
            if (method.equals("createSurfaceLocked") || method.equals("setInitialSurfaceControlProperties")) {
                return false;
            }
            String lowerOwner = owner.toLowerCase(java.util.Locale.ROOT);
            String lowerMethod = method.toLowerCase(java.util.Locale.ROOT);
            if (lowerOwner.contains("screenshot") || lowerOwner.contains("longshot")
                    || lowerMethod.contains("screenshot") || lowerMethod.contains("longshot")
                    || method.equals("captureDisplay") || method.equals("captureLayers")) {
                screenshot = true;
            }
        }
        return screenshot;
    }

    private void install(Method method, XposedInterface.Hooker hooker) {
        if (!installed.add(method)) {
            info("deduplicated " + method.toGenericString());
            return;
        }
        try {
            String id = "ScreenshotCompat:" + method.toGenericString();
            boolean metadataQuery = (method.getName().equals("containsSecureLayers") && method.getParameterCount() == 0)
                    || (method.getDeclaringClass().getName().equals("com.hihonor.android.graphics.BitmapEx")
                    && method.getName().equals("isContainsSecureLayers"));
            hook(method).setId(id).intercept(chain -> {
                // Read the framework-managed preference on each call. Changing this
                // toggle never requires reloading hooks or restarting the host.
                if (!secureEnabled()) return chain.proceed();
                if (hostSystemServer && method.getName().equals("isSecureLocked") && !isScreenshotCheck()) return chain.proceed();
                if (!(metadataQuery ? screenshotMetadataToolGate(method, KEY_FLAG_SECURE_TOOLS)
                        : screenshotToolGate(method, KEY_FLAG_SECURE_TOOLS))) return chain.proceed();
                return hooker.intercept(chain);
            });
            installedHookIds.add(id);
            info("installed " + method.toGenericString());
        } catch (Throwable error) {
            installed.remove(method);
            warn("install failed " + method.toGenericString(), error);
        }
    }

    /** Read-only diagnostics never perform capture qualification or broker I/O. */
    private void installDiagnostic(Method method, XposedInterface.Hooker hooker) {
        if (!installed.add(method)) return;
        try {
            String id = "ScreenshotCompat:" + method.toGenericString();
            hook(method).setId(id).intercept(hooker);
            installedHookIds.add(id);
            info("installed sampled capture diagnostic " + method.toGenericString());
        } catch (Throwable error) {
            installed.remove(method);
            warn("capture diagnostic install failed", error);
        }
    }

    /** SKIP arbitration also covers tools rejected by the window-compatibility gate. */
    private void installCapturePipeline(Method method, XposedInterface.Hooker hooker) {
        if (!installed.add(method)) return;
        try {
            String id = "ScreenshotCompat:" + method.toGenericString();
            hook(method).setId(id).intercept(hooker);
            installedHookIds.add(id);
            info("installed unconditional capture arbitration " + method.toGenericString());
        } catch (Throwable error) {
            installed.remove(method);
            warn("capture arbitration install failed " + method.toGenericString(), error);
        }
    }

    /** Every projection is arbitrated; only its secure-flag update uses the window master. */
    private boolean installRecording(Method method, XposedInterface.Hooker hooker) {
        if (!installed.add(method)) {
            info("deduplicated recording hook " + method.toGenericString());
            return false;
        }
        try {
            String id = "ScreenshotCompatRecording:" + method.toGenericString();
            hook(method).setId(id).intercept(hooker);
            installedHookIds.add(id);
            info("installed recording hook " + method.toGenericString());
            return true;
        } catch (Throwable error) {
            installed.remove(method);
            warn("recording hook install failed " + method.toGenericString(), error);
            return false;
        }
    }

    private Class<?> probeClass(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException missing) {
            info("zero class match " + name);
            return null;
        } catch (Throwable error) {
            warn("class probe failed " + name, error);
            return null;
        }
    }

    private void bindSettings() {
        policyBroker.setArbitrationEnabled(this::arbitrationEnabled);
        if (settings != null && whiteToolSettings != null && blackToolSettings != null) return;
        try {
            if (settings == null) settings = getRemotePreferences("screenshot_compat_v2");
            if (whiteToolSettings == null) whiteToolSettings = getRemotePreferences("capture_whitelist_v3");
            if (blackToolSettings == null) blackToolSettings = getRemotePreferences("capture_blacklist_v3");
            info("configuration connected; FLAG_SECURE=" + secureEnabled()
                    + " recording_FLAG_SECURE=" + recordingSecureEnabled());
        } catch (Throwable error) {
            if (!settingsErrorLogged) {
                settingsErrorLogged = true;
                warn("remote preferences unavailable; default FLAG_SECURE compatibility enabled", error);
            }
        }
    }

    private boolean secureEnabled() {
        SharedPreferences current = settings;
        if (current == null) return true;
        try {
            return current.getBoolean("disable_flag_secure", true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean arbitrationEnabled() {
        SharedPreferences current = settings;
        if (current == null) return true;
        try { return current.getBoolean("arbitration_enabled", true); }
        catch (Throwable ignored) { return true; }
    }

    private boolean recordingSecureEnabled() {
        return secureEnabled();
    }

    private boolean skipEnabled() {
        SharedPreferences current = settings;
        if (current == null) return true;
        try { return current.getBoolean("disable_skip_screenshot", true); }
        catch (Throwable ignored) { return true; }
    }

    private Method[] methods(Class<?> owner) {
        try {
            return owner.getDeclaredMethods();
        } catch (Throwable error) {
            warn("method enumeration failed " + owner.getName(), error);
            return new Method[0];
        }
    }

    private Field probeField(Class<?> owner, String name, Class<?> type) {
        for (Class<?> cursor = owner; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                Field field = cursor.getDeclaredField(name);
                if (field.getType() != type) {
                    info("field type mismatch " + cursor.getName() + "." + name + " expected=" + type.getName());
                    return null;
                }
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException missing) {
                // The capture subclasses inherit the policy field from CaptureArgs.
            } catch (Throwable error) {
                warn("field probe failed " + cursor.getName() + "." + name, error);
                return null;
            }
        }
        return null;
    }

    private void logResult(Method method, Object result) {
        try {
            String value;
            if (method.getReturnType() == int.class || method.getReturnType() == long.class
                    || method.getReturnType() == boolean.class) {
                value = "status=" + result;
            } else {
                value = "resultNull=" + (result == null);
            }
            info(method.getDeclaringClass().getName() + "." + method.getName() + " " + value);
        } catch (Throwable error) {
            runtimeError(method, "result diagnostic", error);
        }
    }

    private void runtimeError(Method method, String operation, Throwable error) {
        String key = method.toGenericString() + ":" + operation;
        if (loggedRuntimeErrors.add(key)) warn(operation + " " + method.toGenericString(), error);
    }

    private void info(String message) {
        try {
            log(Log.INFO, TAG, "[" + processLabel + "] " + message);
        } catch (Throwable ignored) {
            // Diagnostics must never interrupt the host process.
        }
    }

    private void warn(String message, Throwable error) {
        try {
            log(Log.WARN, TAG, "[" + processLabel + "] " + message, error);
        } catch (Throwable ignored) {
            // Diagnostics must never interrupt the host process.
        }
    }

    private static final class CapturePolicyFailure extends SecurityException {
        CapturePolicyFailure(String message, Throwable cause) { super(message, cause); }
    }

    private static final class CaptureSubmissionFailure extends IllegalStateException {
        CaptureSubmissionFailure(int status) { super("Capture submission failed with status " + status); }
    }

    private static final class CaptureCaller {
        final int uid;
        final String packageName;

        CaptureCaller(int uid, String packageName) {
            this.uid = uid;
            this.packageName = packageName;
        }
    }

    private static final class ToolPolicy {
        final boolean black;
        final Set<?> selected;
        final Set<?> purposes;

        ToolPolicy(boolean black, Set<?> selected, Set<?> purposes) {
            this.black = black;
            this.selected = selected;
            this.purposes = purposes;
        }

        boolean allows(String packageName) {
            boolean checked = selected.contains(packageName) && purposes.contains(packageName);
            return black ? !checked : checked;
        }
    }

    private static final class RecordingArguments {
        final int projection;
        final int surface;
        final int flags;
        final int ownerUid;
        final int ownerPackage;

        RecordingArguments(int projection, int surface, int flags, int ownerUid, int ownerPackage) {
            this.projection = projection;
            this.surface = surface;
            this.flags = flags;
            this.ownerUid = ownerUid;
            this.ownerPackage = ownerPackage;
        }
    }

    private static final class CaptureFields {
        final Field policy;
        final Field legacySecure;
        final int capturePolicy;

        CaptureFields(Field policy, Field legacySecure, int capturePolicy) {
            this.policy = policy;
            this.legacySecure = legacySecure;
            this.capturePolicy = capturePolicy;
        }
    }
}
