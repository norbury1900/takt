package com.norbury.taktu.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Database(entities = [Task::class, TaskRecord::class], version = 17, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v1 → v2：新增 createdAt 与两个联动标记字段 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN createdAt INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}"
                )
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN linkedToClock INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN linkedToCalendar INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v2 → v3：新增「法定节假日是否跳过提醒」字段 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN skipHolidays INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        /** v3 → v4：新增标签字段（首页按标签分组） */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN tag TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v4 → v5：新增按日期重复（房租这类"每 N 个月的某号 + 结束日期"） */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN repeatType INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN anchorDate TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tasks ADD COLUMN intervalMonths INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tasks ADD COLUMN endDate TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v5 → v6：新增每组次数（逐组打卡 / 百分比进度） */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN repsPerSet INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v6 → v7：新增「法定休息日截止时间」（-1 表示与主时间相同） */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN restDayDeadlineMin INTEGER NOT NULL DEFAULT -1"
                )
            }
        }

        /** v7 → v8：日期型重复支持按天间隔（0 = 不按天、用 intervalMonths） */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN intervalDays INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v8 → v9：新增优先级（0=高 1=中 2=低），老数据一律落在「中」 */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN priority INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** v9 → v10：新增任务清单（子项）与当日勾选状态 */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN items TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE records ADD COLUMN checkedItems TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v10 → v11：清单项支持记组数（每项已完成几组） */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE records ADD COLUMN itemProgress TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v11 → v12：催促/复查的「关闭」哨兵从 0 改为 -1，0 腾给「准时」。
         * 老数据里的 0 一律原本是「关闭」，迁成 -1 后行为不变；
         * 不迁的话这些任务会突然变成在截止点响铃。
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE tasks SET preOffsetMin = -1 WHERE preOffsetMin = 0")
                db.execSQL("UPDATE tasks SET postOffsetMin = -1 WHERE postOffsetMin = 0")
            }
        }

        /**
         * v12 → v13：农历每年任务（TYPE_LUNAR）的「提前 N 天」字段。
         * 老数据全为 0（当天提醒），行为不变。
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN lunarLeadDays INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v13 → v14：每日触发时刻表（统一「响铃期间 + 方式 + 每天时刻」模型）。
         * 空串 = 旧单时刻模式（继续走 deadlineMin），老任务行为完全不变；
         * 只有新编辑/新建的任务才写入时刻表。
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN timeSpec TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v14 → v15：逐时刻的「开始 / 复查」偏移表（Task.timeOffsets）。
         * 空串 = 所有时刻统一用任务级 preOffsetMin / postOffsetMin，
         * 老任务行为完全不变（这正是迁移后所有行的取值）。
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN timeOffsets TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v15 → v16：择一提醒（Task.eitherMode，v3.34.96）。
         * 0 = 普通多时刻任务（老行为：每个时刻都要确认），1 = 确认任一个即完成。
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN eitherMode INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v16 → v17：每年多选月份（v3.34.130） */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN monthsMask INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "task_alarm.db"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                        MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                        MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
                        MIGRATION_15_16, MIGRATION_16_17
                    )
                    // 不再用 fallbackToDestructiveMigration：迁移链遗漏时宁可启动报错，
                    // 也不要静默把用户的全部任务和记录删掉（v3.34.120）
                    .build()
                    .also { INSTANCE = it }
            }

        // v3.34.112：共享 SimpleDateFormat 不是线程安全的（首页/响铃页/组件同时调用会算错日期），
        // 换成不可变的 DateTimeFormatter + LocalDate
        private val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val zone = java.time.ZoneId.systemDefault()

        private fun localDate(date: Date): java.time.LocalDate =
            java.time.Instant.ofEpochMilli(date.time).atZone(zone).toLocalDate()

        /** 今日日期键，如 2026-10-10 */
        fun todayKey(): String = java.time.LocalDate.now().format(fmt)

        fun keyOf(date: Date): String = localDate(date).format(fmt)

        /** 把日期键解析回 Date（用于连续天数推算） */
        fun parseKey(key: String): Date? = runCatching {
            Date(java.time.LocalDate.parse(key, fmt).atStartOfDay(zone).toInstant().toEpochMilli())
        }.getOrNull()

        /** 往前推 n 天的日期键 */
        fun keyDaysAgo(n: Int): String = java.time.LocalDate.now().minusDays(n.toLong()).format(fmt)

        /** key2 - key1 相差天数 */
        fun daysBetween(key1: String, key2: String): Int {
            val d1 = runCatching { java.time.LocalDate.parse(key1, fmt) }.getOrNull() ?: return 0
            val d2 = runCatching { java.time.LocalDate.parse(key2, fmt) }.getOrNull() ?: return 0
            return java.time.temporal.ChronoUnit.DAYS.between(d1, d2).toInt()
        }
    }
}
