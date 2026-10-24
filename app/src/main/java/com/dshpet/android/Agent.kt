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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.media.MediaPlayer
import android.media.MediaRecorder
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
    private const val K_VBASE = "vision_base"
    private const val K_VPATH = "vision_path"
    private const val K_VKEY = "vision_key"
    private const val K_WSIZE = "win_size"
    private const val K_TTS = "tts_on"
    private const val K_ASR_BASE = "asr_base"
    private const val K_ASR_KEY = "asr_key"
    private const val K_ASR_MODEL = "asr_model"
    private const val K_TTS_BASE = "tts_base"
    private const val K_TTS_KEY = "tts_key"
    private const val K_TTS_MODEL = "tts_model"
    private const val K_TTS_VOICE = "tts_voice"
    private const val K_VPROVIDER = "voice_provider"

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

    /** 是否朗读她的回复（默认开） */
    fun ttsOn(ctx: Context): Boolean = sp(ctx).getBoolean(K_TTS, true)

    fun setTtsOn(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(K_TTS, v).apply()
    }

    /**
     * 语音服务商：
     *  openai    —— 硅基流动 / OpenAI / Groq / 自建，走 /audio/transcriptions + /audio/speech
     *  dashscope —— 阿里云百炼。它的兼容模式只有 /chat/completions 和 /embeddings，
     *              音频必须走自有协议，所以单独一条通道。
     */
    fun voiceProvider(ctx: Context): String =
        sp(ctx).getString(K_VPROVIDER, "").orEmpty().trim().lowercase().ifBlank { "openai" }

    fun setVoiceProvider(ctx: Context, v: String) =
        sp(ctx).edit().putString(K_VPROVIDER, v.trim().lowercase()).apply()

    // ---- 语音识别（ASR），走 OpenAI 兼容的 /audio/transcriptions
    fun asrBase(ctx: Context): String = sp(ctx).getString(K_ASR_BASE, "").orEmpty().trim()
    fun setAsrBase(ctx: Context, v: String) = sp(ctx).edit().putString(K_ASR_BASE, v.trim()).apply()

    fun asrKey(ctx: Context): String = sp(ctx).getString(K_ASR_KEY, "").orEmpty().trim()
    fun setAsrKey(ctx: Context, v: String) = sp(ctx).edit().putString(K_ASR_KEY, v.trim()).apply()

    fun asrModel(ctx: Context): String =
        sp(ctx).getString(K_ASR_MODEL, "").orEmpty().trim().ifBlank { "FunAudioLLM/SenseVoiceSmall" }
    fun setAsrModel(ctx: Context, v: String) = sp(ctx).edit().putString(K_ASR_MODEL, v.trim()).apply()

    /** 配了端点和 Key 才走云端识别，否则用系统自带 */
    fun asrReady(ctx: Context): Boolean =
        asrBase(ctx).isNotBlank() && asrKey(ctx).isNotBlank()

    // ---- 语音合成（TTS），走 OpenAI 兼容的 /audio/speech
    fun ttsBase(ctx: Context): String = sp(ctx).getString(K_TTS_BASE, "").orEmpty().trim()
    fun setTtsBase(ctx: Context, v: String) = sp(ctx).edit().putString(K_TTS_BASE, v.trim()).apply()

    fun ttsKey(ctx: Context): String = sp(ctx).getString(K_TTS_KEY, "").orEmpty().trim()
    fun setTtsKey(ctx: Context, v: String) = sp(ctx).edit().putString(K_TTS_KEY, v.trim()).apply()

    fun ttsModel(ctx: Context): String =
        sp(ctx).getString(K_TTS_MODEL, "").orEmpty().trim().ifBlank { "FunAudioLLM/CosyVoice2-0.5B" }
    fun setTtsModel(ctx: Context, v: String) = sp(ctx).edit().putString(K_TTS_MODEL, v.trim()).apply()

    fun ttsVoice(ctx: Context): String =
        sp(ctx).getString(K_TTS_VOICE, "").orEmpty().trim().ifBlank { "FunAudioLLM/CosyVoice2-0.5B:anna" }
    fun setTtsVoice(ctx: Context, v: String) = sp(ctx).edit().putString(K_TTS_VOICE, v.trim()).apply()

    fun ttsReady(ctx: Context): Boolean =
        ttsBase(ctx).isNotBlank() && ttsKey(ctx).isNotBlank()

    /** 悬浮窗口大小档位：0 小 / 1 中（默认）/ 2 大 */
    fun winSize(ctx: Context): Int = sp(ctx).getInt(K_WSIZE, 1).coerceIn(0, 2)

    fun setWinSize(ctx: Context, v: Int) {
        sp(ctx).edit().putInt(K_WSIZE, v.coerceIn(0, 2)).apply()
    }

    /** 视觉端点：全部留空时复用主配置（DeepSeek）。想用别家支持视觉的模型就单独填 */

    fun visionBase(ctx: Context): String =
        sp(ctx).getString(K_VBASE, "").orEmpty().trim().ifBlank { baseUrl(ctx) }

    fun setVisionBase(ctx: Context, v: String) {
        sp(ctx).edit().putString(K_VBASE, v.trim()).apply()
    }

    fun visionPath(ctx: Context): String =
        sp(ctx).getString(K_VPATH, "").orEmpty().trim().ifBlank { chatPath(ctx) }

    fun setVisionPath(ctx: Context, v: String) {
        val x = v.trim()
        sp(ctx).edit().putString(K_VPATH, if (x.startsWith("/")) x else "/$x").apply()
    }

    fun visionKey(ctx: Context): String =
        sp(ctx).getString(K_VKEY, "").orEmpty().trim().ifBlank { apiKey(ctx) }

    fun setVisionKey(ctx: Context, v: String) {
        sp(ctx).edit().putString(K_VKEY, v.trim()).apply()
    }

    /**
     * 原始值（不做回退）。备份和设置页显示必须用这个：
     * 用回退值的话「没填」会被写成主 Key 存进备份，
     * 恢复时又把主 Key 固化成视觉 Key，导致看图拿错 Key 调不通。
     */
    fun visionBaseRaw(ctx: Context): String = sp(ctx).getString(K_VBASE, "").orEmpty().trim()

    fun visionPathRaw(ctx: Context): String = sp(ctx).getString(K_VPATH, "").orEmpty().trim()

    fun visionKeyRaw(ctx: Context): String = sp(ctx).getString(K_VKEY, "").orEmpty().trim()
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

    /** 最简 JSON POST：给百炼这类自定义端点用 */
    fun postRaw(urlStr: String, key: String, body: JSONObject, timeoutMs: Int = 60_000): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
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
            return raw
        } finally {
            conn.disconnect()
        }
    }

    /** 指定端点 / Key 的非流式请求（视觉端点可能和主配置不同） */
    fun postAt(
        baseUrl: String,
        path: String,
        key: String,
        body: JSONObject,
        timeoutMs: Int = 60_000,
    ): JSONObject {
        val url = baseUrl.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
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
            val o = JSONObject(raw)
            // 把原始报文塞回去，失败时好诊断
            o.put("_raw", raw.take(400))
            return o
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
            400 -> "请求参数不对（400）：最常见是「模型和音色不匹配」，比如 Cherry 只能配 qwen3-tts-*，配到 qwen-audio-* 上就会被拒"
            422 -> "请求参数不对（422）：通常是消息格式有问题，或模型名 / max_tokens 越界"
            401 -> "API Key 不对、没填或已被删除（401）。最常见是填错服务商：百炼和硅基流动的 Key 不通用，别把视觉那个直接拿来用"
            402 -> "账户余额不足，去充值（402）"
            403 -> "没有权限调用这个模型（403）"
            404 -> "地址不对，通常是 Base URL 或接口路径拼错（404）"
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
        put("vision_base", AiPrefs.visionBaseRaw(ctx))
        put("vision_path", AiPrefs.visionPathRaw(ctx))
        put("vision_key", AiPrefs.visionKeyRaw(ctx))
        put("temperature", AiPrefs.temperature(ctx).toDouble())
        put("max_tokens", AiPrefs.maxTokens(ctx))
        put("system_prompt", AiPrefs.systemPrompt(ctx))
        put("confirm_danger", AiPrefs.confirmDanger(ctx))
        put("tts_on", AiPrefs.ttsOn(ctx))
        put("asr_base", AiPrefs.asrBase(ctx))
        put("asr_key", AiPrefs.asrKey(ctx))
        put("asr_model", AiPrefs.asrModel(ctx))
        put("tts_base", AiPrefs.ttsBase(ctx))
        put("tts_key", AiPrefs.ttsKey(ctx))
        put("tts_model", AiPrefs.ttsModel(ctx))
        put("tts_voice", AiPrefs.ttsVoice(ctx))
        put("voice_provider", AiPrefs.voiceProvider(ctx))
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
        str("asr_base", { AiPrefs.setAsrBase(ctx, it) }, AiPrefs.asrBase(ctx), "")
        str("asr_key", { AiPrefs.setAsrKey(ctx, it) }, AiPrefs.asrKey(ctx), "")
        str("asr_model", { AiPrefs.setAsrModel(ctx, it) }, AiPrefs.asrModel(ctx), "FunAudioLLM/SenseVoiceSmall")
        str("tts_base", { AiPrefs.setTtsBase(ctx, it) }, AiPrefs.ttsBase(ctx), "")
        str("tts_key", { AiPrefs.setTtsKey(ctx, it) }, AiPrefs.ttsKey(ctx), "")
        str("tts_model", { AiPrefs.setTtsModel(ctx, it) }, AiPrefs.ttsModel(ctx), "FunAudioLLM/CosyVoice2-0.5B")
        str("tts_voice", { AiPrefs.setTtsVoice(ctx, it) }, AiPrefs.ttsVoice(ctx), "FunAudioLLM/CosyVoice2-0.5B:anna")
        str("voice_provider", { AiPrefs.setVoiceProvider(ctx, it) }, AiPrefs.voiceProvider(ctx), "openai")
        // 旧版本备份会把主配置回退值写进 vision_*，这里识别出来当空处理并清掉
        val mainKey = o.optString("api_key", "").trim()
        val mainBase = o.optString("base_url", "").trim()
        val mainPath = o.optString("chat_path", "").trim()

        val vBase = o.optString("vision_base", "").trim()
        val vPath = o.optString("vision_path", "").trim()
        val vKey = o.optString("vision_key", "").trim()

        if (vBase.isNotEmpty() && vBase != mainBase) {
            if (vBase != AiPrefs.visionBaseRaw(ctx)) {
                AiPrefs.setVisionBase(ctx, vBase)
                changed = true
            }
        } else if (vBase == mainBase && vBase.isNotEmpty() && AiPrefs.visionBaseRaw(ctx).isNotEmpty()) {
            AiPrefs.setVisionBase(ctx, "")   // 修掉旧备份的串位
            changed = true
        }

        if (vPath.isNotEmpty() && vPath != mainPath) {
            if (vPath != AiPrefs.visionPathRaw(ctx)) {
                AiPrefs.setVisionPath(ctx, vPath)
                changed = true
            }
        } else if (vPath == mainPath && vPath.isNotEmpty() && AiPrefs.visionPathRaw(ctx).isNotEmpty()) {
            AiPrefs.setVisionPath(ctx, "")
            changed = true
        }

        if (vKey.isNotEmpty() && vKey != mainKey) {
            if (vKey != AiPrefs.visionKeyRaw(ctx)) {
                AiPrefs.setVisionKey(ctx, vKey)
                changed = true
            }
        } else if (vKey == mainKey && vKey.isNotEmpty() && AiPrefs.visionKeyRaw(ctx).isNotEmpty()) {
            AiPrefs.setVisionKey(ctx, "")    // 修掉旧备份的串位
            changed = true
        }

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

// ==================================================================== 语音（听 + 说）

object Voice {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile
    private var speaking = false

    /** 初始化 TTS。必须在主线程调用 */
    fun init(ctx: Context) {
        if (tts != null) return
        try {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                try {
                    tts?.language = java.util.Locale.SIMPLIFIED_CHINESE
                } catch (_: Throwable) {
                }
                try {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            speaking = true
                        }

                        override fun onDone(utteranceId: String?) {
                            speaking = false
                        }

                        @Deprecated("legacy")
                        override fun onError(utteranceId: String?) {
                            speaking = false
                        }
                    })
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** 念一句话。太长会截断，免得念半天 */
    fun say(ctx: Context, text: String) {
        if (!AiPrefs.ttsOn(ctx)) return
        // 不用 Regex：省得写一堆转义。念之前把 markdown 符号和空白清一下
        val t = buildString {
            var lastSpace = false
            for (ch in text) {
                val isNoise = ch in "#*`>|[]()（）【】《》\n\r\t"
                val isSpace = ch == ' ' || ch == '\u3000'
                if (isNoise || isSpace) {
                    if (!lastSpace) append(' ')
                    lastSpace = true
                } else {
                    append(ch)
                    lastSpace = false
                }
            }
        }.trim()
        if (t.isBlank()) return
        val speak = if (t.length > 160) t.take(160) + "，后面的内容可以看屏幕" else t
        // 配了云端 TTS 就用云端（有自己的音色），否则退回系统 TTS
        if (AiPrefs.ttsReady(ctx) && sayCloud(ctx, speak)) return
        Handler(Looper.getMainLooper()).post {
            try {
                if (!ttsReady) init(ctx)
                val engine = tts
                if (engine == null) {
                    init(ctx)
                    return@post
                }
                val params = Bundle()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "dsh")
                engine.speak(speak, TextToSpeech.QUEUE_FLUSH, params, "dsh")
            } catch (_: Throwable) {
            }
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Throwable) {
        }
        try {
            player?.let {
                if (it.isPlaying) it.stop()
            }
        } catch (_: Throwable) {
        }
        speaking = false
    }

    fun isSpeaking(): Boolean = speaking

    // ---------------------------------------------------------------- 云端 ASR

    private var recorder: MediaRecorder? = null
    private var recFile: java.io.File? = null

    /** 是否走云端识别（配了端点就用云端，比系统识别稳得多） */
    fun asrCloud(ctx: Context): Boolean = AiPrefs.asrReady(ctx)

    /** 开始录音，返回 null 表示成功，否则返回错误原因 */
    fun startRecord(ctx: Context): String? {
        stopRecordSilent()
        return try {
            val f = java.io.File(ctx.cacheDir, "dsh_voice.m4a")
            if (f.exists()) f.delete()
            val r = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(ctx)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recFile = f
            null
        } catch (t: Throwable) {
            val m = t.message.orEmpty()
            if (m.contains("permission", true)) "没给录音权限，去设置页开启"
            else "录音启动失败：" + m.ifBlank { t.javaClass.simpleName }
        }
    }

    /** 停止录音并交给云端转文字。在子线程调用 */
    fun stopRecord(ctx: Context): String? {
        val f = recFile
        stopRecordSilent()
        if (f == null || !f.exists() || f.length() < 512L) {
            return null   // 太短，当成没录到
        }
        return transcribe(ctx, f)
    }

    private fun stopRecordSilent() {
        try {
            recorder?.stop()
        } catch (_: Throwable) {
        }
        try {
            recorder?.reset()
        } catch (_: Throwable) {
        }
        try {
            recorder?.release()
        } catch (_: Throwable) {
        }
        recorder = null
    }

    fun cancelRecord() {
        stopRecordSilent()
        try {
            recFile?.delete()
        } catch (_: Throwable) {
        }
        recFile = null
    }

    /** multipart 上传到 /audio/transcriptions */
    fun transcribe(ctx: Context, file: java.io.File): String? {
        if (AiPrefs.voiceProvider(ctx) == "dashscope") return asrDashScope(ctx, file)
        val base = AiPrefs.asrBase(ctx).trimEnd('/')
        val url = base + (if (base.endsWith("/v1")) "" else "/v1") + "/audio/transcriptions"
        val bytes = try {
            file.readBytes()
        } catch (t: Throwable) {
            throw RuntimeException("读录音文件失败：" + (t.message ?: ""))
        }
        val raw = multipartPost(
            url,
            AiPrefs.asrKey(ctx),
            mapOf("model" to AiPrefs.asrModel(ctx), "language" to "zh"),
            "file",
            "voice.m4a",
            "audio/mp4",
            bytes,
            60_000,
        )
        return try {
            val o = JSONObject(raw)
            o.optString("text").trim().ifBlank { null }
        } catch (_: Throwable) {
            // response_format=text 的服务会直接返回纯文本
            raw.trim().ifBlank { null }
        }
    }

    /**
     * 百炼 ASR：兼容模式下没有 /audio/transcriptions，
     * 要走 /chat/completions，把音频塞进 input_audio 的 data URI。
     */
    private fun asrDashScope(ctx: Context, file: java.io.File): String? {
        val url = originOf(AiPrefs.asrBase(ctx)) +
            "/api/v1/services/aigc/multimodal-generation/generation"
        val b64 = android.util.Base64.encodeToString(
            file.readBytes(),
            android.util.Base64.NO_WRAP,
        )
        val dataUri = "data:audio/mp4;base64,$b64"
        // 百炼 ASR 是自己的协议：{ model, input:{messages:[...]}, parameters:{...} }
        val body = JSONObject().apply {
            put("model", AiPrefs.asrModel(ctx))
            put("input", JSONObject().put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", JSONArray().apply {
                    put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject().apply {
                        put("data", dataUri)
                    }))
                }))
            }))
            put("parameters", JSONObject().apply {
                put("enable_itn", false)
                put("format", "mp4")
                put("sample_rate", 16000)
            })
        }
        val raw = try {
            DsNet.postRaw(url, AiPrefs.asrKey(ctx), body, 90_000)
        } catch (t: Throwable) {
            throw RuntimeException("识别请求失败：" + (t.message ?: t.javaClass.simpleName))
        }
        rememberRaw(raw)
        // 返回形如 output.choices[0].message.content[0].text
        val o = JSONObject(raw)
        val txt = o.optJSONObject("output")
            ?.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optJSONArray("content")
            ?.optJSONObject(0)
            ?.optString("text")
            .orEmpty()
            .ifBlank { o.optJSONObject("output")?.optString("text").orEmpty() }
        return txt.trim().ifBlank { null }
    }

    /**
     * 把用户填的地址规范成 https://host。
     * 百炼的 WebSocket 地址（wss://xxx/api-ws/v1/inference）不能直接拼 HTTP 路径，
     * 会变成 wss://.../api/v1/... 然后报 "unknown protocol: wss"，
     * 所以这里强制换成 https 并丢掉路径。
     */
    private fun originOf(base: String): String {
        var b = base.trim().trimEnd('/')
        when {
            b.startsWith("wss://", true) -> b = "https" + b.substring(3)
            b.startsWith("ws://", true) -> b = "https" + b.substring(2)
            !b.startsWith("http", true) -> b = "https://$b"
        }
        val m = java.util.regex.Pattern.compile("^(https?://[^/]+)").matcher(b)
        val host = if (m.find()) m.group(1) else b
        // 业务空间域名可能带 /api/v1，路径一律丢掉，端点路径我们自己拼
        return host.removeSuffix("/api/v1").trimEnd('/')
    }

    /**
     * 百炼 TTS：走原生 SpeechSynthesizer 端点，返回的是音频 URL，要再下载一次。
     * 注意 qwen-audio 系列仅华北 2（北京）地域可用。
     */
    private fun ttsDashScope(ctx: Context, text: String, onUrl: (String) -> Unit) {
        val host = originOf(AiPrefs.ttsBase(ctx))
        val url = host + "/api/v1/services/audio/tts/SpeechSynthesizer"
        lastEndpoint = url
        val voice = AiPrefs.ttsVoice(ctx).substringAfter(':').trim().ifBlank { "Cherry" }
        val body = JSONObject().apply {
            put("model", AiPrefs.ttsModel(ctx))
            put("input", JSONObject().apply {
                put("text", text)
                put("voice", voice)
                put("format", "mp3")
            })
        }
        val raw = DsNet.postRaw(url, AiPrefs.ttsKey(ctx), body, 60_000)
        rememberRaw(raw)
        val o = JSONObject(raw)
        val u = o.optJSONObject("output")?.optJSONObject("audio")?.optString("url").orEmpty()
        if (u.isBlank()) {
            val msg = o.optString("message").ifBlank { o.optString("code") }.ifBlank { raw }
            throw RuntimeException("百炼没返回音频地址：$msg")
        }
        onUrl(u)
    }

    /** 上次发出的请求内容（脱敏），贴在错误详情里 */
    @Volatile
    var lastRequest: String = ""

    private fun buildDebugLog(ctx: Context, text: String): String {
        val prov = AiPrefs.voiceProvider(ctx)
        return buildString {
            append("服务商：").append(prov).append('\n')
            append("朗读模型：").append(AiPrefs.ttsModel(ctx)).append('\n')
            append("音色：").append(AiPrefs.ttsVoice(ctx)).append('\n')
            append("地址：").append(AiPrefs.ttsBase(ctx)).append('\n')
            append("文本长度：").append(text.length).append('\n')
            append("实际端点：").append(lastEndpoint.ifBlank { "(未发出请求)" })
            val rawBase = AiPrefs.ttsBase(ctx)
            if (rawBase.startsWith("ws", true)) {
                append("\n⚠️ 你填的是 WebSocket 地址（").append(rawBase)
                    .append("）\n已自动转成 ").append(originOf(rawBase))
                    .append("\n建议直接改成 https:// 开头的域名，别带 /api-ws 路径")
            }
        }
    }

    /**
     * 百炼返回的音频地址是 http:// 明文，Android 9+ 默认禁止明文流量
     * （会报 Cleartext HTTP traffic not permitted），所以这里强制升成 https。
     * OSS 两边都支持，不会因为换协议而失效。
     */
    private fun downloadTo(urlStr0: String, f: java.io.File) {
        val urlStr = if (urlStr0.startsWith("http://", true)) {
            "https" + urlStr0.substring(4)
        } else {
            urlStr0
        }
        var lastErr: Throwable? = null
        // 升 https 失败就退回 http 再试一次（配合 manifest 里的明文开关）
        for (u in listOf(urlStr, urlStr0).distinct()) {
            try {
                val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    setRequestProperty("Accept", "audio/*,*/*")
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        throw RuntimeException("下载音频失败（HTTP $code）")
                    }
                    conn.inputStream.use { inp ->
                        f.outputStream().use { out -> inp.copyTo(out) }
                    }
                } finally {
                    conn.disconnect()
                }
                if (f.length() > 256) return
                lastErr = RuntimeException("下载到的文件太小（" + f.length() + " 字节）")
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        throw RuntimeException(
            "音频下载失败：" + (lastErr?.message ?: "未知原因") +
                "｜合成其实成功了，只是取不回音频文件",
        )
    }

    /** 手写 multipart/form-data（零第三方依赖） */
    private fun multipartPost(
        urlStr: String,
        key: String,
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
        timeoutMs: Int,
    ): String {
        val boundary = "----DSH${System.currentTimeMillis()}"
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = timeoutMs
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }
        try {
            conn.outputStream.use { out ->
                val w = java.io.DataOutputStream(out)
                fun line(s: String) = w.write(s.toByteArray(StandardCharsets.UTF_8))
                for ((k, v) in fields) {
                    line("--$boundary\r\n")
                    line("Content-Disposition: form-data; name=\"$k\"\r\n\r\n")
                    line(v)
                    line("\r\n")
                }
                line("--$boundary\r\n")
                line("Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"\r\n")
                line("Content-Type: $mime\r\n\r\n")
                w.write(bytes)
                line("\r\n--$boundary--\r\n")
                w.flush()
            }
            val code = conn.responseCode
            val raw = readAll(if (code in 200..299) conn.inputStream else conn.errorStream)
            if (code !in 200..299) throw RuntimeException(DsNet.explain(code, raw))
            return raw
        } finally {
            conn.disconnect()
        }
    }

    private fun readAll(input: java.io.InputStream?): String {
        if (input == null) return ""
        return try {
            input.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (_: Throwable) {
            ""
        }
    }

    // ---------------------------------------------------------------- 云端 TTS

    private var player: MediaPlayer? = null

    /** 调云端 TTS 合成并播放；成功返回 true，失败返回 false（调用方再退回系统 TTS） */
    fun sayCloud(ctx: Context, text: String): Boolean {
        if (!AiPrefs.ttsReady(ctx)) return false
        val t = text.trim()
        if (t.isBlank()) return true
        val speak = if (t.length > 300) t.take(300) else t
        return try {
            val f = java.io.File(ctx.cacheDir, "dsh_tts.mp3")
            if (AiPrefs.voiceProvider(ctx) == "dashscope") {
                ttsDashScope(ctx, speak) { u -> downloadTo(u, f) }
            } else {
                val base = AiPrefs.ttsBase(ctx).trimEnd('/')
                val url = base + (if (base.endsWith("/v1")) "" else "/v1") + "/audio/speech"
                val body = JSONObject().apply {
                    put("model", AiPrefs.ttsModel(ctx))
                    put("input", speak)
                    put("voice", AiPrefs.ttsVoice(ctx))
                    put("response_format", "mp3")
                    put("speed", 1.05)
                }
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer " + AiPrefs.ttsKey(ctx))
                }
                val audio: ByteArray
                try {
                    conn.outputStream.use {
                        it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                        it.flush()
                    }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        throw RuntimeException(DsNet.explain(code, readAll(conn.errorStream)))
                    }
                    audio = conn.inputStream.readBytes()
                } finally {
                    conn.disconnect()
                }
                if (audio.size < 256) return false
                f.writeBytes(audio)
            }
            if (f.length() < 256) return false
            play(ctx, f)
            true
        } catch (t: Throwable) {
            lastTtsError = t.message ?: t.javaClass.simpleName
            lastRequest = buildDebugLog(ctx, speak)
            false
        }
    }

    @Volatile
    var lastTtsError: String? = null

    /** 上次实际请求的完整 URL */
    @Volatile
    var lastEndpoint: String = ""

    /** 上一次合成/识别的完整原始报文，排查用 */
    @Volatile
    var lastRaw: String = ""

    /** 记录一次请求原文，方便把 400 的真实原因贴出来 */
    private fun rememberRaw(raw: String) {
        lastRaw = raw.take(1200)
    }

    private fun play(ctx: Context, f: java.io.File) {
        Handler(Looper.getMainLooper()).post {
            try {
                player?.let {
                    try {
                        it.stop()
                    } catch (_: Throwable) {
                    }
                    try {
                        it.release()
                    } catch (_: Throwable) {
                    }
                }
                val mp = MediaPlayer()
                mp.setDataSource(f.absolutePath)
                mp.setOnCompletionListener {
                    try {
                        it.release()
                    } catch (_: Throwable) {
                    }
                    if (player === it) player = null
                    speaking = false
                }
                mp.setOnErrorListener { _, _, _ ->
                    try {
                        mp.release()
                    } catch (_: Throwable) {
                    }
                    if (player === mp) player = null
                    speaking = false
                    true
                }
                mp.prepare()
                mp.start()
                speaking = true
                player = mp
            } catch (_: Throwable) {
            }
        }
    }

    /** 引擎是否可用（有些精简 ROM 没装 TTS 引擎） */
    fun available(ctx: Context): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(ctx)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 开始听写。回调均在主线程。
     * 需要在有 Activity 的上下文里启动更稳，但用 ApplicationContext 也能跑。
     */
    fun listen(
        ctx: Context,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ): SpeechRecognizer? {
        if (!available(ctx)) {
            onError("这台手机没有可用的语音识别引擎")
            return null
        }
        var sr: SpeechRecognizer? = null
        val main = Handler(Looper.getMainLooper())
        try {
            sr = SpeechRecognizer.createSpeechRecognizer(ctx.applicationContext)
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    main.post { onPartial("（请说话…）") }
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "录音出错，检查麦克风权限"
                        SpeechRecognizer.ERROR_CLIENT -> "识别客户端出错，重试一下"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没给录音权限，去设置页开启"
                        SpeechRecognizer.ERROR_NETWORK -> "识别服务需要联网"
                        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一次"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙，稍等"
                        SpeechRecognizer.ERROR_SERVER -> "识别服务出错"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到声音"
                        else -> "识别失败（$error）"
                    }
                    main.post { onError(msg) }
                }

                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = list?.firstOrNull().orEmpty().trim()
                    main.post {
                        if (best.isEmpty()) onError("没听清，再说一次") else onFinal(best)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val list = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val p = list?.firstOrNull().orEmpty().trim()
                    if (p.isNotEmpty()) main.post { onPartial(p) }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L)
            }
            sr.startListening(intent)
        } catch (t: Throwable) {
            try {
                sr?.destroy()
            } catch (_: Throwable) {
            }
            val m = t.message.orEmpty()
            onError(if (m.contains("permission", true)) "没给录音权限，去设置页开启" else "启动识别失败：${
                m.ifBlank { t.javaClass.simpleName }
            }")
            return null
        }
        return sr
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

        /**
         * 系统设置里是否把本服务列在「已启用」列表里。
         * 注意它和 isEnabled() 不是一回事：
         * 设置里开了 ≠ 服务真的连上了。Android 经常出现「开关是开的、但服务没起来」，
         * 表现就是设置页显示已开启、但动不了手机。这种情况只能让用户在系统里关一下再开。
         */
        fun enabledInSettings(ctx: Context): Boolean {
            return try {
                val want = ctx.packageName + "/" + AgentService::class.java.name
                val s = android.provider.Settings.Secure.getString(
                    ctx.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty()
                s.split(':').any { it.equals(want, ignoreCase = true) }
            } catch (_: Throwable) {
                false
            }
        }

        /** 服务连上了，但当前拿不到任何窗口 —— 也是「动不了」的常见原因 */
        fun rootOk(): Boolean {
            return try {
                instance?.rootInActiveWindow != null || instance?.windows?.isNotEmpty() == true
            } catch (_: Throwable) {
                false
            }
        }
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
    fun screenDump(limit: Int = 220): String {
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
        else {
            out.forEach { sb.append(it).append('\n') }
            if (out.size >= limit) {
                sb.append("\n（已截断：只列出前 ").append(limit)
                    .append(" 个元素。想看某个区域，可以用 look_screen 截图看。）")
            }
        }

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
    /**
     * 无障碍没连上时，所有工具都会失败。
     * 这时必须明确禁止模型编造「已经打开啦」之类的话 ——
     * 用户会以为真打开了，其实什么都没发生，比直接报错更糟。
     */
    fun systemExtra(a11yOn: Boolean): String {
        val guard = if (a11yOn) "" else """

【重要·当前状态】你现在没有操作手机的能力（无障碍服务未连接）。
任何 open_app / tap_text / tap_xy / swipe / input_text / screen_dump / look_screen 都会失败。
严禁编造操作结果，严禁说「已经打开啦」「点了一下」「已经帮你弄好了」这类话。
你应当如实告诉用户：无障碍没开，我现在动不了手机，并引导他去系统设置里开启。
"""
        return SYSTEM_EXTRA + guard
    }

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

    /** 悬浮窗和全屏页共用同一份上下文，避免两边各说各的 */
    @Volatile
    var shared = Session()

    /** 主请求是否被降级成纯聊天（诊断用） */
    @Volatile
    var lastDegraded = false

    /** 最近一次主请求的错误原因 */
    @Volatile
    var lastError: String = ""

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
        session.system(
            AiPrefs.systemPrompt(ctx) + "\n" +
                AgentTools.systemExtra(AgentService.isEnabled()),
        )
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
                AgentRunner.lastDegraded = true
                AgentRunner.lastError = DsNet.friendly(t)
                if (rounds == 1) {
                    // 降级后必须告诉用户「我现在动不了手机」。
                    // 之前静默降级，模型拿不到工具就自己编一段「已经打开啦」，
                    // 看起来像在干活，其实什么都没做 —— 比报错更糟。
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
                        val body = if (t.isEmpty()) "（模型没返回内容）" else t
                        return "⚠️ 工具调用失败，我现在动不了手机，下面只是普通聊天：\n\n" +
                            DsNet.friendly(t) + "\n\n" + body
                    } catch (t2: Throwable) {
                        return "⚠️ 接不上模型：" + DsNet.friendly(t2)
                    }
                }
                return "⚠️ 工具调用失败：" + DsNet.friendly(t)
            }

            val msg = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: return "（模型返回了看不懂的内容）"
            val calls = msg.optJSONArray("tool_calls")

            if (rounds == 1) {
                AgentRunner.lastDegraded = false
                AgentRunner.lastError = ""
            }
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
        val vBase = AiPrefs.visionBase(ctx)
        val vPath = AiPrefs.visionPath(ctx)
        val vKey = AiPrefs.visionKey(ctx)

        val errs = ArrayList<String>()
        for (m in models) {
            try {
                body.put("model", m)
                val o = DsNet.postAt(vBase, vPath, vKey, body, timeoutMs = 60_000)
                val msgObj = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val txt = DsNet.extractContent(msgObj)
                if (txt.isBlank()) {
                    val raw = o.optString("_raw", "")
                    val why = when {
                        raw.contains("does not support image") -> "该模型明确拒收图片"
                        raw.contains("model") && raw.contains("not") -> "模型名可能被拒绝"
                        else -> "返回空内容"
                    }
                    errs.add("$m：$why" + if (raw.isNotBlank()) "｜原文 " + raw.take(150) else "")
                    continue
                }
                // 记住这次能用哪个，下次直接从它开始
                if (AiPrefs.visionModel(ctx) != m) AiPrefs.setVisionModel(ctx, m)
                return "看图结果（$m）：$txt"
            } catch (t: Throwable) {
                errs.add("$m：" + DsNet.friendly(t).take(120))
            }
        }
        return "看图失败，已试过 " + models.joinToString("、") +
            "（端点 $vBase）。\n" + errs.joinToString("\n") +
            "\n提示：如果每个都返回空，多半是这个账号/端点没开通图片能力。" +
            "可以在设置页「视觉（看图）」里单独填一家支持图片的模型。"
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
    private val session: AgentRunner.Session get() = AgentRunner.shared
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

    private var winLp: WindowManager.LayoutParams? = null
    private var micBtn: TextView? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var recording = false
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

        // 标题文字兼任拖动手柄：按住它就能把窗口拖到任意位置（横屏挡路时尤其需要）
        val handle = TextView(c).apply {
            text = "🐟 大肥鱼 · 按住拖动"
            setTextColor(0xFFEAF1F8.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        handle.setOnTouchListener(object : android.view.View.OnTouchListener {
            var sx = 0f
            var sy = 0f
            var bx = 0
            var by = 0
            var moved = false
            override fun onTouch(v: android.view.View, e: android.view.MotionEvent): Boolean {
                val lp = winLp ?: return false
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX
                        sy = e.rawY
                        bx = lp.x
                        by = lp.y
                        moved = false
                        return true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - sx).toInt()
                        val dy = (e.rawY - sy).toInt()
                        if (!moved && (kotlin.math.abs(dx) > 6 || kotlin.math.abs(dy) > 6)) moved = true
                        if (moved) {
                            lp.x = bx + dx
                            lp.y = by - dy
                            try {
                                wm?.updateViewLayout(root, lp)
                            } catch (_: Throwable) {
                            }
                        }
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!moved) v.performClick()
                        return true
                    }
                }
                return false
            }
        })
        bar.addView(handle)
        bar.addView(barBtn("⤢", 0xFF8FB6E8.toInt()) {
            val next = (AiPrefs.winSize(c) + 1) % 3
            AiPrefs.setWinSize(c, next)
            applySize(c)
            Toast.makeText(c, "窗口大小：" + listOf("小", "中", "大")[next], Toast.LENGTH_SHORT).show()
        })
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
        // 麦克风：说完自动发送
        micBtn = TextView(c).apply {
            text = "🎤"
            setTextColor(0xFFEAF1F8.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = 16f * d
                setColor(0xFF2A3550.toInt())
            }
            isClickable = true
            setOnClickListener { toggleListen() }
        }
        bottom.addView(inputView)
        bottom.addView(micBtn)
        bottom.addView(btnSend)
        box.addView(bottom)
        bottomView = bottom

        val (w2, h2) = calcSize(c)
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
        winLp = lp

        try {
            w.addView(box, lp)
            root = box
            Voice.init(c)
        } catch (t: Throwable) {
            Toast.makeText(c, "悬浮窗打开失败：" + (t.message ?: ""), Toast.LENGTH_SHORT).show()
            Agent.markClosed()
            return
        }

        // 重新打开时把上次的对话捞回来（关掉小窗不该丢记录）
        session.load(c)
        val restored = renderHistory()

        val a11y = AgentService.isEnabled()
        val hasKey = AiPrefs.hasKey(c)
        if (!restored) {
            addMsg("assistant", when {
                !hasKey -> "先在右上角 ⚙️ 里填上 DeepSeek 的 API Key～"
                !a11y -> "API Key 有了，但还没开无障碍，我现在只能聊天、还动不了手机。去设置页点「开启无障碍」吧。"
                else -> "我在呢～想让我帮你操作手机就直接说，比如「打开微信」「帮我点一下发送」。"
            })
        } else {
            addMsg("assistant", "（以上是之前的对话）")
        }
    }

    /** 按屏幕方向和档位算窗口尺寸。横屏时屏幕又宽又矮，比例要换一套 */
    private fun calcSize(c: Context): Pair<Int, Int> {
        val dm = c.resources.displayMetrics
        val portrait = dm.heightPixels >= dm.widthPixels
        val lvl = AiPrefs.winSize(c)
        val (wf, hf) = if (portrait) {
            when (lvl) {
                0 -> 0.82f to 0.40f
                2 -> 0.98f to 0.80f
                else -> 0.92f to 0.60f
            }
        } else {
            // 横屏：别摊得太宽，高度要尽量吃满，否则按钮露不出来
            when (lvl) {
                0 -> 0.38f to 0.62f
                2 -> 0.66f to 0.92f
                else -> 0.52f to 0.78f
            }
        }
        val w = (dm.widthPixels * wf).toInt().coerceIn(220, dm.widthPixels)
        val h = (dm.heightPixels * hf).toInt().coerceIn(200, dm.heightPixels)
        return Pair(w, h)
    }

    /** 切换尺寸档位后原地更新窗口 */
    private fun applySize(c: Context) {
        main.post {
            val v = root ?: return@post
            val lp = winLp ?: return@post
            val (w, h) = calcSize(c)
            lp.width = w
            lp.height = h
            fullH = h
            try {
                wm?.updateViewLayout(v, lp)
            } catch (_: Throwable) {
            }
        }
    }

    fun detach() {
        if (recording) {
            recording = false
            Voice.cancelRecord()
        }
        stopListen()
        Voice.stop()
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

    /** 点麦克风开始/停止听写 */
    private var listenRetry = 0
    private var savedInput = ""
    private var statusClearJob: Runnable? = null

    /** 显示状态，holdMs>0 时到点自动清掉（避免错误提示一闪而过看不清） */
    private fun showStatus(t: String, holdMs: Long = 0) {
        main.post {
            statusClearJob?.let { main.removeCallbacks(it); statusClearJob = null }
            statusView?.isClickable = false
            statusView?.text = t
            if (holdMs > 0 && t.isNotBlank()) {
                val job = Runnable {
                    if (statusView?.text?.toString() == t) statusView?.text = ""
                }
                statusClearJob = job
                main.postDelayed(job, holdMs)
            }
        }
    }

    private fun killRecognizer() {
        try {
            recognizer?.stopListening()
        } catch (_: Throwable) {
        }
        try {
            recognizer?.cancel()
        } catch (_: Throwable) {
        }
        try {
            recognizer?.destroy()
        } catch (_: Throwable) {
        }
        recognizer = null
    }

    /** 点麦克风：开 / 停。配了云端 ASR 走「录音—上传」，否则走系统识别 */
    private fun toggleListen() {
        val c = ctx ?: return
        if (recording) {
            stopAndTranscribe(c)
            return
        }
        if (listening) {
            stopListen()
            return
        }
        if (Voice.asrCloud(c)) {
            startCloudRecord(c)
            return
        }
        // 用框架自带的检查，不引入 androidx（本项目刻意零第三方依赖）
        val granted = if (Build.VERSION.SDK_INT >= 23) {
            c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (!granted) {
            Toast.makeText(c, "需要录音权限，去设置页点「开启语音」", Toast.LENGTH_LONG).show()
            try {
                c.startActivity(
                    Intent(c, AiSettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Throwable) {
            }
            return
        }
        savedInput = inputView?.text?.toString().orEmpty()
        listenRetry = 0
        startListen(c)
    }

    /** 云端模式：点一下开始录，再点一下停止并转文字 */
    private fun startCloudRecord(c: Context) {
        val err = Voice.startRecord(c)
        if (err != null) {
            showStatus("🎤 $err", 8000)
            Toast.makeText(c, err, Toast.LENGTH_LONG).show()
            return
        }
        recording = true
        micBtn?.text = "⏹"
        showStatus("● 录音中…说完再点一下 ⏹")
    }

    private fun stopAndTranscribe(c: Context) {
        recording = false
        micBtn?.text = "🎤"
        showStatus("识别中…")
        Thread {
            var text: String? = null
            var err: String? = null
            try {
                text = Voice.stopRecord(c)
            } catch (t: Throwable) {
                err = t.message ?: t.javaClass.simpleName
            }
            main.post {
                showStatus("")
                when {
                    !text.isNullOrBlank() -> {
                        val merged = if (savedInput.isBlank()) text else savedInput + text
                        inputView?.setText(merged)
                        submit()
                    }
                    err != null -> showStatus("🎤 $err", 8000)
                    else -> showStatus("（没录到声音，再试一次）", 5000)
                }
            }
        }.start()
    }

    private fun startListen(c: Context) {
        Voice.stop()
        listening = true
        micBtn?.text = "🔴"
        showStatus("（正在听…请说话）")

        recognizer = Voice.listen(
            c,
            // 中间结果只显示在状态栏，不覆盖输入框，免得把已输入的内容冲掉
            onPartial = { p -> if (p.isNotBlank()) showStatus(p) },
            onFinal = { text ->
                listenRetry = 0
                listening = false
                micBtn?.text = "🎤"
                killRecognizer()
                showStatus("")
                val merged = if (savedInput.isBlank()) text else "$savedInput$text"
                inputView?.setText(merged)
                submit()   // 说完直接发
            },
            onError = { err -> handleListenError(c, err) },
        )

        if (recognizer == null) {
            listening = false
            micBtn?.text = "🎤"
            showStatus("🎤 启动失败，试试全屏页里的麦克风", 8000)
        }
    }

    private fun handleListenError(c: Context, err: String) {
        // 这几类可以立刻重试一次：很多 ROM 第一次唤起识别器会失败
        val retryable = err.contains("客户端") || err.contains("忙") ||
            err.contains("识别失败") || err.contains("ERROR")
        if (retryable && listenRetry < 1) {
            listenRetry++
            killRecognizer()
            showStatus("识别被打断，重试一次…", 2500)
            main.postDelayed({
                if (listening) startListen(c)
            }, 800)
            return
        }
        listenRetry = 0
        listening = false
        micBtn?.text = "🎤"
        killRecognizer()
        // 还原用户原本输入的文字，别把它弄丢
        inputView?.setText(savedInput)

        val limited = err.contains("客户端") || err.contains("没有可用") ||
            err.contains("启动失败") || err.contains("识别失败")
        if (limited) {
            // 悬浮窗用的是 Application 上下文，很多 ROM 不允许从这里唤起识别器。
            // 给一条可点的出路：跳到全屏页（有真实 Activity）并自动开麦。
            showStatus("🎤 $err ｜ 点这里去全屏页说", 15000)
            main.post {
                statusView?.isClickable = true
                statusView?.setOnClickListener {
                    showStatus("")
                    statusView?.isClickable = false
                    try {
                        c.startActivity(
                            Intent(c, AgentActivity::class.java)
                                .putExtra("auto_listen", true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: Throwable) {
                    }
                }
            }
        } else {
            showStatus("🎤 $err", 8000)
        }
        Toast.makeText(c, err, Toast.LENGTH_LONG).show()
    }

    private fun stopListen() {
        listening = false
        listenRetry = 0
        micBtn?.text = "🎤"
        killRecognizer()
        showStatus("")
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
                // 按你的选择：只在悬浮窗念出来，全屏页保持安静
                Voice.say(c, ans)
            }
        }.start()
    }

    /** 把历史消息重画到界面上；返回是否真的画了内容 */
    private fun renderHistory(): Boolean {
        val c = ctx ?: return false
        var n = 0
        val msgs = session.messages
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            val role = m.optString("role")
            if (role == "system" || role == "tool") continue
            val txt = DsNet.extractContent(m)
            if (txt.isBlank()) continue
            addMsgSilent(role, txt)
            n++
        }
        if (n > 0) scrollDown()
        return n > 0
    }

    /** 气泡最大宽度：横屏时屏幕又宽又矮，用短边算才不会撑满一行 */
    private fun bubbleMaxWidth(c: Context): Int {
        val dm = c.resources.displayMetrics
        val shortSide = kotlin.math.min(dm.widthPixels, dm.heightPixels)
        return (shortSide * 0.78f).toInt().coerceAtLeast(160)
    }

    private fun addMsg(role: String, text: String) {
        addMsgSilent(role, text)
        scrollDown()
    }

    /** 只画不滚（恢复历史时用它，最后统一滚一次） */
    private fun addMsgSilent(role: String, text: String) {
        val c = ctx ?: return
        val d = c.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).roundToInt()
        val mine = role == "user"
        val tv = TextView(c).apply {
            this.text = text
            setTextColor(if (mine) 0xFFEAF1F8.toInt() else 0xFF16223B.toInt())
            textSize = 13f
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            maxWidth = bubbleMaxWidth(c)
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
    }

    private fun scrollDown() {
        scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
    }
}

// ==================================================================== 全屏对话页（键盘更好用）

class AgentActivity : Activity() {

    private val session: AgentRunner.Session get() = AgentRunner.shared
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var btnSend: TextView
    private lateinit var status: TextView
    private lateinit var micBtn: TextView
    private var busy = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var recording = false
    private var savedInput = ""
    private val REQ_MIC = 9101

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
        // 全屏页也有麦克风：这里有真实 Activity 上下文，
        // 有些 ROM 不允许从悬浮窗（Application Context）唤起识别器，在这边更稳
        micBtn = TextView(this).apply {
            text = "🎤"
            setTextColor(0xFFEAF1F8.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = 16f * d
                setColor(0xFF2A3550.toInt())
            }
            isClickable = true
            setOnClickListener { toggleListen() }
        }
        bottom.addView(input)
        bottom.addView(
            micBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(6f), 0, 0, 0) },
        )
        bottom.addView(
            btnSend,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(8f), 0, 0, 0) },
        )
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)

        Voice.init(this)
        session.load(this)
        val n = renderHistory()
        if (intent?.getBooleanExtra("auto_listen", false) == true) {
            // 延迟一点等界面稳住再开麦，直接调很多 ROM 会报 ERROR_CLIENT
            scroll.postDelayed({
                if (!busy) toggleListen()
            }, 600)
        }
        if (n == 0) {
            addMsg("assistant", when {
                !AiPrefs.hasKey(this) -> "先去右上角 ⚙️ 填 DeepSeek 的 API Key～"
                !AgentService.isEnabled() -> "Key 有了，但无障碍还没开，我现在只能聊天、动不了手机。回设置页点「开启无障碍」。"
                else -> "说吧，想让我帮你做什么？比如「打开微信」「帮我给文件传输助手发个 1」。"
            })
        } else {
            addMsg("assistant", "（以上是之前的对话）")
        }
    }

    private fun toggleListen() {
        if (recording) {
            stopAndTranscribe()
            return
        }
        if (listening) {
            stopListen()
            return
        }
        if (Voice.asrCloud(this)) {
            startCloudRecord()
            return
        }
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        Voice.init(this)
        Voice.stop()
        savedInput = input.text.toString()
        listening = true
        micBtn.text = "🔴"
        status.text = "（正在听…请说话）"
        recognizer = Voice.listen(
            this,
            onPartial = { p -> if (p.isNotBlank()) status.text = p },
            onFinal = { txt ->
                listening = false
                micBtn.text = "🎤"
                killRec()
                status.text = ""
                input.setText(if (savedInput.isBlank()) txt else savedInput + txt)
                send()
            },
            onError = { err ->
                listening = false
                micBtn.text = "🎤"
                killRec()
                input.setText(savedInput)
                status.text = "🎤 $err"
                Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            },
        )
        if (recognizer == null) {
            listening = false
            micBtn.text = "🎤"
            status.text = "🎤 启动失败"
        }
    }

    /** 云端模式：点一下开始录，再点一下停止并转文字 */
    private fun startCloudRecord() {
        val err = Voice.startRecord(this)
        if (err != null) {
            status.text = "🎤 $err"
            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            return
        }
        recording = true
        micBtn.text = "⏹"
        status.text = "● 录音中…说完再点一下 ⏹"
    }

    private fun stopAndTranscribe() {
        recording = false
        micBtn.text = "🎤"
        status.text = "识别中…"
        Thread {
            var text: String? = null
            var err: String? = null
            try {
                text = Voice.stopRecord(this)
            } catch (t: Throwable) {
                err = t.message ?: t.javaClass.simpleName
            }
            runOnUiThread {
                status.text = ""
                when {
                    !text.isNullOrBlank() -> {
                        input.setText(if (savedInput.isBlank()) text else savedInput + text)
                        send()
                    }
                    err != null -> status.text = "🎤 $err"
                    else -> status.text = "（没录到声音，再试一次）"
                }
            }
        }.start()
    }

    private fun killRec() {
        try {
            recognizer?.stopListening()
        } catch (_: Throwable) {
        }
        try {
            recognizer?.cancel()
        } catch (_: Throwable) {
        }
        try {
            recognizer?.destroy()
        } catch (_: Throwable) {
        }
        recognizer = null
    }

    private fun stopListen() {
        listening = false
        micBtn.text = "🎤"
        killRec()
        status.text = ""
    }

    override fun onPause() {
        if (recording) {
            recording = false
            Voice.cancelRecord()
        }
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "好了，再点一次 🎤", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "没给录音权限，说话功能用不了", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        if (recording) {
            recording = false
            Voice.cancelRecord()
        }
        killRec()
        Voice.stop()
        super.onDestroy()
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

    /** 把历史消息重画出来，返回条数 */
    private fun renderHistory(): Int {
        var n = 0
        val msgs = session.messages
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            val role = m.optString("role")
            if (role == "system" || role == "tool") continue
            val txt = DsNet.extractContent(m)
            if (txt.isBlank()) continue
            addMsgQuiet(role, txt)
            n++
        }
        if (n > 0) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return n
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
        addMsgQuiet(role, text)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addMsgQuiet(role: String, text: String) {
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).roundToInt()
        val mine = role == "user"
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(if (mine) 0xFFEAF1F8.toInt() else 0xFF16223B.toInt())
            textSize = 14f
            setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
            maxWidth = (kotlin.math.min(
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels,
            ) * 0.80f).toInt()
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
    private lateinit var edVBase: EditText
    private lateinit var edVPath: EditText
    private lateinit var edVModel: EditText
    private lateinit var edVKey: EditText
    private lateinit var cbConfirm: CheckBox
    private lateinit var cbTts: CheckBox
    private lateinit var btnMicPerm: TextView
    private lateinit var edAsrBase: EditText
    private lateinit var edAsrKey: EditText
    private lateinit var edAsrModel: EditText
    private lateinit var edTtsBase: EditText
    private lateinit var edTtsKey: EditText
    private lateinit var edTtsModel: EditText
    private lateinit var edTtsVoice: EditText
    private lateinit var btnVpOpenai: TextView
    private lateinit var btnVpDash: TextView
    private var voiceProv: String = "openai"
    private lateinit var result: TextView
    private lateinit var btnTest: TextView
    private lateinit var btnBalance: TextView
    private lateinit var btnA11y: TextView
    private val REQ_MIC = 9001
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

        // ---- 语音
        run {
            body.addView(
                TextView(this).apply {
                    text = "语音"
                    setTextColor(0xFF9FB0C8.toInt())
                    textSize = 12f
                    setPadding(dp(2f), dp(16f), dp(2f), dp(4f))
                },
            )
            val granted = if (Build.VERSION.SDK_INT >= 23) {
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            btnMicPerm = TextView(this).apply {
                text = if (granted) "✅ 录音权限已开启" else "开启语音（需要录音权限）"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
                background = GradientDrawable().apply {
                    cornerRadius = 14f * d
                    setColor(if (granted) 0xFF2E7D5B.toInt() else 0xFF4E6BFF.toInt())
                }
                isClickable = true
                setOnClickListener {
                    if (Build.VERSION.SDK_INT >= 23 &&
                        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        requestPermissions(
                            arrayOf(android.Manifest.permission.RECORD_AUDIO),
                            REQ_MIC,
                        )
                    } else {
                        Toast.makeText(
                            this@AiSettingsActivity,
                            "录音权限已开启。悬浮窗输入框旁的 🎤 可以直接说话。",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            body.addView(btnMicPerm)

            cbTts = CheckBox(this).apply {
                text = "朗读她的回复（仅悬浮窗，全屏页不念）"
                setTextColor(0xFFCFDCEA.toInt())
                textSize = 13f
                isChecked = AiPrefs.ttsOn(this@AiSettingsActivity)
                setPadding(dp(2f), dp(8f), dp(2f), dp(2f))
            }
            body.addView(cbTts)

            body.addView(
                TextView(this).apply {
                    val ok = Voice.available(this@AiSettingsActivity)
                    text = if (ok) {
                        "系统识别引擎可用。若下面填了云端 ASR，会优先用云端（更准、更稳）。"
                    } else {
                        "⚠️ 这台手机没有可用的语音识别引擎。填下面的云端 ASR 即可正常使用说话功能。"
                    }
                    setTextColor(0xFF6B7A90.toInt())
                    textSize = 10f
                    setPadding(dp(2f), dp(2f), dp(2f), dp(4f))
                },
            )

            // ---- 云端 ASR / TTS：想要「听得准 + 有自己的音色」就填这里
            body.addView(
                TextView(this).apply {
                    text = "云端语音（可选）"
                    setTextColor(0xFF9FB0C8.toInt())
                    textSize = 12f
                    setPadding(dp(2f), dp(14f), dp(2f), dp(2f))
                },
            )

            // 服务商切换：两家协议不同，必须分开走
            voiceProv = AiPrefs.voiceProvider(this@AiSettingsActivity)
            val provRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(2f), dp(4f), dp(2f), dp(4f))
            }
            fun provBtn(text: String, isDash: Boolean): TextView {
                val tv = TextView(this).apply {
                    this.text = text
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
                    background = GradientDrawable().apply {
                        cornerRadius = 10f * d
                        setColor(if (voiceProv == if (isDash) "dashscope" else "openai") {
                            0xFF4E6BFF.toInt()
                        } else {
                            0xFF232B38.toInt()
                        })
                        setStroke(dp(1f), 0xFF3A4560.toInt())
                    }
                    isClickable = true
                }
                tv.setOnClickListener {
                    voiceProv = if (isDash) "dashscope" else "openai"
                    AiPrefs.setVoiceProvider(this@AiSettingsActivity, voiceProv)
                    paintProvButtons()
                }
                return tv
            }
            btnVpOpenai = provBtn("硅基流动 / OpenAI 兼容", false)
            btnVpDash = provBtn("阿里云百炼", true)
            provRow.addView(btnVpOpenai, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            provRow.addView(btnVpDash, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(6f), 0, 0, 0)
            })
            body.addView(provRow)

            body.addView(
                TextView(this).apply {
                    text = "两家协议不一样：硅基流动等走标准 /audio/* 接口；百炼的兼容模式只有聊天接口，音频走自有协议（已单独适配）。"
                    setTextColor(0xFF6B7A90.toInt())
                    textSize = 10f
                    setPadding(dp(2f), dp(0f), dp(2f), dp(4f))
                },
            )
            body.addView(
                TextView(this).apply {
                    text = "两项都留空 = 用系统自带。填了之后：识别走云端（稳，不再报「客户端出错」），朗读用你选的音色。"
                    setTextColor(0xFF6B7A90.toInt())
                    textSize = 10f
                    setPadding(dp(2f), dp(0f), dp(2f), dp(4f))
                },
            )

            edAsrBase = edit(AiPrefs.asrBase(this))
            edAsrBase.hint = "识别地址，百炼填 https://cn-beijing.maas.qianwenaiapi.com（别带 wss 和路径）"
            edAsrKey = edit(AiPrefs.asrKey(this), password = true)
            edAsrKey.hint = "识别 API Key"
            edAsrModel = edit(AiPrefs.asrModel(this))
            edAsrModel.hint = "识别模型，默认 FunAudioLLM/SenseVoiceSmall"

            edTtsBase = edit(AiPrefs.ttsBase(this))
            edTtsBase.hint = "朗读地址，百炼填 https://cn-beijing.maas.qianwenaiapi.com（别带 wss 和路径）"
            edTtsKey = edit(AiPrefs.ttsKey(this), password = true)
            edTtsKey.hint = "朗读 API Key"
            edTtsModel = edit(AiPrefs.ttsModel(this))
            edTtsModel.hint = "朗读模型，默认 FunAudioLLM/CosyVoice2-0.5B"
            edTtsVoice = edit(AiPrefs.ttsVoice(this))
            edTtsVoice.hint = "音色。硅基流动填 FunAudioLLM/CosyVoice2-0.5B:anna；百炼配 qwen3-tts-* 填 Cherry"
            // 模型和音色必须配套，给一行提醒，免得又是 400
            body.addView(
                TextView(this).apply {
                    text = "⚠️ 音色要和模型配套：qwen-audio-3.0-tts-* 用 longanfengyue / longanhuan_v3 这类；Cherry 只能配 qwen3-tts-*，混着用会报 400。地址别填 wss:// 开头的（那是 WebSocket），要填 https:// 域名。"
                    setTextColor(0xFF8A7A5A.toInt())
                    textSize = 10f
                    setPadding(dp(2f), dp(2f), dp(2f), dp(4f))
                },
            )

            val rowVoice = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(2f), dp(8f), dp(2f), dp(4f))
            }
            fun vBtn(text: String, onClick: () -> Unit) = TextView(this).apply {
                this.text = text
                setTextColor(0xFFB9A6FF.toInt())
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
                background = GradientDrawable().apply {
                    cornerRadius = 10f * d
                    setColor(0x00000000)
                    setStroke(dp(1f), 0xFF3A4560.toInt())
                }
                isClickable = true
                setOnClickListener { onClick() }
            }
            rowVoice.addView(vBtn("填百炼模板") {
                voiceProv = "dashscope"
                AiPrefs.setVoiceProvider(this@AiSettingsActivity, voiceProv)
                paintProvButtons()
                edAsrBase.setText("https://cn-beijing.maas.qianwenaiapi.com")
                // 官方 HTTP 端点，别填 wss:// 那个（那是 WebSocket，走 SDK 用的）
                edAsrModel.setText("qwen-audio-3.0-asr-flash")
                edTtsBase.setText("https://cn-beijing.maas.qianwenaiapi.com")
                // qwen-audio 系列配 cosyvoice 音色；Cherry 是 qwen3-tts 的，混用会 400
                edTtsModel.setText("qwen-audio-3.0-tts-flash")
                edTtsVoice.setText("longanfengyue")
                if (edAsrKey.text.isBlank() && AiPrefs.visionKey(this@AiSettingsActivity).isNotBlank()) {
                    edAsrKey.setText(AiPrefs.visionKey(this@AiSettingsActivity))
                    edTtsKey.setText(AiPrefs.visionKey(this@AiSettingsActivity))
                    Toast.makeText(
                        this@AiSettingsActivity,
                        "已填北京地域端点 + qwen-audio-3.0-asr-flash + longanfengyue。Key 沿用了视觉那个，确认下是不是百炼的",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(this@AiSettingsActivity, "模板已填，再补一下 Key", Toast.LENGTH_SHORT).show()
                }
                save(true)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            rowVoice.addView(vBtn("填入硅基流动") {
                voiceProv = "openai"
                AiPrefs.setVoiceProvider(this@AiSettingsActivity, voiceProv)
                paintProvButtons()
                edAsrBase.setText("https://api.siliconflow.cn/v1")
                edTtsBase.setText("https://api.siliconflow.cn/v1")
                if (edAsrKey.text.isBlank() && AiPrefs.visionKey(this@AiSettingsActivity).isNotBlank()) {
                    edAsrKey.setText(AiPrefs.visionKey(this@AiSettingsActivity))
                    edTtsKey.setText(AiPrefs.visionKey(this@AiSettingsActivity))
                    Toast.makeText(
                        this@AiSettingsActivity,
                        "地址已填，Key 沿用了视觉那个，确认一下对不对",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(
                        this@AiSettingsActivity,
                        "地址已填，再填一下两个 Key",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                save(true)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            rowVoice.addView(vBtn("试听音色") {
                save(true)
                if (!AiPrefs.ttsReady(this@AiSettingsActivity)) {
                    Toast.makeText(this@AiSettingsActivity, "先填朗读的 Key 和地址", Toast.LENGTH_SHORT).show()
                    return@vBtn
                }
                Toast.makeText(this@AiSettingsActivity, "合成中…", Toast.LENGTH_SHORT).show()
                Thread {
                    val ok = Voice.sayCloud(this@AiSettingsActivity, "你好呀，我是大肥鱼，以后就这么跟你说话啦～")
                    runOnUiThread {
                        Toast.makeText(
                            this@AiSettingsActivity,
                            if (ok) "播放中，听听效果" else "合成失败，点右上角看详情",
                            Toast.LENGTH_LONG,
                        ).show()
                        if (!ok) {
                            val detail = buildString {
                                append("错误信息：\n").append(Voice.lastTtsError ?: "未知").append("\n\n")
                                if (Voice.lastRequest.isNotBlank()) {
                                    append("本次请求：\n").append(Voice.lastRequest).append("\n\n")
                                }
                                if (Voice.lastRaw.isNotBlank()) {
                                    append("服务端原文：\n").append(Voice.lastRaw)
                                }
                            }
                            showDetails("语音合成失败", detail)
                        }
                    }
                }.start()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(6f), 0, 0, 0)
            })
            rowVoice.addView(vBtn("清空语音") {
                edAsrBase.setText("")
                edAsrKey.setText("")
                edTtsBase.setText("")
                edTtsKey.setText("")
                save(true)
                Toast.makeText(this@AiSettingsActivity, "已清空，改回系统自带语音", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(6f), 0, 0, 0)
            })
            body.addView(rowVoice)
        }

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

            row.addView(smallBtn("诊断") {
                val r = buildDiagnoseReport()
                showDetails("运行诊断", r)
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

        // ---- 视觉（看图）独立端点：DeepSeek 官方看图对部分账号不可用，
        //      这里允许单独填一家支持图片输入、OpenAI 兼容的模型。
        run {
            body.addView(
                TextView(this).apply {
                    text = "视觉（看图）"
                    setTextColor(0xFF9FB0C8.toInt())
                    textSize = 12f
                    setPadding(dp(2f), dp(16f), dp(2f), dp(2f))
                },
            )
            body.addView(
                TextView(this).apply {
                    text = "全部留空 = 复用上面的 DeepSeek 配置。若看图一直返回空，说明该账号没开通图片能力，可改填其他支持视觉的服务。\n填过的值会显示出来，不会自动清空。"
                    setTextColor(0xFF6B7A90.toInt())
                    textSize = 10f
                    setPadding(dp(2f), dp(0f), dp(2f), dp(4f))
                },
            )
            // 必须回填已存的值：写死成 "" 的话，每次保存都会把用户填的 Key 清掉
            edVBase = edit(AiPrefs.visionBaseRaw(this))
            edVBase.hint = "视觉 Base URL（留空 = 复用上方 DeepSeek）"
            edVPath = edit(AiPrefs.visionPathRaw(this))
            edVPath.hint = "视觉接口路径（留空 = 复用上方）"
            edVModel = edit(AiPrefs.visionModel(this))
            edVModel.hint = "视觉模型名，留空自动探测"
            edVKey = edit(AiPrefs.visionKeyRaw(this), password = true)
            edVKey.hint = "视觉 API Key（留空 = 复用上方 DeepSeek Key）"
        }

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
        refreshA11yState()
    }

    /**
     * 无障碍有四种状态，之前只分了两种，
     * 「设置里显示已开启、但实际动不了手机」这种最常见的情况完全看不出来。
     */
    private fun refreshA11yState() {
        val on = AgentService.isEnabled()
        val inSettings = AgentService.enabledInSettings(this)
        val stuck = inSettings && !on
        val rootOk = AgentService.rootOk()

        stateA11y.text = when {
            on && rootOk -> "✅ 无障碍已开启，可以操作手机了"
            on -> "⚠️ 服务连着但读不到界面，锁屏再亮一下试试"
            stuck -> "⚠️ 开关是开的，但服务没起来 —— 去系统设置关掉再开一次"
            else -> "⚠️ 无障碍未开启，现在只能聊天"
        }
        stateA11y.setTextColor(
            Color.parseColor(if (on && rootOk) "#6BE3B0" else "#FFC46B"),
        )
        btnA11y.text = if (on && rootOk) {
            "去系统里关闭无障碍"
        } else {
            "开启 / 重启无障碍（关掉再开一次）"
        }
        if (stuck) {
            Toast.makeText(
                this,
                "装新版或授予新权限后 Android 常会这样，去系统设置关一下再开就好",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun save(silent: Boolean) {
        AiPrefs.setBaseUrl(this, edBase.text.toString())
        AiPrefs.setChatPath(this, edPath.text.toString())
        AiPrefs.setModel(this, edModel.text.toString())
        AiPrefs.setApiKey(this, edKey.text.toString())
        AiPrefs.setTemperature(this, edTemp.text.toString().toFloatOrNull() ?: AiPrefs.DEFAULT_TEMP)
        AiPrefs.setMaxTokens(this, edMax.text.toString().toIntOrNull() ?: AiPrefs.DEFAULT_MAX)
        AiPrefs.setSystemPrompt(this, edSys.text.toString())
        AiPrefs.setVisionBase(this, edVBase.text.toString())
        AiPrefs.setVisionPath(this, edVPath.text.toString())
        AiPrefs.setVisionModel(this, edVModel.text.toString())
        AiPrefs.setVisionKey(this, edVKey.text.toString())
        AiPrefs.setConfirmDanger(this, cbConfirm.isChecked)
        AiPrefs.setTtsOn(this, cbTts.isChecked)
        AiPrefs.setAsrBase(this, edAsrBase.text.toString())
        AiPrefs.setAsrKey(this, edAsrKey.text.toString())
        AiPrefs.setAsrModel(this, edAsrModel.text.toString())
        AiPrefs.setTtsBase(this, edTtsBase.text.toString())
        AiPrefs.setTtsKey(this, edTtsKey.text.toString())
        AiPrefs.setTtsModel(this, edTtsModel.text.toString())
        AiPrefs.setTtsVoice(this, edTtsVoice.text.toString())
        AiPrefs.setVoiceProvider(this, voiceProv)
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
        AiPrefs.setTtsOn(this, true)
        AiPrefs.setVisionModel(this, "")
        edBase.setText(AiPrefs.DEFAULT_BASE)
        edPath.setText(AiPrefs.DEFAULT_PATH)
        edModel.setText(AiPrefs.DEFAULT_MODEL)
        edTemp.setText(AiPrefs.DEFAULT_TEMP.toString())
        edMax.setText(AiPrefs.DEFAULT_MAX.toString())
        edSys.setText(AiPrefs.DEFAULT_SYS)
        edVModel.setText("")
        cbConfirm.isChecked = true
        cbTts.isChecked = true
        AiStore.saveToDisk(this)
        result.text = "当前：" + AiPrefs.all(this)
        Toast.makeText(this, "已恢复默认（API Key 保留）", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val ok = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
            btnMicPerm.text = if (ok) "✅ 录音权限已开启" else "开启语音（需要录音权限）"
            Toast.makeText(
                this,
                if (ok) "好了，去悬浮窗点 🎤 说话吧" else "没给权限，说话功能用不了",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /**
     * 详情对话框：Toast 装不下、也看不全的东西都走这里。
     * 内容可滚动、可长按全选，另外给一个「复制」按钮。
     */
    private fun showDetails(title: String, content: String) {
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).roundToInt()
        val tv = android.widget.EditText(this).apply {
            setText(content)
            setTextColor(0xFFEAF1F8.toInt())
            setBackgroundColor(0xFF151A22.toInt())
            textSize = 11f
            setSingleLine(false)
            isFocusableInTouchMode = true
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setTextIsSelectable(true)
        }
        val sv = ScrollView(this).apply {
            addView(tv)
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1117.toInt())
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            addView(sv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(320f),
            ))
        }
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("复制全部") { _, _ ->
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("dsh-error", content))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .create()
        dlg.show()
    }

    /** Key 指纹：只显示前 6 位和长度，既能核对又不泄露 */
    private fun fp(k: String): String {
        if (k.isBlank()) return "（未填）"
        return k.take(6) + "…（共 " + k.length + " 位）"
    }

    /** 跑一遍关键状态，输出成可复制的纯文本 */
    private fun buildDiagnoseReport(): String {
        val b = StringBuilder()
        fun line(k: String, v: Any) { b.append(k).append("：").append(v).append('\n') }

        line("无障碍·设置里已开启", AgentService.enabledInSettings(this))
        line("无障碍·服务已连接", AgentService.isEnabled())
        line("无障碍·能拿到窗口", AgentService.rootOk())
        b.append('\n')
        line("主请求上次被降级", AgentRunner.lastDegraded)
        if (AgentRunner.lastDegraded) {
            b.append("↓ 这就是「她只会编造回复」的原因：主请求失败后退化成普通聊天，\n")
            b.append("  模型拿不到工具，只能靠文字编。真实错误是：\n  ")
            b.append(AgentRunner.lastError).append("\n")
        }
        b.append('\n')
        b.append('\n')
        if (AgentService.enabledInSettings(this) && !AgentService.isEnabled()) {
            b.append("⚠️ 典型的「开关开着但服务没起来」。\n")
            b.append("去系统设置把「大肥鱼助手」关掉，再打开一次就好。\n")
            b.append("（装新版、授权新权限后 Android 常会这样）\n\n")
        }
        if (AgentService.isEnabled() && !AgentService.rootOk()) {
            b.append("⚠️ 服务连着但读不到窗口。锁屏再亮一下通常能恢复。\n\n")
        }

        val overlay = if (Build.VERSION.SDK_INT >= 23) {
            android.provider.Settings.canDrawOverlays(this)
        } else {
            true
        }
        line("悬浮窗权限", overlay)
        if (!overlay) b.append("⚠️ 没给悬浮窗权限，长按桌宠弹不出对话框。\n\n")

        val mic = if (Build.VERSION.SDK_INT >= 23) {
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        line("录音权限", mic)
        line("识别引擎可用", Voice.available(this))

        b.append('\n')
        if (!AgentService.isEnabled()) {
            b.append("🔴 无障碍没连上 —— 打开应用 / 点击 / 截图 / 看图 全部依赖它，\n")
            b.append("   所以现在这几样都不可用。去系统设置关掉再开一次。\n\n")
        }
        line("视觉端点", AiPrefs.visionBase(this))
        line("视觉模型", AiPrefs.visionModel(this).ifBlank { "（自动探测）" })
        line("语音服务商", AiPrefs.voiceProvider(this))
        // Key 指纹：百炼和硅基流动的 Key 前缀不一样，看一眼就知道有没有填串
        line("主对话 Key", AiPrefs.maskedKey(this))
        line("视觉 Key", fp(AiPrefs.visionKey(this)))
        line("识别 Key", fp(AiPrefs.asrKey(this)))
        line("朗读 Key", fp(AiPrefs.ttsKey(this)))
        line("朗读已配置", AiPrefs.ttsReady(this))
        line("主对话 Key 已填", AiPrefs.hasKey(this))
        return b.toString()
    }

    /** 重画服务商按钮的选中态 */
    private fun paintProvButtons() {
        val dash = voiceProv == "dashscope"
        btnVpOpenai.background = GradientDrawable().apply {
            cornerRadius = 10f * resources.displayMetrics.density
            setColor(if (!dash) 0xFF4E6BFF.toInt() else 0xFF232B38.toInt())
            setStroke(
                resources.displayMetrics.density.roundToInt(),
                0xFF3A4560.toInt(),
            )
        }
        btnVpDash.background = GradientDrawable().apply {
            cornerRadius = 10f * resources.displayMetrics.density
            setColor(if (dash) 0xFF4E6BFF.toInt() else 0xFF232B38.toInt())
            setStroke(
                resources.displayMetrics.density.roundToInt(),
                0xFF3A4560.toInt(),
            )
        }
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
