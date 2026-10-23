package net.hawyn.screenshotfreedoma17

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.IBinder
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException

data class SurfaceTestStatus(val submitted: Boolean, val message: String, val layerId: Int? = null)

/** A real, independent SCVH subtree. No secure-window or secure-surface flag is used here. */
class SkipTestSurface(
    context: Context,
    private val report: (SurfaceTestStatus) -> Unit,
) : SurfaceView(context), SurfaceHolder.Callback {
    private var host: Any? = null
    private var childPackage: Any? = null
    private var control: Any? = null
    private var pattern: PatternView? = null
    private var surfaceReady = false
    private var disposed = false
    private var requestedSkip = true
    private var seed = 1

    init {
        holder.addCallback(this)
        contentDescription = "SKIP_SCREENSHOT 实际子图层测试图案"
        setZOrderOnTop(false)
    }

    fun update(skip: Boolean, patternSeed: Int) {
        val changed = requestedSkip != skip
        requestedSkip = skip
        if (seed != patternSeed) {
            seed = patternSeed
            pattern?.seed = seed
            pattern?.invalidate()
        }
        if (changed && host != null) applyRequestedFlag()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        createHost(width, height)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (disposed) return
        if (host == null) createHost(width, height)
        else runCatching {
            host!!.javaClass.getMethod("relayout", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(host, width, height)
        }.onFailure { reportError("调整测试图层尺寸失败", it) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        releaseHost()
    }

    private fun createHost(width: Int, height: Int) {
        if (disposed || !surfaceReady || width <= 0 || height <= 0 || host != null) return
        try {
            check(HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/view/SurfaceControl;", "Landroid/view/SurfaceControl\$Transaction;",
                "Landroid/view/SurfaceControlViewHost\$SurfacePackage;", "Landroid/view/SurfaceView;",
            )) { "本进程隐藏接口访问准备失败" }
            val hostClass = Class.forName("android.view.SurfaceControlViewHost")
            val packageClass = Class.forName("android.view.SurfaceControlViewHost\$SurfacePackage")
            val token = SurfaceView::class.java.getMethod("getHostToken").invoke(this) as? IBinder
            val currentDisplay = display ?: error("测试视图尚未关联显示屏")
            val created = hostClass.getConstructor(Context::class.java, android.view.Display::class.java, IBinder::class.java)
                .newInstance(context, currentDisplay, token)
            host = created
            val testView = PatternView(context).also { it.seed = seed }
            pattern = testView
            hostClass.getMethod("setView", View::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(created, testView, width, height)
            val pack = hostClass.getMethod("getSurfacePackage").invoke(created)
                ?: error("SurfacePackage 为空")
            childPackage = pack
            control = packageClass.getMethod("getSurfaceControl").invoke(pack)
                ?: error("测试 SurfaceControl 为空")
            SurfaceView::class.java.getMethod("setChildSurfacePackage", packageClass).invoke(this, pack)
            applyRequestedFlag()
        } catch (error: Throwable) {
            reportError("测试图层创建失败", error)
            releaseHost()
        }
    }

    private fun setSkip(target: Any, skip: Boolean) {
        val surfaceClass = Class.forName("android.view.SurfaceControl")
        val transactionClass = Class.forName("android.view.SurfaceControl\$Transaction")
        val transaction = transactionClass.getConstructor().newInstance()
        try {
            transactionClass.getMethod("setSkipScreenshot", surfaceClass, Boolean::class.javaPrimitiveType)
                .invoke(transaction, target, skip)
            transactionClass.getMethod("apply").invoke(transaction)
        } finally {
            runCatching { transactionClass.getMethod("close").invoke(transaction) }
        }
    }

    private fun applyRequestedFlag() {
        val target = control ?: return
        try {
            setSkip(target, requestedSkip)
            val id = runCatching { target.javaClass.getMethod("getLayerId").invoke(target) as Int }.getOrNull()
            report(SurfaceTestStatus(
                submitted = true,
                message = if (requestedSkip) "排除请求已提交 · 请用系统截屏核验图案" else "排除请求已清除 · 图案作为对照",
                layerId = id,
            ))
        } catch (error: Throwable) {
            reportError("SKIP_SCREENSHOT 请求失败", error)
        }
    }

    private fun reportError(prefix: String, failure: Throwable) {
        if (disposed) return
        val error = if (failure is InvocationTargetException) failure.targetException else failure
        report(SurfaceTestStatus(false, "$prefix\n${error.javaClass.simpleName}: ${error.message ?: "未返回详情"}"))
    }

    private fun releaseHost() {
        control?.let { runCatching { setSkip(it, false) } }
        runCatching {
            val packageClass = Class.forName("android.view.SurfaceControlViewHost\$SurfacePackage")
            SurfaceView::class.java.getMethod("setChildSurfacePackage", packageClass).invoke(this, null)
        }
        host?.let { runCatching { it.javaClass.getMethod("release").invoke(it) } }
        // release() is idempotent. Also release explicitly when an OEM rejects null detachment.
        childPackage?.let { runCatching { it.javaClass.getMethod("release").invoke(it) } }
        host = null
        childPackage = null
        control = null
        pattern = null
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        holder.removeCallback(this)
        releaseHost()
    }

    override fun onDetachedFromWindow() {
        releaseHost()
        super.onDetachedFromWindow()
    }

    // UI_PREVIEW_FACTORY_BEGIN
    enum class FramePhase { WAITING_SURFACE, DRAWING, COMMITTING, READY, FAILED }

    data class FrameState(val phase: FramePhase, val message: String, val clearSubmission: Boolean = false)

    interface FrameObservation {
        fun requestRedraw()
        fun cancel()
    }

    companion object {
        /** SurfaceView owns a native surface, so give the View the same rounded clip as Compose. */
        fun configureTestView(surface: SkipTestSurface) {
            surface.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 24f * view.resources.displayMetrics.density)
                }
            }
            surface.clipToOutline = true
        }

        /** Synchronize an actual windowless child draw and its transaction with SurfaceFlinger. */
        fun observeFrameReadiness(
            surface: SkipTestSurface,
            onState: (FrameState) -> Unit,
        ): FrameObservation {
            class Observer : FrameObservation, SurfaceHolder.Callback, View.OnAttachStateChangeListener {
                private val handler = android.os.Handler(android.os.Looper.getMainLooper())
                private val started = android.os.SystemClock.uptimeMillis()
                private var stopped = false
                private var generation = 0
                private var observedHost: Any? = null
                private var observedChild: PatternView? = null
                private var requested = false
                private var failed = false
                private var ready = false
                private var drawSeen = false
                private var commitSeen = false
                private var queuedRedraw = false
                private var lastWidth = 0
                private var lastHeight = 0
                private var poll: Runnable? = null
                private var timeout: Runnable? = null
                private var drawObserver: android.view.ViewTreeObserver? = null
                private var drawListener: android.view.ViewTreeObserver.OnDrawListener? = null
                private val visibleRect = android.graphics.Rect()
                private var waitingForVisibility = false

                private fun log(event: String) {
                    android.util.Log.d("SkipTestFrame", "surface=${System.identityHashCode(surface)} gen=$generation " +
                        "host=${observedHost?.let { System.identityHashCode(it) }} " +
                        "child=${observedChild?.let { System.identityHashCode(it) }} " +
                        "elapsed=${android.os.SystemClock.uptimeMillis() - started}ms $event")
                }

                private fun publish(phase: FramePhase, message: String, clearSubmission: Boolean = false) {
                    if (!stopped) onState(FrameState(phase, message, clearSubmission))
                }

                private fun current(token: Int, host: Any, child: PatternView): Boolean =
                    !stopped && !surface.disposed && token == generation &&
                        observedHost === host && observedChild === child &&
                        surface.host === host && surface.pattern === child && child.isAttachedToWindow

                private fun visible(): Boolean = surface.getGlobalVisibleRect(visibleRect) && !visibleRect.isEmpty

                private fun clearWaiters() {
                    poll?.let { handler.removeCallbacks(it) }
                    timeout?.let { handler.removeCallbacks(it) }
                    poll = null
                    timeout = null
                    val observer = drawObserver
                    val listener = drawListener
                    drawObserver = null
                    drawListener = null
                    // Never mutate the observer's listener list during dispatchOnDraw.
                    if (observer != null && listener != null) handler.post {
                        if (observer.isAlive) runCatching { observer.removeOnDrawListener(listener) }
                    }
                }

                private fun fail(token: Int, message: String, error: Throwable? = null) {
                    if (stopped || token != generation) return
                    failed = true
                    ready = false
                    clearWaiters()
                    log("failed=$message error=${error?.javaClass?.simpleName}")
                    publish(FramePhase.FAILED, message)
                }

                private fun completeIfReady(token: Int, host: Any, child: PatternView) {
                    if (!current(token, host, child) || failed || ready || !drawSeen || !commitSeen) return
                    if (queuedRedraw) {
                        begin("queued_update")
                        return
                    }
                    ready = true
                    clearWaiters()
                    log("ready draw=true sfCommit=true size=${child.width}x${child.height}")
                    publish(FramePhase.READY, "当前真实图层绘制已同步到系统合成器")
                }

                private fun requestSyncedDraw(token: Int, host: Any, child: PatternView) {
                    try {
                        check(HiddenApiBypass.addHiddenApiExemptions(
                            "Landroid/view/SurfaceControlViewHost;", "Landroid/view/WindowlessWindowManager;",
                            "Landroid/view/SurfaceControl\$Transaction;", "Landroid/view/SurfaceControl\$TransactionCommittedListener;",
                        )) { "本进程绘制同步接口准备失败" }
                        val resizeCallback = Class.forName("android.view.WindowlessWindowManager\$ResizeCompleteCallback")
                        val transactionClass = Class.forName("android.view.SurfaceControl\$Transaction")
                        val commitCallback = Class.forName("android.view.SurfaceControl\$TransactionCommittedListener")
                        val apply = transactionClass.getMethod("apply")
                        val addCommitted = transactionClass.getMethod("addTransactionCommittedListener", java.util.concurrent.Executor::class.java, commitCallback)
                        val relayout = host.javaClass.getMethod("relayout", android.view.WindowManager.LayoutParams::class.java, resizeCallback)
                        val attrs = android.view.WindowManager.LayoutParams().also {
                            it.copyFrom(child.layoutParams as android.view.WindowManager.LayoutParams)
                        }
                        val executor = java.util.concurrent.Executor { runnable -> handler.post(runnable); Unit }
                        val committed = java.lang.reflect.Proxy.newProxyInstance(
                            commitCallback.classLoader ?: surface.javaClass.classLoader, arrayOf(commitCallback),
                        ) { proxy, method, args ->
                            when (method.name) {
                                "onTransactionCommitted" -> {
                                    handler.post {
                                        if (current(token, host, child)) {
                                            commitSeen = true
                                            log("sf_transaction_committed drawSeen=$drawSeen")
                                            completeIfReady(token, host, child)
                                        }
                                    }
                                    null
                                }
                                "toString" -> "SkipTestFrameCommit($token)"
                                "hashCode" -> System.identityHashCode(proxy)
                                "equals" -> proxy === args?.firstOrNull()
                                else -> null
                            }
                        }
                        val resized = java.lang.reflect.Proxy.newProxyInstance(
                            resizeCallback.classLoader ?: surface.javaClass.classLoader, arrayOf(resizeCallback),
                        ) { proxy, method, args ->
                            when (method.name) {
                                "finished" -> {
                                    val transaction = args?.firstOrNull()
                                    var failure: Throwable? = null
                                    if (transaction == null) {
                                        failure = IllegalStateException("绘制同步未返回事务")
                                    } else {
                                        try {
                                            if (current(token, host, child)) addCommitted.invoke(transaction, executor, committed)
                                        } catch (error: Throwable) {
                                            failure = error
                                        } finally {
                                            // WindowlessWindowManager delegates application to this callback.
                                            // The borrowed transaction MUST be applied synchronously, even
                                            // after cancellation/timeout; ViewRoot clears it on return.
                                            // Never close it, postpone apply, or throw through the framework.
                                            try {
                                                apply.invoke(transaction)
                                            } catch (error: Throwable) {
                                                failure = error
                                            }
                                        }
                                    }
                                    val callbackFailure = failure
                                    android.util.Log.d("SkipTestFrame", "surface=${System.identityHashCode(surface)} callbackGen=$token " +
                                        "callbackHost=${System.identityHashCode(host)} finishDrawingApplied=${callbackFailure == null} current=${current(token, host, child)}")
                                    handler.post {
                                        if (current(token, host, child)) {
                                            log("finishDrawing applied=${callbackFailure == null}")
                                            if (callbackFailure != null) fail(token, "真实图层绘制事务同步失败，请重建测试图层", callbackFailure)
                                            else if (!ready && !failed) publish(FramePhase.COMMITTING, "真实图层已绘制，等待系统合成器确认")
                                        }
                                    }
                                    null
                                }
                                "toString" -> "SkipTestFrameResize($token)"
                                "hashCode" -> System.identityHashCode(proxy)
                                "equals" -> proxy === args?.firstOrNull()
                                else -> null
                            }
                        }
                        val observer = child.viewTreeObserver
                        val listener = android.view.ViewTreeObserver.OnDrawListener {
                            if (current(token, host, child) && !drawSeen) {
                                drawSeen = true
                                handler.post {
                                    if (current(token, host, child)) {
                                        log("child_draw")
                                        completeIfReady(token, host, child)
                                    }
                                }
                            }
                        }
                        drawObserver = observer
                        drawListener = listener
                        observer.addOnDrawListener(listener)
                        requested = true
                        lastWidth = surface.width
                        lastHeight = surface.height
                        var visibleWait = 0L
                        var lastCheck = android.os.SystemClock.uptimeMillis()
                        lateinit var deadline: Runnable
                        deadline = Runnable {
                            if (stopped || token != generation || ready || failed) return@Runnable
                            val now = android.os.SystemClock.uptimeMillis()
                            if (visible()) visibleWait += now - lastCheck
                            lastCheck = now
                            if (visibleWait >= 8_000) {
                                val stage = if (!drawSeen) "真实图层绘制" else "系统合成器确认"
                                fail(token, "$stage 等待超时，请重建测试图层")
                            } else handler.postDelayed(deadline, 250)
                        }
                        timeout = deadline
                        handler.postDelayed(deadline, 250)
                        publish(FramePhase.DRAWING, "正在同步当前真实图层的绘制")
                        log("sync_relayout size=${child.width}x${child.height} hw=${child.isHardwareAccelerated}")
                        relayout.invoke(host, attrs, resized)
                        child.invalidate()
                    } catch (error: Throwable) {
                        val cause = if (error is InvocationTargetException) error.targetException else error
                        fail(token, "真实图层绘制同步失败：${cause.javaClass.simpleName}；可重建测试图层", cause)
                    }
                }

                private fun begin(reason: String) {
                    if (stopped || surface.disposed) return
                    generation++
                    clearWaiters()
                    observedHost = null
                    observedChild = null
                    requested = false
                    failed = false
                    ready = false
                    drawSeen = false
                    commitSeen = false
                    queuedRedraw = false
                    waitingForVisibility = false
                    val token = generation
                    log("begin=$reason")
                    publish(FramePhase.WAITING_SURFACE, "正在创建当前真实测试图层")
                    poll = Runnable {
                        if (stopped || token != generation || surface.disposed) return@Runnable
                        val host = surface.host
                        val child = surface.pattern
                        if (surface.isAttachedToWindow && host != null && child != null &&
                            child.isAttachedToWindow && child.width > 0 && child.height > 0 && child.viewTreeObserver.isAlive) {
                            observedHost = host
                            observedChild = child
                            if (visible()) requestSyncedDraw(token, host, child)
                            else {
                                if (!waitingForVisibility) {
                                    waitingForVisibility = true
                                    log("waiting_for_visible_surface")
                                    publish(FramePhase.WAITING_SURFACE, "真实测试图层已保留，进入可见区域后同步绘制")
                                }
                                poll?.let { handler.postDelayed(it, 32) }
                            }
                        } else poll?.let { handler.postDelayed(it, 16) }
                    }
                    handler.post(poll!!)
                }

                private fun lost(reason: String) {
                    if (stopped) return
                    generation++
                    clearWaiters()
                    observedHost = null
                    observedChild = null
                    requested = false
                    ready = false
                    failed = false
                    log("surface_lost=$reason")
                    publish(FramePhase.WAITING_SURFACE, "当前真实图层已释放，等待重新创建", clearSubmission = true)
                }

                fun start() {
                    surface.holder.addCallback(this)
                    surface.addOnAttachStateChangeListener(this)
                    begin("observe")
                }

                override fun requestRedraw() {
                    if (stopped || surface.disposed) return
                    if (failed) {
                        log("redraw_requires_rebuild")
                        return
                    }
                    if (requested && !ready) {
                        queuedRedraw = true
                        publish(FramePhase.DRAWING, "测试内容已更新，等待当前真实图层重新同步")
                    } else begin("content_update")
                }

                override fun surfaceCreated(holder: SurfaceHolder) = begin("surface_created")

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    if (surface.host !== observedHost || surface.pattern !== observedChild ||
                        (ready && (width != lastWidth || height != lastHeight))) {
                        // An initial size callback before host discovery needs no second request.
                        if (requested || ready || failed) begin("surface_changed")
                    }
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) = lost("destroyed")

                override fun onViewAttachedToWindow(view: View) {
                    log("view_attached")
                    if (!requested && !ready && !failed) begin("view_attached")
                }

                override fun onViewDetachedFromWindow(view: View) = lost("detached")

                override fun cancel() {
                    if (stopped) return
                    log("cancel")
                    stopped = true
                    generation++
                    clearWaiters()
                    surface.holder.removeCallback(this)
                    surface.removeOnAttachStateChangeListener(this)
                }
            }
            return Observer().also { it.start() }
        }
    }
    // UI_PREVIEW_FACTORY_END

    private class PatternView(context: Context) : View(context) {
        var seed = 1
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val colors = intArrayOf(0xFF397CF6.toInt(), 0xFF26BA96.toInt(), 0xFFFFAB46.toInt(), 0xFF9D73F3.toInt())

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0xFF15243C.toInt())
            val inset = width * .055f
            val gap = width * .025f
            val tileWidth = (width - inset * 2 - gap) / 2f
            val startY = height * .15f
            val tileHeight = height * .29f
            "SKIP".forEachIndexed { index, letter ->
                val left = inset + (index % 2) * (tileWidth + gap)
                val top = startY + (index / 2) * (tileHeight + gap)
                paint.color = colors[(index + seed - 1) % colors.size]
                canvas.drawRoundRect(RectF(left, top, left + tileWidth, top + tileHeight), inset * .6f, inset * .6f, paint)
                paint.color = Color.WHITE
                paint.textSize = tileHeight * .5f
                paint.isFakeBoldText = true
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(letter.toString(), left + tileWidth / 2, top + tileHeight * .67f, paint)
            }
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.LEFT
            paint.isFakeBoldText = false
            paint.textSize = width * .036f
            canvas.drawText("真实 SCVH 图层  /  图案 $seed", inset, height * .09f, paint)
            paint.color = 0xFFAFC3E1.toInt()
            paint.textSize = width * .029f
            canvas.drawText("截图中看得到这四色与 SKIP，即采集到了子图层", inset, height * .91f, paint)
        }
    }
}
