package com.norbury.taktu.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object Prefs {
    private const val FILE = "taskalarm_prefs"
    private const val KEY_EXACT_HINT = "exact_hint_shown"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_COLLAPSED = "collapsed_tags"
    private const val KEY_NEXT_AT = "next_alarm_at"
    private const val KEY_NEXT_LABEL = "next_alarm_label"
    private const val KEY_RING_MODE = "ring_mode"

    /**
     * 响铃方式。
     * 跟随系统：铃声模式开 → 声音+震动；震动模式 → 只震动；静音 → 只震动（闹钟还是要叫得醒）。
     */
    const val RING_FOLLOW = 0
    const val RING_SOUND_VIBRATE = 1
    const val RING_VIBRATE_ONLY = 2
    const val RING_SOUND_ONLY = 3

    /** 外观模式：只有浅色 / 深色两套（跟随系统保留常量仅为兼容旧值） */
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2
    const val THEME_SYSTEM = 0

    private fun sp(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hasShownExactHint(context: Context): Boolean =
        sp(context).getBoolean(KEY_EXACT_HINT, false)

    fun markExactHintShown(context: Context) {
        sp(context).edit().putBoolean(KEY_EXACT_HINT, true).apply()
    }

    fun themeMode(context: Context): Int = sp(context).getInt(KEY_THEME, THEME_LIGHT)

    /** 保存外观模式并立即生效（浅色 / 深色） */
    fun setThemeMode(context: Context, mode: Int) {
        sp(context).edit().putInt(KEY_THEME, mode).apply()
        applyTheme(mode)
    }

    fun applyTheme(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun ringMode(context: Context): Int = sp(context).getInt(KEY_RING_MODE, RING_FOLLOW)

    fun setRingMode(context: Context, mode: Int) {
        sp(context).edit().putInt(KEY_RING_MODE, mode).apply()
    }

    // ---- 外部控制（局域网 HTTP server）----

    private const val KEY_CONTROL_ENABLED = "control_enabled"
    private const val KEY_CONTROL_TOKEN = "control_token"

    fun controlEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_CONTROL_ENABLED, false)

    fun setControlEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_CONTROL_ENABLED, enabled).apply()
    }

    /** 首次调用时生成随机 token 并持久化，作为局域网接口的鉴权凭据 */
    fun controlToken(context: Context): String {
        val sp = sp(context)
        return sp.getString(KEY_CONTROL_TOKEN, null) ?: run {
            val token = java.util.UUID.randomUUID().toString().replace("-", "")
            sp.edit().putString(KEY_CONTROL_TOKEN, token).apply()
            token
        }
    }

    // ---- 过期数据自动清理（v3.34.104）----

    private const val KEY_LAST_PURGE = "last_purge_at"

    /** 距上次清理是否已满一周（首次启动也清一次） */
    fun needsPurge(context: Context): Boolean {
        val last = sp(context).getLong(KEY_LAST_PURGE, 0L)
        return System.currentTimeMillis() - last >= com.norbury.taktu.data.AutoPurge.INTERVAL_MS
    }

    fun markPurged(context: Context) {
        sp(context).edit().putLong(KEY_LAST_PURGE, System.currentTimeMillis()).apply()
    }

    // ---- 节假日数据过期提示（v3.34.123）----

    private const val KEY_HOLIDAY_HINT_YEAR = "holiday_hint_year"

    /** 用户点 × 关闭提示的年份；等于当前年就不再显示 */
    fun holidayHintYear(context: Context): Int =
        sp(context).getInt(KEY_HOLIDAY_HINT_YEAR, 0)

    fun setHolidayHintYear(context: Context, year: Int) {
        sp(context).edit().putInt(KEY_HOLIDAY_HINT_YEAR, year).apply()
    }

    // ---- 每日组数进度重置（v3.34.112）----

    private const val KEY_DONE_DAY = "done_reset_day"

    /** 上次把任务表 doneCount 清零的日期键；!= 今天说明要重置 */
    fun doneResetKey(context: Context): String =
        sp(context).getString(KEY_DONE_DAY, "") ?: ""

    fun setDoneResetKey(context: Context, key: String) {
        sp(context).edit().putString(KEY_DONE_DAY, key).apply()
    }

    /**
     * 最近一次已排定的提醒（时间 + 任务名）。
     * 设置页显示它，让用户能确认"闹钟真的排上了"，而不是靠猜。
     */
    fun nextAlarmAt(context: Context): Long = sp(context).getLong(KEY_NEXT_AT, 0L)

    fun nextAlarmLabel(context: Context): String =
        sp(context).getString(KEY_NEXT_LABEL, "") ?: ""

    fun setNextAlarm(context: Context, at: Long, label: String) {
        sp(context).edit().putLong(KEY_NEXT_AT, at).putString(KEY_NEXT_LABEL, label).apply()
    }

    /** 首页被折叠的分组（跨启动记忆） */
    fun collapsedTags(context: Context): Set<String> =
        HashSet(sp(context).getStringSet(KEY_COLLAPSED, emptySet()) ?: emptySet())

    fun setCollapsedTags(context: Context, tags: Set<String>) {
        sp(context).edit().putStringSet(KEY_COLLAPSED, tags).apply()
    }

    // ---- 补响去重：同一天同一 (任务, 提醒类型, 时刻) 只拉起一次 ----
    // 用途有二：①正常响铃与补响互不重复；②同刻双重投递只响一次。
    // 条目带 dateKey，跨天自动剪掉，集合不会膨胀。
    // v3.27 起带时刻下标（多时刻任务同一天多次响），key 形如 "taskId:dateKey:type:idx"。

    private const val KEY_FIRED = "fired_today"

    fun wasFired(context: Context, taskId: Long, dateKey: String, type: Int, timeIdx: Int = 0): Boolean =
        sp(context).getStringSet(KEY_FIRED, emptySet())
            ?.contains("$taskId:$dateKey:$type:$timeIdx") == true

    fun markFired(context: Context, taskId: Long, dateKey: String, type: Int, timeIdx: Int = 0) {
        val sp = sp(context)
        val cur = sp.getStringSet(KEY_FIRED, emptySet()) ?: emptySet()
        val pruned = cur.filter { it.split(":").getOrNull(1) == dateKey }.toSet()
        sp.edit().putStringSet(KEY_FIRED, pruned + "$taskId:$dateKey:$type:$timeIdx").apply()
    }

    // ---- 调度槽位数：每条任务上次排了多少个闹钟（按类型分开记） ----
    // 取消闹钟时按这个数枚举 data 精确匹配，避免全量枚举几千个槽位的 binder 开销。
    // 没有记录时回退 [LEGACY_SLOT_COUNT]（v3.26.9 单时刻模式的每类型上限）。

    private const val KEY_SLOT_PREFIX = "slots"
    const val LEGACY_SLOT_COUNT = 30

    fun slotCount(context: Context, taskId: Long, type: Int): Int =
        sp(context).getInt("$KEY_SLOT_PREFIX/$taskId/$type", LEGACY_SLOT_COUNT)

    fun setSlotCount(context: Context, taskId: Long, type: Int, count: Int) {
        sp(context).edit().putInt("$KEY_SLOT_PREFIX/$taskId/$type", count).apply()
    }

    // ---- 免打扰窗口：考试/会议时段，落在窗口内的提醒后移到窗口尾 ----
    // 串格式见 data/QuietWindows.kt："2026-10-24:510-750,870-990;..."，空串=未设置

    private const val KEY_QUIET = "quiet_windows"

    fun quietWindows(context: Context): String = sp(context).getString(KEY_QUIET, "") ?: ""

    fun setQuietWindows(context: Context, raw: String) {
        sp(context).edit().putString(KEY_QUIET, raw).apply()
    }

    // ---- 联动写入记录（v3.34.18）：系统闹钟 / 日历到底写成了什么样，PC 端可查 ----
    // 每条一行："taskId|目标(clock/calendar)|结果(ok/fail)|详情"
    // 详情记实际写入的响铃时刻（"07:00"）或失败原因——软考那次「8:30 vs 7:00」
    // 的问题就是没有这个记录、只能靠用户肉眼发现才暴露出来。

    private const val KEY_LINK_LOG = "link_log"

    fun linkLog(context: Context): String = sp(context).getString(KEY_LINK_LOG, "") ?: ""

    fun appendLinkLog(context: Context, line: String) {
        val cur = linkLog(context)
        val kept = cur.lines().filter { it.isNotBlank() }.takeLast(49).toMutableList()
        kept.add(line)
        sp(context).edit().putString(KEY_LINK_LOG, kept.joinToString("\n")).apply()
    }

    // ---- 待确认的导入预览（v3.34.20）----
    // 存在的意义：以前 PC 端 POST /import 后只收到一句"预览已展示"，
    // 但后台启动 Activity 被系统静默拦掉时**既没弹窗也没通知**，
    // 任务就一直躺在 PC 端"以为推成功了"的状态里。
    // 这里记录"有预览等着用户确认"，让 PC 端能查出到底是没送到还是没确认。

    private const val KEY_PENDING_IMPORT = "pending_import"

    /** 待确认预览的原始 JSON（空串 = 当前没有待确认的预览） */
    fun pendingImport(context: Context): String = sp(context).getString(KEY_PENDING_IMPORT, "") ?: ""

    fun setPendingImport(context: Context, json: String) {
        sp(context).edit().putString(KEY_PENDING_IMPORT, json).apply()
    }

    fun clearPendingImport(context: Context) {
        sp(context).edit().remove(KEY_PENDING_IMPORT).apply()
    }

    // ---- 小组件诊断（v3.34.30）----
    // 小组件「载入出现问题」时 PC 端看不到任何线索（logcat 拿不到、用户只能口述），
    // refresh 每次把关键步骤结果留痕在这里，经 ControlServer /widget_debug 读取。
    // 只存最近一条（覆盖写），行首带时间戳。

    private const val KEY_WIDGET_DEBUG = "widget_debug"

    fun widgetDebug(context: Context): String =
        sp(context).getString(KEY_WIDGET_DEBUG, "") ?: ""

    fun setWidgetDebug(context: Context, line: String) {
        sp(context).edit().putString(KEY_WIDGET_DEBUG, line.takeLast(900)).apply()
    }

    /** 留痕时间戳（MM-dd HH:mm:ss），跟随本机时区 */
    fun nowStamp(): String =
        java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date())

    // ---- 自定义标签（编辑页「＋ 自定义」添加，无删除入口：不用了留着也不碍事） ----
    /** 内置标签：编辑页选项与「导入时自动建标签」共用这一份，别处不要再抄一遍 */
    val BUILTIN_TAGS = listOf("运动", "饮食", "工作", "学习")

    private const val KEY_CUSTOM_TAGS = "custom_tags"

    fun customTags(context: Context): Set<String> =
        HashSet(sp(context).getStringSet(KEY_CUSTOM_TAGS, emptySet()) ?: emptySet())

    fun addCustomTag(context: Context, tag: String) {
        if (tag.isBlank()) return
        val cur = sp(context).getStringSet(KEY_CUSTOM_TAGS, emptySet()) ?: emptySet()
        sp(context).edit().putStringSet(KEY_CUSTOM_TAGS, cur + tag).apply()
    }

    fun removeCustomTag(context: Context, tag: String) {
        val cur = sp(context).getStringSet(KEY_CUSTOM_TAGS, emptySet()) ?: emptySet()
        sp(context).edit().putStringSet(KEY_CUSTOM_TAGS, cur - tag).apply()
    }

    fun renameCustomTag(context: Context, old: String, new: String) {
        if (new.isBlank()) return
        val cur = sp(context).getStringSet(KEY_CUSTOM_TAGS, emptySet()) ?: emptySet()
        sp(context).edit().putStringSet(KEY_CUSTOM_TAGS, (cur - old) + new).apply()
    }

    /** 内置标签的「删除」= 隐藏（想恢复就用「＋ 自定义」输同名，自然回来） */
    private const val KEY_HIDDEN_BUILTIN = "hidden_builtin_tags"

    fun hiddenBuiltinTags(context: Context): Set<String> =
        HashSet(sp(context).getStringSet(KEY_HIDDEN_BUILTIN, emptySet()) ?: emptySet())

    fun hideBuiltinTag(context: Context, tag: String) {
        val cur = sp(context).getStringSet(KEY_HIDDEN_BUILTIN, emptySet()) ?: emptySet()
        sp(context).edit().putStringSet(KEY_HIDDEN_BUILTIN, cur + tag).apply()
    }
}
