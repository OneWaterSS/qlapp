package com.example.qlapp

import com.example.qlapp.data.DiaryEntry
import com.example.qlapp.data.Mood
import com.example.qlapp.ui.monthDiaryRows
import com.example.qlapp.ui.shiftMonth
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日记功能的纯逻辑部分。
 *
 * 日历全靠月份字符串推导，跨年翻月算错会让整月数据对不上，
 * 所以这里重点验证月份加减和日期格式；心情代号则要保证和后端白名单一致
 * （后端 CHECK 约束只认这五个，写错了整条日记会被拒）。
 */
class DiaryLogicTest {

    @Test fun shiftMonthMovesForwardAndBackward() {
        assertEquals("2026-10", shiftMonth("2026-09", 1))
        assertEquals("2026-08", shiftMonth("2026-09", -1))
    }

    @Test fun shiftMonthRollsOverTheYearBoundary() {
        // 十二月往后应该是次年一月，九月往前跨年也一样
        assertEquals("2027-01", shiftMonth("2026-12", 1))
        assertEquals("2026-12", shiftMonth("2027-01", -1))
        assertEquals("2025-12", shiftMonth("2026-01", -1))
        assertEquals("2026-01", shiftMonth("2025-12", 1))
    }

    @Test fun shiftMonthKeepsZeroPadding() {
        // 补零不能丢，否则后端 LIKE '2026-01-%' 会匹配不到
        assertTrue(shiftMonth("2026-10", 3).endsWith("-01"))
        assertEquals("2026-02", shiftMonth("2026-01", 1))
    }

    @Test fun moodCodesMatchBackendWhitelist() {
        assertEquals(
            listOf("happy", "miss", "calm", "sad", "angry"),
            Mood.entries.map { it.code },
        )
    }

    @Test fun moodLookupByCodeIsCaseSensitiveAndRejectsUnknown() {
        assertEquals(Mood.HAPPY, Mood.from("happy"))
        assertEquals(Mood.ANGRY, Mood.from("angry"))
        assertNull(Mood.from("HAPPY"))
        assertNull(Mood.from(""))
        assertNull(Mood.from(null))
    }

    @Test fun everyMoodHasAnEmojiAndLabel() {
        Mood.entries.forEach {
            assertTrue("心情 ${it.code} 缺少表情", it.emoji.isNotBlank())
            assertTrue("心情 ${it.code} 缺少名称", it.label.isNotBlank())
        }
        // 五个心情在日历上要能区分开，表情不能重复
        assertEquals(Mood.entries.size, Mood.entries.map { it.emoji }.toSet().size)
    }

    @Test fun weekLeadingBlanksMatchJavaTimeDayOfWeek() {
        // 日历靠 dayOfWeek.value - 1 算前置空格，周一 = 0、周日 = 6
        assertEquals(0, LocalDate.of(2026, 9, 7).dayOfWeek.value - 1)  // 2026-09-07 是周一
        assertEquals(6, LocalDate.of(2026, 9, 6).dayOfWeek.value - 1)  // 2026-09-06 是周日
    }

    @Test fun monthLengthHandlesLeapFebruary() {
        assertEquals(29, java.time.YearMonth.of(2028, 2).lengthOfMonth())
        assertEquals(28, java.time.YearMonth.of(2026, 2).lengthOfMonth())
    }

    /* -------------------- 日历下方「本月日记」列表的排序 -------------------- */

    @Test fun monthDiaryRowsPutsNewestDateFirst() {
        val rows = monthDiaryRows(
            mapOf(
                "2026-09-24" to listOf(entry("2026-09-24", "dev-a", 100L)),
                "2026-09-27" to listOf(entry("2026-09-27", "dev-a", 200L)),
                "2026-09-08" to listOf(entry("2026-09-08", "dev-b", 300L)),
            ),
        )
        // 注意 09-08 的 updatedAt 最大，但它日期最旧，必须排最后——
        // 排序主键是日期，不是写入时间。
        assertEquals(listOf("2026-09-27", "2026-09-24", "2026-09-08"), rows.map { it.date })
    }

    @Test fun monthDiaryRowsBreaksSameDayTiesByUpdatedAt() {
        // 同一天两人各写一条：谁写得晚谁排前面
        val rows = monthDiaryRows(
            mapOf(
                "2026-09-27" to listOf(
                    entry("2026-09-27", "dev-mine", 100L),
                    entry("2026-09-27", "dev-theirs", 900L),
                ),
            ),
        )
        assertEquals(listOf("dev-theirs", "dev-mine"), rows.map { it.deviceId })
    }

    @Test fun monthDiaryRowsFlattensEveryEntryAndHandlesEmpty() {
        assertTrue(monthDiaryRows(emptyMap()).isEmpty())
        val rows = monthDiaryRows(
            mapOf(
                "2026-09-27" to listOf(entry("2026-09-27", "dev-a", 1L), entry("2026-09-27", "dev-b", 2L)),
                "2026-09-24" to listOf(entry("2026-09-24", "dev-a", 3L)),
            ),
        )
        // 一天两条不能被合并成一条，三条就得出来三条
        assertEquals(3, rows.size)
    }

    private fun entry(date: String, deviceId: String, updatedAt: Long) =
        DiaryEntry(date = date, deviceId = deviceId, updatedAt = updatedAt)
}
