package com.dshpet.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 桌宠前台服务：管住所有悬浮窗，并按帧驱动物理/动画。
 *
 * 本版本（v1.1）把每一步都包在 try/catch 里并写入 [Diag]：
 * 手机上闪退看不到 logcat，所以宁可降级成「界面上显示错误」也不要让进程死掉。
 */
class PetService : Service() {

    companion object {
        private const val TAG = "DshPetSvc"
        private const val CHANNEL_ID = "dsh_pet_overlay"
        private const val NOTIF_ID = 0x5E70

        const val ACTION_START = "com.dshpet.android.action.START"
        const val ACTION_STOP = "com.dshpet.android.action.STOP"
        const val ACTION_RELOAD = "com.dshpet.android.action.RELOAD"

        fun start(ctx: Context) {
            val i = Intent(ctx, PetService::class.java).setAction(ACTION_START)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, PetService::class.java).setAction(ACTION_STOP))
        }

        fun reload(ctx: Context) {
            ctx.startService(Intent(ctx, PetService::class.java).setAction(ACTION_RELOAD))
        }
    }

    private class Held(val pet: Pet, val view: View, val params: WindowManager.LayoutParams)

    private var wm: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val held = ArrayList<Held>()
    private var cfg: PetConfig? = null
    private var menuView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        } catch (t: Throwable) {
            Diag.record(this, "onCreate: 取 WindowManager 失败", t)
        }
        try {
            ensureChannel()
        } catch (t: Throwable) {
            Diag.record(this, "onCreate: 建通知渠道失败", t)
        }
        try {
            goForeground()
        } catch (t: Throwable) {
            Diag.record(this, "onCreate: startForeground 失败（前台服务起不来）", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "(null)"
        Diag.record(this, "onStartCommand action=$action sdk=${Build.VERSION.SDK_INT}")
        return try {
            when (intent?.action) {
                ACTION_STOP -> {
                    Prefs.setRunning(this, false)
                    stopSelf()
                    return START_NOT_STICKY
                }
                ACTION_RELOAD -> {
                    teardown()
                    spawnAll(Prefs.sizeDp(this))
                }
                else -> {
                    if (held.isEmpty()) spawnAll(Prefs.sizeDp(this))
                }
            }
            startTicker()
            // 用 NOT_STICKY：崩溃后不要被系统反复拉起形成闪退循环
            START_NOT_STICKY
        } catch (t: Throwable) {
            Diag.record(this, "onStartCommand 失败", t)
            Prefs.setRunning(this, false)
            stopSelf()
            START_NOT_STICKY
        }
    }

    // ------------------------------------------------------------ 前台通知

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIcon = Icon.createWithResource(this, R.drawable.ic_launcher)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(stopIcon, getString(R.string.notif_stop), stop).build())
            .build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ------------------------------------------------------------ 悬浮窗

    private fun spawnAll(sizeDp: Int) {
        val w = wm
        if (w == null) {
            Diag.record(this, "spawnAll: WindowManager 为空")
            return
        }
        val can = Settings.canDrawOverlays(this)
        Diag.record(this, "spawnAll: 悬浮窗权限=$can sizeDp=$sizeDp")
        if (!can) {
            Prefs.setRunning(this, false)
            return
        }

        val conf = try {
            PetConfig.load(this)
        } catch (t: Throwable) {
            Diag.record(this, "spawnAll: 配置加载失败（pet-config.json）", t)
            return
        }

        val dm = resources.displayMetrics
        Diag.record(
            this,
            "spawnAll: 屏幕 ${dm.widthPixels}x${dm.heightPixels} density=${dm.density} 宠物数=${conf.pets.size}",
        )
        cfg = conf

        for (spec in conf.pets) {
            try {
                var paramsRef: WindowManager.LayoutParams? = null
                var viewRef: View? = null

                val pet = Pet(this, conf, spec) { x, y, ww, hh ->
                    val p = paramsRef
                    val v = viewRef
                    if (p != null && v != null) {
                        p.x = x
                        p.y = y
                        p.width = ww
                        p.height = hh
                        try {
                            w.updateViewLayout(v, p)
                        } catch (_: Throwable) {
                        }
                    }
                }
                pet.applyScale(sizeDp)
                pet.onLongPress = { showActionMenu(pet) }

                val lp = WindowManager.LayoutParams(
                    pet.sizePx,
                    pet.heightPx,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                )
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = pet.x.toInt()
                lp.y = pet.y.toInt()

                paramsRef = lp
                viewRef = pet.view

                Diag.record(this, "addView: ${lp.width}x${lp.height} @ (${lp.x},${lp.y})")
                w.addView(pet.view, lp)
                pet.refreshWindow()
                pet.start()
                held.add(Held(pet, pet.view, lp))
                Diag.record(this, "宠物已创建: ${spec.name}")
            } catch (t: Throwable) {
                Diag.record(this, "创建宠物失败（${spec.name}）", t)
            }
        }
        Diag.record(this, "spawnAll 完成，成功 ${held.size} 只")
    }

    private fun teardown() {
        handler.removeCallbacks(tickRunnable)
        dismissMenu()
        val w = wm
        for (h in held) {
            try {
                h.pet.release()
            } catch (_: Throwable) {
            }
            try {
                w?.removeView(h.view)
            } catch (_: Throwable) {
            }
        }
        held.clear()
    }

    // ------------------------------------------------------------ 长按动作菜单

    private fun dismissMenu() {
        val v = menuView ?: return
        menuView = null
        try {
            wm?.removeView(v)
        } catch (_: Throwable) {
        }
    }

    /**
     * 长按宠物弹出的动作菜单 —— 对应电脑版的「右键 → 动作 → 分类 → 点播动画」。
     * 手机没有右键，所以落在长按上；分类默认折叠，点标题展开。
     */
    private fun showActionMenu(pet: Pet) {
        val w = wm ?: return
        val conf = cfg ?: return
        dismissMenu()

        val dm = resources.displayMetrics
        val d = dm.density

        val root = FrameLayout(this)
        root.setBackgroundColor(0xB3000000.toInt())
        root.setOnClickListener { dismissMenu() }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 18f * d
                setColor(0xFF161A21.toInt())
                setStroke((1 * d).toInt(), 0xFF2C3442.toInt())
            }
            setOnClickListener { /* 吃掉点击，避免穿透到 scrim */ }
        }
        root.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (dm.heightPixels * 0.66f).toInt(),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { setMargins((12 * d).toInt(), 0, (12 * d).toInt(), (12 * d).toInt()) },
        )

        panel.addView(
            TextView(this).apply {
                text = getString(R.string.menu_title) + " · " + pet.spec.name
                setTextColor(0xFFEAF1F8.toInt())
                textSize = 16f
                setPadding((4 * d).toInt(), 0, (4 * d).toInt(), (8 * d).toInt())
            },
        )

        val scroll = ScrollView(this)
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)

        fun addRow(text: String, onClick: () -> Unit) {
            val tv = TextView(this).apply {
                this.text = text
                setTextColor(0xFFCFDCEA.toInt())
                textSize = 14f
                setPadding((8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt())
                isClickable = true
                setOnClickListener { onClick() }
            }
            list.addView(
                tv,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }

        fun addSection(title: String, slugs: List<String>) {
            if (slugs.isEmpty()) return
            val head = TextView(this).apply {
                this.text = "▸ $title (${slugs.size})"
                setTextColor(0xFF8FB6E8.toInt())
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding((6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt(), (8 * d).toInt())
                isClickable = true
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            list.addView(head)
            list.addView(body)
            for (slug in slugs) {
                val tv = TextView(this).apply {
                    text = conf.nameOf(slug)
                    setTextColor(0xFFCFDCEA.toInt())
                    textSize = 14f
                    setPadding((24 * d).toInt(), (9 * d).toInt(), (8 * d).toInt(), (9 * d).toInt())
                    isClickable = true
                    setOnClickListener {
                        dismissMenu()
                        pet.playAction(slug)
                    }
                }
                body.addView(
                    tv,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
                )
            }
            head.setOnClickListener {
                val opening = body.visibility != View.VISIBLE
                body.visibility = if (opening) View.VISIBLE else View.GONE
                head.text = (if (opening) "▾ " else "▸ ") + "$title (${slugs.size})"
            }
        }

        addRow("↺ 回到初始位置") {
            dismissMenu()
            pet.resetPosition()
        }
        addRow("⇄ 翻转朝向") {
            dismissMenu()
            pet.flip()
        }
        addRow("✕ 收起来") {
            dismissMenu()
            Prefs.setRunning(this, false)
            stopSelf()
        }

        addSection("待机", conf.idle)
        addSection("转向", conf.turn)
        addSection("拖拽", conf.drag)
        addSection("点击回应", conf.clicks)
        addSection("移动", conf.moves.map { it.slug })
        for (cat in conf.categories) addSection("随机动作 · ${cat.id}", cat.actions)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        try {
            w.addView(root, lp)
            menuView = root
        } catch (t: Throwable) {
            Diag.record(this, "动作菜单创建失败", t)
        }
    }

    // ------------------------------------------------------------ 帧循环

    private val tickRunnable = object : Runnable {
        override fun run() {
            var active = false
            for (h in held) {
                try {
                    if (h.pet.tick()) active = true
                } catch (t: Throwable) {
                    Diag.record(this@PetService, "tick 异常", t)
                }
            }
            handler.postDelayed(this, if (active) 16L else 66L)
        }
    }

    private fun startTicker() {
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    override fun onDestroy() {
        try {
            teardown()
        } catch (t: Throwable) {
            Log.w(TAG, "onDestroy: ${t.message}")
        }
        super.onDestroy()
    }
}
