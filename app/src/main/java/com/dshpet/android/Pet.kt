package com.dshpet.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.ImageDecoder
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 一只桌宠的完整运行时：动画链 + 触摸交互 + 拖拽/甩抛物理 + 屏幕漫游。
 *
 * 与电脑版的对应关系（dsh-pet 源码）：
 *  - 物理与常量        → src/shared/physics.ts（见 Physics.kt，逐行移植，未改数值）
 *  - 命中框/比例        → src/shared/constants.ts（HIT_BOX 200..440 / 640）
 *  - 角落定位          → src/shared/motion.ts anchorPixel()
 *  - 动画链权重        → assets/config.jsonc animationWeights + categories
 * 手机端去掉了碎碎念/对话/余额/通知，因此没有任何网络与 LLM 调用。
 */
class Pet(
    private val context: Context,
    val cfg: PetConfig,
    val spec: PetSpec,
    private val onMove: (x: Int, y: Int, w: Int, h: Int) -> Unit,
) {
    companion object {
        private const val TAG = "DshPet"
        /** 移动动画名义时长（10s 片段减去两端 lead/tail 约 2s+2s） */
        private const val MOVE_WINDOW_MS = 6000L
        /** 拖出这个距离才算拖拽（dp） */
        private const val DRAG_SLOP_DP = 5.0
        /** 载入解压/解码动画用的后台线程 */
        private val io = Executors.newSingleThreadExecutor()
    }

    private val density = context.resources.displayMetrics.density

    /** 宠物盒宽度（屏幕 px） */
    var sizePx: Int = 0
        private set
    var heightPx: Int = 0
        private set

    /** 包围盒左上角（屏幕 px） */
    var x = 0.0
        private set
    var y = 0.0
        private set

    private var facing = 1                 // 1 = 朝右，-1 = 朝左
    private var forcedNoMirror = false      // 文字类动作不镜像

    private var current: AnimatedImageDrawable? = null
    private var currentSlug: String? = null
    private var loadToken = 0

    private enum class Mode { IDLE, DRAG, THROW, MOVE }
    private var mode = Mode.IDLE

    // ---- 拖拽 ----
    private val trail = ArrayList<DragSample>(32)
    private var dragTargetX = 0.0
    private var dragTargetY = 0.0
    private var dragVX = 0.0
    private var dragVY = 0.0
    private var grabDX = 0.0
    private var grabDY = 0.0
    private var downRawX = 0.0
    private var downRawY = 0.0
    private var downAtMs = 0.0
    private var dragging = false

    // ---- 抛掷 ----
    private var body = Body(0.0, 0.0, 0.0, 0.0)

    // ---- 漫游 ----
    private var moveFromX = 0.0
    private var moveToX = 0.0
    private var moveStartMs = 0L
    private var moveDurationMs = MOVE_WINDOW_MS

    // ---- Q 弹 ----
    private var squashAtMs = 0L
    private var squashAmount = Physics.SQ_SQUASH
    private var squashActive = false

    private val canvasView = PetCanvas(context)

    /** 长按回调（Service 里挂上一个「动作菜单」） */
    var onLongPress: (() -> Unit)? = null
    private var pressed = false
    private var longPressFired = false
    private val longPressRunnable = object : Runnable {
        override fun run() {
            if (pressed && !dragging) {
                longPressFired = true
                onLongPress?.invoke()
            }
        }
    }

    init {
        applyScale(spec.size)
        val (ax, ay) = anchorPixel()
        x = ax
        y = ay
    }

    val view: View get() = canvasView

    // ---------------------------------------------------------------- 尺寸/位置

    fun applyScale(sizeDp: Int) {
        sizePx = (sizeDp * density).toInt().coerceAtLeast(64)
        heightPx = (sizePx * 9 / 16).coerceAtLeast(36)
        canvasView.updateLayoutSize(sizePx, heightPx)
        push()
    }

    private fun sideAllow(): Double = sizePx * 200.0 / 640.0

    private fun bounds(): Bounds {
        val dm = context.resources.displayMetrics
        return Physics.bounds(
            dm.widthPixels.toDouble(),
            dm.heightPixels.toDouble(),
            sizePx.toDouble(),
            sideAllow(),
        )
    }

    /** motion.ts anchorPixel()：角落 + 边距 → 左上角 */
    private fun anchorPixel(): Pair<Double, Double> {
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels.toDouble()
        val h = dm.heightPixels.toDouble()
        val d = density.toDouble()
        val left = spec.marginX * d
        val top = spec.marginY * d
        val right = w - sizePx - spec.marginX * d
        val bottom = h - heightPx - spec.marginY * d
        return when (spec.corner) {
            "top-left" -> Pair(left, top)
            "bottom-left" -> Pair(left, bottom)
            "bottom-right" -> Pair(right, bottom)
            else -> Pair(right, top)   // top-right
        }
    }

    /** 回到配置的角落（右键菜单/设置里用） */
    fun resetPosition() {
        mode = Mode.IDLE
        body = Body(x, y, 0.0, 0.0)
        val (ax, ay) = anchorPixel()
        x = ax
        y = ay
        push()
        playIdle()
    }

    private fun push() {
        onMove(x.toInt(), y.toInt(), sizePx, heightPx)
    }

    /** 外部（Service）把窗口加上去之后调用一次，把当前位置同步给 WindowManager */
    fun refreshWindow() = push()

    private fun clampToScreen() {
        val b = bounds()
        x = x.coerceIn(b.minX, b.maxX)
        y = y.coerceIn(b.minY, b.maxY)
    }

    // ---------------------------------------------------------------- 动画播放

    /** 当前是否在播（供链式调度判断） */
    private fun isPlaying(): Boolean {
        val d = current ?: return false
        return d.isRunning
    }

    private fun play(slug: String, onEnd: (() -> Unit)? = null) {
        if (slug.isEmpty()) {
            onEnd?.invoke()
            return
        }
        loadToken++
        val token = loadToken
        io.execute {
            var d: AnimatedImageDrawable? = null
            try {
                val src = ImageDecoder.createSource(context.assets, "webp/$slug.webp")
                val decoded = ImageDecoder.decodeDrawable(src)
                if (decoded is AnimatedImageDrawable) d = decoded
            } catch (t: Throwable) {
                Log.w(TAG, "解码失败 $slug: ${t.message}")
            }
            canvasView.post {
                if (token != loadToken) return@post      // 已被更新的动画取代
                val dd = d ?: run { onEnd?.invoke(); return@post }
                dd.repeatCount = 1
                dd.callback = canvasView
                current?.stop()
                current = dd
                currentSlug = slug
                dd.start()
                canvasView.invalidate()
                if (onEnd != null) waitClipEnd(dd, token, onEnd)
            }
        }
    }

    /** 轮询 isRunning 判断片段是否播完（AnimatedImageDrawable 没有完成回调） */
    private fun waitClipEnd(d: AnimatedImageDrawable, token: Int, onEnd: () -> Unit) {
        val poll = object : Runnable {
            override fun run() {
                if (token != loadToken) return
                if (d.isRunning) {
                    canvasView.postDelayed(this, 120)
                } else {
                    onEnd()
                }
            }
        }
        canvasView.postDelayed(poll, 200)
    }

    private fun pickByWeight(items: List<AnimCategory>): AnimCategory? {
        val total = items.sumOf { max(it.weight, 0) }
        if (total <= 0) return items.randomOrNull()
        var r = Random.nextInt(total)
        for (c in items) {
            if (c.weight <= 0) continue
            r -= c.weight
            if (r < 0) return c
        }
        return items.lastOrNull()
    }

    /**
     * 权重选下一段：idle/turn/move 各占配置权重，剩余 80% 全给「随机动作分类」。
     * 与 dsh-pet 客户端一致（默认 idle 10 / turn 5 / move 5，其余随机动作）。
     */
    private fun scheduleNext() {
        val wIdle = max(cfg.weightIdle, 0)
        val wTurn = max(cfg.weightTurn, 0)
        val wMove = max(cfg.weightMove, 0)
        val wRandom = 80
        val total = wIdle + wTurn + wMove + wRandom
        var r = Random.nextInt(total.coerceAtLeast(1))
        if (r < wIdle) { playIdle(); return }
        r -= wIdle
        if (r < wTurn) { playTurn(); return }
        r -= wTurn
        if (r < wMove) { playMove(); return }
        playRandomAction()
    }

    private fun playIdle() {
        if (cfg.idle.isEmpty()) { playRandomAction(); return }
        mode = Mode.IDLE
        play(cfg.idle.random()) { scheduleNext() }
    }

    private fun playTurn() {
        if (cfg.turn.isEmpty()) { playIdle(); return }
        facing = if (facing == 1) -1 else 1
        forcedNoMirror = false
        mode = Mode.IDLE
        canvasView.invalidate()
        play(cfg.turn.random()) { scheduleNext() }
    }

    private fun playRandomAction() {
        val cat = pickByWeight(cfg.categories)
        if (cat == null || cat.actions.isEmpty()) { playIdle(); return }
        forcedNoMirror = cat.noMirror
        mode = Mode.IDLE
        play(cat.actions.random()) {
            forcedNoMirror = false
            scheduleNext()
        }
    }

    /** 漫游：朝当前朝向走一段（距离按 size/462 等比缩放），出界则退回待机 */
    private fun playMove() {
        if (cfg.moves.isEmpty()) { playIdle(); return }
        val act = cfg.moves.random()
        val scale = sizePx / PetConfig.PET_REF_WIDTH
        val dist = Random.nextDouble(act.minDist, max(act.maxDist, act.minDist + 1.0)) * scale
        val target = x + facing * dist
        val halfW = sizePx / 2.0
        val side = sideAllow()
        val margin = cfg.moveMargin * density
        val dm = context.resources.displayMetrics
        val leftBound = margin + halfW - side
        val rightBound = dm.widthPixels - margin - halfW + side
        if (target < leftBound || target > rightBound) {
            // 走不了就掉头（避免卡在边上连续放弃）
            facing = -facing
            canvasView.invalidate()
            playIdle()
            return
        }
        mode = Mode.MOVE
        moveFromX = x
        moveToX = target
        moveStartMs = SystemClock.uptimeMillis()
        moveDurationMs = MOVE_WINDOW_MS
        play(act.slug) {
            x = moveToX
            push()
            mode = Mode.IDLE
            scheduleNext()
        }
    }

    // ---------------------------------------------------------------- 触摸

    private fun nowMs(): Double = SystemClock.uptimeMillis().toDouble()

    private fun startThrow(vx: Double, vy: Double) {
        body = Body(x, y, vx, vy)
        mode = Mode.THROW
        playDragIfAny()
    }

    private fun playDragIfAny() {
        if (cfg.drag.isEmpty()) return
        play(cfg.drag.random())
    }

    private fun squash(amount: Double = Physics.SQ_SQUASH) {
        squashAmount = amount
        squashAtMs = SystemClock.uptimeMillis()
        squashActive = true
        canvasView.invalidate()
    }

    private fun onTap() {
        squash(Physics.SQ_SQUASH)
        if (cfg.clicks.isEmpty()) { playIdle(); return }
        mode = Mode.IDLE
        forcedNoMirror = false
        play(cfg.clicks.random()) { scheduleNext() }
    }

    // ---------------------------------------------------------------- 每帧

    fun tick(): Boolean {
        val now = SystemClock.uptimeMillis()
        var active = false

        when (mode) {
            Mode.DRAG -> {
                val dt = 1.0 / 60.0
                dragVX = Physics.springStep(dragVX, x, dragTargetX, dt)
                dragVY = Physics.springStep(dragVY, y, dragTargetY, dt)
                x += dragVX * dt
                y += dragVY * dt
                clampToScreen()
                push()
                active = true
            }
            Mode.THROW -> {
                val b = bounds()
                val vyBefore = body.vy
                val r = Physics.throwStep(Body(x, y, body.vx, body.vy), 1.0 / 60.0, b, cfg.physics)
                x = r.body.x
                y = r.body.y
                body = r.body
                if (r.bounced && abs(r.body.y - b.maxY) < 1.5 && abs(vyBefore) > Physics.SQ_SOFT_SPEED) {
                    squash(Physics.landingSquash(vyBefore))
                }
                push()
                if (r.atRest) {
                    mode = Mode.IDLE
                    playIdle()
                } else {
                    active = true
                }
            }
            Mode.MOVE -> {
                val elapsed = now - moveStartMs
                val p = (elapsed.toDouble() / moveDurationMs).coerceIn(0.0, 1.0)
                // 中段匀速、两端缓入缓出（近似 leadSec/tailSec 的效果）
                val eased = if (p < 0.2) (p / 0.2).let { it * it * (3 - 2 * it) * 0.2 }
                else if (p > 0.8) 1.0 - ((1 - p) / 0.2).let { it * it * (3 - 2 * it) } * 0.2
                else p
                x = moveFromX + (moveToX - moveFromX) * eased
                push()
                active = p < 1.0
            }
            Mode.IDLE -> {}
        }

        if (squashActive) {
            val u = (now - squashAtMs).toDouble() / Physics.SQ_DURATION_MS
            if (u >= 1.0) {
                squashActive = false
            } else {
                active = true
            }
            canvasView.invalidate()
            active = true
        }

        // 动画链由 start() 启动、由每段动画的 onEnd 续接；
        // 这里绝不能兜底调用 scheduleNext()——解码是异步的，每帧重触发会不停取消上一段解码。
        return active
    }

    fun start() {
        val (ax, ay) = anchorPixel()
        x = ax
        y = ay
        push()
        playIdle()
    }

    fun release() {
        loadToken++
        current?.stop()
        current = null
    }

    /** 点播指定动画（长按动作菜单用），播完自动回到随机链 */
    fun playAction(slug: String) {
        mode = Mode.IDLE
        dragging = false
        pressed = false
        forcedNoMirror = false
        play(slug) { scheduleNext() }
    }

    /** 左右翻转朝向 */
    fun flip() {
        facing = if (facing == 1) -1 else 1
        forcedNoMirror = false
        canvasView.invalidate()
    }

    // ---------------------------------------------------------------- 画布

    inner class PetCanvas(ctx: Context) : View(ctx), Drawable.Callback {
        init {
            setWillNotDraw(false)
        }

        fun updateLayoutSize(w: Int, h: Int) {
            // 注意：View.setLayoutParams(null) 会抛 NPE，而视图挂到 WindowManager 之前
            // layoutParams 就是 null。窗口尺寸由 Service 的 WindowManager.LayoutParams 决定，
            // 这里只在已存在 LP 时同步一下，绝不能让 null 回流。
            val lp = layoutParams ?: return
            lp.width = w
            lp.height = h
            layoutParams = lp
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = current ?: return
            val w = width.toFloat()
            val h = height.toFloat()
            val mirror = !forcedNoMirror && facing < 0
            var sy = 1.0
            if (squashActive) {
                val u = ((SystemClock.uptimeMillis() - squashAtMs).toDouble() / Physics.SQ_DURATION_MS)
                    .coerceIn(0.0, 1.0)
                sy = Physics.squashScale(u, squashAmount)
            }
            canvas.save()
            canvas.translate(w / 2f, h)          // 以脚底中点为锚点
            canvas.scale(if (mirror) -1f else 1f, sy.toFloat())
            canvas.translate(-w / 2f, -h)
            d.setBounds(0, 0, width, height)
            d.draw(canvas)
            canvas.restore()
        }

        // Drawable.Callback：动画帧推进时重绘
        override fun invalidateDrawable(who: Drawable) = invalidate()
        override fun scheduleDrawable(who: Drawable, what: Runnable, whenMs: Long) {
            postDelayed(what, whenMs)
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            removeCallbacks(what)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val rawX = event.rawX.toDouble()
            val rawY = event.rawY.toDouble()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = rawX
                    downRawY = rawY
                    downAtMs = nowMs()
                    grabDX = rawX - this@Pet.x
                    grabDY = rawY - this@Pet.y
                    dragging = false
                    pressed = true
                    longPressFired = false
                    canvasView.removeCallbacks(longPressRunnable)
                    canvasView.postDelayed(longPressRunnable, 450L)
                    mode = Mode.IDLE
                    trail.clear()
                    trail.add(DragSample(downAtMs, rawX, rawY))
                    body = Body(this@Pet.x, this@Pet.y, 0.0, 0.0)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    trail.add(DragSample(nowMs(), rawX, rawY))
                    if (trail.size > 64) trail.removeAt(0)
                    val dist = Math.hypot(rawX - downRawX, rawY - downRawY)
                    if (!dragging && dist > DRAG_SLOP_DP * density) {
                        dragging = true
                        pressed = false   // 一旦拖动就不再算长按
                        canvasView.removeCallbacks(longPressRunnable)
                        playDragIfAny()
                    }
                    if (dragging) {
                        mode = Mode.DRAG
                        dragTargetX = rawX - grabDX
                        dragTargetY = rawY - grabDY
                        dragVX = 0.0
                        dragVY = 0.0
                        // 拖动中让动画跟着朝向走
                        if (dragTargetX > this@Pet.x + 2) { facing = 1; forcedNoMirror = false }
                        else if (dragTargetX < this@Pet.x - 2) { facing = -1; forcedNoMirror = false }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    pressed = false
                    canvasView.removeCallbacks(longPressRunnable)
                    val now = nowMs()
                    if (longPressFired) {
                        // 长按已经弹出动作菜单，抬手不再触发点击回应
                        longPressFired = false
                        dragging = false
                        return true
                    }
                    if (!dragging) {
                        onTap()
                    } else {
                        val trimmed = Physics.trimTrail(trail, now)
                        val v = Physics.estimateReleaseVelocity(trimmed, now, cfg.physics)
                        if (v == null) {
                            mode = Mode.IDLE
                            playIdle()
                        } else {
                            startThrow(v.first, v.second)
                        }
                    }
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressed = false
                    canvasView.removeCallbacks(longPressRunnable)
                    dragging = false
                    mode = Mode.IDLE
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
