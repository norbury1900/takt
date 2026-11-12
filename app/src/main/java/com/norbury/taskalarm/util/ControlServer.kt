package com.norbury.taskalarm.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.norbury.taskalarm.MainActivity
import com.norbury.taskalarm.R
import com.norbury.taskalarm.TaskAlarmApp
import com.norbury.taskalarm.alarm.AlarmScheduler
import com.norbury.taskalarm.data.AppDatabase
import com.norbury.taskalarm.data.QuietWindows
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 局域网控制 HTTP server：PC 端 AI（Claude Code 等）免 USB 对接。
 *
 * 接口（除 /health 外均需请求头 X-Token = 设置页里显示的 token）：
 *   GET  /health        → 存活探测
 *   GET  /tasks         → 导出当前全部任务（v3 JSON，带 id，同 ImportActivity 导出格式）
 *   POST /import        → 请求体为任务 JSON：不带 id = 新增、带 id = 改那条、
 *                         {"id":12,"delete":true} = 删那条；
 *                         解析后一律送手机预览页确认，不直接写库
 *   GET  /quiet         → 读免打扰窗口（v3.34.16）：raw 存储串 + 解析出的窗口列表
 *   POST /quiet         → 写免打扰窗口：请求体即存储串
 *                         "2026-10-24:510-750,870-990;2026-10-25:540-600"（空 body = 清空）；
 *                         解析失败整包拒绝，过期窗口顺手清掉，写完立即重排闹钟
 *   GET  /links         → 联动写入记录（v3.34.18）：系统闹钟 / 日历每次写成了什么、
 *                         实际落在几点（核对「响铃时刻对不对」用，不用靠肉眼猜）
 *   GET  /pending       → 待确认的导入预览（v3.34.20）：有没有预览正等着用户点确认。
 *                         后台启动 Activity 被系统静默拦掉时，PC 端靠它区分
 *                         「没送到」和「送到了但用户还没点」
 *   GET  /widget_debug  → 小组件最近一次刷新的留痕（v3.34.30）：组件尺寸、
 *                         位图计划、渲染是否成功 / 异常类型——「载入出现问题」
 *                         不再靠猜，PC 端一条 curl 看到根因
 *   POST /notify        → PC 推系统通知（v3.34.99）：请求体 {"title":"…","body":"…"}
 *                         （body 必填），手机弹横幅通知。用途：CC 干完活主动回传，
 *                         弥补 ControlServer 只有「PC→任务」没有「PC→用户」的缺口
 *
 * 安全边界：仅监听局域网，token 鉴权，明文 HTTP（局域网内可接受；
 * 不对公网暴露是前提，流量不走外网）。
 *
 * ponytail: server 挂在应用进程上，进程被杀即下线；需要 7x24 常驻时
 * 升级为前台服务（复用 RingService 的保活引导）。
 */
class ControlServer(context: Context) : NanoHTTPD("0.0.0.0", PORT) {

    private val appContext = context.applicationContext

    override fun serve(session: IHTTPSession): Response {
        // /health 免鉴权，供 PC 端快速探测
        if (session.uri == "/health") {
            // 带版本号：PC 端据此判断手机装的是新是旧
            // （v3.34.18 之前 pushToClock 写的是截止时刻，且一次性任务会退化成"每天重复"，
            //   旧版上重推任务会往小米时钟里写错闹钟，所以要靠版本号拦一下）
            // foreground：PC 端据此知道推送预览是"直接弹窗"还是"发通知等点按"
            return json(
                Response.Status.OK,
                """{"ok":true,"app":"TaskAlarm","version":"${appVersionName()}","foreground":${TaskAlarmApp.isForeground}}"""
            )
        }

        // NanoHTTPD 把请求头全部转小写
        val token = session.headers["x-token"].orEmpty()
        if (token != Prefs.controlToken(appContext)) {
            return json(Response.Status.UNAUTHORIZED, """{"ok":false,"error":"bad token"}""")
        }

        return when {
            session.uri == "/tasks" && session.method == Method.GET -> serveTasks()
            session.uri == "/import" && session.method == Method.POST -> serveImport(session)
            session.uri == "/quiet" && session.method == Method.GET -> serveQuietGet()
            session.uri == "/quiet" && session.method == Method.POST -> serveQuietSet(session)
            session.uri == "/links" && session.method == Method.GET -> serveLinks()
            session.uri == "/pending" && session.method == Method.GET -> servePending()
            session.uri == "/widget_debug" && session.method == Method.GET -> serveWidgetDebug()
            session.uri == "/notify" && session.method == Method.POST -> serveNotify(session)
            else -> json(Response.Status.NOT_FOUND, """{"ok":false,"error":"unknown route"}""")
        }
    }

    /**
     * 小组件最近一次刷新的留痕（v3.34.30）。
     *
     * 存在的意义：v3.34.28/29 上 MIUI 报「载入出现问题」，但 App 侧 refresh
     * 是否崩过、崩在哪一步、位图算成了多大，PC 端全都看不到——只能盲改。
     * TodayWidget.refresh 每次把步骤链写进 Prefs（成功一行 OK、失败一行 ERROR），
     * 这里原样吐出来。空串 = 装上后还没刷新过（说明 provider 没被触发）。
     */
    private fun serveWidgetDebug(): Response {
        val raw = Prefs.widgetDebug(appContext)
        return json(
            Response.Status.OK,
            JSONObject().apply {
                put("ok", true)
                put("widget", raw)
            }.toString()
        )
    }

    /**
     * PC 推系统通知（v3.34.99）：POST /notify，请求体 {"title":"…","body":"…"}。
     *
     * 存在的意义：此前 ControlServer 只有「PC→任务」一条道（POST /import 还要用户
     * 去手机点确认），没有轻量的「PC→用户」通道。CC 在 PC 上干完活想喊一声，
     * 以前只能等用户自己来查。这里走系统通知横幅，点按打开 App。
     * body 必填（空白 = 400），title 可省略；长度截断到 NOTIFY_TITLE_MAX / NOTIFY_BODY_MAX。
     */
    private fun serveNotify(session: IHTTPSession): Response {
        val body = readBody(session)
            ?: return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"unreadable body"}""")
        if (body.isBlank()) {
            return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"empty body"}""")
        }
        val obj = try {
            JSONObject(body)
        } catch (e: Exception) {
            return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"invalid json"}""")
        }
        val title = obj.optString("title", "PC 通知").ifBlank { "PC 通知" }.take(NOTIFY_TITLE_MAX)
        val text = obj.optString("body").trim()
        if (text.isEmpty()) {
            return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"body required"}""")
        }
        val posted = postNotification(title, text.take(NOTIFY_BODY_MAX))
        return if (posted) {
            json(Response.Status.OK, """{"ok":true}""")
        } else {
            json(Response.Status.INTERNAL_ERROR, """{"ok":false,"error":"notification failed"}""")
        }
    }

    /** 发一条系统通知；通知 id 自增保证多条并存，点按打开 App 主页 */
    private fun postNotification(title: String, text: String): Boolean {
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTIFY_CHANNEL_ID,
                    appContext.getString(R.string.pc_notify_channel),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val openApp = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return try {
            nm.notify(
                nextNotifyId(),
                NotificationCompat.Builder(appContext, NOTIFY_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(openApp)
                    .build()
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 待确认的导入预览（v3.34.20）。
     *
     * 存在的意义：以前 PC 端 POST /import 后只收到一句"预览已展示"，
     * 但 Android 10+ 后台启动 Activity 被系统**静默拦掉**时（不抛异常）
     * 既没弹窗也没通知——PC 端却以为推成功了，任务一直躺在"等确认"里。
     * 这个端点让 PC 端能明确区分三种状态：
     *   pending=false → 没有待确认的预览（推成功且已落库，或从没推过）
     *   pending=true  → 预览已送到手机（弹窗或通知），等用户点确认
     */
    private fun servePending(): Response {
        val raw = Prefs.pendingImport(appContext)
        if (raw.isBlank()) {
            return json(Response.Status.OK, """{"ok":true,"pending":false}""")
        }
        // 只回报条数和任务名，不把整段 JSON 原样吐出来
        val names = ArrayList<String>()
        try {
            // v3.34.33：pendingImport 存的是整个请求体（{"version":7,"tasks":[…]}），
            // 旧代码直接 JSONArray(raw) 必抛异常走 catch → count 恒 0、names 恒空。
            // 兼容两种格式：顶层即任务数组，或顶层对象带 tasks 字段。
            val arr = if (raw.trimStart().startsWith("[")) {
                JSONArray(raw)
            } else {
                JSONObject(raw).optJSONArray("tasks") ?: JSONArray()
            }
            for (i in 0 until arr.length()) {
                val o = arr.opt(i) as? JSONObject ?: continue
                names.add(o.optString("name").ifBlank { "(未命名)" })
            }
        } catch (e: Exception) {
            // 解析不了就只说"有待确认"
        }
        return json(
            Response.Status.OK,
            """{"ok":true,"pending":true,"count":${names.size},"names":${JSONArray(names)}}"""
        )
    }

    /**
     * 联动写入记录（v3.34.18）：系统闹钟 / 日历到底写成了什么样。
     *
     * 存在的意义：软考那次「8:30 vs 7:00」的问题，是因为没有任何留痕，
     * 只能靠用户肉眼发现才暴露出来。这里把每次写入的**实际时刻**记下来，
     * PC 端可直接核对，不用猜。
     */
    private fun serveLinks(): Response {
        val raw = Prefs.linkLog(appContext)
        val arr = JSONArray()
        raw.lines().filter { it.isNotBlank() }.forEach { line ->
            val p = line.split("|")
            arr.put(JSONObject().apply {
                put("taskId", p.getOrNull(0)?.toLongOrNull() ?: 0L)
                put("target", p.getOrNull(1) ?: "")
                put("result", p.getOrNull(2) ?: "")
                put("detail", p.getOrNull(3) ?: "")
            })
        }
        return json(
            Response.Status.OK,
            """{"ok":true,"count":${arr.length()},"entries":$arr}"""
        )
    }

    private fun serveTasks(): Response {
        val tasks = runBlocking {
            AppDatabase.get(appContext).taskDao().getAll()
        }
        return json(Response.Status.OK, TaskExporter.toJson(tasks))
    }


    private fun serveImport(session: IHTTPSession): Response {
        val body = readBody(session)
            ?: return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"unreadable body"}""")

        val result = TaskImporter.parse(body)
        // 纯删除请求（{"id":7,"delete":true}）只有 deletes、tasks 为空，不能当成「没任务」（v3.34.112）
        if (result.tasks.isEmpty() && result.deletes.isEmpty()) {
            val err = (result.error ?: result.warnings.firstOrNull() ?: "no tasks")
                .replace("\"", "'")
            return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"$err"}""")
        }

        // 送预览页（前台直接弹，后台走通知）；写库与否由用户在手机上确认
        val delivery = ImportLauncher.launchPreview(appContext, body)
        val warnings = JSONArray(result.warnings).toString()
        val shown = delivery == ImportLauncher.Delivery.DIRECT
        return json(
            Response.Status.OK,
            """{"ok":true,"parsed":${result.tasks.size},"previewShown":$shown,"delivery":"${delivery.name.lowercase()}","warnings":$warnings}"""
        )
    }

    /**
     * 读请求体原文；读不到返回 null。
     * len == 0 是合法空 body（如 POST /quiet 空体 = 清空窗口），返回 ""；
     * 不用 parseBody：它对无 charset 的请求体按 ISO-8859-1 解码，中文会乱码。
     * 按 Content-Length 手工读原始字节，恒按 UTF-8 解（AI 端提交的 JSON 恒为 UTF-8）。
     */
    private fun readBody(session: IHTTPSession): String? {
        return try {
            val len = session.headers["content-length"]?.trim()?.toIntOrNull() ?: 0
            if (len < 0 || len > MAX_BODY) return null
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = session.inputStream.read(buf, off, len - off)
                if (n < 0) break
                off += n
            }
            String(buf, 0, off, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** 免打扰窗口读取（v3.34.16）：raw 存储串 + 解析出的窗口列表（时间给 "HH:mm" 便读） */
    private fun serveQuietGet(): Response {
        val raw = Prefs.quietWindows(appContext)
        val arr = JSONArray()
        QuietWindows.parse(raw).sortedWith(compareBy({ it.dateKey }, { it.startMin })).forEach { w ->
            arr.put(JSONObject().apply {
                put("date", w.dateKey)
                put("start", QuietWindows.fmtMin(w.startMin))
                put("end", QuietWindows.fmtMin(w.endMin))
            })
        }
        return json(
            Response.Status.OK,
            """{"ok":true,"raw":"${raw.replace("\"", "'")}","windows":$arr}"""
        )
    }

    /**
     * 免打扰窗口写入（v3.34.16）：请求体即存储串
     * "2026-10-24:510-750,870-990;2026-10-25:540-600"（空 body = 清空全部）。
     * 非空但整包解析不出窗口 → 400 拒绝（防手滑写错把窗口清掉还没响）；
     * 过期窗口顺手清掉；写完立即重排闹钟，不依赖下次开 App。
     */
    private fun serveQuietSet(session: IHTTPSession): Response {
        val raw = readBody(session)
            ?: return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"unreadable body"}""")
        val body = raw.trim()
        val parsed = QuietWindows.parse(body)
        if (body.isNotEmpty() && parsed.isEmpty()) {
            return json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"invalid windows"}""")
        }
        val kept = QuietWindows.prunePast(parsed, AppDatabase.todayKey())
        val out = QuietWindows.serialize(kept)
        Prefs.setQuietWindows(appContext, out)
        // 与设置页保存同链路：写 Prefs → 重排（排定与补响同源读这份窗口）
        AlarmScheduler.rescheduleAll(appContext)
        return json(
            Response.Status.OK,
            """{"ok":true,"count":${kept.size},"raw":"${out.replace("\"", "'")}"}"""
        )
    }

    private fun json(status: Response.Status, text: String) =
        newFixedLengthResponse(status, "application/json", text)

    /** 读 manifest 里的 versionName，取不到就返回 "unknown" */
    private fun appVersionName(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    companion object {
        const val PORT = 8931

        /** PC 推送通知的通道 id（v3.34.99） */
        private const val NOTIFY_CHANNEL_ID = "pc_notify"

        private const val NOTIFY_TITLE_MAX = 100
        private const val NOTIFY_BODY_MAX = 2000

        /** 通知 id 自增：多条通知并存而不是互相顶掉 */
        private val notifyIdCounter = java.util.concurrent.atomic.AtomicInteger(9000)

        private fun nextNotifyId(): Int = notifyIdCounter.incrementAndGet()

        /** 请求体上限 512KB，防误传超大内容 */
        private const val MAX_BODY = 512 * 1024

        @Volatile private var server: ControlServer? = null

        /** 按设置开关启动；重复调用安全 */
        fun startIfEnabled(context: Context) {
            if (!Prefs.controlEnabled(context)) return
            if (server != null) return
            server = runCatching { ControlServer(context).also { it.start(SOCKET_READ_TIMEOUT, true) } }
                .getOrNull()
        }

        /** 只停 server，不动开关状态（服务回收 / 设置页关开关共用） */
        fun stopServer() {
            server?.stop()
            server = null
        }

        fun isRunning(): Boolean = server != null

        /** 局域网 IPv4，取不到（无 Wi-Fi/热点）返回 null */
        fun lanIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
