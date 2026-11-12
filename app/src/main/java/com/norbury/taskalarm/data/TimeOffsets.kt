package com.norbury.taskalarm.data

/**
 * 逐时刻的「开始 / 复查」偏移表（v3.34.21，Task.timeOffsets 的解析层）。
 *
 * 背景（用户 2026-10-22 反馈）：「每日时刻」有多个时间时，开始时间 / 复查时间
 * 原来只有一个任务级的值，套在所有时刻上 —— 这既不是用户想要的（每个时刻各有
 * 自己的开始/复查），也让响铃时间对不上（「11:25 和 17:25 都在 16:00 提醒」）。
 *
 * **key 用「时刻的当日分钟数」而不是下标**：编辑页往时刻表中间插一个时刻时，
 * 下标全部后移，按下标存的配置会集体错位；按分钟数存则原地命中。
 *
 * 格式：`"480:90/30,1020:120/60"` → 8:00 提前 90 分 / 延后 30 分，17:00 提前 120 分 / 延后 60 分。
 * 空串 = 所有时刻统一用任务级的 [Task.preOffsetMin] / [Task.postOffsetMin]（老任务零迁移）。
 *
 * 偏移量本身仍是三态（-1 关闭 / 0 准时 / >0 N 分钟），与 UI 上选的绝对钟点互逆。
 * 全部纯函数，单测覆盖（TimeOffsetsTest）。
 */
object TimeOffsets {

    /** 逐时刻配置的条数上限：再多卡片上排不开，也没人真给 20 个时刻分别配提醒 */
    const val MAX_ENTRIES = 16

    /** `"480:90/30"` → 480 to (90 to 30)；非法条目跳过 */
    private fun parseEntry(seg: String): Triple<Int, Int, Int>? {
        val p = seg.split(':')
        val timeMin = p.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        if (timeMin !in 0 until 1440) return null
        val off = p.getOrNull(1)?.trim()?.split('/') ?: return null
        val pre = off.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val post = off.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        if (pre < Task.OFFSET_OFF || post < Task.OFFSET_OFF) return null
        return Triple(timeMin, pre, post)
    }

    /** 解析成 时刻分钟 → (提前, 延后)；非法条目跳过，重复取后写的 */
    fun parse(spec: String): Map<Int, Pair<Int, Int>> {
        if (spec.isBlank()) return emptyMap()
        val out = LinkedHashMap<Int, Pair<Int, Int>>()
        spec.split(',').forEach { seg ->
            parseEntry(seg.trim())?.let { (t, pre, post) -> out[t] = pre to post }
        }
        return out
    }

    /** 编码：按时刻分钟升序；空表返回空串（= 统一偏移） */
    fun encode(map: Map<Int, Pair<Int, Int>>): String =
        map.entries
            .filter { it.key in 0 until 1440 && it.value.first >= Task.OFFSET_OFF && it.value.second >= Task.OFFSET_OFF }
            .sortedBy { it.key }
            .take(MAX_ENTRIES)
            .joinToString(",") { "${it.key}:${it.value.first}/${it.value.second}" }

    /** 该时刻的提前量；没单独配就回落任务级 */
    fun preOf(spec: String, timeMin: Int, defPre: Int): Int =
        parse(spec)[norm(timeMin)]?.first ?: defPre

    /** 该时刻的延后量；没单独配就回落任务级 */
    fun postOf(spec: String, timeMin: Int, defPost: Int): Int =
        parse(spec)[norm(timeMin)]?.second ?: defPost

    /** 有没有针对该时刻的单独配置（编辑页决定要不要展开逐时刻 UI 用） */
    fun has(spec: String, timeMin: Int): Boolean = parse(spec).containsKey(norm(timeMin))

    /**
     * 写入 / 覆盖某个时刻的配置（其余原样保留）。
     * pre/post 越界（超过 [Task.OFFSET_OFF]）会被钳掉；等于任务级默认值的条目删掉，
     * 免得表里堆一串和默认值一样的冗余（[equalsDefault]）。
     */
    fun with(
        spec: String,
        timeMin: Int,
        pre: Int,
        post: Int,
        defPre: Int,
        defPost: Int
    ): String {
        val t = norm(timeMin)
        val map = parse(spec).toMutableMap()
        val p = pre.coerceAtLeast(Task.OFFSET_OFF)
        val q = post.coerceAtLeast(Task.OFFSET_OFF)
        if (p == defPre && q == defPost) map.remove(t) else map[t] = p to q
        return encode(map)
    }

    /** 删掉某个时刻的配置（编辑页删时刻时同步调用） */
    fun without(spec: String, timeMin: Int): String {
        val map = parse(spec).toMutableMap()
        map.remove(norm(timeMin))
        return encode(map)
    }

    /**
     * 只保留仍在时刻表里的条目。
     * 时刻表改了（增删改）之后调用，免得表里攒下一堆永远命中的僵尸配置。
     */
    fun retain(spec: String, times: List<Int>): String {
        val keep = times.map { norm(it) }.toSet()
        return encode(parse(spec).filterKeys { it in keep })
    }

    /**
     * 把全部仍在时刻表里的时刻一次性覆盖成同一组偏移（「应用到全部时刻」按钮）。
     * 传的就是任务级默认值时，整张表清空（等价于回到统一偏移）。
     */
    fun applyToAll(
        spec: String,
        times: List<Int>,
        pre: Int,
        post: Int,
        defPre: Int,
        defPost: Int
    ): String {
        val p = pre.coerceAtLeast(Task.OFFSET_OFF)
        val q = post.coerceAtLeast(Task.OFFSET_OFF)
        if (p == defPre && q == defPost) return retain(spec, emptyList())
        return encode(
            times.map { norm(it) }.distinct().associateWith { p to q }
        )
    }

    /** 提前量钳到「0 ~ 目标时刻」：开始时间不可能晚于目标 */
    fun clampPre(pre: Int, targetMin: Int): Int =
        pre.coerceIn(Task.OFFSET_OFF, targetMin.coerceIn(0, 1435))

    /** 延后量钳到「0 ~ 1435-target」：复查时间不可能早于目标 */
    fun clampPost(post: Int, targetMin: Int): Int =
        post.coerceIn(Task.OFFSET_OFF, (1435 - targetMin.coerceIn(0, 1435)))

    private fun norm(min: Int): Int = ((min % 1440) + 1440) % 1440
}