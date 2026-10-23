package net.hawyn.screenshotfreedoma17;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** System-scope capture arbitration. No application process or screen content is inspected. */
final class CapturePolicyBroker {
    interface Reporter { void report(String message, Throwable error); }
    interface ToolQualification { boolean allowed() throws Exception; }

    private static final String SOCKET = "hawyn.screenshotcompat.policy.v1";
    private static final String LISTENER_DESCRIPTOR = "android.gui.IScreenCaptureListener";
    private static final long METADATA_PRESENCE_NANOS = 100_000_000L;
    private final Reporter reporter;
    private final Map<String, Lease> sessions = new ConcurrentHashMap<>();
    private final Map<Object, Lease> displays = new ConcurrentHashMap<>();
    private final Set<LegacyDisplay> legacyDisplays = ConcurrentHashMap.newKeySet();
    private final Map<IBinder, LegacyDisplay> legacyTokens = new ConcurrentHashMap<>();
    private volatile LocalSocket control;
    private volatile Thread readyThread;
    private volatile boolean running;
    private volatile boolean retiring;
    private int pendingBegins;
    private volatile MetadataPresence metadataPresence;
    private final AtomicBoolean metadataProbePending = new AtomicBoolean();
    private volatile LocalSocket metadataSocket;
    private final ExecutorService metadataWorker = worker("ScreenshotCompat-metadata-presence", 1);
    private final ExecutorService completionWorker = worker("ScreenshotCompat-completion", 512);
    private final AtomicInteger beginLogSamples = new AtomicInteger();
    private final AtomicInteger endLogSamples = new AtomicInteger();
    private volatile String lastAvailabilityFailure = "";
    private volatile Object observedAdapter;
    private volatile Method inventoryServiceGetter;
    private volatile InventoryAccess inventoryAccess;
    private volatile boolean legacyInventoryRequired;
    private volatile Method legacyInventoryFactory;
    private volatile Method legacyInventoryCheck;
    private volatile BooleanSupplier arbitrationEnabled = () -> true;

    CapturePolicyBroker(Reporter reporter) { this.reporter = reporter; }

    private static ExecutorService worker(String name, int capacity) {
        return new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity), task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    void setArbitrationEnabled(BooleanSupplier enabled) { arbitrationEnabled = enabled; }

    private boolean arbitrationEnabled() {
        try { return arbitrationEnabled.getAsBoolean(); }
        catch (Throwable error) { return true; }
    }

    /** A fresh live peer reply is required before an out-of-band metadata gate uses tool rules. */
    boolean arbitrationRunning() {
        return readArbitrationRunning(false);
    }

    /** Presence only: never used to authorize BEGIN or to prove a capture completed. */
    boolean arbitrationRunningForMetadata() {
        if (!arbitrationEnabled() || retiring) return false;
        MetadataPresence cached = metadataPresence;
        long now = System.nanoTime();
        if (cached != null && now - cached.sampledAt >= 0 && now - cached.sampledAt < METADATA_PRESENCE_NANOS) return cached.active;
        if (metadataProbePending.compareAndSet(false, true)) {
            try {
                metadataWorker.execute(() -> {
                    try { readArbitrationRunning(true); }
                    finally { metadataProbePending.set(false); }
                });
            } catch (Throwable error) { metadataProbePending.set(false); }
        }
        return false;
    }

    private boolean readArbitrationRunning(boolean background) {
        if (!arbitrationEnabled()) return false;
        LocalSocket socket = null;
        try {
            socket = connect();
            if (background) {
                metadataSocket = socket;
                if (retiring) return false;
            }
            socket.setSoTimeout(300);
            boolean active = requiredBoolean(request(socket, "STATUS"), "running") && arbitrationEnabled();
            rememberPresence(active);
            return active;
        } catch (Throwable error) {
            rememberPresence(false);
            return false;
        } finally {
            if (background && metadataSocket == socket) metadataSocket = null;
            close(socket);
        }
    }

    private void rememberPresence(boolean active) { metadataPresence = new MetadataPresence(System.nanoTime(), active); }

    private static final class MetadataPresence {
        final long sampledAt;
        final boolean active;
        MetadataPresence(long sampledAt, boolean active) { this.sampledAt = sampledAt; this.active = active; }
    }

    Lease noOp(String pipeline) { return new Lease("", pipeline, null, false, ""); }

    /** Called only at a real completion point, never to expire an active output. */
    private boolean completedBrokerRetired(String epoch) {
        if (!arbitrationEnabled()) return true;
        LocalSocket socket;
        try { socket = connect(); }
        catch (Throwable error) { return brokerSocketAbsent(error); }
        try {
            socket.setSoTimeout(300);
            JSONObject state = request(socket, "STATUS");
            return !requiredBoolean(state, "running") || !epoch.equals(state.getString("epoch"));
        } catch (Throwable error) {
            return false; // A timeout or malformed reply does not prove an old epoch ended.
        } finally { close(socket); }
    }

    private static boolean brokerSocketAbsent(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof android.system.ErrnoException errno
                    && (errno.errno == android.system.OsConstants.ENOENT
                    || errno.errno == android.system.OsConstants.ECONNREFUSED)) return true;
            String message = cause.getMessage();
            // LocalSocket JNI also throws a plain IOException from strerror(errno).
            if (cause instanceof IOException && ("Connection refused".equals(message)
                    || "No such file or directory".equals(message))) return true;
        }
        return false;
    }

    Lease begin(String pipeline, boolean allowed, int ownerUid) throws Exception {
        return beginQualified(pipeline, () -> allowed, ownerUid);
    }

    Lease beginQualified(String pipeline, ToolQualification qualification, int ownerUid) throws Exception {
        if (!pipeline.equals("SCREENSHOT") && !pipeline.equals("PROJECTION")) throw new IllegalArgumentException("capture pipeline");
        if (!arbitrationEnabled()) return noOp(pipeline);
        synchronized (this) {
            if (retiring) return noOp(pipeline);
            pendingBegins++;
        }
        try {
        LocalSocket socket = null;
        String token = UUID.randomUUID().toString().replace("-", "");
        try {
            if (ownerUid < 0) throw new IllegalStateException("Capture tool identity is unconfirmed");
            socket = connect();
            boolean active = requiredBoolean(request(socket, "STATUS"), "running") && arbitrationEnabled();
            rememberPresence(active);
            if (!active) {
                close(socket);
                return noOp(pipeline);
            }
            // No package/context lookup runs until this same authenticated socket
            // confirms a live broker. A subsequent lost ACK still becomes no-op.
            boolean allowed;
            try { allowed = qualification.allowed(); }
            catch (Throwable error) {
                allowed = false;
                reporter.report("capture tool qualification unavailable while arbitration is running", error);
            }
            JSONObject reply = request(socket, "BEGIN " + token + " " + pipeline + " "
                    + (allowed ? "ALLOW" : "BLOCK") + " " + ownerUid);
            if (!requiredBoolean(reply, "running")) {
                close(socket);
                return noOp(pipeline);
            }
            boolean paused = bool(reply, "paused", "policy_paused");
            boolean ready = bool(reply, "ready", "policy_ready");
            if ((!allowed || paused || !ready) && !requiredBoolean(reply, "restored_or_unmanaged")) {
                throw new IOException("Capture policy did not confirm original filtering");
            }
            Lease lease = new Lease(token, pipeline, socket, true, reply.getString("epoch"));
            sessions.put(token, lease);
            if (!arbitrationEnabled()) {
                // This request has not submitted any pixels/output yet. End only
                // its own acknowledged BEGIN, without touching older sessions.
                lease.finish();
                return noOp(pipeline);
            }
            lastAvailabilityFailure = "";
            if (beginLogSamples.get() < 5 && beginLogSamples.getAndIncrement() < 5) {
                reporter.report("capture policy BEGIN pipeline=" + pipeline + " allowed=" + allowed
                        + " paused=" + paused + " ownerUid=" + ownerUid, null);
            }
            return lease;
        } catch (Throwable error) {
            // User-selected fallback: capture proceeds without tool rules. The
            // server may retain an already accepted/lost session as an orphan;
            // never invent END or clear an older unknown session to hide that state.
            close(socket);
            rememberPresence(false);
            String failure = error.getClass().getName() + ":" + error.getMessage();
            if (!failure.equals(lastAvailabilityFailure)) {
                lastAvailabilityFailure = failure;
                reporter.report("capture arbitration unavailable; tool rules bypassed for this request", error);
            }
            return noOp(pipeline);
        }
        } finally {
            synchronized (this) { pendingBegins--; }
        }
    }

    WrappedListener wrapListener(Class<?> captureClass, long nativeListener, Lease lease) throws Exception {
        if (!lease.arbitrating) return new WrappedListener(nativeListener, null, null);
        if (nativeListener == 0) throw new IOException("Missing capture completion listener");
        Method write = captureClass.getDeclaredMethod("nativeWriteListenerToParcel", long.class, Parcel.class);
        if (!Modifier.isStatic(write.getModifiers()) || write.getReturnType() != void.class) {
            throw new IOException("Unsupported capture listener parcel writer");
        }
        write.setAccessible(true);
        Class<?> listenerClass = Class.forName(captureClass.getName() + "$ScreenCaptureListener", false, captureClass.getClassLoader());
        Constructor<?> constructor = listenerClass.getDeclaredConstructor(Parcel.class);
        constructor.setAccessible(true);
        Field pointer = field(listenerClass, "mNativeObject");
        if (pointer.getType() != long.class) throw new IOException("Unsupported native listener field");
        Parcel originalParcel = Parcel.obtain();
        Parcel replacementParcel = Parcel.obtain();
        try {
            write.invoke(null, nativeListener, originalParcel);
            originalParcel.setDataPosition(0);
            IBinder original = originalParcel.readStrongBinder();
            if (original == null || !LISTENER_DESCRIPTOR.equals(original.getInterfaceDescriptor())) {
                throw new IOException("Unexpected capture listener Binder interface");
            }
            ForwardListener forward = new ForwardListener(original, lease);
            // The framework Parcelable constructor registers ownership in its native
            // allocation registry. Never manually inc/dec the returned native pointer.
            replacementParcel.writeInt(1);
            replacementParcel.writeStrongBinder(forward);
            replacementParcel.setDataPosition(0);
            Object listener = constructor.newInstance(replacementParcel);
            long replacement = pointer.getLong(listener);
            if (replacement == 0) throw new IOException("Capture listener reconstruction failed");
            WrappedListener result = new WrappedListener(replacement, listener, forward);
            lease.retained = result;
            return result;
        } finally {
            originalParcel.recycle();
            replacementParcel.recycle();
        }
    }

    /** Recreate the framework Parcelable around the same Binder, with no callback proxy. */
    Object listenerFromPointer(Class<?> captureClass, long nativeListener) throws Exception {
        if (nativeListener == 0) throw new IOException("Missing capture completion listener");
        Method write = captureClass.getDeclaredMethod("nativeWriteListenerToParcel", long.class, Parcel.class);
        if (!Modifier.isStatic(write.getModifiers()) || write.getReturnType() != void.class) {
            throw new IOException("Unsupported capture listener parcel writer");
        }
        write.setAccessible(true);
        Class<?> listenerClass = Class.forName(captureClass.getName() + "$ScreenCaptureListener", false, captureClass.getClassLoader());
        Constructor<?> constructor = listenerClass.getDeclaredConstructor(Parcel.class);
        constructor.setAccessible(true);
        Field pointer = field(listenerClass, "mNativeObject");
        if (pointer.getType() != long.class) throw new IOException("Unsupported native listener field");
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeInt(1);
            write.invoke(null, nativeListener, parcel);
            parcel.setDataPosition(0);
            Object listener = constructor.newInstance(parcel);
            if (pointer.getLong(listener) == 0) throw new IOException("Capture listener reconstruction failed");
            // The constructor's NativeAllocationRegistry owns the native reference.
            // No manual native free, callback invocation, or buffer access occurs.
            return listener;
        } finally { parcel.recycle(); }
    }

    void observeAdapter(Object adapter) { observedAdapter = adapter; }
    synchronized boolean prepareReload() {
        if (pendingBegins != 0 || !sessions.isEmpty() || !legacyDisplays.isEmpty()) return false;
        // Serialize with BEGIN so no new callback owner appears after the empty check.
        retiring = true;
        return true;
    }
    void retainDisplay(Object display, Lease lease) { displays.put(display, lease); }

    void displayDestroyed(Object display, ClassLoader loader) {
        Lease lease = displays.remove(display);
        if (lease != null) finishAfterDisplayCommit(lease, loader);
    }

    void displayDestructionUncertain(Object display) {
        Lease lease = displays.get(display);
        if (lease != null) lease.abandon();
    }

    void finishAfterDisplayCommit(Lease lease, ClassLoader loader) {
        finishAfterDisplayCommit(lease, loader, null);
    }

    private void finishAfterDisplayCommit(Lease lease, ClassLoader loader, Runnable completed) {
        if (!lease.arbitrating) {
            lease.finishAsync(completed);
            return;
        }
        try {
            Class<?> transactionClass = Class.forName("android.view.SurfaceControl$Transaction", false, loader);
            Class<?> listenerClass = Class.forName("android.view.SurfaceControl$TransactionCommittedListener", false, loader);
            Object transaction = transactionClass.getDeclaredConstructor().newInstance();
            Method close = transactionClass.getMethod("close");
            Object listener = Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class<?>[]{listenerClass}, (proxy, method, args) -> {
                if (method.getName().equals("onTransactionCommitted") && method.getParameterCount() == 0) {
                    try {
                        lease.finishAsync(completed);
                    }
                    finally {
                        try { close.invoke(transaction); }
                        catch (Throwable error) { reporter.report("display commit transaction cleanup failed", error); }
                    }
                    return null;
                }
                if (method.getName().equals("toString")) return "CapturePolicyCommit";
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return args != null && args.length == 1 && proxy == args[0];
                return null;
            });
            lease.retained = new Object[]{transaction, listener};
            transactionClass.getMethod("addTransactionCommittedListener", Executor.class, listenerClass)
                    .invoke(transaction, (Executor) Runnable::run, listener);
            transactionClass.getMethod("apply").invoke(transaction);
        } catch (Throwable error) {
            // An uncertain output must become an orphan blocker, never remain ALLOW.
            lease.abandon();
            reporter.report("display removal commit is unconfirmed; capture policy lease retained", error);
        }
    }

    /** A vendor's original permissions execute before its lower factory is intercepted. */
    LegacyDisplay legacyRequest(int uid, int pid, LegacyCleanup cleanup) throws IOException {
        if (uid < 0 || pid <= 0) {
            throw new IOException("Vendor capture lifecycle is unavailable");
        }
        LegacyDisplay display = new LegacyDisplay(uid, pid, cleanup);
        synchronized (this) {
            if (!retiring && legacyDisplays.size() < 64) {
                legacyDisplays.add(display);
                return display;
            }
        }
        // Do not hold the admission monitor while probing an unavailable broker.
        if (arbitrationEnabled() && arbitrationRunning()) throw new IOException("Vendor capture lifecycle is unavailable");
        display.lease = noOp("PROJECTION");
        return display;
    }

    void legacyRequestReturned(LegacyDisplay display) {
        if (display.lease != null && !display.lease.arbitrating) {
            legacyDisplays.remove(display);
            return;
        }
        if (display.lease != null || display.nativeSubmitted.get()) return;
        // No lower create operation ran. If the vendor already announced this
        // request to its inventory, undo that announcement for this empty output.
        if (display.reachedFactory.get()) {
            try { display.cleanup.destroy(display); }
            catch (Throwable error) {
                reporter.report("vendor capture without an output has unconfirmed inventory cleanup", error);
                return;
            }
        }
        legacyDisplays.remove(display);
    }

    void legacyCreated(LegacyDisplay display, IBinder token, ClassLoader loader) throws IOException {
        if (!display.lease.arbitrating) {
            legacyDisplays.remove(display);
            return;
        }
        display.loader = loader;
        if (token == null) {
            try { display.cleanup.destroy(display); }
            catch (Throwable error) {
                display.lease.abandon();
                reporter.report("vendor empty output inventory cleanup is unconfirmed", error);
                return;
            }
            finishAfterDisplayCommit(display.lease, loader, () -> legacyDisplays.remove(display));
            return;
        }
        LegacyDisplay previous = legacyTokens.putIfAbsent(token, display);
        if (previous != null) {
            display.lease.abandon();
            previous.lease.abandon();
            throw new IOException("Vendor capture token was reused");
        }
        // Publish only after the destruction hook can resolve this token. A
        // concurrent death callback may immediately start the normal destroy path.
        display.token = token;
        if (display.dead.get()) scheduleLegacyCleanup(display);
    }

    LegacyDisplay beginLegacyDestruction(IBinder token) {
        if (token == null) return null;
        LegacyDisplay display = legacyTokens.get(token);
        return display != null && display.destroying.compareAndSet(false, true) ? display : null;
    }

    void legacyDestroyed(LegacyDisplay display, ClassLoader loader) {
        display.destroyed.set(true);
        finishAfterDisplayCommit(display.lease, loader, () -> {
            legacyTokens.remove(display.token, display);
            legacyDisplays.remove(display);
        });
    }

    void legacyDestructionUncertain(LegacyDisplay display) {
        display.lease.abandon();
    }

    void legacyOwnerDied(int pid, int uid) {
        for (LegacyDisplay display : legacyDisplays) {
            if (display.ownerPid != pid || display.ownerUid != uid) continue;
            display.dead.set(true);
            if (display.token != null) scheduleLegacyCleanup(display);
        }
    }

    private void scheduleLegacyCleanup(LegacyDisplay display) {
        if (!display.cleanupScheduled.compareAndSet(false, true)) return;
        Thread cleanup = new Thread(() -> {
            // Death alone never ends a lease. The normal native destroy operation
            // and a committed SF transaction are still required before END.
            if (display.destroying.get() || display.destroyed.get()) return;
            try {
                display.cleanup.destroy(display);
                if (!display.destroyed.get()) throw new IOException("Vendor display destruction was not observed");
            } catch (Throwable error) {
                display.lease.abandon();
                reporter.report("vendor capture owner died; output cleanup is unconfirmed", error);
            }
        }, "ScreenshotCompat-vendor-display-cleanup");
        cleanup.setDaemon(true);
        try { cleanup.start(); }
        catch (Throwable error) {
            display.lease.abandon();
            reporter.report("vendor capture owner cleanup could not start", error);
        }
    }

    void requireLegacyInventory() { legacyInventoryRequired = true; }

    void configureLegacyInventory(Method factory, Method check) {
        legacyInventoryFactory = factory;
        legacyInventoryCheck = check;
    }

    interface LegacyCleanup { void destroy(LegacyDisplay display) throws Exception; }

    final class LegacyDisplay {
        final int ownerUid;
        final int ownerPid;
        final LegacyCleanup cleanup;
        final AtomicBoolean reachedFactory = new AtomicBoolean();
        final AtomicBoolean nativeSubmitted = new AtomicBoolean();
        final AtomicBoolean dead = new AtomicBoolean();
        final AtomicBoolean destroying = new AtomicBoolean();
        final AtomicBoolean destroyed = new AtomicBoolean();
        final AtomicBoolean cleanupScheduled = new AtomicBoolean();
        volatile Lease lease;
        volatile IBinder token;
        volatile ClassLoader loader;

        LegacyDisplay(int uid, int pid, LegacyCleanup cleanup) {
            ownerUid = uid; ownerPid = pid; this.cleanup = cleanup;
        }
    }

    synchronized void startReadiness(ClassLoader loader) {
        if (running) return;
        if (readyThread != null && readyThread.isAlive()) {
            reporter.report("capture policy readiness waits for the previous control thread to stop", null);
            return;
        }
        running = true;
        String generation = UUID.randomUUID().toString().replace("-", "");
        readyThread = new Thread(() -> {
            String lastState = "";
            while (running) {
                try {
                    if (!arbitrationEnabled()) {
                        LocalSocket old = control;
                        control = null;
                        close(old);
                        rememberPresence(false);
                        lastState = "disabled";
                        Thread.sleep(1000);
                        continue;
                    }
                    if (control == null) {
                        LocalSocket socket = connect();
                        control = socket;
                        if (!running) { close(socket); control = null; break; }
                        JSONObject state = request(socket, "STATUS");
                        boolean active = requiredBoolean(state, "running");
                        rememberPresence(active);
                        if (!active) throw new IOException("Capture policy broker is not running");
                        if (!inventoryEmpty(loader)) {
                            if (!lastState.equals("inventory")) reporter.report("capture policy READY waits for empty display/capture inventory", null);
                            lastState = "inventory";
                            close(socket);
                            control = null;
                        } else {
                            if (!running) { close(socket); control = null; break; }
                            JSONObject reply = request(socket, "READY " + generation);
                            rememberPresence(requiredBoolean(reply, "running"));
                            if (!bool(reply, "ready", "policy_ready")) throw new IOException("Broker readiness was not acknowledged");
                            reporter.report("capture policy READY acknowledged", null);
                            lastState = "ready";
                        }
                    } else {
                        JSONObject reply = request(control, "STATUS");
                        rememberPresence(requiredBoolean(reply, "running"));
                        if (!bool(reply, "ready", "policy_ready")) throw new IOException("Broker control is no longer ready");
                    }
                } catch (Throwable error) {
                    LocalSocket old = control;
                    control = null;
                    close(old);
                    rememberPresence(false);
                    String state = error.getClass().getName() + ":" + error.getMessage();
                    if (!state.equals(lastState)) reporter.report("capture policy readiness pending", error);
                    lastState = state;
                }
                try { Thread.sleep(1000); }
                catch (InterruptedException stopped) { if (!running) break; }
            }
        }, "ScreenshotCompat-policy-ready");
        readyThread.setDaemon(true);
        readyThread.start();
    }

    synchronized boolean stopReadiness() {
        running = false;
        LocalSocket old = control;
        control = null;
        close(old);
        close(metadataSocket);
        metadataWorker.shutdownNow();
        Thread thread = readyThread;
        if (thread != null) {
            thread.interrupt();
            try { thread.join(5000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            if (thread.isAlive()) {
                reporter.report("capture policy control thread is still stopping; reload deferred", null);
                return false;
            }
        }
        readyThread = null;
        completionWorker.shutdown();
        try {
            if (!metadataWorker.awaitTermination(1000, TimeUnit.MILLISECONDS)
                    || !completionWorker.awaitTermination(1000, TimeUnit.MILLISECONDS)) {
                reporter.report("capture policy workers are still stopping; reload deferred", null);
                return false;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    private boolean inventoryEmpty(ClassLoader loader) throws Exception {
        for (Lease lease : sessions.values()) if (lease.pipeline.equals("SCREENSHOT")) return false;
        if (!legacyDisplays.isEmpty()) return false;
        if (legacyInventoryRequired) {
            Method factory = legacyInventoryFactory;
            Method check = legacyInventoryCheck;
            if (factory == null || check == null) return false;
            Object manager = factory.invoke(null);
            if (!check.getDeclaringClass().isInstance(manager) || !Boolean.FALSE.equals(check.invoke(manager))) return false;
        }
        Method getService = inventoryServiceGetter;
        if (getService == null) {
            Class<?> manager = Class.forName("android.os.ServiceManager", false, loader);
            getService = manager.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            inventoryServiceGetter = getService;
        }
        Object binder = getService.invoke(null, "display");
        InventoryAccess cached = inventoryAccess;
        if (binder != null && cached != null && cached.binder == binder) return cached.empty();
        if (binder != null) {
            for (Class<?> owner = binder.getClass(); owner != null; owner = owner.getSuperclass()) {
                for (Field member : owner.getDeclaredFields()) {
                    if (member.getType().getName().equals("com.android.server.display.DisplayManagerService")) {
                        member.setAccessible(true);
                        Object service = member.get(binder);
                        if (service != null) {
                            cached = new InventoryAccess(binder, service, member);
                            inventoryAccess = cached;
                            return cached.empty();
                        }
                    }
                }
            }
        }
        Object adapter = observedAdapter;
        if (adapter == null) return false;
        if (cached == null || cached.binder != null || cached.owner != adapter) {
            cached = new InventoryAccess(null, adapter, null);
            inventoryAccess = cached;
        }
        return cached.empty();
    }

    /** Cache reflection members, never an inventory verdict, adapter value, or display map. */
    private static final class InventoryAccess {
        final Object binder;
        final Object owner;
        final Field ownerField;
        final Field syncRoot;
        final Field adapter;
        volatile Field devices;

        InventoryAccess(Object binder, Object owner, Field ownerField) throws Exception {
            this.binder = binder; this.owner = owner; this.ownerField = ownerField;
            syncRoot = field(owner.getClass(), "mSyncRoot");
            adapter = ownerField == null ? null : field(owner.getClass(), "mVirtualDisplayAdapter");
        }

        boolean empty() throws Exception {
            Object current = ownerField == null ? owner : ownerField.get(binder);
            if (current == null) return false;
            Object lock = syncRoot.get(current);
            if (lock == null) return false;
            synchronized (lock) {
                Object currentAdapter = adapter == null ? current : adapter.get(current);
                if (currentAdapter == null) return false;
                Field member = devices;
                if (member == null || !member.getDeclaringClass().isInstance(currentAdapter)) {
                    member = field(currentAdapter.getClass(), "mVirtualDisplayDevices");
                    devices = member;
                }
                Object map = member.get(currentAdapter);
                return map instanceof Map<?, ?> values && values.isEmpty();
            }
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field result = type.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException missing) { }
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }

    private static LocalSocket connect() throws IOException {
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(10000);
            if (socket.getPeerCredentials().getUid() != 0) throw new IOException("Capture broker peer is not root");
            return socket;
        } catch (Throwable error) {
            close(socket);
            throw error;
        }
    }

    private static JSONObject request(LocalSocket socket, String command) throws Exception {
        byte[] bytes = (command + "\n").getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > 512) throw new IOException("Capture policy command exceeds protocol limit");
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int value = socket.getInputStream().read();
            if (value < 0) throw new IOException("Capture broker disconnected");
            if (value == '\n') break;
            if (buffer.size() >= 8192 || value < 32 || value > 126) throw new IOException("Invalid capture broker reply");
            buffer.write(value);
        }
        JSONObject reply = new JSONObject(buffer.toString(StandardCharsets.US_ASCII.name()));
        if (requiredInteger(reply, "schema") != 1 || !"policy".equals(reply.get("pipeline"))) {
            throw new IOException("Unsupported capture broker protocol");
        }
        bool(reply, "ready", "policy_ready");
        bool(reply, "paused", "policy_paused");
        requiredBoolean(reply, "original_verified");
        requiredBoolean(reply, "restored_or_unmanaged");
        requiredInteger(reply, "sessions");
        if (!(reply.get("epoch") instanceof String epoch) || epoch.isEmpty()) throw new IOException("Invalid policy epoch");
        requiredInteger(reply, reply.has("blockers") ? "blockers" : "blocked_count");
        if (reply.has("blockers") && reply.has("blocked_count")
                && requiredInteger(reply, "blockers") != requiredInteger(reply, "blocked_count")) throw new IOException("Conflicting blocker count");
        if (!requiredBoolean(reply, "ok")) throw new IOException("Capture policy rejected: " + reply.optString("reason"));
        return reply;
    }

    private static boolean bool(JSONObject reply, String canonical, String alias) throws Exception {
        boolean value = reply.has(canonical) ? requiredBoolean(reply, canonical) : requiredBoolean(reply, alias);
        if (reply.has(canonical) && reply.has(alias) && value != requiredBoolean(reply, alias)) {
            throw new IOException("Conflicting policy readiness fields");
        }
        return value;
    }

    private static boolean requiredBoolean(JSONObject reply, String name) throws Exception {
        Object value = reply.get(name);
        if (!(value instanceof Boolean result)) throw new IOException("Invalid policy boolean: " + name);
        return result;
    }

    private static long requiredInteger(JSONObject reply, String name) throws Exception {
        Object value = reply.get(name);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IOException("Invalid policy integer: " + name);
        long result = ((Number) value).longValue();
        if (result < 0) throw new IOException("Negative policy integer: " + name);
        return result;
    }

    private static void close(LocalSocket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    final class Lease {
        final String token;
        final String pipeline;
        final LocalSocket socket;
        final boolean arbitrating;
        final String brokerEpoch;
        final AtomicBoolean ending = new AtomicBoolean();
        final AtomicBoolean ended = new AtomicBoolean();
        final AtomicBoolean abandoned = new AtomicBoolean();
        final AtomicBoolean completionQueued = new AtomicBoolean();
        private final Object completionLock = new Object();
        private Runnable completionCleanup;
        private boolean cleanupDelivered;
        volatile Object retained;

        Lease(String token, String pipeline, LocalSocket socket, boolean arbitrating, String brokerEpoch) {
            this.token = token; this.pipeline = pipeline; this.socket = socket; this.arbitrating = arbitrating;
            this.brokerEpoch = brokerEpoch;
        }

        void finishAsync() { finishAsync(null); }

        /** Only callers with a real completion event may enqueue this operation. */
        void finishAsync(Runnable completed) {
            if (completed != null) synchronized (completionLock) {
                if (!cleanupDelivered && completionCleanup == null) completionCleanup = completed;
            }
            if (ended.get()) { deliverCompletionCleanup(); return; }
            if (!arbitrating) { finish(); deliverCompletionCleanup(); return; }
            if (!completionQueued.compareAndSet(false, true)) {
                if (ended.get()) deliverCompletionCleanup();
                return;
            }
            // Enqueue outside all module, display and completion monitors. A full
            // queue is conservative failure, never invented completion or inline I/O.
            try {
                completionWorker.execute(() -> {
                    try {
                        finish();
                        if (ended.get()) deliverCompletionCleanup();
                    } catch (Throwable error) {
                        abandon();
                        reporter.report("capture completion processing failed; lease retained", error);
                    } finally {
                        if (!ended.get()) completionQueued.set(false);
                    }
                });
            } catch (Throwable error) {
                completionQueued.set(false);
                abandon();
                reporter.report("capture completion queue unavailable; lease retained", error);
            }
        }

        private void deliverCompletionCleanup() {
            Runnable completed;
            synchronized (completionLock) {
                if (!ended.get() || cleanupDelivered || completionCleanup == null) return;
                cleanupDelivered = true;
                completed = completionCleanup;
                completionCleanup = null;
            }
            try { completed.run(); }
            catch (Throwable error) { reporter.report("completed display bookkeeping cleanup failed", error); }
        }

        void finish() {
            if (!arbitrating) { ended.set(true); return; }
            if (ended.get()) return;
            if (!arbitrationEnabled() || (abandoned.get() && completedBrokerRetired(brokerEpoch))) {
                releaseCompletedLocally();
                return;
            }
            if (abandoned.get()) return;
            if (!ending.compareAndSet(false, true)) return;
            try {
                request(socket, "END " + token);
                ended.set(true);
                sessions.remove(token, this);
                retained = null;
                close(socket);
                if (endLogSamples.get() < 5 && endLogSamples.getAndIncrement() < 5) reporter.report("capture policy END pipeline=" + pipeline, null);
            } catch (Throwable error) {
                if (completedBrokerRetired(brokerEpoch)) {
                    releaseCompletedLocally();
                    return;
                }
                // Do not expire/release a session on an uncertain transport or native result.
                abandon();
                reporter.report("capture policy END unconfirmed; conservative session retained", error);
                ending.set(false);
            }
        }

        private void releaseCompletedLocally() {
            ended.set(true);
            sessions.remove(token, this);
            retained = null;
            close(socket);
            reporter.report("completed local lease released after arbitration stopped/changed; remote unknown state unchanged", null);
        }

        void abandon() {
            if (!arbitrating) return;
            if (ended.get() || !abandoned.compareAndSet(false, true)) return;
            // EOF without END tells the broker to retain an unknown BLOCK session.
            // Keep this local record and callback references: reload is still unsafe.
            close(socket);
            reporter.report("capture policy completion uncertain; orphan blocker retained pipeline=" + pipeline, null);
        }
    }

    static final class WrappedListener {
        final long pointer;
        final Object listener;
        final Binder forwardingBinder;
        WrappedListener(long pointer, Object listener, Binder binder) { this.pointer = pointer; this.listener = listener; forwardingBinder = binder; }
    }

    private final class ForwardListener extends Binder {
        final IBinder original;
        final Lease lease;
        ForwardListener(IBinder original, Lease lease) {
            this.original = original;
            this.lease = lease;
            attachInterface(null, LISTENER_DESCRIPTOR);
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            boolean completed = false;
            if (code == IBinder.FIRST_CALL_TRANSACTION) {
                int position = data.dataPosition();
                try { data.enforceInterface(LISTENER_DESCRIPTOR); completed = true; }
                catch (SecurityException mismatch) { }
                finally { data.setDataPosition(position); }
            }
            Parcel forwardingData = Parcel.obtain();
            try {
                // Incoming Binder data can be backed by a read-only mmap. BinderProxy
                // may update its work-source header, so forward an owned writable copy.
                // appendFrom preserves the Binder/FD object table, unlike marshalling.
                forwardingData.appendFrom(data, 0, data.dataSize());
                forwardingData.setDataPosition(data.dataPosition());
                return original.transact(code, forwardingData, reply, flags);
            } finally {
                forwardingData.recycle();
                if (completed) lease.finishAsync();
            }
        }
    }
}
