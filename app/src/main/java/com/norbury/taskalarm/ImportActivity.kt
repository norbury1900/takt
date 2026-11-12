package com.norbury.taskalarm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.norbury.taskalarm.alarm.AlarmScheduler
import com.norbury.taskalarm.data.AppDatabase
import com.norbury.taskalarm.data.RepeatRule
import com.norbury.taskalarm.data.Task
import com.norbury.taskalarm.sheet.Sheets
import com.norbury.taskalarm.util.ButtonTuner
import com.norbury.taskalarm.util.MiLinkBridge
import com.norbury.taskalarm.util.TaskExporter
import com.norbury.taskalarm.util.Prefs
import com.norbury.taskalarm.util.TaskImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 任务导入页。
 *
 * 三种数据来源，覆盖"AI 生成计划 → 落到这个 App"的完整链路：
 *   1. 选中 JSON 文件（文件管理器 / 网盘 / 数据线拷进来的都行）
 *   2. 直接粘贴 AI 返回的文本（最快，不需要传文件）
 *   3. 外部分享：在微信/QQ/文件管理器里对 .json 点"用任务闹钟打开"
 *
 * 解析后**先预览再导入**，并且每条都可单独勾选，避免把一整个训练计划里
 * 不想做的动作也一起灌进来。
 */
class ImportActivity : BaseActivity() {

    companion object {
        /** 外部导入 JSON 大小上限：正常任务列表只有几十 KB */
        private const val MAX_IMPORT_BYTES = 1_000_000
    }

    /**
     * 预览里的一行 = 一个操作。远端编辑（带 id / delete）与文件导入共用这一套：
     * 带 id 且库里存在 = 改，其余 = 新增，delete = 删。
     */
    private sealed interface Op {
        val title: String
        val subtitle: String

        data class Add(override val title: String, override val subtitle: String, val task: Task) : Op
        data class Edit(
            override val title: String, override val subtitle: String,
            val task: Task, val old: Task,
            /** JSON 里联动开关是否被显式指定（null = 解析时没拿到，按"未指定"处理） */
            val linkSpec: TaskImporter.LinkSpec? = null
        ) : Op

        data class Remove(override val title: String, override val subtitle: String, val old: Task) : Op
    }

    private var ops: List<Op> = emptyList()
    private var flags = BooleanArray(0)
    private var parsed: List<Task> = emptyList()

    /** 允许选择任意文件（不少 ROM 对 .json/.txt 报告的类型不准，放宽后由解析器兜底校验） */
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readAndParse(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btnPickFile).setOnClickListener {
            runCatching { filePicker.launch(arrayOf("*/*")) }
                .onFailure { toast(getString(R.string.import_no_picker)) }
        }

        findViewById<MaterialButton>(R.id.btnParse).setOnClickListener {
            parse(findViewById<EditText>(R.id.etJson).text.toString())
        }

        findViewById<Button>(R.id.btnShowFormat).setOnClickListener { showFormatHelp() }

        // 剪贴板一键粘贴：AI 复制 JSON 后切过来点一下即可
        findViewById<Button>(R.id.btnPasteClip).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            val text = cm?.primaryClip?.getItemAt(0)
                ?.coerceToText(this)?.toString()?.trim().orEmpty()
            if (text.isBlank()) {
                toast(getString(R.string.import_clip_empty))
            } else {
                findViewById<EditText>(R.id.etJson).setText(text)
                parse(text)
            }
        }

        findViewById<MaterialButton>(R.id.btnExport).setOnClickListener { exportAll() }

        findViewById<MaterialButton>(R.id.btnConfirmImport).setOnClickListener { doImport() }

        handleIncomingIntent(intent)
    }

    /* ---------------- 外部分享进来 ---------------- */

    @Suppress("DEPRECATION")
    private fun handleIncomingIntent(src: Intent?) {
        when (src?.action) {
            Intent.ACTION_VIEW -> src.data?.let { readAndParse(it) }
            Intent.ACTION_SEND -> {
                val txt = src.getStringExtra(Intent.EXTRA_TEXT)
                if (!txt.isNullOrBlank()) {
                    findViewById<EditText>(R.id.etJson).setText(txt)
                    parse(txt)
                } else {
                    (src.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { readAndParse(it) }
                }
            }
        }
    }

    /**
     * 外部 URI 只接受 content://：file:// 会让应用以自身 UID 读私有文件。
     * 读取限 1MB 且放在 IO 线程 —— 以前在主线程 readText 整个流，
     * 任意应用传入大文件或慢速 ContentProvider 就能 ANR/OOM（v3.34.118）。
     */
    private fun readAndParse(uri: Uri) {
        if (uri.scheme == "file") {
            toast(getString(R.string.import_read_failed))
            return
        }
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val buf = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            total += n
                            if (total > MAX_IMPORT_BYTES) error("import too large")
                            buf.write(chunk, 0, n)
                        }
                        buf.toString("UTF-8")
                    }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                toast(getString(R.string.import_read_failed))
                return@launch
            }
            findViewById<EditText>(R.id.etJson).setText(text)
            parse(text)
        }
    }

    /* ---------------- 解析与预览 ---------------- */

    private fun parse(text: String) {
        val r = TaskImporter.parse(text)
        if (r.tasks.isEmpty() && r.deletes.isEmpty()) {
            toast(r.error ?: r.warnings.firstOrNull() ?: getString(R.string.import_empty))
            hidePreview()
            return
        }
        parsed = r.tasks
        // 带 id 的要跟库里比一下才知道是「改」还是「新增」；顺带把原名取出来给预览显示
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@ImportActivity).taskDao()
            val ids = (r.tasks.map { it.id } + r.deletes).filter { it > 0 }.distinct()
            val existing = withContext(Dispatchers.IO) {
                ids.mapNotNull { dao.getById(it) }.associateBy { it.id }
            }
            val warns = r.warnings.toMutableList()
            ops = r.tasks.mapIndexed { idx, t ->
                val old = existing[t.id]
                if (old != null) {
                    Op.Edit(t.name, describe(t), t, old, r.linkSpecs.getOrNull(idx))
                } else {
                    Op.Add(t.name, describe(t), t)
                }
            } + r.deletes.mapNotNull { id ->
                val old = existing[id]
                if (old == null) {
                    warns.add("要删除的任务 id=$id 已不存在，已跳过")
                    null
                } else {
                    Op.Remove(old.name, "删除这条任务，连同它的提醒与日历日程", old)
                }
            }
            flags = BooleanArray(ops.size) { true }
            renderPreview(warns)
        }
    }

    private fun hidePreview() {
        findViewById<View>(R.id.previewCard).visibility = View.GONE
        findViewById<View>(R.id.confirmBar).visibility = View.GONE
        parsed = emptyList()
        ops = emptyList()
        flags = BooleanArray(0)
    }

    private fun renderPreview(warnings: List<String>) {
        val rows = findViewById<LinearLayout>(R.id.previewRows)
        rows.removeAllViews()

        ops.forEachIndexed { i, op ->
            val v = layoutInflater.inflate(R.layout.item_import_preview, rows, false)
            val tag = when (op) {
                is Op.Add -> getString(R.string.import_op_add)
                is Op.Edit -> getString(R.string.import_op_edit)
                is Op.Remove -> getString(R.string.import_op_delete)
            }
            v.findViewById<TextView>(R.id.tvName).text = "$tag · ${op.title}"
            v.findViewById<TextView>(R.id.tvMeta).text = when (op) {
                is Op.Edit -> op.subtitle + "\n" + getString(R.string.import_edit_was_fmt, op.old.name)
                else -> op.subtitle
            }
            val cb = v.findViewById<CheckBox>(R.id.cbPick)
            cb.isChecked = flags[i]
            cb.setOnCheckedChangeListener { _, checked ->
                flags[i] = checked
                updateSummary()
            }
            rows.addView(v)
        }

        val wv = findViewById<TextView>(R.id.tvWarnings)
        if (warnings.isEmpty()) {
            wv.visibility = View.GONE
        } else {
            wv.visibility = View.VISIBLE
            wv.text = warnings.joinToString("\n") { "· $it" }
        }

        findViewById<View>(R.id.previewCard).visibility = View.VISIBLE
        findViewById<View>(R.id.confirmBar).visibility = View.VISIBLE
        updateSummary()
    }

    private fun updateSummary() {
        val n = flags.count { it }
        val pickedOps = ops.filterIndexed { i, _ -> flags[i] }
        findViewById<TextView>(R.id.tvSummary).text = getString(
            R.string.import_summary_mixed_fmt,
            ops.size,
            pickedOps.count { it is Op.Add },
            pickedOps.count { it is Op.Edit },
            pickedOps.count { it is Op.Remove },
            n
        )
        findViewById<MaterialButton>(R.id.btnConfirmImport).text =
            getString(R.string.import_confirm_n, n)
    }

    /** 一行预览文案：把三个时间点的关系用自然语言写清楚 */
    private fun describe(t: Task): String {
        val parts = ArrayList<String>()
        parts.add("${Task.formatMin(t.deadlineMin)} 目标")
        if (t.preOffsetMin >= 0) parts.add("${Task.formatMin(t.preAlertMin)} 开始")
        if (t.postOffsetMin >= 0) parts.add("${Task.formatMin(t.finalCheckMin)} 截止")
        parts.add(
            when (t.repeatType) {
                com.norbury.taskalarm.data.RepeatRule.TYPE_ONCE ->
                    RepeatRule.describeOnce(t.anchorDate)
                com.norbury.taskalarm.data.RepeatRule.TYPE_DATE ->
                    RepeatRule.describeDate(
                        t.anchorDate, t.intervalMonths, t.endDate, t.intervalDays,
                        RepeatRule.dayMaskOf(t.repeatMask), t.monthsMask
                    )
                com.norbury.taskalarm.data.RepeatRule.TYPE_LUNAR ->
                    com.norbury.taskalarm.util.LunarCalendar.describe(t.anchorDate, t.lunarLeadDays)
                else ->
                    RepeatRule.describe(t.repeatMask)
            }
        )
        if (t.tag.isNotBlank()) parts.add("分组 ${t.tag}")
        if (t.targetCount > 0) parts.add("目标 ${t.targetCount}")
        if (t.linkedToClock) parts.add("同步系统闹钟")
        if (t.linkedToCalendar) parts.add("写入日历")
        return parts.joinToString(" · ")
    }

    /* ---------------- 写入 ---------------- */

    private fun doImport() {
        val picked = ops.filterIndexed { i, _ -> flags[i] }
        if (picked.isEmpty()) {
            toast(getString(R.string.import_none_selected))
            return
        }

        lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) {
                val dao = AppDatabase.get(this@ImportActivity).taskDao()
                // 带进来的标签如果是没见过的，自动建一个（否则首页分组里点不开、编辑页也选不到）
                val known = Prefs.BUILTIN_TAGS + Prefs.customTags(this@ImportActivity)
                picked.filterIsInstance<Op.Add>().map { it.task.tag.trim() }
                    .filter { it.isNotEmpty() && it !in known }
                    .distinct()
                    .forEach { Prefs.addCustomTag(this@ImportActivity, it) }

                var count = 0
                // 只重排本次被新增 / 修改的任务，别的任务不动（v3.34.115）
                val touched = ArrayList<com.norbury.taskalarm.data.Task>()
                picked.forEach { op ->
                    when (op) {
                        is Op.Add -> {
                            val t = op.task
                            // 文件导入一律当新增：把可能带进来的 id 清零，免得撞上别人的任务
                            val id = dao.insert(t.copy(id = 0))
                            val saved = dao.getById(id) ?: return@forEach
                            count++
                            touched.add(saved)
                            pushLinks(saved)
                        }
                        is Op.Edit -> {
                            val t = op.task
                            // 打卡进度、创建时间、排序这些运行时字段保留原值，只覆盖 JSON 里带的业务字段
                            // 联动开关：JSON 显式写了才用 JSON 的值，没写才保留旧值。
                            // （之前用 OR 合并 → 一旦打开就再也关不掉，远端改 false 会被旧值 true 吞掉）
                            val spec = op.linkSpec
                            val merged = t.copy(
                                id = op.old.id,
                                doneCount = op.old.doneCount,
                                createdAt = op.old.createdAt,
                                sortOrder = op.old.sortOrder,
                                enabled = op.old.enabled,
                                linkedToClock = if (spec?.clockSpecified == true) t.linkedToClock
                                else op.old.linkedToClock,
                                linkedToCalendar = if (spec?.calendarSpecified == true) t.linkedToCalendar
                                else op.old.linkedToCalendar
                            )
                            dao.update(merged)
                            count++
                            touched.add(merged)
                            pushLinks(merged)
                        }
                        is Op.Remove -> {
                            dao.delete(op.old)
                            MiLinkBridge.removeFromCalendar(this@ImportActivity, op.old.id)
                            AlarmScheduler.cancel(this@ImportActivity, op.old.id)
                            count++
                        }
                    }
                }
                touched.forEach { AlarmScheduler.scheduleLater(this@ImportActivity, it) }
                count
            }

            // 预览已处理完：清掉"待确认"标记，PC 端据此知道这批已经落库
            Prefs.clearPendingImport(this@ImportActivity)

            // 桌面小组件即时同步（v3.34.53）：远程推送 / adb 导入走通知栏确认后
            // 直接 finish 回桌面，MainActivity 从未创建——组件只有等 30 分钟定时
            // onUpdate 才更新，用户看到"不打开 App 数据就是旧的"。
            com.norbury.taskalarm.widget.TodayWidget.refresh(this@ImportActivity)

            Toast.makeText(
                this@ImportActivity,
                getString(R.string.import_done_fmt, changed),
                Toast.LENGTH_LONG
            ).show()
            setResult(RESULT_OK)
            finish()
        }
    }

    /** 改 / 新增之后补推小米时钟与日历（开关状态沿用任务自己的标记） */
    private fun pushLinks(saved: Task) {
        if (saved.linkedToClock) MiLinkBridge.pushToClock(this, saved)
        if (saved.linkedToCalendar && MiLinkBridge.hasCalendarPermission(this)) {
            MiLinkBridge.pushToCalendar(this, saved)
        }
    }

    /* ---------------- 导出 ---------------- */

    /**
     * 导出全部任务为 JSON，走系统分享面板发出。
     *
     * 不直接写文件：分享面板里可选微信/QQ/邮件/网盘/蓝牙……
     * 对方（或换机后的自己）拿到的就是标准 v2 格式，回本页粘贴即可还原。
     */
    private fun exportAll() {
        lifecycleScope.launch {
            val tasks = withContext(Dispatchers.IO) {
                AppDatabase.get(this@ImportActivity).taskDao().getAll()
            }
            if (tasks.isEmpty()) {
                toast(getString(R.string.export_empty))
                return@launch
            }
            val json = TaskExporter.toJson(tasks)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_share_title))
                putExtra(Intent.EXTRA_TEXT, json)
            }
            runCatching {
                startActivity(Intent.createChooser(send, getString(R.string.export_share_title)))
            }.onFailure {
                toast(getString(R.string.export_share_failed))
                return@launch
            }
            toast(getString(R.string.export_done, tasks.size))
        }
    }

    /* ---------------- 格式说明 ---------------- */

    /**
     * 格式说明底部弹窗：与全站一致的暖白卡片 + 24dp 顶部圆角。
     *
     * 说明文字只留一句（用户要求"不要写那么多解释性的语言"），
     * 主体就是模板本身，等宽字体 + 可长按选中复制。
     */
    private fun showFormatHelp() {
        val d = resources.displayMetrics.density
        val textSub = ContextCompat.getColor(this, R.color.app_text_sub)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * d).toInt(), (4 * d).toInt(), (20 * d).toInt(), (12 * d).toInt()
            )
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.format_sheet_title)
            textSize = 19f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@ImportActivity, R.color.app_text))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (14 * d).toInt() })

        root.addView(TextView(this).apply {
            text = getString(R.string.import_help_msg)
            textSize = 12.5f
            setTextColor(textSub)
            setLineSpacing(0f, 1.45f)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (12 * d).toInt() })

        // 模板代码框：复用输入框底（surface 容器色 + 描边），等宽字体，可长按选中
        root.addView(TextView(this).apply {
            text = TaskImporter.TEMPLATE
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(this@ImportActivity, R.color.app_text))
            setBackgroundResource(R.drawable.bg_input)
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val btnClose = MaterialButton(
            ContextThemeWrapper(this, R.style.ThemeOverlay_TaskAlarm_SheetButtonText)
        ).apply {
            text = getString(R.string.import_close)
            setTextColor(textSub)
        }
        val btnCopy = MaterialButton(this).apply {
            text = getString(R.string.import_copy_template)
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            addView(btnClose)
            addView(btnCopy, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8 * d).toInt() })
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (16 * d).toInt() })

        val dialog = BottomSheetDialog(this)
        dialog.setContentView(ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(root)
        })
        ButtonTuner.tune(root)
        btnClose.setOnClickListener { dialog.dismiss() }
        btnCopy.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("TaskAlarm template", TaskImporter.TEMPLATE))
            toast(getString(R.string.import_copied))
        }
        dialog.setOnShowListener { si ->
            Sheets.forceExpanded(si as BottomSheetDialog)
        }
        dialog.show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
