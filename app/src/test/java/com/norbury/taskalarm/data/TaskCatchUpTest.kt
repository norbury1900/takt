package com.norbury.taskalarm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 补响判定回归：闹钟被系统吞掉后，打开 App 能否补响由 Task.catchUpLateMin 决定。
 */
class TaskCatchUpTest {

    @Test
    fun `错过时间在宽限窗口内返回迟到分钟数`() {
        // 午饭外卖 10:30 应响，10:36 打开 App → 迟到 6 分钟，补响
        assertEquals(6, Task.catchUpLateMin(10 * 60 + 30, 10 * 60 + 36))
        assertEquals(120, Task.catchUpLateMin(630, 750)) // 恰好 2 小时边界（含）
    }

    @Test
    fun `还没到点不补`() {
        assertNull(Task.catchUpLateMin(10 * 60 + 30, 10 * 60 + 29))
        assertNull(Task.catchUpLateMin(10 * 60 + 30, 10 * 60 + 30)) // 整点那一分钟交给正常闹钟
    }

    @Test
    fun `超出宽限窗口不补`() {
        assertNull(Task.catchUpLateMin(10 * 60 + 30, 10 * 60 + 30 + 121))
        assertNull(Task.catchUpLateMin(10 * 60 + 30, 23 * 60)) // 中午的任务晚上不补
    }

    @Test
    fun `负fireMin须由调用方先归一化_本函数只做纯差值`() {
        // 跨午夜任务的 fireMin 可能是负数（如截止 00:10 催 30 分 → -20），
        // 调用方（AlarmScheduler）负责 mod 1440；本函数只按字面差值判定
        assertEquals(50, Task.catchUpLateMin(-20, 30))
        assertNull(Task.catchUpLateMin(-20, -30)) // 还没到点
    }
}
