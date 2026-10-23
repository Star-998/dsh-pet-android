package com.dshpet.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 大肥鱼 · 手机 Agent
 *
 * 长按桌宠弹出悬浮对话框，说一句话，她就能：
 *   打开 App / 按文字点按钮 / 按坐标点 / 打字 / 滑动 / 返回 Home /
 *   读当前屏幕的文字结构 / 截屏并用视觉模型「看」屏幕。
 *
 * 三个和原项目一致的原则：
 *   1. 零第三方依赖（HttpURLConnection + org.json）
 *   2. 所有文件集中在一个 Agent.kt 里，方便在手机上改
 *   3. 不开无障碍 = 纯聊天，一行操控代码都不会跑
 *
 * 依赖两个系统授权（都要用户手动开，App 没法自己开）：
 *   - 悬浮窗（原有）
 *   - 无障碍服务（新增，设置页里有入口）
 */

// ==================================================================== 配置

object AiPrefs {

    private const val FILE = "dsh_pet_ai"
    private const val K_BASE = "base_url"
    private const val K_PATH = "chat_path"
    private const val K_KEY = "api_key"
    private const val K_MODEL = "model"
    private const val K_TEMP = "temperature"
    private const val K_MAX = "max_tokens"
    private const val K_SYS = "system_prompt"
    private const val K_CONFIRM = "confirm_danger"

    const val DEFAULT_BASE = "https://api.deepseek.com"
    const val DEFAULT_PATH = "/v1/chat/completions"
    const val DEFAULT_MODEL = "deepseek-v4-flash"
    /**
     * 视觉模型候选链，按顺序尝试，谁通了用谁。
     *
     * 官方已把 deepseek-v4-flash-vision-exp 标记为 legacy（仍接受但已退役），
     * 现在由最新的 deepseek-flash 直接吃图片；不同账号/时间点可用性不一样，
     * 所以这里留一条链，并把最终选择记下来给设置页显示。
     */
    val VISION_MODELS = listOf(
        "deepseek-flash",                  // 官方当前推荐：原生多模态
        "deepseek-v4-flash-vision-exp",    // legacy，仍被接受
        "deepseek-v4-flash",               // 某些账号下 Flash 也收图
    )
    const val VISION_MODEL = "deepseek-flash"
    private const val K_VISION = "vision_model"

    fun visionModel(ctx: Context): String =
        sp(ctx).getString(K_VISION, "").orEmpty().trim()

    fun setVisionModel(ctx: Context, v: String) {
        sp(ctx).edit().putString(K_VISION, v.trim()).apply()
    }
    const val DEFAULT_TEMP = 0.7f
    const val DEFAULT_MAX = 1024

    /** 人设。注意：操控规则的说明在 AgentTools.SYSTEM_EXTRA 里，会自动拼在这句后面 */
    const val DEFAULT_SYS =
        "你是「大肥鱼」，一只住在用户手机上的小鲸鱼娘，也是能替用户操作手机的助手。" +
            "说话简短可爱，1~3 句，可以带语气词和颜文字；汇报操作结果时说清楚做了什么。"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun baseUrl(ctx: Context): String =
        sp(ctx).getString(K_BASE, DEFAULT_BASE).orEmpty().trim().ifBlank { DEFAULT_BASE }

    fun setBaseUrl(ctx: Context, v: String) {
        sp(ctx).edit().putString(K_BASE, v.trim()).apply()
    }

    fun chatPath(ctx: Context): String =
        sp(ctx).getString(K_PATH, DEFAULT_PATH).orEmpty().trim().ifBlank { DEFAULT_PATH }

    fun setChatPath(ctx: Context, v: String) {
        val s = v.trim()
        sp(ctx).edit().putString(K_PATH, if (s.startsWith("/")) s else "/$s").apply()
    }

    fun apiKey(ctx: Context): String = sp(ctx).getString(K_KEY, "").orEmpty().trim()

    fun setApiKey(ctx: Context, v: String) {
        sp(ctx).edit().putString(K_KEY, v.trim()).apply()
    }

    fun model(ctx: Context): String =
        sp(ctx).getString(K_MODEL, DEFAULT_MODEL).orEmpty().trim().ifBlank { DEFAULT_MODEL }

    fun setModel(ctx: Context, v: String) {
        sp(ctx).edit().putString(K_MODEL, v.trim()).apply()
    }

    fun temperature(ctx: Context): Float = sp(ctx).getFloat(K_TEMP, DEFAULT_TEMP).coerceIn(0f, 2f)

    fun setTemperature(ctx: Context, v: Float) {
        sp(ctx).edit().putFloat(K_TEMP, v.coerceIn(0f, 2f)).apply()
    }

    fun maxTokens(ctx: Context): Int = sp(ctx).getInt(K_MAX, DEFAULT_MAX).coerceIn(16, 8192)

    fun setMaxTokens(ctx: Context, v: Int) {
        sp(ctx).edit().putInt(K_MAX, v.coerceIn(16, 8192)).apply()
    }

    fun systemPrompt(ctx: Context): String =
        sp(ctx).getString(K_SYS, DEFAULT_SYS).orEmpty().ifBlank { DEFAULT_SYS }

    fun setSystemPrompt(ctx: Context, v: String) {
        sp(ctx).edit().putString(K_SYS, v).apply()
    }

    /** 危险操作要不要先问一句（默认开） */
    fun confirmDanger(ctx: Context): Boolean = sp(ctx).getBoolean(K_CONFIRM, true)

    fun setConfirmDanger(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(K_CONFIRM, v).apply()
    }

    fun hasKey(ctx: Context): Boolean = apiKey(ctx).isNotBlank()

    fun maskedKey(ctx: Context): String {
        val k = apiKey(ctx)
        if (k.isBlank()) return "（未填写）"
        return if (k.length <= 10) "****" else k.take(6) + "…" + k.takeLast(4)
    }

    fun all(ctx: Context): String =
        "base=${baseUrl(ctx)} path=${chatPath(ctx)} model=${model(ctx)} " +
            "key=${maskedKey(ctx)} 危险操作确认=${if (confirmDanger(ctx)) "开" else "关"}"
}

// ==================================================================== 网络

object DsNet {

    private val main = Handler(Looper.getMainLooper())

    /** 非流式请求，返回整个 JSON。tools 请求必须走这个（流式解析 tool_calls 太容易出错） */
    fun post(ctx: Context, path: String, body: JSONObject, timeoutMs: Int = 90_000): JSONObject {
        val base = AiPrefs.baseUrl(ctx)
        val url = base.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + AiPrefs.apiKey(ctx))
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use {
                it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                it.flush()
            }
            val code = conn.responseCode
            val raw = readAll(if (code in 200..299) conn.inputStream else conn.errorStream)
            if (code !in 200..299) throw RuntimeException(explain(code, raw))
            return JSONObject(raw)
        } finally {
            conn.disconnect()
        }
    }

    /** 流式聊天（不开无障碍时的纯聊天用） */
    fun streamChat(
        ctx: Context,
        path: String,
        body: JSONObject,
        onDelta: (String) -> Unit,
    ): String {
        val base = AiPrefs.baseUrl(ctx)
        val url = base.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 90_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + AiPrefs.apiKey(ctx))
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            conn.outputStream.use {
                it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                it.flush()
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException(explain(code, readAll(conn.errorStream)))
            }
            val full = StringBuilder()
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val p = line.removePrefix("data:").trim()
                    if (p.isEmpty()) continue
                    if (p == "[DONE]") break
                    val d = JSONObject(p).optJSONArray("choices")
                        ?.optJSONObject(0)?.optJSONObject("delta")?.optString("content")
                    if (!d.isNullOrEmpty()) {
                        full.append(d)
                        val piece = d
                        main.post { onDelta(piece) }
                    }
                }
            }
            return full.toString().trim()
        } finally {
            conn.disconnect()
        }
    }

    fun get(ctx: Context, path: String): JSONObject {
        val base = AiPrefs.baseUrl(ctx)
        val conn = (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer " + AiPrefs.apiKey(ctx))
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val raw = readAll(if (code in 200..299) conn.inputStream else conn.errorStream)
            if (code !in 200..299) throw RuntimeException(explain(code, raw))
            return JSONObject(raw)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 多模态/部分兼容端的 content 可能是字符串，也可能是
     * [{"type":"text","text":"..."}] 这样的数组。optString 在数组上只会拿到 ""，
     * 之前 look_screen 一直报「视觉模型没返回内容」就是栽在这里。
     */
    fun extractContent(msg: JSONObject?): String {
        if (msg == null) return ""
        val c = msg.opt("content")
        if (c == null || c === JSONObject.NULL) {
            val rc = msg.opt("reasoning_content")
            return if (rc == null || rc === JSONObject.NULL) "" else rc.toString().trim()
        }
        if (c is String) return c.trim()
        if (c is JSONArray) {
            val sb = StringBuilder()
            for (i in 0 until c.length()) {
                val b = c.optJSONObject(i)
                val piece = if (b != null) {
                    b.optString("text").ifBlank { b.optString("content") }
                } else {
                    c.optString(i)
                }
                if (piece.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(piece)
                }
            }
            return sb.toString().trim()
        }
        return c.toString().trim()
    }

    private fun readAll(input: java.io.InputStream?): String {
        if (input == null) return ""
        return try {
            input.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (_: Throwable) {
            ""
        }
    }

    fun explain(code: Int, raw: String): String {
        var serverMsg = ""
        if (raw.isNotBlank()) {
            serverMsg = try {
                JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
                    .ifBlank { JSONObject(raw).optString("message") }
            } catch (_: Throwable) {
                raw.take(160)
            }
        }
        val head = when (code) {
            422 -> "请求参数不对（422）：通常是消息格式有问题，或模型名 / max_tokens 越界"
            401 -> "API Key 不对、没填或已被删除（401）"
            402 -> "账户余额不足，去 platform.deepseek.com 充值（402）"
            403 -> "没有权限调用这个模型（403）"
            404 -> "地址不对，通常是 Base URL 或接口路径拼错（404）"
            422 -> "请求参数不对，检查模型名 / max_tokens（422）"
            429 -> "请求太频繁被限流，稍等一下（429）"
            500, 502, 503 -> "服务端开小差了（$code），稍后再试"
            else -> "请求失败（HTTP $code）"
        }
        return if (serverMsg.isBlank()) {
            val tail = if (raw.isBlank()) "" else "\n服务端原文：" + raw.take(220)
            head + tail
        } else "$head\n服务端说：$serverMsg"
    }

    fun friendly(t: Throwable): String {
        val m = t.message.orEmpty()
        return when {
            m.contains("Unable to resolve host") || m.contains("UnknownHost") ->
                "连不上服务器，检查网络或 Base URL"
            m.contains("timed out") || m.contains("timeout") || m.contains("Timeout") -> "请求超时，稍后再试"
            m.contains("SSL") || m.contains("certificate") -> "证书校验失败，换 https 地址试试"
            m.contains("Cleartext") || m.contains("cleartext") -> "Android 不允许明文 http，把地址改成 https"
            m.contains("does not support image") -> "这个模型不支持图片，视觉模型要用 " + AiPrefs.VISION_MODEL
            else -> "请求失败：" + m.ifBlank { t.javaClass.simpleName }
        }
    }
}

// ==================================================================== 配置持久化（备份 / 恢复 / 对话历史）

/**
 * AI 配置的持久化：本机备份 + 对话历史。
 *
 * 为什么要这个：SharedPreferences 里存的配置会随「卸载 App」一起消失。
 * 覆盖安装（不卸载）不会丢，但万一哪天卸载重装，API Key 和人设就没了。
 * 这里把所有配置额外写一份到 App 私有目录的 ai-config.json，
 * 并支持导出成一段文本 / 从文本导入 —— 手机上复制粘贴就能搬家。
 */
object AiStore {

    private const val FILE = "ai-config.json"
    private const val HISTORY_FILE = "ai-history.json"
    private const val MAX_HISTORY = 50

    // ---------------------------------------------------------------- 备份

    /** 把所有 AI 配置打包成 JSON */
    fun snapshot(ctx: Context): JSONObject = JSONObject().apply {
        put("_v", 1)
        put("_note", "大肥鱼 AI 配置备份")
        put("base_url", AiPrefs.baseUrl(ctx))
        put("chat_path", AiPrefs.chatPath(ctx))
        put("model", AiPrefs.model(ctx))
        put("api_key", AiPrefs.apiKey(ctx))
        put("vision_model", AiPrefs.visionModel(ctx))
        put("temperature", AiPrefs.temperature(ctx).toDouble())
        put("max_tokens", AiPrefs.maxTokens(ctx))
        put("system_prompt", AiPrefs.systemPrompt(ctx))
        put("confirm_danger", AiPrefs.confirmDanger(ctx))
    }

    /** 写一份到 App 私有目录，供自动恢复用 */
    fun saveToDisk(ctx: Context) {
        try {
            ctx.openFileOutput(FILE, Context.MODE_PRIVATE).use {
                it.write(snapshot(ctx).toString(2).toByteArray(Charsets.UTF_8))
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 自动恢复：只在配置项为空时才回填，避免覆盖用户的新设置。
     * 返回是否恢复了内容。
     */
    fun restoreFromDisk(ctx: Context): Boolean {
        val o = try {
            val s = ctx.openFileInput(FILE).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            JSONObject(s)
        } catch (_: Throwable) {
            null
        } ?: return false

        return apply(ctx, o, onlyIfEmpty = true)
    }

    /** 应用一份配置。onlyIfEmpty=true 时只填空白项 */
    fun apply(ctx: Context, o: JSONObject, onlyIfEmpty: Boolean): Boolean {
        var changed = false
        fun str(key: String, setter: (String) -> Unit, current: String, def: String) {
            val v = o.optString(key, "").trim()
            if (v.isEmpty()) return
            if (onlyIfEmpty && (current.isNotBlank() && current != def)) return
            if (v != current) {
                setter(v)
                changed = true
            }
        }

        str("base_url", { AiPrefs.setBaseUrl(ctx, it) }, AiPrefs.baseUrl(ctx), AiPrefs.DEFAULT_BASE)
        str("chat_path", { AiPrefs.setChatPath(ctx, it) }, AiPrefs.chatPath(ctx), AiPrefs.DEFAULT_PATH)
        str("model", { AiPrefs.setModel(ctx, it) }, AiPrefs.model(ctx), AiPrefs.DEFAULT_MODEL)
        str("vision_model", { AiPrefs.setVisionModel(ctx, it) }, AiPrefs.visionModel(ctx), "")

        val key = o.optString("api_key", "").trim()
        if (key.isNotEmpty() && (!onlyIfEmpty || AiPrefs.apiKey(ctx).isBlank())) {
            if (key != AiPrefs.apiKey(ctx)) {
                AiPrefs.setApiKey(ctx, key)
                changed = true
            }
        }

        val sys = o.optString("system_prompt", "").trim()
        if (sys.isNotEmpty() && (!onlyIfEmpty ||
                AiPrefs.systemPrompt(ctx).isBlank() ||
                AiPrefs.systemPrompt(ctx) == AiPrefs.DEFAULT_SYS)
        ) {
            if (sys != AiPrefs.systemPrompt(ctx)) {
                AiPrefs.setSystemPrompt(ctx, sys)
                changed = true
            }
        }

        if (o.has("temperature")) {
            val t = o.optDouble("temperature", AiPrefs.DEFAULT_TEMP.toDouble()).toFloat()
            if (!onlyIfEmpty || AiPrefs.temperature(ctx) == AiPrefs.DEFAULT_TEMP) {
                if (t != AiPrefs.temperature(ctx)) {
                    AiPrefs.setTemperature(ctx, t)
                    changed = true
                }
            }
        }
        if (o.has("max_tokens")) {
            val m = o.optInt("max_tokens", AiPrefs.DEFAULT_MAX)
            if (!onlyIfEmpty || AiPrefs.maxTokens(ctx) == AiPrefs.DEFAULT_MAX) {
                if (m != AiPrefs.maxTokens(ctx)) {
                    AiPrefs.setMaxTokens(ctx, m)
                    changed = true
                }
            }
        }
        if (o.has("confirm_danger")) {
            val c = o.optBoolean("confirm_danger", true)
            if (!onlyIfEmpty || !AiPrefs.confirmDanger(ctx)) {
                if (c != AiPrefs.confirmDanger(ctx)) {
                    AiPrefs.setConfirmDanger(ctx, c)
                    changed = true
                }
            }
        }
        return changed
    }

    /** 导出成一段可复制的文本（Key 会打码，安全分享结构用） */
    fun exportText(ctx: Context, maskKey: Boolean = true): String {
        val o = snapshot(ctx)
        if (maskKey) o.put("api_key", "")
        return o.toString(2)
    }

    /** 从文本导入，返回 null 表示成功，否则返回错误原因 */
    fun importText(ctx: Context, text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "内容是空的"
        val o = try {
            JSONObject(trimmed)
        } catch (_: Throwable) {
            return "不是有效的 JSON：${
                if (trimmed.length > 60) trimmed.take(60) + "…" else trimmed
            }"
        }
        if (!o.has("model") && !o.has("api_key") && !o.has("system_prompt")) {
            return "这份文本里没有找到任何可识别的配置项"
        }
        return try {
            apply(ctx, o, onlyIfEmpty = false)
            saveToDisk(ctx)
            null
        } catch (t: Throwable) {
            "导入失败：" + (t.message ?: t.javaClass.simpleName)
        }
    }

    // ---------------------------------------------------------------- 对话历史

    /** 保存最近 MAX_HISTORY 条消息 */
    fun saveHistory(ctx: Context, messages: JSONArray) {
        try {
            val keep = JSONArray()
            val from = (messages.length() - MAX_HISTORY).coerceAtLeast(0)
            for (i in from until messages.length()) {
                val m = messages.optJSONObject(i) ?: continue
                val role = m.optString("role")
                // system 每轮都会重新拼，不占额度
                if (role == "system") continue
                if (role == "tool" || role == "assistant") {
                    keep.put(m)
                } else if (role == "user") {
                    keep.put(m)
                }
            }
            ctx.openFileOutput(HISTORY_FILE, Context.MODE_PRIVATE).use {
                it.write(keep.toString().toByteArray(Charsets.UTF_8))
            }
        } catch (_: Throwable) {
        }
    }

    fun loadHistory(ctx: Context): JSONArray {
        return try {
            val s = ctx.openFileInput(HISTORY_FILE).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            JSONArray(s)
        } catch (_: Throwable) {
            JSONArray()
        }
    }

    fun clearHistory(ctx: Context) {
        try {
            ctx.deleteFile(HISTORY_FILE)
        } catch (_: Throwable) {
        }
    }

    /** 启动时调用：先尝试从磁盘备份恢复配置 */
    fun init(ctx: Context) {
        restoreFromDisk(ctx)
        saveToDisk(ctx)
    }
}

// ==================================================================== 无障碍服务（手和眼睛）

class AgentService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentService? = null
            private set

        /** 上一次截图的时间，系统要求两次截图间隔不能太短 */
        private var lastShotMs = 0L

        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------------------------------------------------------- 屏幕结构

    /**
     * 关键修复：rootInActiveWindow 返回的是「当前活动窗口」。
     * 本 App 自己的悬浮面板是 TYPE_APPLICATION_OVERLAY，在最上层且可获取焦点，
     * 于是它自己就成了活动窗口 —— 结果就是助手只能读到自己。
     * 这里改走 windows 列表：跳过本包名，挑层级最高的真实应用窗口。
     */
    fun activeRoot(): AccessibilityNodeInfo? {
        val myPkg = packageName
        val wins = try {
            windows
        } catch (_: Throwable) {
            emptyList<AccessibilityWindowInfo>()
        }
        var bestW: AccessibilityWindowInfo? = null
        var bestLayer = Int.MIN_VALUE
        for (w in wins) {
            var node: AccessibilityNodeInfo? = null
            try {
                node = w.root
                val pkg = node?.packageName?.toString().orEmpty()
                val usable = pkg.isNotEmpty() && pkg != myPkg &&
                    w.type == AccessibilityWindowInfo.TYPE_APPLICATION && node != null
                if (usable && w.layer > bestLayer) {
                    try { bestW?.recycle() } catch (_: Throwable) {}
                    bestW = w
                    bestLayer = w.layer
                } else {
                    try { w.recycle() } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {
                try { w.recycle() } catch (_: Throwable) {}
            } finally {
                try { node?.recycle() } catch (_: Throwable) {}
            }
        }
        val out = try { bestW?.root } catch (_: Throwable) { null }
        try { bestW?.recycle() } catch (_: Throwable) {}
        if (out != null) return out
        return try { rootInActiveWindow } catch (_: Throwable) { null }
    }

    /** 当前前台应用的包名（已排除本 App 自己的悬浮窗口） */
    fun currentPkg(): String? {
        val r = activeRoot() ?: return null
        val p = try { r.packageName?.toString() } catch (_: Throwable) { null }
        try { r.recycle() } catch (_: Throwable) {}
        return p
    }

    /** 把当前屏幕整理成一份文字清单，喂给模型当「眼睛」 */
    fun screenDump(limit: Int = 120): String {
        val root = activeRoot() ?: return "（读不到其他应用的窗口：无障碍服务可能没真正连上，或当前没有别的应用在前台）"
        val sb = StringBuilder()
        val dm = Resources.dm(this)
        sb.append("屏幕 ${dm.widthPixels}x${dm.heightPixels}，当前包名 ${root.packageName}\n")
        val out = ArrayList<String>()
        try {
            walk(root, out, limit)
        } finally {
            try {
                root.recycle()
            } catch (_: Throwable) {
            }
        }
        if (out.isEmpty()) sb.append("（屏幕上没有可识别的文字或按钮）")
        else out.forEach { sb.append(it).append('\n') }

        // 只读到零星几个节点，说明挑中的窗口不对（挑到了输入法、悬浮层或空壳窗口）。
        // 这时把所有应用窗口合并扫一遍，尽量把真实界面捞出来。
        if (out.size <= 2) {
            val extra = ArrayList<String>()
            val wins = try { windows } catch (_: Throwable) { emptyList<AccessibilityWindowInfo>() }
            for (w in wins) {
                try {
                    if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    val r = w.root
                    if (r == null) continue
                    val pkg = r.packageName?.toString().orEmpty()
                    if (pkg.isEmpty() || pkg == packageName) continue
                    val before = extra.size
                    walk(r, extra, limit)
                    for (k in before until extra.size) extra[k] = extra[k] + " [窗口 $pkg]"
                    try { r.recycle() } catch (_: Throwable) {}
                } catch (_: Throwable) {
                }
                if (extra.size >= limit) break
            }
            for (w in wins) {
                try { w.recycle() } catch (_: Throwable) {}
            }
            if (extra.size > out.size) {
                sb.append("\n（补充扫描到以下内容）\n")
                extra.forEach { sb.append(it).append('\n') }
            }
        }
        return sb.toString().trim()
    }

    private fun walk(node: AccessibilityNodeInfo?, out: ArrayList<String>, limit: Int) {
        if (node == null || out.size >= limit) return
        val txt = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val clickable = node.isClickable
        val editable = node.isEditable
        val label = when {
            txt.isNotEmpty() -> txt
            desc.isNotEmpty() -> desc
            else -> ""
        }
        if (label.isNotEmpty() || clickable || editable) {
            if (label.isNotEmpty() || clickable) {
                if (out.size < limit) out.add(formatNode(out.size, label, clickable, editable, node))
            }
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i)
            if (c != null) {
                walk(c, out, limit)
                try {
                    c.recycle()
                } catch (_: Throwable) {
                }
            }
            if (out.size >= limit) break
        }
    }

    private fun formatNode(
        idx: Int,
        label: String,
        clickable: Boolean,
        editable: Boolean,
        node: AccessibilityNodeInfo,
    ): String {
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        val cx = (r.left + r.right) / 2
        val cy = (r.top + r.bottom) / 2
        val tags = ArrayList<String>()
        if (clickable) tags.add("可点击")
        if (editable) tags.add("可输入")
        val t = if (tags.isEmpty()) "" else "[" + tags.joinToString("/") + "]"
        val l = if (label.length > 24) label.take(24) + "…" else label
        return "#$idx $t「$l」 中心($cx,$cy)"
    }

    // ---------------------------------------------------------- 点击

    /** 按文字 / 描述找控件并点击；找不到返回 false */
    fun tapByText(keyword: String): String {
        val root = activeRoot() ?: return "读不到其他应用的界面，可能不在可操作的界面"
        val kw = keyword.trim()
        if (kw.isEmpty()) return "没给要点的文字"
        var hit: AccessibilityNodeInfo? = null
        try {
            val byText = root.findAccessibilityNodeInfosByText(kw)
            hit = pickBest(byText, kw)
            if (hit == null) hit = searchTree(root, kw)
        } catch (t: Throwable) {
            return "查找失败：" + (t.message ?: "")
        }
        val target = hit
        if (target == null) {
            try {
                root.recycle()
            } catch (_: Throwable) {
            }
            return "屏幕上没找到包含「$kw」的元素。可以先用 screen_dump 看看屏幕上有什么；如果文字读不出来（游戏、自绘界面、图片里的按钮），改用 look_screen 截图看。"
        }
        var node: AccessibilityNodeInfo? = target
        if (node?.isClickable != true) {
            var p = node?.parent
            var guard = 0
            while (p != null && guard++ < 5) {
                if (p.isClickable) {
                    node = p
                    break
                }
                p = p.parent
            }
        }
        val ok = try {
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        } catch (t: Throwable) {
            false
        }
        try {
            root.recycle()
        } catch (_: Throwable) {
        }
        return if (ok) "已点击「$kw」" else "找到了「$kw」但点不动（它不是可点击元素），试试 tap_xy 点坐标"
    }

    private fun pickBest(list: List<AccessibilityNodeInfo>?, kw: String): AccessibilityNodeInfo? {
        if (list.isNullOrEmpty()) return null
        var best: AccessibilityNodeInfo? = null
        for (n in list) {
            if (n.isClickable) return n
            val t = n.text?.toString() ?: n.contentDescription?.toString() ?: ""
            if (t.trim() == kw && best == null) best = n
            if (best == null) best = n
        }
        return best
    }

    private fun searchTree(root: AccessibilityNodeInfo, kw: String): AccessibilityNodeInfo? {
        val txt = root.text?.toString() ?: ""
        val desc = root.contentDescription?.toString() ?: ""
        if (txt.contains(kw) || desc.contains(kw)) {
            if (root.isClickable) return AccessibilityNodeInfo.obtain(root)
            val c = searchChildren(root, kw)
            if (c != null) return c
            return AccessibilityNodeInfo.obtain(root)
        }
        return searchChildren(root, kw)
    }

    private fun searchChildren(node: AccessibilityNodeInfo, kw: String): AccessibilityNodeInfo? {
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = searchTree(c, kw)
            try {
                c.recycle()
            } catch (_: Throwable) {
            }
            if (r != null) return r
        }
        return null
    }

    /** 按坐标点击（视觉模型给出的坐标走这条路） */
    fun tapXY(x: Int, y: Int): String {
        return gestureTap(x.toFloat(), y.toFloat())
    }

    private fun gestureTap(x: Float, y: Float): String {
        if (Build.VERSION.SDK_INT < 24) return "系统版本太低，不支持模拟点击"
        val p = Path()
        p.moveTo(x, y)
        p.lineTo(x, y)
        val stroke = GestureDescription.StrokeDescription(p, 0, 80)
        val g = GestureDescription.Builder().addStroke(stroke).build()
        val done = AtomicBoolean(false)
        val res = dispatchGesture(
            g,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    done.set(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    done.set(true)
                }
            },
            null,
        )
        return if (res) "已在 (${x.toInt()},${y.toInt()}) 点了一下" else "系统拒绝了这次点击"
    }

    fun swipe(fx: Float, fy: Float, tx: Float, ty: Float, ms: Long = 400): String {
        if (Build.VERSION.SDK_INT < 24) return "系统版本太低，不支持滑动"
        val p = Path()
        p.moveTo(fx, fy)
        p.lineTo(tx, ty)
        val stroke = GestureDescription.StrokeDescription(p, 0, ms)
        val g = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGesture(
            g,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {}
                override fun onCancelled(gestureDescription: GestureDescription?) {}
            },
            null,
        )
        return if (ok) "已滑动" else "系统拒绝了这次滑动"
    }

    // ---------------------------------------------------------- 输入

    fun inputText(text: String): String {
        val root = activeRoot() ?: return "读不到其他应用的界面"
        val target = findEditable(root) ?: run {
            try {
                root.recycle()
            } catch (_: Throwable) {
            }
            return "当前界面没有输入框。先点一下输入框再说。"
        }
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = try {
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Throwable) {
            false
        }
        if (!ok) {
            // 兜底：聚焦 + 走剪贴板粘贴
            try {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("dsh", text))
                val pasted = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                try {
                    root.recycle()
                } catch (_: Throwable) {
                }
                return if (pasted) "已输入（走剪贴板）：$text" else "输入失败，这个输入框不接受模拟输入"
            } catch (t: Throwable) {
                try {
                    root.recycle()
                } catch (_: Throwable) {
                }
                return "输入失败：" + (t.message ?: "")
            }
        }
        try {
            root.recycle()
        } catch (_: Throwable) {
        }
        return "已输入：$text"
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocusable) return node
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = findEditable(c)
            if (r != null) {
                try {
                    c.recycle()
                } catch (_: Throwable) {
                }
                return r
            }
            try {
                c.recycle()
            } catch (_: Throwable) {
            }
        }
        return null
    }

    // ---------------------------------------------------------- 全局动作

    fun globalBack(): String {
        performGlobalAction(GLOBAL_ACTION_BACK)
        return "已返回"
    }

    fun globalHome(): String {
        performGlobalAction(GLOBAL_ACTION_HOME)
        return "已回到桌面"
    }

    fun globalRecents(): String {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        return "已打开最近任务"
    }

    // ---------------------------------------------------------- 截图

    /** 截屏并返回 base64（JPEG）。走无障碍 takeScreenshot，不弹窗、不需要额外授权 */
    /** 官方会把图缩到约 800x800 等效分辨率（384 token 封顶），发更大的图纯属浪费流量和时间 */
    fun screenshotB64(maxW: Int = 768): String {
        if (Build.VERSION.SDK_INT < 30) return ""
        val wait = System.currentTimeMillis() - lastShotMs
        if (wait < 400) Thread.sleep(400 - wait)
        lastShotMs = System.currentTimeMillis()

        val latch = CountDownLatch(1)
        val out = java.util.concurrent.atomic.AtomicReference<Bitmap>(null)
        val ctx = this
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ctx.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        try {
                            val hb = screenshot.hardwareBuffer
                            val cs = screenshot.colorSpace
                            val wrapped = Bitmap.wrapHardwareBuffer(hb, cs)
                            if (wrapped != null) {
                                val soft = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                                wrapped.recycle()
                                if (soft != null) out.set(soft)
                            }
                            try {
                                hb.close()
                            } catch (_: Throwable) {
                            }
                        } catch (_: Throwable) {
                        }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                },
            )
        } catch (t: Throwable) {
            return ""
        }
        latch.await(6, TimeUnit.SECONDS)
        val bmp = out.get() ?: return ""
        val w = bmp.width
        val h = bmp.height
        val scale = if (w > maxW) maxW.toFloat() / w else 1f
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bmp, nw, nh, true)
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 68, bos)
        val bytes = bos.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun screenW(): Int = Resources.dm(this).widthPixels
    fun screenH(): Int = Resources.dm(this).heightPixels
}

// 小工具：拿屏幕尺寸
private object Resources {
    fun dm(ctx: Context): DisplayMetrics = ctx.resources.displayMetrics
}

// ==================================================================== 打开 App

object AppLauncher {

    private var cache: List<Pair<String, String>>? = null

    /** 返回 (显示名, 包名) 列表，只保留有启动入口的 */
    fun list(ctx: Context): List<Pair<String, String>> {
        cache?.let { return it }
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val res = ArrayList<Pair<String, String>>()
        val acts = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        for (ri in acts) {
            val pkg = ri.activityInfo?.packageName ?: continue
            val name = try {
                ri.loadLabel(pm).toString()
            } catch (_: Throwable) {
                pkg
            }
            if (name.isBlank()) continue
            res.add(Pair(name, pkg))
        }
        res.sortBy { it.first }
        // 只在查到东西时才缓存：万一首次查询被包可见性限制削弱，别把错误结果固化住
        if (res.isNotEmpty()) cache = res
        return res
    }

    /** 按名字模糊找并打开；返回给模型看的结果 */
    fun open(ctx: Context, keyword: String): String {
        val kw = keyword.trim()
        if (kw.isEmpty()) return "没说要打开哪个 App"
        val all = list(ctx)
        val exact = all.firstOrNull { it.first.equals(kw, true) }
        val hit = exact ?: all.firstOrNull { it.first.contains(kw, true) }
            ?: all.firstOrNull { kw.contains(it.first, true) }
        if (hit == null) {
            val near = if (kw.length >= 2) {
                all.filter { it.first.contains(kw.take(2), true) }.take(8).joinToString("、") { it.first }
            } else ""
            return "没找到叫「$kw」的应用（我只能看到 ${all.size} 个应用）。" +
                if (near.isNotBlank()) "相近的有：$near"
                else "如果手机上确实装了它，多半是 Android 11+ 的包可见性限制（manifest 需要 <queries>）。"
        }
        val pm = ctx.packageManager
        var launch = pm.getLaunchIntentForPackage(hit.second)
        if (launch == null) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(hit.second)
            val ri = if (Build.VERSION.SDK_INT >= 33) {
                pm.resolveActivity(i, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(i, 0)
            }
            if (ri != null) launch = i
        }
        if (launch == null) {
            return "找到了「${hit.first}」（${hit.second}）但拿不到启动入口，通常是包可见性限制。"
        }
        return try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ctx.startActivity(launch)
            "已打开「${hit.first}」。注意本助手的悬浮面板会盖在它上面，点面板「×」可以收起。"
        } catch (t: Throwable) {
            "打开「${hit.first}」失败：" + t.javaClass.simpleName + " " +
                (t.message ?: "（多半是 Android 10+ 的后台启动限制：先手动回桌面再让她打开）")
        }
    }

    fun isSystemApp(ctx: Context, pkg: String): Boolean = try {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (_: Throwable) {
        false
    }
}

// ==================================================================== 工具定义 + 执行

object AgentTools {

    /**
     * 拼在人设后面的操控规则。写清楚边界能显著减少模型瞎点的概率。
     */
    val SYSTEM_EXTRA: String = """

你还能操作这台安卓手机。可用工具：
- open_app(app_name)：按名字打开应用（用中文名，例如「微信」「设置」）
- screen_dump()：读取当前屏幕上所有可点击元素和文字，以及它们的中心坐标。做操盘前先调用它。
- tap_text(text)：按文字点按钮（会匹配包含该文字的元素）
- tap_xy(x, y)：按屏幕坐标点（视觉模型给出坐标时用这个）
- input_text(text)：往当前输入框里打字
- swipe(direction)：滑动，direction 取 up/down/left/right
- press_back() / press_home() / press_recents()
- wait_ms(ms)：等待
- look_screen(question)：截屏并让视觉模型看图，用来确认界面状态或找没有文字的图标

规则（很重要）：
1. 不要凭空猜界面。每一步操作前先用 screen_dump 看清楚，操作后再调用一次确认结果。
2. 找不到目标不要硬点，用 look_screen 看一眼，或者直接告诉用户你找不到。
3. 涉及付款、转账、删除、发送、提交订单这类不可撤销的操作，必须先向用户说明你要点什么。
4. 一次只做一步，逐步推进，不要连续盲操作 5 次以上。
5. 完成或卡住都要用一句话告诉用户结果，不要沉默。
""".trimIndent()

    /** 工具 JSON Schema */
    fun schema(): JSONArray = JSONArray().apply {
        put(fn("open_app", "按名字打开手机上的应用", obj(p("app_name", "string", "应用中文名，例如 微信、设置、相册"))))
        put(fn("screen_dump", "读取当前屏幕上的可点击元素、文字和坐标，操作前先看一眼", obj()))
        put(fn("tap_text", "点击屏幕上包含指定文字的按钮或条目", obj(p("text", "string", "要点的文字，例如 发送、搜索、关注"))))
        put(fn("tap_xy", "按屏幕坐标点击（坐标来自 screen_dump 或 look_screen）",
            obj(p("x", "integer", "横坐标"), p("y", "integer", "纵坐标")), listOf("x", "y")))
        put(fn("input_text", "在当前输入框里输入文字", obj(p("text", "string", "要输入的内容")), listOf("text")))
        put(fn("swipe", "滑动屏幕", obj(p("direction", "string", "up / down / left / right")), listOf("direction")))
        put(fn("press_back", "按返回键", obj()))
        put(fn("press_home", "回到桌面", obj()))
        put(fn("press_recents", "打开最近任务", obj()))
        put(fn("wait_ms", "等待一段时间（毫秒）", obj(p("ms", "integer", "毫秒，最多 5000")), listOf("ms")))
        put(fn("look_screen", "截屏并让视觉模型看图，用于确认界面或找没有文字的图标",
            obj(p("question", "string", "你想从这张截图里知道什么")), listOf("question")))
    }

    private fun p(name: String, type: String, desc: String): JSONObject =
        JSONObject().put(name, JSONObject().put("type", type).put("description", desc))

    private fun obj(vararg parts: JSONObject): JSONObject {
        val o = JSONObject().put("type", "object").put("properties", JSONObject())
        for (part in parts) {
            val keys = part.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                o.getJSONObject("properties").put(k, part.get(k))
            }
        }
        return o
    }

    private fun fn(name: String, desc: String, params: JSONObject, required: List<String> = emptyList()): JSONObject {
        if (required.isNotEmpty()) {
            params.put("required", JSONArray().apply { required.forEach { put(it) } })
        }
        return JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", name).put("description", desc).put("parameters", params))
    }

    // ---------------------------------------------------------- 危险判定

    private val DANGER_WORDS = listOf(
        "发送", "付款", "支付", "转账", "下单", "提交订单", "确认订单", "预订", "购买",
        "删除", "卸载", "清除", "清空", "退出登录", "注销", "举报", "拉黑", "屏蔽",
        "充值", "订阅", "发表", "发布", "评论", "关注", "私信", "拨打", "呼叫",
    )

    private val PAY_PKGS = listOf(
        "com.eg.android.AlipayGphone", "com.tencent.mm", "com.unionpay",
        "com.android.bank", "com.icbc", "com.bankcomm", "com.chinamworld.main",
    )

    /** 返回 null 表示不用确认；否则返回要给用户看的确认文案 */
    fun confirmText(ctx: Context, name: String, args: JSONObject): String? {
        when (name) {
            "tap_text" -> {
                val t = args.optString("text")
                val hit = DANGER_WORDS.firstOrNull { t.contains(it) }
                if (hit != null) return "要点击「$t」（涉及「$hit」）"
                return pkgHint(ctx)
            }
            "tap_xy" -> return pkgHint(ctx)
            "input_text" -> {
                val t = args.optString("text")
                return "要输入「${if (t.length > 30) t.take(30) + "…" else t}」"
            }
            "swipe" -> return null
            else -> return null
        }
    }

    private fun pkgHint(ctx: Context): String? {
        val svc = AgentService.instance
        val pkg = svc?.currentPkg() ?: return null
        if (PAY_PKGS.any { pkg.startsWith(it) }) return "当前在支付/银行类应用（$pkg）里，要继续点击吗"
        return null
    }
}

// ==================================================================== Agent 运行循环

object AgentRunner {

    /** 单条对话的上下文（JSON 直传，方便把 tool_calls 原样回传） */
    class Session {
        val messages = JSONArray()
        private var loaded = false

        fun system(s: String) {
            if (messages.length() == 0) messages.put(JSONObject().put("role", "system").put("content", s))
        }

        /** 首次使用前把上次存下的对话捞回来（system 每轮重新拼，不占历史额度） */
        fun load(ctx: Context) {
            if (loaded) return
            loaded = true
            val old = AiStore.loadHistory(ctx)
            for (i in 0 until old.length()) {
                messages.put(old.optJSONObject(i) ?: continue)
            }
        }

        /** 每轮结束落盘，只留最近 50 条 */
        fun persist(ctx: Context) {
            AiStore.saveHistory(ctx, messages)
        }

        fun clear(ctx: Context) {
            for (i in messages.length() - 1 downTo 1) messages.remove(i)
            AiStore.clearHistory(ctx)
        }
    }

    interface UI {
        fun step(text: String)
        /** 需要用户确认时调用，返回 true 表示允许 */
        fun confirm(text: String): Boolean
    }

    /**
     * 跑一轮：用户说话 → 模型决定要不要调工具 → 执行 → 再问 → 直到给出回复。
     * 必须在子线程调用。
     */
    fun run(ctx: Context, session: Session, userText: String, ui: UI): String {
        session.load(ctx)
        session.system(AiPrefs.systemPrompt(ctx) + "\n" + AgentTools.SYSTEM_EXTRA)
        session.messages.put(JSONObject().put("role", "user").put("content", userText))

        val model = AiPrefs.model(ctx)
        val path = AiPrefs.chatPath(ctx)
        var rounds = 0

        try {
        while (rounds++ < 12) {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", session.messages)
                put("tools", AgentTools.schema())
                put("tool_choice", "auto")
                put("temperature", AiPrefs.temperature(ctx).toDouble())
                put("max_tokens", AiPrefs.maxTokens(ctx))
                put("stream", false)
            }

            val resp = try {
                DsNet.post(ctx, path, body)
            } catch (t: Throwable) {
                // 带 tools 的请求被拒（422/400）：多半是模型不吃这套工具格式。
                // 降级成不带工具的纯聊天再问一次，至少别让用户看到崩溃。
                if (rounds == 1) {
                    return try {
                        val plain = JSONObject().apply {
                            put("model", model)
                            put("messages", JSONArray().apply {
                                put(JSONObject().put("role", "system")
                                    .put("content", AiPrefs.systemPrompt(ctx)))
                                put(JSONObject().put("role", "user").put("content", userText))
                            })
                            put("temperature", AiPrefs.temperature(ctx).toDouble())
                            put("max_tokens", AiPrefs.maxTokens(ctx))
                            put("stream", false)
                        }
                        val o = DsNet.post(ctx, path, plain)
                        val t = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                            ?.optString("content")?.trim().orEmpty()
                        if (t.isEmpty()) "（模型没返回内容）" else t
                    } catch (t2: Throwable) {
                        "（没接上）" + DsNet.friendly(t2)
                    }
                }
                return "（没接上）" + DsNet.friendly(t)
            }

            val msg = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: return "（模型返回了看不懂的内容）"
            val calls = msg.optJSONArray("tool_calls")

            if (calls == null || calls.length() == 0) {
                val answer = msg.optString("content").trim().ifBlank { "（模型没说话）" }
                session.messages.put(cleanAssistantMsg(msg))
                return answer
            }

            session.messages.put(cleanAssistantMsg(msg))
            AgentWindow.setCollapsed(true)

            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val id = call.optString("id")
                val fn = call.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                val rawArgs = fn.optString("arguments")
                val args = try {
                    JSONObject(rawArgs)
                } catch (_: Throwable) {
                    JSONObject()
                }

                val need = AgentTools.confirmText(ctx, name, args)
                if (need != null && AiPrefs.confirmDanger(ctx)) {
                    ui.step("等你确认：$need")
                    if (!ui.confirm(need)) {
                        session.messages.put(toolMsg(id, "用户拒绝了这一步，请停止并说明情况。"))
                        continue
                    }
                }

                ui.step(describe(name, args))
                val result = try {
                    execute(ctx, name, args)
                } catch (t: Throwable) {
                    "执行出错：" + (t.message ?: t.javaClass.simpleName)
                }
                ui.step("　→ $result")
                session.messages.put(toolMsg(id, result))
            }

            try {
                Thread.sleep(350)
            } catch (_: Throwable) {
            }
        }
        return "（走了太多次还没完成，先停一下。你可以再说具体一点。）"
        } finally {
            try {
                session.persist(ctx)
            } catch (_: Throwable) {
            }
            AgentWindow.setCollapsed(false)
            AgentWindow.setPassthrough(false)
        }
    }

    /**
     * 把模型返回的 assistant 消息整理成可以安全回传的格式。
     *
     * 这是 422 的根因：模型在 tool_calls 那一轮返回的 content 是 null，
     * 原样塞回 messages 时会变成 "content": null，服务端反序列化直接失败。
     * 这里逐字段重建，全部转成非空字符串；顺便把可能为空的字段剔掉。
     */
    private fun cleanAssistantMsg(src: JSONObject): JSONObject {
        val o = JSONObject().put("role", "assistant")

        val c = src.opt("content")
        o.put("content", if (c == null || c === JSONObject.NULL) "" else c.toString())

        // 思考模式下会有 reasoning_content，官方要求原样回传；没有就别塞 null
        val rc = src.opt("reasoning_content")
        if (rc != null && rc !== JSONObject.NULL) o.put("reasoning_content", rc.toString())

        val tc = src.optJSONArray("tool_calls")
        if (tc != null && tc.length() > 0) {
            val clean = JSONArray()
            for (i in 0 until tc.length()) {
                val call = tc.optJSONObject(i) ?: continue
                val fn = call.optJSONObject("function") ?: continue
                val argsRaw = fn.opt("arguments")
                val args = when {
                    argsRaw == null || argsRaw === JSONObject.NULL -> "{}"
                    argsRaw is String -> if (argsRaw.isBlank()) "{}" else argsRaw
                    else -> argsRaw.toString()
                }
                clean.put(
                    JSONObject()
                        .put("id", call.optString("id", "call_$i"))
                        .put("type", call.optString("type", "function"))
                        .put("function", JSONObject()
                            .put("name", fn.optString("name", ""))
                            .put("arguments", args)),
                )
            }
            if (clean.length() > 0) o.put("tool_calls", clean)
        }
        return o
    }

    /** tool 消息：role 必须是 tool，content 必须是非空字符串 */
    private fun toolMsg(id: String, content: String): JSONObject =
        JSONObject().put("role", "tool")
            .put("tool_call_id", id)
            .put("content", if (content.isBlank()) "（执行完成，无输出）" else content)

    /** 给 UI 看的一步描述 */
    fun describe(name: String, args: JSONObject): String = when (name) {
        "open_app" -> "打开 " + args.optString("app_name")
        "screen_dump" -> "看一眼屏幕"
        "tap_text" -> "点「" + args.optString("text") + "」"
        "tap_xy" -> "点坐标 (" + args.optInt("x") + "," + args.optInt("y") + ")"
        "input_text" -> "输入「" + args.optString("text") + "」"
        "swipe" -> "向" + args.optString("direction") + "滑动"
        "press_back" -> "按返回"
        "press_home" -> "回桌面"
        "press_recents" -> "打开最近任务"
        "wait_ms" -> "等 " + args.optInt("ms") + "ms"
        "look_screen" -> "截屏看图"
        else -> name
    }

    /** 真正干活。涉及无障碍的都切到主线程执行 */
    fun execute(ctx: Context, name: String, args: JSONObject): String {
        val svc = AgentService.instance
            ?: return "无障碍服务没开，操作不了手机。让用户去设置里打开「大肥鱼桌宠」的无障碍开关。"

        return when (name) {
            "open_app" -> {
                val n = args.optString("app_name")
                // 用无障碍服务自己的 Context：它由系统绑定，
                // 从它发起 startActivity 比 Application Context 更不容易被后台启动限制拦下
                onMain { AppLauncher.open(svc, n) }
            }
            "screen_dump" -> onMain { svc.screenDump() }
            "tap_text" -> {
                val t = args.optString("text")
                onMain { svc.tapByText(t) }
            }
            "tap_xy" -> {
                val x = args.optInt("x")
                val y = args.optInt("y")
                AgentWindow.setPassthrough(true)
                Thread.sleep(250)
                val r = onMain { svc.tapXY(x, y) }
                Thread.sleep(150)
                AgentWindow.setPassthrough(false)
                r
            }
            "input_text" -> {
                val t = args.optString("text")
                onMain { svc.inputText(t) }
            }
            "swipe" -> {
                val dir = args.optString("direction")
                val w = svc.screenW().toFloat()
                val h = svc.screenH().toFloat()
                val r = when (dir) {
                    "up" -> listOf(w / 2, h * 0.75f, w / 2, h * 0.25f)
                    "down" -> listOf(w / 2, h * 0.25f, w / 2, h * 0.75f)
                    "left" -> listOf(w * 0.8f, h / 2, w * 0.2f, h / 2)
                    "right" -> listOf(w * 0.2f, h / 2, w * 0.8f, h / 2)
                    else -> listOf(w / 2, h * 0.75f, w / 2, h * 0.25f)
                }
                AgentWindow.setPassthrough(true)
                Thread.sleep(250)
                val rr = onMain { svc.swipe(r[0], r[1], r[2], r[3]) }
                Thread.sleep(150)
                AgentWindow.setPassthrough(false)
                rr
            }
            "press_back" -> onMain { svc.globalBack() }
            "press_home" -> onMain { svc.globalHome() }
            "press_recents" -> onMain { svc.globalRecents() }
            "wait_ms" -> {
                val ms = args.optInt("ms", 800).coerceIn(0, 5000)
                Thread.sleep(ms.toLong())
                "已等待 ${ms}ms"
            }
            "look_screen" -> {
                // 注意：不能走 onMain。screenshotB64 内部会阻塞等回调，
                // 而回调又要跑在主线程 —— 在主线程里等 = 自己等自己，必死等 6 秒超时。
                val q = args.optString("question")
                lookScreen(ctx, svc, q)
            }
            else -> "没有这个工具：$name"
        }
    }

    /** 截屏 → 视觉模型 → 文字 */
    private fun lookScreen(ctx: Context, svc: AgentService, question: String): String {
        AgentWindow.setHidden(true)
        Thread.sleep(300)
        val b64 = svc.screenshotB64()
        AgentWindow.setHidden(false)
        if (b64.isBlank()) return "截屏失败：可能是系统版本低于 Android 11，或系统拒绝了这次截图。"
        val prompt =
            "这是一张安卓手机截图（宽 ${svc.screenW()} 高 ${svc.screenH()} 像素）。\n" +
                "用户想知道：$question\n" +
                "请用中文回答：1) 这是什么界面；2) 屏幕上有哪些可点击的元素，每个给出它在截图里的中心像素坐标；3) 针对用户的问题给出结论。\n" +
                "坐标要写成 (x,y) 的形式，x 和 y 都按这张截图的像素算。"
        // 上次成功过的优先，其次按候选链
        val saved = AiPrefs.visionModel(ctx)
        val models = if (saved.isNotBlank()) {
            (listOf(saved) + AiPrefs.VISION_MODELS).distinct()
        } else {
            AiPrefs.VISION_MODELS
        }

        val body = JSONObject().apply {
            put("model", models.first())
            put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", prompt))
                    put(JSONObject().put("type", "image_url").put("image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$b64")
                    ))
                })
            ))
            put("max_tokens", 800)
            put("stream", false)
        }
        val errs = ArrayList<String>()
        for (m in models) {
            try {
                body.put("model", m)
                val o = DsNet.post(ctx, AiPrefs.chatPath(ctx), body, timeoutMs = 60_000)
                val txt = DsNet.extractContent(
                    o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message"),
                )
                if (txt.isBlank()) {
                    errs.add("$m：返回空内容")
                    continue
                }
                // 记住这次能用哪个，下次直接从它开始
                if (AiPrefs.visionModel(ctx) != m) AiPrefs.setVisionModel(ctx, m)
                return "看图结果（$m）：$txt"
            } catch (t: Throwable) {
                errs.add("$m：" + DsNet.friendly(t).take(90))
            }
        }
        return "看图失败，已试过 " + models.joinToString("、") + "。\n" + errs.joinToString("\n")
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 在主线程执行并等待结果（无障碍 API 只能在主线程调） */
    private fun <T> onMain(block: () -> T): T {
        val ref = java.util.concurrent.atomic.AtomicReference<T>()
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                ref.set(block())
            } catch (_: Throwable) {
            }
            latch.countDown()
        }
        latch.await(15, TimeUnit.SECONDS)
        return ref.get()
    }
}

// ==================================================================== 悬浮对话框

object Agent {

    @Volatile
    private var opened = false

    /** 长按桌宠时调这个 */
    fun openDialog(ctx: Context) {
        if (!Settings.canDrawOverlays(ctx)) {
            Toast.makeText(ctx, "先给桌宠开「显示在其他应用上层」权限", Toast.LENGTH_SHORT).show()
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Throwable) {
            }
            return
        }
        if (opened) return
        AgentWindow.attach(ctx.applicationContext)
        opened = true
    }

    fun markClosed() {
        opened = false
    }
}

/**
 * 悬浮对话框本体。
 * 用悬浮窗而不是普通 Activity，是为了让它浮在别的应用上面——
 * 大肥鱼一边操作微信，你一边还能看见她在说什么。
 */
object AgentWindow {

    private var wm: WindowManager? = null
    private var root: View? = null
    private var ctx: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val session = AgentRunner.Session()
    private var busy = false

    private var listView: LinearLayout? = null
    private var barView: View? = null
    private var bottomView: View? = null
    private var fullH = 0
    private var baseFlags = 0
    private var scrollView: ScrollView? = null
    private var statusView: TextView? = null
    private var inputView: EditText? = null
    private var btnSend: TextView? = null
    private var confirmBar: LinearLayout? = null
    private var confirmLabel: TextView? = null

    private var pendingAnswer: AtomicBoolean? = null
    private var pendingLatch: CountDownLatch? = null

    fun attach(c: Context) {
        detach()
        ctx = c
        val w = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        val d = c.resources.displayMetrics.density
        val dm = c.resources.displayMetrics
        fun dp(v: Float) = (v * d).roundToInt()

        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2161A21.toInt())
            background = GradientDrawable().apply {
                cornerRadius = 18f * d
                setColor(0xF2161A21.toInt())
                setStroke(dp(1f), 0x553A4560.toInt())
            }
        }

        // ---- 标题栏
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        }
        fun barBtn(text: String, color: Int, onClick: () -> Unit) = TextView(c).apply {
            this.text = text
            setTextColor(color)
            textSize = 14f
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            isClickable = true
            setOnClickListener { onClick() }
        }
        bar.addView(barBtn("×", 0xFF8FB6E8.toInt()) { detach(); Agent.markClosed() })
        bar.addView(
            TextView(c).apply {
                text = "🐟 大肥鱼"
                setTextColor(0xFFEAF1F8.toInt())
                textSize = 15f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        bar.addView(barBtn("⚙️", 0xFF8FB6E8.toInt()) {
            try {
                c.startActivity(Intent(c, AiSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
            }
        })
        bar.addView(barBtn("🗑", 0xFF8FB6E8.toInt()) {
            session.clear(c)
            listView?.removeAllViews()
            addMsg("assistant", "（对话已清空）")
        })
        bar.addView(barBtn("全屏", 0xFF8FB6E8.toInt()) {
            detach()
            Agent.markClosed()
            try {
                c.startActivity(Intent(c, AgentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
            }
        })
        box.addView(bar)
        barView = bar

        // ---- 消息区
        scrollView = ScrollView(c).apply { isFillViewport = true }
        listView = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
        }
        scrollView!!.addView(listView)
        box.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- 状态行（正在做什么）
        statusView = TextView(c).apply {
            text = ""
            setTextColor(0xFFFFC46B.toInt())
            textSize = 11f
            setPadding(dp(12f), dp(2f), dp(12f), dp(2f))
        }
        box.addView(statusView)

        // ---- 确认条（危险操作时弹出）
        confirmBar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            setBackgroundColor(0x33FFC46B.toInt())
        }
        confirmLabel = TextView(c).apply {
            setTextColor(0xFFFFE1A8.toInt())
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun confirmBtn(text: String, bg: Int, allow: Boolean) = TextView(c).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            background = GradientDrawable().apply { cornerRadius = 12f * d; setColor(bg) }
            isClickable = true
            setOnClickListener {
                pendingAnswer?.set(allow)
                pendingLatch?.countDown()
                confirmBar?.visibility = View.GONE
            }
        }
        confirmBar!!.addView(confirmLabel)
        confirmBar!!.addView(confirmBtn("允许", 0xFF4E6BFF.toInt(), true))
        confirmBar!!.addView(confirmBtn("拒绝", 0xFF8A5A5A.toInt(), false))
        box.addView(confirmBar)

        // ---- 输入区
        val bottom = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(10f))
        }
        inputView = EditText(c).apply {
            hint = "让我帮你做点什么…"
            setHintTextColor(0xFF6B7A90.toInt())
            setTextColor(0xFFEAF1F8.toInt())
            textSize = 14f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = 16f * d
                setColor(0xFF202634.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnSend = TextView(c).apply {
            text = "发送"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = GradientDrawable().apply { cornerRadius = 16f * d; setColor(0xFF4E6BFF.toInt()) }
            isClickable = true
            setOnClickListener { submit() }
        }
        bottom.addView(inputView)
        bottom.addView(btnSend)
        box.addView(bottom)
        bottomView = bottom

        val w2 = (dm.widthPixels * 0.94f).toInt()
        val h2 = (dm.heightPixels * 0.62f).toInt()
        val lp = WindowManager.LayoutParams(
            w2,
            h2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(8f)
        }
        fullH = h2
        baseFlags = lp.flags

        try {
            w.addView(box, lp)
            root = box
        } catch (t: Throwable) {
            Toast.makeText(c, "悬浮窗打开失败：" + (t.message ?: ""), Toast.LENGTH_SHORT).show()
            Agent.markClosed()
            return
        }

        val a11y = AgentService.isEnabled()
        val hasKey = AiPrefs.hasKey(c)
        addMsg("assistant", when {
            !hasKey -> "先在右上角 ⚙️ 里填上 DeepSeek 的 API Key～"
            !a11y -> "API Key 有了，但还没开无障碍，我现在只能聊天、还动不了手机。去设置页点「开启无障碍」吧。"
            else -> "我在呢～想让我帮你操作手机就直接说，比如「打开微信」「帮我点一下发送」。"
        })
    }

    fun detach() {
        val v = root ?: return
        root = null
        try {
            wm?.removeView(v)
        } catch (_: Throwable) {
        }
    }

    // ---------- 执行动作时的窗口处理：别自己挡着自己干活

    /** 让触摸穿透：临时加 NOT_TOUCHABLE / NOT_FOCUSABLE，手势才能落到下面的 App */
    fun setPassthrough(on: Boolean) {
        main.post {
            val v = root ?: return@post
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: return@post
            lp.flags = if (on) {
                baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            } else {
                baseFlags
            }
            try { wm?.updateViewLayout(v, lp) } catch (_: Throwable) {}
        }
    }

    /** 收成一条状态条：执行动作时别占着半块屏幕 */
    fun setCollapsed(on: Boolean) {
        main.post {
            val v = root ?: return@post
            val c = ctx ?: return@post
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: return@post
            val d = c.resources.displayMetrics.density
            if (on) {
                barView?.visibility = View.GONE
                scrollView?.visibility = View.GONE
                bottomView?.visibility = View.GONE
                statusView?.visibility = View.VISIBLE
                lp.height = (40 * d).roundToInt()
            } else {
                barView?.visibility = View.VISIBLE
                scrollView?.visibility = View.VISIBLE
                bottomView?.visibility = View.VISIBLE
                statusView?.visibility = View.VISIBLE
                lp.height = fullH
            }
            try { wm?.updateViewLayout(v, lp) } catch (_: Throwable) {}
        }
    }

    /** 截屏前把自己藏掉，免得拍进去的全是自己的面板 */
    fun setHidden(on: Boolean) {
        main.post {
            val v = root ?: return@post
            v.visibility = if (on) View.GONE else View.VISIBLE
        }
    }

    private fun submit() {
        val c = ctx ?: return
        val text = inputView?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || busy) return
        inputView?.setText("")
        if (!AiPrefs.hasKey(c)) {
            addMsg("assistant", "还没填 API Key，点右上角 ⚙️")
            return
        }
        addMsg("user", text)
        runTurn(c, text)
    }

    /** 供全屏页面复用 */
    fun submitFromActivity(
        c: Context,
        sess: AgentRunner.Session,
        text: String,
        onDone: (String) -> Unit,
        onStep: (String) -> Unit,
        onConfirm: ((String) -> Boolean)? = null,
    ) {
        if (!AiPrefs.hasKey(c)) {
            onDone("还没填 API Key，先去设置页填")
            return
        }
        Thread {
            val ui = object : AgentRunner.UI {
                override fun step(text: String) {
                    main.post { onStep(text) }
                }

                override fun confirm(text: String): Boolean {
                    val cb = onConfirm
                    if (cb == null) {
                        onStep("（未接确认回调，已放行：$text）")
                        return true
                    }
                    val latch = java.util.concurrent.CountDownLatch(1)
                    val ans = java.util.concurrent.atomic.AtomicBoolean(false)
                    main.post {
                        ans.set(
                            try {
                                cb(text)
                            } catch (_: Throwable) {
                                false
                            },
                        )
                        latch.countDown()
                    }
                    return try {
                        latch.await(180, java.util.concurrent.TimeUnit.SECONDS)
                        ans.get()
                    } catch (_: Throwable) {
                        false
                    }
                }
            }
            val ans = try {
                AgentRunner.run(c, sess, text, ui)
            } catch (t: Throwable) {
                "出错啦：" + (t.message ?: "")
            }
            main.post { onDone(ans) }
        }.start()
    }

    private fun runTurn(c: Context, text: String) {
        busy = true
        btnSend?.text = "…"
        Thread {
            val ui = object : AgentRunner.UI {
                override fun step(s: String) {
                    main.post { statusView?.text = s }
                }

                override fun confirm(s: String): Boolean {
                    val latch = CountDownLatch(1)
                    val ans = AtomicBoolean(false)
                    main.post {
                        setCollapsed(false)
                        pendingAnswer = ans
                        pendingLatch = latch
                        confirmLabel?.text = s
                        confirmBar?.visibility = View.VISIBLE
                        main.postDelayed({
                            if (pendingLatch === latch) {
                                ans.set(false)
                                confirmBar?.visibility = View.GONE
                                latch.countDown()
                            }
                        }, 90_000)
                    }
                    try {
                        latch.await(95, TimeUnit.SECONDS)
                    } catch (_: Throwable) {
                    }
                    main.post { statusView?.text = "" }
                    return ans.get()
                }
            }
            val ans = try {
                AgentRunner.run(c, session, text, ui)
            } catch (t: Throwable) {
                "出错啦：" + (t.message ?: t.javaClass.simpleName)
            }
            main.post {
                addMsg("assistant", ans)
                statusView?.text = ""
                busy = false
                btnSend?.text = "发送"
                scrollDown()
            }
        }.start()
    }

    private fun addMsg(role: String, text: String) {
        val c = ctx ?: return
        val d = c.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).roundToInt()
        val mine = role == "user"
        val tv = TextView(c).apply {
            this.text = text
            setTextColor(if (mine) 0xFFEAF1F8.toInt() else 0xFF16223B.toInt())
            textSize = 13f
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            maxWidth = (c.resources.displayMetrics.widthPixels * 0.74f).toInt()
            background = GradientDrawable().apply {
                cornerRadius = 14f * d
                setColor(if (mine) 0xFF2A3550.toInt() else 0xFFCFE3FF.toInt())
            }
        }
        val wrap = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
        }
        wrap.addView(tv)
        listView?.addView(
            wrap,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        scrollDown()
    }

    private fun scrollDown() {
        scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
    }
}

// ==================================================================== 全屏对话页（键盘更好用）

class AgentActivity : Activity() {

    private val session = AgentRunner.Session()
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var btnSend: TextView
    private lateinit var status: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AiStore.init(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).roundToInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1117.toInt())
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), dp(10f), dp(6f), dp(10f))
            setBackgroundColor(0xFF161A21.toInt())
        }
        fun barBtn(text: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            setTextColor(0xFF8FB6E8.toInt())
            textSize = 15f
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            isClickable = true
            setOnClickListener { onClick() }
        }
        bar.addView(barBtn("‹ 返回") { finish() })
        bar.addView(
            TextView(this).apply {
                text = "🐟 大肥鱼 · 帮你操作手机"
                setTextColor(0xFFEAF1F8.toInt())
                textSize = 15f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        bar.addView(barBtn("🗑") {
            session.clear(this)
            list.removeAllViews()
            addMsg("assistant", "（对话已清空）")
        })
        bar.addView(barBtn("⚙️") { startActivity(Intent(this, AiSettingsActivity::class.java)) })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        scroll = ScrollView(this).apply { isFillViewport = true }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        status = TextView(this).apply {
            setTextColor(0xFFFFC46B.toInt())
            textSize = 12f
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
        }
        root.addView(status)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(8f), dp(10f), dp(12f))
            setBackgroundColor(0xFF161A21.toInt())
        }
        input = EditText(this).apply {
            hint = "让我帮你做点什么…"
            setHintTextColor(0xFF6B7A90.toInt())
            setTextColor(0xFFEAF1F8.toInt())
            textSize = 14f
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = 16f * d
                setColor(0xFF202634.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnSend = TextView(this).apply {
            text = "发送"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            background = GradientDrawable().apply { cornerRadius = 16f * d; setColor(0xFF4E6BFF.toInt()) }
            isClickable = true
            setOnClickListener { send() }
        }
        bottom.addView(input)
        bottom.addView(
            btnSend,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(8f), 0, 0, 0) },
        )
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)

        addMsg("assistant", when {
            !AiPrefs.hasKey(this) -> "先去右上角 ⚙️ 填 DeepSeek 的 API Key～"
            !AgentService.isEnabled() -> "Key 有了，但无障碍还没开，我现在只能聊天、动不了手机。回设置页点「开启无障碍」。"
            else -> "说吧，想让我帮你做什么？比如「打开微信」「帮我给文件传输助手发个 1」。"
        })
    }

    private fun send() {
        if (busy) return
        val text = input.text.toString().trim()
        if (text.isBlank()) return
        if (!AiPrefs.hasKey(this)) {
            startActivity(Intent(this, AiSettingsActivity::class.java))
            return
        }
        input.setText("")
        addMsg("user", text)
        busy = true
        btnSend.text = "…"

        AgentWindow.submitFromActivity(
            this,
            session,
            text,
            onDone = { ans ->
                addMsg("assistant", ans)
                status.text = ""
                busy = false
                btnSend.text = "发送"
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            },
            onStep = { s -> status.text = s },
            onConfirm = { q -> askConfirm(q) },
        )
    }

    /** 危险操作确认：弹一个对话框问一句，用户点允许才放行 */
    private fun askConfirm(what: String): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        val ans = java.util.concurrent.atomic.AtomicBoolean(false)
        runOnUiThread {
            try {
                android.app.AlertDialog.Builder(this)
                    .setTitle("要继续吗？")
                    .setMessage(what)
                    .setPositiveButton("允许") { _, _ ->
                        ans.set(true)
                        latch.countDown()
                    }
                    .setNegativeButton("拒绝") { _, _ ->
                        ans.set(false)
                        latch.countDown()
                    }
                    .setOnCancelListener {
                        ans.set(false)
                        latch.countDown()
                    }
                    .show()
            } catch (_: Throwable) {
                ans.set(false)
                latch.countDown()
            }
        }
        return try {
            latch.await(180, java.util.concurrent.TimeUnit.SECONDS)
            ans.get()
        } catch (_: Throwable) {
            false
        }
    }

    private fun addMsg(role: String, text: String) {
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).roundToInt()
        val mine = role == "user"
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(if (mine) 0xFFEAF1F8.toInt() else 0xFF16223B.toInt())
            textSize = 14f
            setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
            maxWidth = (resources.displayMetrics.widthPixels * 0.76f).toInt()
            background = GradientDrawable().apply {
                cornerRadius = 16f * d
                setColor(if (mine) 0xFF2A3550.toInt() else 0xFFCFE3FF.toInt())
            }
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(dp(6f), dp(3f), dp(6f), dp(3f))
        }
        wrap.addView(tv)
        list.addView(
            wrap,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}

// ==================================================================== 设置页

class AiSettingsActivity : Activity() {

    private lateinit var edBase: EditText
    private lateinit var edPath: EditText
    private lateinit var edModel: EditText
    private lateinit var edKey: EditText
    private lateinit var edTemp: EditText
    private lateinit var edMax: EditText
    private lateinit var edSys: EditText
    private lateinit var cbConfirm: CheckBox
    private lateinit var result: TextView
    private lateinit var btnTest: TextView
    private lateinit var btnBalance: TextView
    private lateinit var btnA11y: TextView
    private lateinit var stateA11y: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AiStore.init(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).roundToInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1117.toInt())
        }

        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6f), dp(10f), dp(6f), dp(10f))
                setBackgroundColor(0xFF161A21.toInt())
                addView(
                    TextView(this@AiSettingsActivity).apply {
                        text = "‹ 返回"
                        setTextColor(0xFF8FB6E8.toInt())
                        textSize = 15f
                        setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
                        isClickable = true
                        setOnClickListener { finish() }
                    },
                )
                addView(
                    TextView(this@AiSettingsActivity).apply {
                        text = "AI / Agent 设置"
                        setTextColor(0xFFEAF1F8.toInt())
                        textSize = 16f
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
                addView(
                    TextView(this@AiSettingsActivity).apply {
                        text = "恢复默认"
                        setTextColor(0xFFFFC46B.toInt())
                        textSize = 13f
                        setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
                        isClickable = true
                        setOnClickListener { restoreDefaults() }
                    },
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        fun label(text: String) {
            body.addView(
                TextView(this).apply {
                    this.text = text
                    setTextColor(0xFF9FB0C8.toInt())
                    textSize = 12f
                    setPadding(dp(2f), dp(10f), dp(2f), dp(4f))
                },
            )
        }

        fun edit(value: String, multiline: Boolean = false, password: Boolean = false): EditText {
            val et = EditText(this).apply {
                setText(value)
                setTextColor(0xFFEAF1F8.toInt())
                setHintTextColor(0xFF6B7A90.toInt())
                textSize = 14f
                setSingleLine(!multiline)
                if (multiline) {
                    minLines = 4
                    gravity = Gravity.TOP
                }
                inputType = when {
                    password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    else -> InputType.TYPE_CLASS_TEXT
                }
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = GradientDrawable().apply {
                    cornerRadius = 12f * d
                    setColor(0xFF202634.toInt())
                    setStroke(dp(1f), 0xFF2C3442.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            body.addView(et)
            return et
        }

        // ---- 无障碍状态 + 开启入口
        stateA11y = TextView(this).apply {
            setTextColor(0xFFFFC46B.toInt())
            textSize = 12f
            setPadding(dp(2f), dp(8f), dp(2f), dp(6f))
        }
        body.addView(stateA11y)
        btnA11y = TextView(this).apply {
            text = "开启无障碍（不开启就只能聊天，不能操作手机）"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            background = GradientDrawable().apply { cornerRadius = 14f * d; setColor(0xFF4E6BFF.toInt()) }
            isClickable = true
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Throwable) {
                    Toast.makeText(this@AiSettingsActivity, "打不开设置页，请手动去系统设置 → 无障碍", Toast.LENGTH_LONG).show()
                }
            }
        }
        body.addView(btnA11y)

        // ---- 备份 / 恢复：卸载重装后靠它把 Key 和人设搬回来
        run {
            val tip = TextView(this).apply {
                text = "备份 / 恢复"
                setTextColor(0xFF9FB0C8.toInt())
                textSize = 12f
                setPadding(dp(2f), dp(12f), dp(2f), dp(2f))
            }
            body.addView(tip)

            val box = EditText(this).apply {
                setText(AiStore.exportText(this@AiSettingsActivity, maskKey = true))
                setTextColor(0xFFA9C4E4.toInt())
                textSize = 11f
                setSingleLine(false)
                minLines = 3
                gravity = android.view.Gravity.TOP
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                background = GradientDrawable().apply {
                    cornerRadius = 10f * d
                    setColor(0xFF1A202B.toInt())
                    setStroke(dp(1f), 0xFF2C3442.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            body.addView(box)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(2f), dp(6f), dp(2f), dp(6f))
            }

            fun smallBtn(text: String, onClick: () -> Unit) = TextView(this).apply {
                this.text = text
                setTextColor(0xFFB9A6FF.toInt())
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
                background = GradientDrawable().apply {
                    cornerRadius = 10f * d
                    setColor(0x00000000)
                    setStroke(dp(1f), 0xFF3A4560.toInt())
                }
                isClickable = true
                setOnClickListener { onClick() }
            }

            row.addView(smallBtn("复制(带Key)") {
                box.setText(AiStore.exportText(this@AiSettingsActivity, maskKey = false))
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("dsh-ai-config", box.text.toString()))
                Toast.makeText(this@AiSettingsActivity, "已复制到剪贴板（含 Key，别乱发）", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(smallBtn("从文本恢复") {
                val err = AiStore.importText(this@AiSettingsActivity, box.text.toString())
                if (err == null) {
                    Toast.makeText(this@AiSettingsActivity, "已恢复，重进本页查看", Toast.LENGTH_LONG).show()
                    recreate()
                } else {
                    Toast.makeText(this@AiSettingsActivity, err, Toast.LENGTH_LONG).show()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(6f), 0, 0, 0)
            })

            row.addView(smallBtn("重置对话") {
                AiStore.clearHistory(this@AiSettingsActivity)
                Toast.makeText(this@AiSettingsActivity, "已清空保存的对话记录", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(6f), 0, 0, 0)
            })
            body.addView(row)

            body.addView(
                TextView(this).apply {
                    text = "配置会自动存在手机里，覆盖安装不会丢。卸载重装才需要用上面这段文本恢复。"
                    setTextColor(0xFF6B7A90.toInt())
                    textSize = 10f
                    setPadding(dp(2f), dp(2f), dp(2f), dp(4f))
                },
            )
        }

        label("Base URL（中转站填对方地址，官方留默认）")
        edBase = edit(AiPrefs.baseUrl(this))
        label("聊天接口路径（报 404 改成 /chat/completions）")
        edPath = edit(AiPrefs.chatPath(this))
        label("模型（推荐 deepseek-v4-flash）")
        edModel = edit(AiPrefs.model(this))
        label("API Key（platform.deepseek.com 申请，只存本机）")
        edKey = edit(AiPrefs.apiKey(this), password = true)
        body.addView(
            CheckBox(this).apply {
                text = "显示 API Key"
                setTextColor(0xFF9FB0C8.toInt())
                textSize = 12f
                setPadding(dp(2f), dp(6f), dp(2f), dp(2f))
                setOnCheckedChangeListener { _, checked ->
                    edKey.inputType = if (checked) {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    } else {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                    edKey.setSelection(edKey.text.length)
                }
            },
        )
        label("温度（0~2，操作类任务建议 0.3~0.7）")
        edTemp = edit(AiPrefs.temperature(this).toString())
        label("单次最大 token（默认 1024）")
        edMax = edit(AiPrefs.maxTokens(this).toString())
        label("人设 / 系统提示词")
        edSys = edit(AiPrefs.systemPrompt(this), multiline = true)

        cbConfirm = CheckBox(this).apply {
            text = "危险操作（付款/发送/删除等）先问我一句"
            setTextColor(0xFFCFDCEA.toInt())
            textSize = 13f
            isChecked = AiPrefs.confirmDanger(this@AiSettingsActivity)
            setPadding(dp(2f), dp(12f), dp(2f), dp(4f))
        }
        body.addView(cbConfirm)

        result = TextView(this).apply {
            val vm = AiPrefs.visionModel(this@AiSettingsActivity)
            text = "当前：" + AiPrefs.all(this@AiSettingsActivity) +
                "\n视觉模型：" + (if (vm.isBlank()) "（待首次看图自动选择）" else vm)
            setTextColor(0xFF8FB6E8.toInt())
            textSize = 12f
            setPadding(dp(2f), dp(8f), dp(2f), dp(4f))
        }
        body.addView(result)

        fun ghost(text: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            setTextColor(0xFFB9A6FF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = 14f * d
                setColor(0x00000000)
                setStroke(dp(1f), 0xFF3A4560.toInt())
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(14f))
                setBackgroundColor(0xFF161A21.toInt())
                btnTest = ghost("测试连接") { save(true); doTest() }
                addView(btnTest, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                btnBalance = ghost("查余额") { save(true); doBalance() }
                addView(
                    btnBalance,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(8f), 0, 0, 0)
                    },
                )
                addView(
                    TextView(this@AiSettingsActivity).apply {
                        text = "保存"
                        setTextColor(0xFFFFFFFF.toInt())
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
                        background = GradientDrawable().apply {
                            cornerRadius = 14f * d
                            setColor(0xFF4E6BFF.toInt())
                        }
                        isClickable = true
                        setOnClickListener { save(false) }
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(8f), 0, 0, 0)
                    },
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val on = AgentService.isEnabled()
        stateA11y.text = if (on) "✅ 无障碍已开启，可以操作手机了" else "⚠️ 无障碍未开启，现在只能聊天"
        stateA11y.setTextColor(if (on) Color.parseColor("#6BE3B0") else Color.parseColor("#FFC46B"))
        btnA11y.text = if (on) "去系统里关闭无障碍" else "开启无障碍（不开启就只能聊天，不能操作手机）"
    }

    private fun save(silent: Boolean) {
        AiPrefs.setBaseUrl(this, edBase.text.toString())
        AiPrefs.setChatPath(this, edPath.text.toString())
        AiPrefs.setModel(this, edModel.text.toString())
        AiPrefs.setApiKey(this, edKey.text.toString())
        AiPrefs.setTemperature(this, edTemp.text.toString().toFloatOrNull() ?: AiPrefs.DEFAULT_TEMP)
        AiPrefs.setMaxTokens(this, edMax.text.toString().toIntOrNull() ?: AiPrefs.DEFAULT_MAX)
        AiPrefs.setSystemPrompt(this, edSys.text.toString())
        AiPrefs.setConfirmDanger(this, cbConfirm.isChecked)
        AiStore.saveToDisk(this)
        result.text = "当前：" + AiPrefs.all(this)
        if (!silent) Toast.makeText(this, "已保存到本机", Toast.LENGTH_SHORT).show()
    }

    private fun restoreDefaults() {
        AiPrefs.setBaseUrl(this, AiPrefs.DEFAULT_BASE)
        AiPrefs.setChatPath(this, AiPrefs.DEFAULT_PATH)
        AiPrefs.setModel(this, AiPrefs.DEFAULT_MODEL)
        AiPrefs.setTemperature(this, AiPrefs.DEFAULT_TEMP)
        AiPrefs.setMaxTokens(this, AiPrefs.DEFAULT_MAX)
        AiPrefs.setSystemPrompt(this, AiPrefs.DEFAULT_SYS)
        AiPrefs.setConfirmDanger(this, true)
        edBase.setText(AiPrefs.DEFAULT_BASE)
        edPath.setText(AiPrefs.DEFAULT_PATH)
        edModel.setText(AiPrefs.DEFAULT_MODEL)
        edTemp.setText(AiPrefs.DEFAULT_TEMP.toString())
        edMax.setText(AiPrefs.DEFAULT_MAX.toString())
        edSys.setText(AiPrefs.DEFAULT_SYS)
        cbConfirm.isChecked = true
        AiStore.saveToDisk(this)
        result.text = "当前：" + AiPrefs.all(this)
        Toast.makeText(this, "已恢复默认（API Key 保留）", Toast.LENGTH_SHORT).show()
    }

    private fun doTest() {
        btnTest.text = "测试中…"
        btnTest.isClickable = false
        Thread {
            var msg: String
            try {
                if (AiPrefs.apiKey(this).isBlank()) {
                    msg = "还没填 API Key"
                } else {
                    val body = JSONObject().apply {
                        put("model", AiPrefs.model(this@AiSettingsActivity))
                        put("messages", JSONArray().put(
                            JSONObject().put("role", "user").put("content", "hi"),
                        ))
                        put("max_tokens", 16)
                        put("stream", false)
                    }
                    val o = DsNet.post(this, AiPrefs.chatPath(this), body, 30_000)
                    val t = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                        ?.optString("content")?.trim().orEmpty()
                    msg = "连接成功 · " + AiPrefs.model(this) + "\n「" + t.take(40) + "」"
                }
            } catch (e2: Throwable) {
                msg = DsNet.friendly(e2)
            }
            runOnUiThread {
                btnTest.text = "测试连接"
                btnTest.isClickable = true
                result.text = msg
            }
        }.start()
    }

    private fun doBalance() {
        btnBalance.text = "查询中…"
        btnBalance.isClickable = false
        Thread {
            var msg: String
            try {
                if (AiPrefs.apiKey(this).isBlank()) {
                    msg = "还没填 API Key"
                } else {
                    val o = DsNet.get(this, "/user/balance")
                    val infos = o.optJSONArray("balance_infos")
                    val sb = StringBuilder()
                    if (infos == null || infos.length() == 0) {
                        sb.append("有余额接口但没明细：").append(o.toString().take(120))
                    } else {
                        for (i in 0 until infos.length()) {
                            val b = infos.optJSONObject(i) ?: continue
                            if (i > 0) sb.append("\n")
                            sb.append(b.optString("currency", "CNY")).append(" 总余额 ")
                                .append(b.optString("total_balance", "?"))
                                .append("（充值 ").append(b.optString("topped_up_balance", "?"))
                                .append(" / 赠送 ").append(b.optString("granted_balance", "?")).append("）")
                        }
                        if (!o.optBoolean("is_available", true)) sb.append("\n⚠️ 余额不可用，调用会被拒绝")
                    }
                    msg = sb.toString()
                }
            } catch (e2: Throwable) {
                msg = DsNet.friendly(e2)
            }
            runOnUiThread {
                btnBalance.text = "查余额"
                btnBalance.isClickable = true
                result.text = msg
            }
        }.start()
    }
}
