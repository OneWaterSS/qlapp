package com.example.qlapp

import com.example.qlapp.games.GOMOKU_SIZE
import com.example.qlapp.games.Gomoku
import com.example.qlapp.games.Point
import com.example.qlapp.games.Stone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 五子棋纯逻辑。
 *
 * 这些规则和后端 `gomokuWins` 是同一套，必须逐条对上：
 * 一旦客户端比服务端「松」（比如客户端认六连赢、服务端不认），
 * 就会出现本机已经庆祝、棋盘却还在等下棋的局面。
 * 所以重点测四方向的连五、断点不误判、以及「六连也算赢」这条无禁手规则。
 */
class GomokuTest {

    /* ------------------------------ 走子串编解码 ------------------------------ */

    @Test fun parseMovesReadsPairsAndSkipsJunk() {
        assertEquals(listOf(Point(7, 7), Point(8, 8)), Gomoku.parseMoves("7,7;8,8"))
        // 空片段、越界、非数字都丢掉，剩下合法的照常解析
        assertEquals(listOf(Point(7, 7), Point(8, 8)), Gomoku.parseMoves("7,7;;8,8"))
        assertEquals(listOf(Point(0, 0)), Gomoku.parseMoves("0,0;99,1;x,y;3"))
        assertEquals(emptyList<Point>(), Gomoku.parseMoves(""))
        assertEquals(emptyList<Point>(), Gomoku.parseMoves(";;"))
        // 15 路棋盘，14 是最后一格，15 就越界了
        assertEquals(listOf(Point(14, 14)), Gomoku.parseMoves("14,14"))
        assertEquals(emptyList<Point>(), Gomoku.parseMoves("15,0"))
    }

    @Test fun serializeMovesRoundTrips() {
        val raw = "7,7;8,8;6,7"
        assertEquals(raw, Gomoku.serializeMoves(Gomoku.parseMoves(raw)))
        assertEquals("", Gomoku.serializeMoves(emptyList()))
    }

    /* -------------------------------- 轮次 -------------------------------- */

    @Test fun blackMovesFirstAndTurnsAlternate() {
        assertEquals(Stone.BLACK, Gomoku.stoneAt(0))
        assertEquals(Stone.WHITE, Gomoku.stoneAt(1))
        assertEquals(Stone.BLACK, Gomoku.stoneAt(2))
        assertEquals(Stone.BLACK, Gomoku.turnOf(0))
        assertEquals(Stone.WHITE, Gomoku.turnOf(1))
        assertEquals(Stone.BLACK, Gomoku.turnOf(2))
    }

    @Test fun fullBoardIsFifteenByFifteen() {
        assertEquals(15, GOMOKU_SIZE)
        assertFalse(Gomoku.isFull(GOMOKU_SIZE * GOMOKU_SIZE - 1))
        assertTrue(Gomoku.isFull(GOMOKU_SIZE * GOMOKU_SIZE))
    }

    /* -------------------------------- 胜负 -------------------------------- */

    @Test fun fiveInARowWinsHorizontally() {
        // 黑横着连五个，白在下面一行陪着落，保证手数严格交替
        val moves = listOf(
            Point(0, 0), Point(0, 1),
            Point(1, 0), Point(1, 1),
            Point(2, 0), Point(2, 1),
            Point(3, 0), Point(3, 1),
            Point(4, 0),
        )
        assertTrue(Gomoku.wins(moves))
        assertEquals(
            listOf(Point(0, 0), Point(1, 0), Point(2, 0), Point(3, 0), Point(4, 0)),
            Gomoku.winningLine(moves),
        )
    }

    @Test fun fiveInARowWinsVertically() {
        val moves = listOf(
            Point(0, 0), Point(1, 0),
            Point(0, 1), Point(1, 1),
            Point(0, 2), Point(1, 2),
            Point(0, 3), Point(1, 3),
            Point(0, 4),
        )
        assertTrue(Gomoku.wins(moves))
        assertEquals(
            listOf(Point(0, 0), Point(0, 1), Point(0, 2), Point(0, 3), Point(0, 4)),
            Gomoku.winningLine(moves),
        )
    }

    @Test fun fiveInARowWinsOnBothDiagonals() {
        // 撇：黑走 (0,0)→(4,4)，白在右边一格陪着
        val slash = listOf(
            Point(0, 0), Point(0, 1),
            Point(1, 1), Point(1, 2),
            Point(2, 2), Point(2, 3),
            Point(3, 3), Point(3, 4),
            Point(4, 4),
        )
        assertTrue(Gomoku.wins(slash))
        assertEquals(
            listOf(Point(0, 0), Point(1, 1), Point(2, 2), Point(3, 3), Point(4, 4)),
            Gomoku.winningLine(slash),
        )

        // 捺：黑走 (0,4)→(4,0)，白在最下面一行陪着（只有四个，不构成连五）
        val backslash = listOf(
            Point(0, 4), Point(0, 6),
            Point(1, 3), Point(1, 6),
            Point(2, 2), Point(2, 6),
            Point(3, 1), Point(3, 6),
            Point(4, 0),
        )
        assertTrue(Gomoku.wins(backslash))
        assertEquals(
            listOf(Point(0, 4), Point(1, 3), Point(2, 2), Point(3, 1), Point(4, 0)),
            Gomoku.winningLine(backslash),
        )
    }

    @Test fun fourInARowIsNotAWin() {
        val moves = listOf(
            Point(0, 0), Point(0, 1),
            Point(1, 0), Point(1, 1),
            Point(2, 0), Point(2, 1),
            Point(3, 0),
        )
        assertFalse(Gomoku.wins(moves))
        assertNull(Gomoku.winningLine(moves))
    }

    @Test fun aGapBreaksTheLine() {
        // 第 3 列被白棋占住，黑的四个子被断开，不算赢
        val moves = listOf(
            Point(0, 0), Point(3, 0),
            Point(1, 0), Point(3, 1),
            Point(2, 0), Point(3, 2),
            Point(4, 0),
        )
        assertFalse(Gomoku.wins(moves))
    }

    @Test fun sixInARowAlsoWins() {
        // 无禁手：长连（六个以上）照样算赢，所以是 >= 5 而不是 == 5
        val moves = listOf(
            Point(0, 0), Point(0, 5),
            Point(1, 0), Point(2, 5),
            Point(2, 0), Point(4, 5),
            Point(3, 0), Point(1, 6),
            Point(4, 0), Point(3, 6),
            Point(5, 0),
        )
        assertTrue(Gomoku.wins(moves))
        assertEquals(6, Gomoku.winningLine(moves)?.size)
    }

    @Test fun emptyOrTinyBoardNeverWins() {
        assertFalse(Gomoku.wins(emptyList()))
        assertNull(Gomoku.winningLine(emptyList()))
        assertFalse(Gomoku.wins(listOf(Point(7, 7))))
    }
}
