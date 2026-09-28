package com.example.qlapp

import com.example.qlapp.ui.buildGomokuStanding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 入口卡战绩排行的换算。
 *
 * 后端只给「本机胜场 / 对方胜场」两个数字，看不出谁是谁；这里保证不管从哪台
 * 手机看，卡片上的顺序都是 小江、甜甜，而不是各看各的。
 * 重点测两件事：顺序与手机无关、只有真的领先才算领先。
 */
class GomokuStandingTest {

    @Test fun orderIsAlwaysXiaojiangThenTiantian() {
        // 甜甜这台看到的是「我赢 1、对方赢 3」
        val fromTiantian = buildGomokuStanding("甜甜", mine = 1, theirs = 3)
        assertEquals(listOf("小江" to 3, "甜甜" to 1), fromTiantian.entries)
        assertEquals("小江", fromTiantian.leader)

        // 小江那台看到的是「我赢 3、对方赢 1」—— 结果必须一模一样
        val fromXiaojiang = buildGomokuStanding("小江", mine = 3, theirs = 1)
        assertEquals(listOf("小江" to 3, "甜甜" to 1), fromXiaojiang.entries)
        assertEquals("小江", fromXiaojiang.leader)
    }

    @Test fun leaderOnlyWhenStrictlyAhead() {
        assertEquals("甜甜", buildGomokuStanding("小江", mine = 0, theirs = 2).leader)
        // 打平：没有领先者
        assertNull(buildGomokuStanding("小江", mine = 2, theirs = 2).leader)
        // 都还没赢过：同样没有领先者
        assertNull(buildGomokuStanding("小江", mine = 0, theirs = 0).leader)
    }

    @Test fun unknownNicknameDoesNotInventAWinner() {
        // 昵称不是这两个（理论上不会发生），对不上号的那份按 0 算，不瞎标领先
        val standing = buildGomokuStanding("我", mine = 5, theirs = 1)
        assertEquals(listOf("小江" to 1, "甜甜" to 0), standing.entries)
        assertEquals("小江", standing.leader)
    }

    @Test fun blankNicknameStillProducesFixedOrder() {
        val standing = buildGomokuStanding("", mine = 0, theirs = 0)
        assertEquals(listOf("小江" to 0, "甜甜" to 0), standing.entries)
        assertNull(standing.leader)
    }
}
