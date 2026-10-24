// SPDX-License-Identifier: GPL-3.0-only
package net.hawyn.screenshotfreedoma17;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Bounded, opt-in offline diagnostics. Collection never changes the module's state. */
public final class Diagnostics {
    private Diagnostics() {}
    private static final String TAG = "ScreenshotFreedomLog";
    private static final Object LOG_LOCK = new Object();
    private static final Object EXPORT_LOCK = new Object();
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static final String OPTIONS = "diagnostic_options";
    private static volatile Boolean enabled;
    private static volatile Process rootCollector;
    private static Thread.UncaughtExceptionHandler diagnosticHandler;
    private static Thread.UncaughtExceptionHandler previousHandler;
    private static final int LOG_LIMIT = 256 * 1024;
    private static final int INSTALL_LIMIT = 1024 * 1024;
    private static final long DATA_LIMIT = 12L * 1024 * 1024;
    private static final long ELF_LIMIT = 64L * 1024 * 1024;
    private static final long ELF_TOTAL_LIMIT = 3 * ELF_LIMIT;
    private static final long MODULE_BIN_LIMIT = 16L * 1024 * 1024;
    private static final String MODULE = "/data/adb/modules/enable_screenshot17fix";
    private static final String STATE = "/data/adb/enable_screenshot17fix";
    private static final String BEGIN = "__SFX17FIX_DIAG_BEGIN__|";
    private static final String END = "__SFX17FIX_DIAG_END__|";
    private static final Map<String, StateRecord> STATES = new HashMap<>();
    private static final Map<String, StateRecord> ERRORS = new HashMap<>();

    public static final class ExportResult {
        public final Uri uri;
        public final String fileName;
        /** Nonempty when some optional diagnostics were unavailable. */
        public final String warning;
        ExportResult(Uri uri, String fileName, String warning) {
            this.uri = uri; this.fileName = fileName; this.warning = warning;
        }
    }

    private static final class StateRecord {
        String value; long written;
        StateRecord(String value, long written) { this.value = value; this.written = written; }
    }

    public static void initialize(Context source) {
        if (source == null || !INITIALIZED.compareAndSet(false, true)) return;
        Context context = source.getApplicationContext();
        if (!isEnabled(context)) {
            clearPrivateDiagnostics(context);
            return;
        }
        installCrashHandler(context);
        record(context, "app.start", "version=" + BuildConfig.VERSION_NAME + " code="
                + BuildConfig.VERSION_CODE + " uid=" + android.os.Process.myUid()
                + " pid=" + android.os.Process.myPid());
    }

    /** Independent of the functional configuration and framework remote preferences. */
    public static boolean isEnabled(Context context) {
        if (context == null) return false;
        Boolean current = enabled;
        if (current != null) return current;
        synchronized (LOG_LOCK) {
            if (enabled == null) {
                try { enabled = options(context).getBoolean("enabled", false); }
                catch (Exception ignored) { enabled = false; }
            }
            return enabled;
        }
    }

    /** Run off the UI thread: disabling also removes bounded, app-private diagnostic history. */
    public static void setEnabled(Context source, boolean value) {
        if (source == null) return;
        Context context = source.getApplicationContext();
        synchronized (LOG_LOCK) {
            enabled = value;
            options(context).edit().putBoolean("enabled", value).apply();
            STATES.clear(); ERRORS.clear();
            if (!value && diagnosticHandler != null
                    && Thread.getDefaultUncaughtExceptionHandler() == diagnosticHandler) {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler);
                diagnosticHandler = null; previousHandler = null;
            }
        }
        if (value) {
            installCrashHandler(context);
            record(context, "logging.enabled", "version=" + BuildConfig.VERSION_NAME);
        } else {
            Process running = rootCollector;
            if (running != null) {
                running.destroyForcibly();
                try { running.getInputStream().close(); } catch (Exception ignored) { }
            }
            clearPrivateDiagnostics(context);
        }
    }

    private static SharedPreferences options(Context context) {
        return context.getApplicationContext().createDeviceProtectedStorageContext()
                .getSharedPreferences(OPTIONS, Context.MODE_PRIVATE);
    }

    private static void installCrashHandler(Context context) {
        try {
            synchronized (LOG_LOCK) {
                if (!isEnabled(context) || diagnosticHandler != null) return;
                Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
                previousHandler = previous;
                diagnosticHandler = (thread, error) -> {
                    recordError(context, "uncaught." + thread.getName(), error);
                    if (previous != null) previous.uncaughtException(thread, error);
                    else {
                        android.os.Process.killProcess(android.os.Process.myPid());
                        System.exit(10);
                    }
                };
                Thread.setDefaultUncaughtExceptionHandler(diagnosticHandler);
            }
        } catch (Throwable ignored) { Log.w(TAG, "Diagnostic uncaught handler was not installed"); }
    }

    /** Never visits module ownership/cache directories or public Download archives. */
    private static void clearPrivateDiagnostics(Context source) {
        synchronized (EXPORT_LOCK) {
            synchronized (LOG_LOCK) {
                if (isEnabled(source)) return;
                Context[] contexts = {source.getApplicationContext(), source.createDeviceProtectedStorageContext()};
                for (Context context : contexts) {
                    deletePrivateTree(new File(context.getFilesDir(), "diagnostics"), 0, new int[]{512});
                    deletePrivateTree(new File(context.getCacheDir(), "diagnostic-export"), 0, new int[]{512});
                }
            }
        }
    }

    private static void deletePrivateTree(File file, int depth, int[] remaining) {
        if (depth > 4 || remaining[0]-- <= 0) return;
        try {
            StructStat stat = lstat(file);
            if (OsConstants.S_ISDIR(stat.st_mode) && !OsConstants.S_ISLNK(stat.st_mode)) {
                File[] children = file.listFiles();
                if (children != null) for (File child : children) {
                    if (remaining[0] <= 0) break;
                    deletePrivateTree(child, depth + 1, remaining);
                }
            }
            file.delete();
        } catch (Exception ignored) { /* Missing or inaccessible diagnostic history is optional. */ }
    }

    /** Logging is deliberately best effort: diagnostics must not alter functional error handling. */
    public static void record(Context context, String event, String detail) {
        if (!isEnabled(context)) return;
        try {
            synchronized (LOG_LOCK) {
                if (!isEnabled(context)) return;
                File directory = diagDir(context);
                String text = timestamp() + " elapsed_ms=" + SystemClock.elapsedRealtime() + " ["
                        + cleanEvent(event) + "] " + boundedText(detail, 64 * 1024) + "\n";
                appendRotating(new File(directory, "runtime.log"), text, LOG_LIMIT, 3);
            }
        } catch (Throwable ignored) { Log.w(TAG, "Diagnostic log write failed"); }
    }

    /** Polling is throttled; a new reason/detail is always preserved immediately. */
    public static void recordState(Context context, String key, String detail) {
        if (!isEnabled(context)) return;
        try {
            synchronized (LOG_LOCK) {
                if (!isEnabled(context)) return;
                String stableKey = cleanEvent(key);
                String value = boundedText(detail, 32 * 1024);
                long now = SystemClock.elapsedRealtime();
                StateRecord old = STATES.get(stableKey);
                boolean detailedReason = stableKey.contains(".detail") || stableKey.contains(".error") || stableKey.contains(".reason");
                if (old != null && (old.value.equals(value) || !detailedReason && now - old.written < 30_000)) return;
                if (STATES.size() >= 128 && old == null) STATES.clear();
                STATES.put(stableKey, new StateRecord(value, now));
                record(context, "state." + stableKey, value);
            }
        } catch (Throwable ignored) { Log.w(TAG, "Diagnostic state write failed"); }
    }

    public static void recordError(Context context, String event, Throwable error) {
        if (!isEnabled(context)) return;
        try {
            StringWriter output = new StringWriter();
            if (error != null) error.printStackTrace(new PrintWriter(output));
            String eventName = cleanEvent(event);
            String stack = boundedText(output.toString(), 128 * 1024);
            synchronized (LOG_LOCK) {
                if (!isEnabled(context)) return;
                long now = SystemClock.elapsedRealtime();
                StateRecord old = ERRORS.get(eventName);
                // The status worker can observe the same unreadable file every second.
                if (old != null && now - old.written < 30_000 && old.value.equals(stack)) return;
                if (ERRORS.size() >= 64 && old == null) ERRORS.clear();
                ERRORS.put(eventName, new StateRecord(stack, now));
                String text = timestamp() + " elapsed_ms=" + now + " [" + eventName + "]\n" + stack;
                File directory = diagDir(context);
                rotate(new File(directory, "last-error.txt"), 2);
                writeText(new File(directory, "last-error.txt"), text);
                record(context, "error." + eventName, text);
            }
        } catch (Throwable ignored) { Log.w(TAG, "Diagnostic error write failed"); }
    }

    public static void recordInstall(Context context, String detail) {
        recordInstall(context, "complete", detail, false);
    }

    /** Successful installs never overwrite the independently retained most recent failure. */
    public static void recordInstall(Context context, String phase, String detail, boolean failed) {
        if (!isEnabled(context)) return;
        try {
            synchronized (LOG_LOCK) {
                if (!isEnabled(context)) return;
                File directory = diagDir(context);
                String text = timestamp() + " elapsed_ms=" + SystemClock.elapsedRealtime()
                        + " phase=" + cleanEvent(phase) + " failed=" + failed + "\n"
                        + boundedText(detail, INSTALL_LIMIT);
                appendRotating(new File(directory, "install.log"), text + "\n", INSTALL_LIMIT, 2);
                // A new start must not replace the result of a previously completed install.
                if (!"begin".equals(phase) && !"start".equals(phase)) {
                    writeText(new File(directory, "last-install.txt"), text);
                }
                if (failed) {
                    File failure = new File(directory, "last-install-failure.txt");
                    rotate(failure, 2);
                    writeText(failure, text);
                }
                record(context, "install." + cleanEvent(phase), "failed=" + failed
                        + "; full output is retained in diagnostics/install.log");
            }
        } catch (Throwable ignored) { Log.w(TAG, "Diagnostic install write failed"); }
    }

    public static ExportResult export(Context context) throws IOException { return export(context, ""); }

    /** Run on a worker thread. The only root invocation is a bounded, read-only collector. */
    public static ExportResult export(Context source, String controllerSnapshot) throws IOException {
        if (source == null) throw new IOException("Application context is missing");
        Context context = source.getApplicationContext();
        synchronized (EXPORT_LOCK) {
            requireEnabled(context);
            String name = filename();
            record(context, "export.begin", name);
            File staging = new File(context.getCacheDir(), "diagnostic-export");
            if (!staging.isDirectory() && !staging.mkdirs()) throw new IOException("Cannot prepare diagnostic cache");
            if (OsConstants.S_ISLNK(lstat(staging).st_mode)) throw new IOException("Unexpected diagnostic cache symlink");
            File archiveFile = File.createTempFile("sfxlog-", ".zip", staging);
            ZipArchive archive = null;
            try {
                archive = new ZipArchive(archiveFile, staging);
                archive.text("README.txt", "自由截图A17 diagnostic archive\n"
                        + "Exported at: " + timestamp() + "\nTimezone: " + TimeZone.getDefault().getID() + "\n"
                        + "Failure-time evidence: app/diagnostics/last-install-failure.txt and install.log; "
                        + "timestamps inside these files identify the failure.\n"
                        + "Export-time snapshot: snapshot/, root/, system-elf/ and collection-report.json. "
                        + "Collection is not atomic; compare each item's capture time/mtime and PID/start time.\n"
                        + "Collection does not execute enable/disable, stop services, repair ownership, "
                        + "or run native discovery/status (which could write compatibility caches).\n"
                        + "System ELF: at most three complete files, 64 MiB each; no process memory or screen capture.\n"
                        + "Installed module native binary: one complete file, 16 MiB maximum, with SHA-256 in collection-report.json.\n"
                        + "Contains this app's settings (including capture-tool package lists), module logs, "
                        + "device build details and selected system implementation files. Review before sharing.\n"
                        + "Optional absent files and root denial are listed explicitly. A missing file alone is not a malfunction.\n");
                archive.text("snapshot/app-device.json", appInformation(context).toString(2));
                archive.text("snapshot/controller.txt", "Captured at: " + timestamp() + "\n"
                        + boundedText(controllerSnapshot, 256 * 1024));
                collectApp(context, archive);
                archive.note("native-live-status", "sf17fix status / record-status", "skipped",
                        "Discovery can update compatibility caches; existing status files are collected instead", false);
                collectRoot(context, archive);
                requireEnabled(context);
                archive.finish();
                requireEnabled(context);
                Uri destination = publishDownload(context, archiveFile, name);
                String warning = archive.rootUnavailable ? "Root 日志未完整获取，应用日志及原因已导出"
                        : archive.hasCollectionErrors ? "部分诊断项目未获取，详情见 collection-report.json" : "";
                record(context, "export.complete", name + "; " + warning);
                return new ExportResult(destination, name, warning);
            } catch (IOException error) {
                recordError(context, "export.failed", error);
                throw error;
            } catch (Exception error) {
                recordError(context, "export.failed", error);
                throw new IOException("Diagnostic export failed: " + error.getMessage(), error);
            } finally {
                if (archive != null) archive.closeQuietly();
                if (archiveFile.exists() && !archiveFile.delete()) Log.w(TAG, "Temporary diagnostic archive cleanup failed");
            }
        }
    }

    private static void requireEnabled(Context context) throws IOException {
        if (!isEnabled(context)) throw new IOException("请先开启日志，再复现问题并导出");
    }

    private static String filename() {
        String numeric = BuildConfig.VERSION_NAME.replaceFirst("[^0-9.].*$", "");
        if (numeric.isEmpty()) numeric = Integer.toString(BuildConfig.VERSION_CODE);
        return "screenshotfreedom_log_" + numeric + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()) + ".zip";
    }

    private static JSONObject appInformation(Context context) throws Exception {
        JSONObject info = new JSONObject();
        info.put("captured_at", timestamp()); info.put("timezone", TimeZone.getDefault().getID());
        info.put("version_name", BuildConfig.VERSION_NAME); info.put("version_code", BuildConfig.VERSION_CODE);
        info.put("build_type", BuildConfig.BUILD_TYPE); info.put("package", context.getPackageName());
        info.put("uid", android.os.Process.myUid()); info.put("pid", android.os.Process.myPid());
        info.put("elapsed_realtime_ms", SystemClock.elapsedRealtime());
        info.put("sdk", Build.VERSION.SDK_INT); info.put("release", Build.VERSION.RELEASE);
        info.put("security_patch", Build.VERSION.SECURITY_PATCH); info.put("fingerprint", Build.FINGERPRINT);
        info.put("manufacturer", Build.MANUFACTURER); info.put("brand", Build.BRAND);
        info.put("model", Build.MODEL); info.put("device", Build.DEVICE); info.put("product", Build.PRODUCT);
        info.put("hardware", Build.HARDWARE); info.put("abis", new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)));
        info.put("app_data_dir", context.getApplicationInfo().dataDir);
        info.put("device_protected_files_dir", context.createDeviceProtectedStorageContext().getFilesDir());
        File apk = new File(context.getApplicationInfo().sourceDir);
        info.put("apk_size", apk.length()); info.put("apk_sha256", digestFile(apk, 128L * 1024 * 1024));
        try {
            PackageInfo pkg = context.getPackageManager().getPackageInfo(context.getPackageName(),
                    Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
            JSONArray certificates = new JSONArray();
            android.content.pm.Signature[] signatures = Build.VERSION.SDK_INT >= 28
                    ? pkg.signingInfo == null ? new android.content.pm.Signature[0] : pkg.signingInfo.getApkContentsSigners()
                    : pkg.signatures;
            if (signatures != null) for (android.content.pm.Signature signature : signatures)
                certificates.put(hex(sha256().digest(signature.toByteArray())));
            info.put("signing_certificate_sha256", certificates);
            info.put("first_install_time", pkg.firstInstallTime); info.put("last_update_time", pkg.lastUpdateTime);
        } catch (Exception error) { info.put("package_metadata_error", error.toString()); }
        try (InputStream input = context.getAssets().open("root-component/module.prop")) {
            info.put("bundled_module_prop", new String(readBounded(input, 8192), StandardCharsets.UTF_8));
        } catch (Exception error) { info.put("bundled_module_prop_error", error.toString()); }
        JSONObject assets = new JSONObject();
        for (String abi : Build.SUPPORTED_ABIS) {
            try (InputStream input = context.getAssets().open("native/" + abi + "/libsf17fix.so")) {
                MessageDigest digest = sha256(); byte[] buffer = new byte[32768]; int count; long total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count; if (total > ELF_LIMIT) throw new IOException("Bundled native exceeds bound");
                    digest.update(buffer, 0, count);
                }
                assets.put(abi, new JSONObject().put("size", total).put("sha256", hex(digest.digest())));
            } catch (IOException error) { assets.put(abi, new JSONObject().put("reason", error.toString())); }
        }
        info.put("bundled_native", assets);
        return info;
    }

    private static void collectApp(Context source, ZipArchive archive) throws Exception {
        Context dp = source.createDeviceProtectedStorageContext();
        Context[] contexts = {dp, source};
        Set<String> roots = new HashSet<>();
        for (Context context : contexts) {
            File data = context.getDataDir();
            if (!roots.add(data.getCanonicalPath())) continue;
            String prefix = context == dp ? "app/device-protected/" : "app/credential-protected/";
            String[] preferences = {"appearance.xml", "screenshot_compat_v2.xml", "capture_whitelist_v3.xml", "capture_blacklist_v3.xml", "diagnostic_options.xml"};
            for (String name : preferences) copyLocal(archive, new File(data, "shared_prefs/" + name), prefix + "shared_prefs/" + name,
                    data, INSTALL_LIMIT, false);
            String[] files = {"sf17fix.conf", "sf17fix-recording.conf", "sf17fix-arbitration.conf", "sf17fix-status.json",
                    "sf17fix-recording-status.json", "sf17fix-policy-status.json"};
            for (String name : files) copyLocal(archive, new File(context.getFilesDir(), name), prefix + "files/" + name,
                    data, 256 * 1024, false);
        }
        synchronized (LOG_LOCK) {
            File directory = diagDir(source);
            String[] bases = {"runtime.log", "install.log", "last-install.txt", "last-install-failure.txt", "last-error.txt"};
            for (String base : bases) {
                int history = base.equals("runtime.log") ? 3 : base.equals("last-install.txt") ? 0 : 2;
                for (int i = 0; i <= history; i++) {
                    String name = base + (i == 0 ? "" : "." + i);
                    copyLocal(archive, new File(directory, name), "app/diagnostics/" + name, directory,
                            INSTALL_LIMIT + 4096, false);
                }
            }
        }
        archive.text("snapshot/app-process.txt", "Captured at: " + timestamp() + "\n"
                + "pid=" + android.os.Process.myPid() + " uid=" + android.os.Process.myUid() + "\n");
        File process = new File("/proc/" + android.os.Process.myPid());
        for (String part : new String[]{"status", "stat", "limits"})
            copyLocal(archive, new File(process, part), "snapshot/app-process-" + part + ".txt", process, 64 * 1024, false);
    }

    private static void copyLocal(ZipArchive archive, File source, String target, File allowedRoot,
                                  int maximum, boolean tail) throws Exception {
        JSONObject item = archive.item(target, source.getAbsolutePath(), "file");
        File temporary = null;
        try {
            if (!source.getCanonicalFile().toPath().startsWith(allowedRoot.getCanonicalFile().toPath()))
                throw new IOException("Source escapes this application's diagnostic allowlist");
            File walking = source;
            while (walking != null && !walking.equals(allowedRoot)) {
                StructStat metadata = lstat(walking);
                if (OsConstants.S_ISLNK(metadata.st_mode)) throw new IOException("Symlink sources are excluded");
                walking = walking.getParentFile();
            }
            StructStat before = lstat(source);
            if (!OsConstants.S_ISREG(before.st_mode)) throw new IOException("Source is not a regular file");
            item.put("source_size", before.st_size); item.put("source_mtime_epoch_seconds", before.st_mtime);
            item.put("uid", before.st_uid); item.put("gid", before.st_gid); item.put("mode", Integer.toOctalString(before.st_mode));
            FileDescriptor descriptor = Os.open(source.getAbsolutePath(), OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
            try (InputStream input = new FileInputStream(descriptor)) {
                StructStat opened = Os.fstat(descriptor);
                if (opened.st_ino != before.st_ino || opened.st_dev != before.st_dev || !OsConstants.S_ISREG(opened.st_mode))
                    throw new IOException("Source identity changed while opening");
                if (tail && opened.st_size > maximum) {
                    long toSkip = opened.st_size - maximum;
                    while (toSkip > 0) { long skipped = input.skip(toSkip); if (skipped <= 0) break; toSkip -= skipped; }
                }
                temporary = File.createTempFile("item-", ".tmp", archive.staging);
                try (OutputStream output = new FileOutputStream(temporary)) { copyBounded(input, output, maximum); }
                item.put("truncated", opened.st_size > maximum);
                archive.file(target, temporary, item, false);
                if (opened.st_size > maximum) item.put("reason", tail ? "tail retained; source exceeded item bound" : "prefix retained; source exceeded item bound");
            }
        } catch (Exception error) {
            // Optional files may not exist on first run or when a feature is disabled.
            item.put("status", source.exists() ? "error" : "missing");
            item.put("reason", error.toString());
            if (source.exists()) archive.hasCollectionErrors = true;
        } finally { if (temporary != null && temporary.exists()) temporary.delete(); }
    }

    /** Framed base64 is streamed; even complete 64 MiB ELFs never reside in Java heap. */
    private static void collectRoot(Context context, ZipArchive archive) throws Exception {
        Process process = null;
        AtomicBoolean timedOut = new AtomicBoolean();
        Thread timeout = null;
        ByteArrayOutputStream notices = new ByteArrayOutputStream();
        int framedItems = 0;
        try {
            requireEnabled(context);
            process = new ProcessBuilder("su", "-c", rootCollectorScript()).redirectErrorStream(true).start();
            rootCollector = process;
            if (!isEnabled(context)) throw new IOException("日志已关闭，已取消 Root 采集");
            Process running = process;
            timeout = new Thread(() -> {
                try {
                    if (!running.waitFor(110, TimeUnit.SECONDS)) {
                        timedOut.set(true); running.destroy();
                        if (!running.waitFor(2, TimeUnit.SECONDS)) running.destroyForcibly();
                        try { running.getInputStream().close(); } catch (Exception ignored) {}
                    }
                } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }, "ScreenshotFreedom-root-log-timeout");
            timeout.setDaemon(true); timeout.start();
            process.getOutputStream().close();
            try (InputStream input = new BufferedInputStream(process.getInputStream(), 32768)) {
                String line;
                while ((line = protocolLine(input)) != null) {
                    if (!line.startsWith(BEGIN)) {
                        if (notices.size() < 64 * 1024) notices.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                        continue;
                    }
                    framedItems++;
                    receiveRootItem(archive, input, line.substring(BEGIN.length()));
                }
            }
            if (!process.waitFor(2, TimeUnit.SECONDS)) throw new IOException("Root collector did not finish");
            JSONObject status = archive.item("snapshot/root-collector.txt", "su -c (fixed read-only allowlist)", "command");
            status.put("exit_code", process.exitValue()); status.put("framed_items", framedItems);
            status.put("timed_out", timedOut.get());
            String output = notices.toString(StandardCharsets.UTF_8.name());
            archive.text("snapshot/root-collector.txt", "Captured at: " + timestamp() + "\n"
                    + "exit=" + process.exitValue() + " items=" + framedItems + " timeout=" + timedOut.get() + "\n" + output);
            status.put("status", process.exitValue() == 0 && framedItems > 0 ? "collected" : "error");
            if (process.exitValue() != 0 || framedItems == 0 || timedOut.get()) {
                archive.rootUnavailable = true; archive.hasCollectionErrors = true;
                status.put("reason", timedOut.get() ? "root collector deadline exceeded" : "root denied, unavailable, or collector failed");
            }
        } catch (Exception error) {
            archive.rootUnavailable = true; archive.hasCollectionErrors = true;
            archive.note("root-collector", "su -c (fixed read-only allowlist)", "error", error.toString(), false);
            archive.text("snapshot/root-collector-error.txt", timestamp() + "\n" + error + "\n"
                    + notices.toString(StandardCharsets.UTF_8.name()));
        } finally {
            rootCollector = null;
            if (timeout != null) timeout.interrupt();
            if (process != null) {
                process.destroy();
                try { if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly(); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                try { process.getInputStream().close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void receiveRootItem(ZipArchive archive, InputStream input, String header) throws Exception {
        String[] fields = header.split("\\|", -1);
        if (fields.length != 13 || !fields[0].matches("[A-Za-z0-9_.-]{1,100}"))
            throw new IOException("Invalid root collector framing");
        String key = fields[0], source = fields[1], kind = fields[2];
        boolean elf = "elf".equals(kind);
        boolean moduleBinary = "module-binary".equals(kind);
        boolean completeBinary = elf || moduleBinary;
        long sourceSize = parseLong(fields[3], -1), maximum = parseLong(fields[10], 0);
        maximum = Math.min(maximum, elf ? ELF_LIMIT : moduleBinary ? MODULE_BIN_LIMIT : INSTALL_LIMIT);
        if (maximum < 0) throw new IOException("Invalid diagnostic bound");
        JSONObject item = archive.item((elf ? "system-elf/" : "root/") + key, source, kind);
        item.put("source_size", sourceSize); item.put("source_mtime_epoch_seconds", fields[4]);
        item.put("uid", fields[5]); item.put("gid", fields[6]); item.put("mode", fields[7]);
        item.put("inode", fields[8]); item.put("source_device", fields[9]); item.put("limit_bytes", maximum);
        item.put("tail", "1".equals(fields[11])); item.put("root_captured_at", fields[12]);
        File temporary = File.createTempFile("root-item-", ".tmp", archive.staging);
        boolean overflow = false;
        long received = 0;
        String footer = null;
        try {
            try (OutputStream output = new BufferedOutputStream(new FileOutputStream(temporary), 32768)) {
                String line;
                while ((line = protocolLine(input)) != null) {
                    if (line.startsWith(END)) { footer = line.substring(END.length()); break; }
                    if (line.isEmpty()) continue;
                    byte[] decoded;
                    try { decoded = Base64.getDecoder().decode(line); }
                    catch (IllegalArgumentException error) { throw new IOException("Invalid base64 frame for " + key, error); }
                    received += decoded.length;
                    if (received > maximum) overflow = true;
                    if (!overflow) output.write(decoded);
                }
            }
            item.put("received_bytes", received);
            if (footer == null) throw new IOException("Incomplete root item (collector interrupted)");
            String[] end = footer.split("\\|", -1);
            int code = (int) parseLong(end[0], -1);
            item.put("exit_code", code);
            if (end.length > 1) item.put("after_source_size", end[1]);
            if (end.length > 2) item.put("after_source_mtime_epoch_seconds", end[2]);
            boolean changed = end.length > 2 && sourceSize >= 0
                    && (!fields[3].equals(end[1]) || !fields[4].equals(end[2]));
            item.put("changed_during_collection", changed);
            boolean shortRead = sourceSize >= 0 && received < Math.min(sourceSize, maximum);
            boolean invalidElf = elf && code == 0 && !hasElfHeader(temporary);
            if (code != 0 || overflow || shortRead || (completeBinary && (received != sourceSize || changed)) || invalidElf) {
                item.put("status", code == 44 ? "missing" : code == 45 || code == 46 ? "skipped" : "error");
                item.put("reason", overflow ? "item exceeded its byte bound"
                        : completeBinary && received != sourceSize && code == 0 ? "Binary was incomplete; omitted to avoid a damaged offline fixture"
                        : completeBinary && changed ? "Binary changed during collection; omitted"
                        : invalidElf ? "System implementation did not have a valid ELF header; omitted"
                        : shortRead ? "Source read was incomplete; source may have changed or access was denied" : "collector exit=" + code);
                if (code != 0 && received <= 4096) item.put("collector_detail", readTail(temporary, 4096));
                if (code != 44) archive.hasCollectionErrors = true;
                if (!elf && received > 0 && received < 64 * 1024)
                    archive.file("collection-errors/" + key + ".txt", temporary, new JSONObject(), false);
                return;
            }
            if ("command".equals(kind)) {
                String tail = readTail(temporary, 1024);
                int marker = tail.lastIndexOf("command_exit=");
                if (marker >= 0) {
                    String codeText = tail.substring(marker + 13).trim().split("\\s", 2)[0];
                    long commandCode = parseLong(codeText, -1);
                    item.put("command_exit_code", commandCode);
                    if (commandCode != 0) {
                        item.put("reason", "read-only collection command returned " + commandCode);
                        archive.hasCollectionErrors = true;
                    }
                } else if (received >= maximum) {
                    item.put("reason", "command output reached item bound; tail and exit status may be omitted");
                    item.put("truncated", true);
                }
            }
            if (!item.has("truncated")) item.put("truncated", sourceSize > maximum);
            if (sourceSize > maximum) item.put("reason", "1".equals(fields[11]) ? "tail retained" : "prefix retained");
            archive.file((elf ? "system-elf/" : "root/") + key, temporary, item, elf);
        } catch (Exception error) {
            item.put("status", "error").put("reason", error.toString());
            archive.hasCollectionErrors = true;
            throw error;
        } finally { if (temporary.exists()) temporary.delete(); }
    }

    private static String rootCollectorScript() {
        StringBuilder script = new StringBuilder(18000);
        script.append("#!/system/bin/sh\numask 077\n")
                .append("[ \"$(id -u)\" = 0 ] || { echo 'Root access was not granted'; exit 77; }\n")
                .append("command -v base64 >/dev/null 2>&1 || { echo 'base64 tool unavailable'; exit 78; }\n")
                .append("b64_wrap=0; base64 -w 4096 </dev/null >/dev/null 2>&1 && b64_wrap=1\nb64() { if [ \"$b64_wrap\" = 1 ]; then base64 -w 4096; else base64; fi; }\n")
                .append("begin_item() { printf '").append(BEGIN)
                .append("%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\\n' \"$1\" \"$2\" \"$3\" \"$4\" \"$5\" \"$6\" \"$7\" \"$8\" \"$9\" \"${10}\" \"${11}\" \"${12}\" \"$(date '+%Y-%m-%dT%H:%M:%S%z')\"; }\n")
                .append("end_item() { printf '\\n").append(END).append("%s|%s|%s\\n' \"$1\" \"$2\" \"$3\"; }\n")
                .append("emit_file() {\n key=$1; path=$2; limit=$3; tailmode=$4; kind=$5\n")
                .append(" if [ -L \"$path\" ]; then begin_item \"$key\" \"$path\" \"$kind\" -1 0 - - - - - \"$limit\" \"$tailmode\"; printf 'symlink excluded' | base64; end_item 45 0 0; return; fi\n")
                .append(" if [ ! -f \"$path\" ]; then begin_item \"$key\" \"$path\" \"$kind\" -1 0 - - - - - \"$limit\" \"$tailmode\"; printf 'missing or non-regular source' | base64; end_item 44 0 0; return; fi\n")
                .append(" canonical=$(readlink -f \"$path\")\n case \"$kind:$canonical\" in elf:/system/*|elf:/system_ext/*|elf:/vendor/*|elf:/apex/*) ;; elf:*) begin_item \"$key\" \"$path\" elf -1 0 - - - - - \"$limit\" 0; printf 'ELF outside read-only partition allowlist' | base64; end_item 45 0 0; return ;; *) [ \"$canonical\" = \"$path\" ] || { begin_item \"$key\" \"$path\" \"$kind\" -1 0 - - - - - \"$limit\" 0; printf 'symlink parent excluded' | base64; end_item 45 0 0; return; } ;; esac\n")
                .append(" meta=$(stat -c '%s %Y %u %g %a %i %D' \"$path\" 2>/dev/null) || { begin_item \"$key\" \"$path\" \"$kind\" -1 0 - - - - - \"$limit\" 0; printf 'stat failed' | base64; end_item 47 0 0; return; }\n")
                .append(" set -- $meta; size=$1; modified=$2; begin_item \"$key\" \"$path\" \"$kind\" \"$1\" \"$2\" \"$3\" \"$4\" \"$5\" \"$6\" \"$7\" \"$limit\" \"$tailmode\"\n")
                .append(" if { [ \"$kind\" = elf ] || [ \"$kind\" = module-binary ]; } && [ \"$size\" -gt \"$limit\" ]; then printf 'binary exceeds bound; no partial binary exported' | base64; end_item 46 \"$size\" \"$modified\"; return; fi\n")
                .append(" if [ ! -r \"$path\" ]; then printf 'source is unreadable' | base64; end_item 47 \"$size\" \"$modified\"; return; fi\n")
                .append(" if [ \"$tailmode\" = 1 ]; then tail -c \"$limit\" \"$path\" 2>/dev/null | base64; else head -c \"$limit\" \"$path\" 2>/dev/null | base64; fi\n")
                .append(" after=$(stat -c '%s %Y' \"$path\" 2>/dev/null); set -- $after; end_item 0 \"$1\" \"$2\"\n}\n")
                .append("emit_command() {\n key=$1; label=$2; cmd=$3; limit=$4\n begin_item \"$key\" \"$label\" command -1 0 - - - - - \"$limit\" 0\n")
                .append(" { if command -v timeout >/dev/null 2>&1; then timeout 5 sh -c \"$cmd\"; else sh -c \"$cmd\"; fi; code=$?; printf '\\ncommand_exit=%s\\n' \"$code\"; } 2>&1 | head -c \"$limit\" | base64\n end_item 0 -1 0\n}\n");
        appendCommand(script, "root-environment.txt", "root identity / SELinux / kernel / ptrace policy", "id; id -Z; getenforce; uname -a; cat /proc/version; if [ -f /proc/sys/kernel/yama/ptrace_scope ]; then cat /proc/sys/kernel/yama/ptrace_scope; else echo 'ptrace_scope unavailable'; fi", 32768);
        appendCommand(script, "module-selinux-denials.txt", "only module / SurfaceFlinger AVC denial lines, last 120 matches",
                "dmesg 2>&1 | grep -E 'avc:.*(sf17fix|enable_screenshot17fix|screenshotfreedoma17|surfaceflinger)|(sf17fix|enable_screenshot17fix|screenshotfreedoma17|surfaceflinger).*avc:|^dmesg:|Permission denied|Operation not permitted' | tail -n 120", 128 * 1024);
        appendCommand(script, "build-properties.txt", "targeted build properties (no serial or account identifiers)",
                "for p in ro.build.fingerprint ro.system.build.fingerprint ro.vendor.build.fingerprint ro.build.version.release ro.build.version.sdk ro.build.version.security_patch ro.product.manufacturer ro.product.brand ro.product.model ro.product.device ro.product.cpu.abilist ro.build.version.incremental; do printf '%s=' \"$p\"; getprop \"$p\"; done", 32768);
        appendCommand(script, "module-metadata.txt", "module directories and fixed filenames metadata",
                "ls -ldZ " + shell(MODULE) + " " + shell(STATE) + " " + shell(STATE + "/compat-cache")
                        + "; for d in " + shell(MODULE) + " " + shell(STATE) + "; do [ -L \"$d\" ] && { echo 'symlink directory excluded'; continue; }; ls -lZ \"$d\"; done", 65536);
        for (String name : new String[]{"module.prop", "daemon.log", "status.json", "recording-status.json", "policy-status.json",
                "daemon.pid", "broker.pid", "policy-system-uids.conf", "disable", "remove", "service.sh", "uninstall.sh"}) {
            int cap = name.equals("daemon.log") ? 512 * 1024 : 128 * 1024;
            appendRootFile(script, "module-" + name, MODULE + "/" + name, cap, name.equals("daemon.log"), "file");
        }
        for (String name : new String[]{"native-patch.state", "native-recording-patch.state", "native.lock",
                "compat-cache/screenshot.cache", "compat-cache/recording.cache", "compat-cache/access.cache"})
            appendRootFile(script, "state-" + name.replace('/', '-'), STATE + "/" + name, 256 * 1024, false, "file");
        appendRootFile(script, "module-sf17fix.bin", MODULE + "/bin/sf17fix", (int) MODULE_BIN_LIMIT, false, "module-binary");
        appendCommand(script, "installed-native-hash.txt", "installed module native binary SHA-256 and metadata",
                "p=" + shell(MODULE + "/bin/sf17fix") + "; [ -L \"$p\" ] && exit 45; [ \"$(readlink -f \"$p\")\" = \"$p\" ] || exit 45; stat \"$p\"; size=$(stat -c '%s' \"$p\") || exit 44; [ \"$size\" -le " + MODULE_BIN_LIMIT + " ] || { echo 'native hash skipped: exceeds bound'; exit 46; }; sha256sum \"$p\"", 32768);
        appendCommand(script, "native-lock-owners.txt", "only /proc/locks entries matching native.lock inode",
                "p=" + shell(STATE + "/native.lock") + "; [ -L \"$p\" ] && exit 45; inode=$(stat -c '%i' \"$p\") || exit 44; case \"$inode\" in ''|*[!0-9]*) exit 47;; esac; stat \"$p\"; awk -v n=\"$inode\" '$6 ~ (\":\" n \"$\") {print}' /proc/locks", 32768);
        appendCommand(script, "relevant-processes.txt", "only surfaceflinger / module process rows",
                "ps -A -o USER,PID,PPID,NAME,ARGS | grep -E 'surfaceflinger|sf17fix|/data/adb/modules/enable_screenshot17fix/'", 65536);
        appendCommand(script, "surfaceflinger-process.txt", "SurfaceFlinger PID identity / status / mappings; no memory",
                "for p in $(pidof surfaceflinger); do case \"$p\" in ''|*[!0-9]*) continue;; esac; printf '\\nPID=%s\\n' \"$p\"; readlink /proc/\"$p\"/exe; cat /proc/\"$p\"/stat /proc/\"$p\"/status /proc/\"$p\"/maps; done", 512 * 1024);
        appendCommand(script, "module-process-identities.txt", "verified broker / supervisor proc metadata",
                "for f in " + shell(MODULE + "/daemon.pid") + " " + shell(MODULE + "/broker.pid")
                        + "; do [ -L \"$f\" ] && continue; read -r p rest < \"$f\" || continue; case \"$p\" in ''|*[!0-9]*) continue;; esac; cmd=$(tr '\\000' ' ' < /proc/\"$p\"/cmdline); case \"$cmd\" in *'/data/adb/modules/enable_screenshot17fix/'*) printf '\\nsource=%s PID=%s cmd=%s\\n' \"$f\" \"$p\" \"$cmd\"; cat /proc/\"$p\"/stat /proc/\"$p\"/status; ls -l /proc/\"$p\"/fd 2>/dev/null | grep -E 'native.lock|enable_screenshot17fix';; *) printf 'PID identity mismatch: %s\\n' \"$p\";; esac; done", 128 * 1024);
        appendCommand(script, "module-logcat.txt", "only ScreenshotCompat / ScreenshotCompatUI / ScreenshotFreedomLog tags, last 400 lines",
                "logcat -d -t 400 -v threadtime ScreenshotCompat:V ScreenshotCompatUI:V ScreenshotFreedomLog:V '*:S'", 256 * 1024);
        appendCommand(script, "app-logcat.txt", "only current app PID, last 250 lines",
                "logcat -d -t 250 -v threadtime --pid=" + android.os.Process.myPid(), 256 * 1024);
        script.append("elf_count=0\nelf_seen='|'\nadd_elf() {\n p=$1\n")
                .append(" [ \"$elf_count\" -lt 3 ] || return\n case \"$p\" in /system/*|/system_ext/*|/vendor/*|/apex/*) ;; *) return;; esac\n")
                .append(" case \"$p\" in *'..'*|*'|'*|*'\\n'*) return;; esac\n canonical=$(readlink -f \"$p\")\n case \"$canonical\" in /system/*|/system_ext/*|/vendor/*|/apex/*) ;; *) return;; esac\n")
                .append(" case \"$elf_seen\" in *\"|$canonical|\"*) return;; esac\n p=$canonical\n [ -f \"$p\" ] && [ ! -L \"$p\" ] || return\n elf_count=$((elf_count + 1)); elf_seen=\"$elf_seen$canonical|\"\n")
                .append(" emit_file \"system-elf-$elf_count.bin\" \"$p\" ").append(ELF_LIMIT).append(" 0 elf\n}\n")
                .append("add_elf /system/bin/surfaceflinger\n")
                .append("for status in ").append(shell(MODULE + "/status.json")).append(" ").append(shell(MODULE + "/recording-status.json"))
                .append("; do [ -L \"$status\" ] && continue; p=$(head -c 262144 \"$status\" 2>/dev/null | sed -n 's/.*\"module_path\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p' | head -n 1); [ -n \"$p\" ] && add_elf \"$p\"; done\n")
                .append("add_elf /system/lib64/libsurfaceflinger.so\nadd_elf /system/lib64/libgui.so\n")
                .append("begin_item elf-selection.txt 'bounded offline ELF selection summary' command -1 0 - - - - - 8192 0\nprintf 'count=%s\\ncanonical_paths=%s\\n' \"$elf_count\" \"$elf_seen\" | b64\nend_item 0 -1 0\n")
                .append("exit 0\n");
        // Android base64 defaults to 76 columns; prefer larger safe frames when supported.
        return script.toString().replace("| base64", "| b64");
    }

    private static void appendRootFile(StringBuilder script, String key, String path, int maximum, boolean tail, String kind) {
        script.append("emit_file ").append(shell(key)).append(' ').append(shell(path)).append(' ')
                .append(maximum).append(tail ? " 1 " : " 0 ").append(shell(kind)).append('\n');
    }

    private static void appendCommand(StringBuilder script, String key, String label, String command, int maximum) {
        script.append("emit_command ").append(shell(key)).append(' ').append(shell(label)).append(' ')
                .append(shell(command)).append(' ').append(maximum).append('\n');
    }

    private static String shell(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    private static String protocolLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128); int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') break;
            if (line.size() >= 8192) throw new IOException("Diagnostic protocol line exceeds bound");
            if (value != '\r') line.write(value);
        }
        return value == -1 && line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8.name());
    }

    private static Uri publishDownload(Context context, File file, String name) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name); values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Downloads provider did not create the diagnostic ZIP");
            boolean published = false;
            try {
                try (InputStream input = new FileInputStream(file); OutputStream output = resolver.openOutputStream(uri, "w")) {
                    if (output == null) throw new IOException("Downloads provider did not open the diagnostic ZIP");
                    if (copyBounded(input, output, DATA_LIMIT + ELF_TOTAL_LIMIT + MODULE_BIN_LIMIT + 4L * 1024 * 1024) != file.length())
                        throw new IOException("Diagnostic ZIP transfer was incomplete");
                }
                ContentValues complete = new ContentValues(); complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(uri, complete, null, null) != 1) throw new IOException("Downloads provider did not publish the diagnostic ZIP");
                published = true; return uri;
            } finally {
                if (!published) try { resolver.delete(uri, null, null); } catch (Exception ignored) { Log.w(TAG, "Pending ZIP cleanup failed"); }
            }
        }
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            throw new IOException("Android 8/9 requires storage permission to export into Download");
        File download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!download.isDirectory() && !download.mkdirs()) throw new IOException("Download directory is unavailable");
        File destination = new File(download, name);
        if (!destination.createNewFile()) throw new IOException("A log archive with this timestamp already exists; retry in one second");
        boolean complete = false;
        try {
            try (InputStream input = new FileInputStream(file); OutputStream output = new FileOutputStream(destination)) {
                if (copyBounded(input, output, DATA_LIMIT + ELF_TOTAL_LIMIT + MODULE_BIN_LIMIT + 4L * 1024 * 1024) != file.length())
                    throw new IOException("Diagnostic ZIP transfer was incomplete");
            }
            complete = true; return Uri.fromFile(destination);
        } finally { if (!complete) destination.delete(); }
    }

    private static final class ZipArchive {
        final ZipOutputStream zip;
        final File staging;
        final JSONArray items = new JSONArray();
        final Set<String> names = new HashSet<>();
        final String startedAt = timestamp();
        long dataBytes, elfBytes, moduleBinaryBytes;
        boolean hasCollectionErrors, rootUnavailable, closed;
        ZipArchive(File output, File staging) throws IOException {
            this.staging = staging;
            zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output), 65536));
            zip.setLevel(Deflater.BEST_SPEED);
        }
        JSONObject item(String name, String source, String kind) throws Exception {
            JSONObject item = new JSONObject().put("entry", name).put("source", source).put("kind", kind)
                    .put("captured_at", timestamp()).put("status", "pending");
            items.put(item); return item;
        }
        void note(String name, String source, String status, String reason, boolean error) throws Exception {
            item(name, source, "note").put("status", status).put("reason", reason);
            hasCollectionErrors |= error;
        }
        void text(String name, String text) throws Exception {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > INSTALL_LIMIT || dataBytes + bytes.length > DATA_LIMIT) throw new IOException("Diagnostic text budget exceeded");
            begin(name); zip.write(bytes); zip.closeEntry(); dataBytes += bytes.length;
        }
        void file(String name, File source, JSONObject metadata, boolean elf) throws Exception {
            long size = source.length();
            boolean moduleBinary = "module-binary".equals(metadata.optString("kind"));
            if (size > (elf ? ELF_LIMIT : moduleBinary ? MODULE_BIN_LIMIT : INSTALL_LIMIT + 4096)
                    || (elf ? elfBytes + size > ELF_TOTAL_LIMIT : moduleBinary ? moduleBinaryBytes + size > MODULE_BIN_LIMIT : dataBytes + size > DATA_LIMIT - 512 * 1024)) {
                metadata.put("status", "skipped").put("reason", "archive byte budget exceeded"); hasCollectionErrors = true; return;
            }
            begin(name); MessageDigest digest = sha256(); byte[] buffer = new byte[32768]; int count;
            long saved = 0;
            try (InputStream input = new FileInputStream(source)) {
                while ((count = input.read(buffer)) != -1) {
                    saved += count; if (saved > size) throw new IOException("Diagnostic source changed while archiving");
                    zip.write(buffer, 0, count); digest.update(buffer, 0, count);
                }
            }
            zip.closeEntry();
            if (elf) elfBytes += saved; else if (moduleBinary) moduleBinaryBytes += saved; else dataBytes += saved;
            metadata.put("saved_bytes", saved).put("saved_sha256", hex(digest.digest())).put("status", "collected");
        }
        void begin(String name) throws IOException {
            boolean dotSegment = false;
            for (String segment : name.split("/", -1))
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) dotSegment = true;
            if (name.startsWith("/") || name.contains("\\") || dotSegment || !names.add(name))
                throw new IOException("Invalid or duplicate diagnostic ZIP path");
            zip.putNextEntry(new ZipEntry(name));
        }
        void finish() throws Exception {
            JSONObject report = new JSONObject().put("schema", 1).put("app_version", BuildConfig.VERSION_NAME)
                    .put("version_code", BuildConfig.VERSION_CODE).put("collection_started_at", startedAt)
                    .put("collection_finished_at", timestamp()).put("timezone", TimeZone.getDefault().getID())
                    .put("snapshot_is_atomic", false).put("root_unavailable_or_incomplete", rootUnavailable)
                    .put("has_collection_errors", hasCollectionErrors).put("diagnostic_data_bytes", dataBytes)
                    .put("system_elf_bytes", elfBytes).put("diagnostic_data_limit", DATA_LIMIT)
                    .put("module_binary_bytes", moduleBinaryBytes).put("module_binary_limit", MODULE_BIN_LIMIT)
                    .put("system_elf_limit_each", ELF_LIMIT).put("system_elf_limit_total", ELF_TOTAL_LIMIT)
                    .put("reason_fields", "Native reason/detail/errno are retained verbatim in status JSON and installation output; per-item reason records collection failures.")
                    .put("items", items);
            text("collection-report.json", report.toString(2));
            zip.close(); closed = true;
        }
        void closeQuietly() { if (!closed) { try { zip.close(); } catch (Exception ignored) {} closed = true; } }
    }

    private static File diagDir(Context context) throws IOException {
        File root = context.getApplicationContext().createDeviceProtectedStorageContext().getFilesDir();
        File directory = new File(root, "diagnostics");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot prepare private diagnostic directory");
        StructStat metadata = lstat(directory);
        if (!OsConstants.S_ISDIR(metadata.st_mode) || OsConstants.S_ISLNK(metadata.st_mode)) throw new IOException("Unexpected diagnostic directory type");
        return directory;
    }

    private static void appendRotating(File file, String text, int maximum, int history) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximum) bytes = ("[entry truncated; tail retained]\n"
                + new String(bytes, bytes.length - maximum + 64, maximum - 64, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        if (file.length() + bytes.length > maximum) rotate(file, history);
        try (OutputStream output = noFollowOutput(file, true)) { output.write(bytes); }
    }

    private static void writeText(File file, String text) throws IOException {
        try (OutputStream output = noFollowOutput(file, false)) { output.write(text.getBytes(StandardCharsets.UTF_8)); }
    }

    private static OutputStream noFollowOutput(File file, boolean append) throws IOException {
        try {
            return new FileOutputStream(Os.open(file.getAbsolutePath(), OsConstants.O_WRONLY | OsConstants.O_CREAT
                    | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW | (append ? OsConstants.O_APPEND : OsConstants.O_TRUNC), 0600));
        } catch (Exception error) { throw new IOException("Cannot open private diagnostic file", error); }
    }

    private static void rotate(File file, int history) throws IOException {
        for (int i = history; i >= 1; i--) {
            File source = i == 1 ? file : new File(file.getParentFile(), file.getName() + "." + (i - 1));
            File target = new File(file.getParentFile(), file.getName() + "." + i);
            if (i == history && target.exists() && !target.delete()) throw new IOException("Cannot rotate diagnostic history");
            if (source.exists() && !source.renameTo(target)) throw new IOException("Cannot rotate diagnostic file");
        }
    }

    private static String cleanEvent(String event) {
        if (event == null) return "unspecified";
        String result = event.replace('\n', '_').replace('\r', '_');
        return result.substring(0, Math.min(result.length(), 128));
    }
    private static String boundedText(String text, int maximum) {
        if (text == null) return "";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maximum) return text;
        int half = (maximum - 128) / 2;
        return new String(bytes, 0, half, StandardCharsets.UTF_8) + "\n[TRUNCATED: middle omitted; original_bytes="
                + bytes.length + "]\n" + new String(bytes, bytes.length - half, half, StandardCharsets.UTF_8);
    }
    private static String timestamp() { return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).format(new Date()); }
    private static long parseLong(String value, long fallback) { try { return Long.parseLong(value); } catch (Exception ignored) { return fallback; } }
    private static StructStat lstat(File file) throws IOException { try { return Os.lstat(file.getAbsolutePath()); } catch (Exception error) { throw new IOException(error); } }
    private static MessageDigest sha256() { try { return MessageDigest.getInstance("SHA-256"); } catch (Exception error) { throw new AssertionError(error); } }
    private static String hex(byte[] bytes) { StringBuilder output = new StringBuilder(bytes.length * 2); for (byte value : bytes) output.append(String.format(Locale.ROOT, "%02x", value & 255)); return output.toString(); }
    private static String digestFile(File file, long maximum) throws IOException {
        if (file.length() > maximum) return "skipped: file exceeds hash bound";
        MessageDigest digest = sha256(); byte[] buffer = new byte[32768]; int count; long total = 0;
        try (InputStream input = new FileInputStream(file)) {
            while ((count = input.read(buffer)) != -1) { total += count; if (total > maximum) return "skipped: hash bound exceeded"; digest.update(buffer, 0, count); }
        }
        return hex(digest.digest());
    }
    private static String readTail(File file, int maximum) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            input.getChannel().position(Math.max(0, file.length() - maximum));
            return new String(readBounded(input, maximum), StandardCharsets.UTF_8);
        }
    }
    private static boolean hasElfHeader(File file) throws IOException {
        if (file.length() < 52) return false;
        byte[] ident = new byte[16];
        try (InputStream input = new FileInputStream(file)) {
            int done = 0, count;
            while (done < ident.length && (count = input.read(ident, done, ident.length - done)) != -1) done += count;
            return done == ident.length && (ident[4] != 2 || file.length() >= 64)
                    && ident[0] == 0x7f && ident[1] == 'E' && ident[2] == 'L' && ident[3] == 'F'
                    && (ident[4] == 1 || ident[4] == 2) && (ident[5] == 1 || ident[5] == 2) && ident[6] == 1;
        }
    }
    private static byte[] readBounded(InputStream input, int maximum) throws IOException { ByteArrayOutputStream output = new ByteArrayOutputStream(); copyBounded(input, output, maximum); return output.toByteArray(); }
    private static long copyBounded(InputStream input, OutputStream output, long maximum) throws IOException {
        byte[] buffer = new byte[32768]; int count; long total = 0;
        while (total < maximum && (count = input.read(buffer, 0, (int) Math.min(buffer.length, maximum - total))) != -1) { output.write(buffer, 0, count); total += count; }
        return total;
    }
}
