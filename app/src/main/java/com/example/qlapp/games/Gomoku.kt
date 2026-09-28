package com.example.qlapp.games

/**
 * 五子棋的纯逻辑：棋盘尺寸、走子串编解码、轮次、胜负判定。
 *
 * 这里**不碰任何 Android API**，规则和后端 `backend/src/index.ts` 里的
 * `parseMoves` / `serializeMoves` / `gomokuWins` 一一对应。两边算出来的结果
 * 必须一致，否则会出现「本机觉得赢了、服务端说还没赢」这种诡异场面，
 * 所以刻意写成纯函数，用单测把规则钉死。
 *
 * 规则是**无禁手**：横竖斜任意方向连成五个及以上即胜（六连也算），
 * 没有黑棋的三三、四四、长连禁手。
 */

/** 棋盘边长，和后端 `GOMOKU_SIZE` 一致。 */
const val GOMOKU_SIZE = 15

/** 棋盘上的一手。[x] 是列、[y] 是行，都是 0..14。 */
data class Point(val x: Int, val y: Int)

/** 黑先白后。棋盘上第 0 手是黑，第 1 手是白，依次交替。 */
enum class Stone(val code: String, val label: String) {
    BLACK("black", "黑"),
    WHITE("white", "白");

    companion object {
        fun from(code: String?): Stone? = entries.firstOrNull { it.code == code }
    }
}

object Gomoku {

    /** 星位（含天元），15 路棋盘的标准位置。 */
    val STAR_POINTS: List<Point> = listOf(
        Point(3, 3), Point(11, 3), Point(3, 11), Point(11, 11), Point(7, 7),
    )

    /**
     * 把 `"7,7;8,8"` 解成点列。
     * 空串得到空列表；格式不对或越界的片段直接跳过，和后端同样的宽容度——
     * 宁可少画一个子，也不能因为一条脏数据整盘棋画不出来。
     */
    fun parseMoves(raw: String): List<Point> =
        raw.split(';')
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { part ->
                val bits = part.split(',')
                if (bits.size != 2) return@mapNotNull null
                val x = bits[0].trim().toIntOrNull() ?: return@mapNotNull null
                val y = bits[1].trim().toIntOrNull() ?: return@mapNotNull null
                if (x !in 0 until GOMOKU_SIZE || y !in 0 until GOMOKU_SIZE) return@mapNotNull null
                Point(x, y)
            }
            .toList()

    /** 反过来，把点列拼成走子串。 */
    fun serializeMoves(moves: List<Point>): String =
        moves.joinToString(";") { "${it.x},${it.y}" }

    /** 第 [index] 手是谁下的（0 起，偶数黑）。 */
    fun stoneAt(index: Int): Stone = if (index % 2 == 0) Stone.BLACK else Stone.WHITE

    /** 已经落了 [moveCount] 手时轮到谁。 */
    fun turnOf(moveCount: Int): Stone = if (moveCount % 2 == 0) Stone.BLACK else Stone.WHITE

    /** 棋盘是不是下满了（满盘还没连成五个就是和棋）。 */
    fun isFull(moveCount: Int): Boolean = moveCount >= GOMOKU_SIZE * GOMOKU_SIZE

    private val DIRS = arrayOf(
        intArrayOf(1, 0),  // 横
        intArrayOf(0, 1),  // 竖
        intArrayOf(1, 1),  // 撇
        intArrayOf(1, -1), // 捺
    )

    /** 刚落的这一手有没有连成五个及以上。 */
    fun wins(moves: List<Point>): Boolean = winningLine(moves) != null

    /**
     * 返回连成的那一串（含最后一手），没连成返回 null。
     *
     * 只看最后一手所在的四条线：之前的局面早就查过，不可能因为这一手才连上别的线。
     * 棋盘上用这个把五个子圈出来，让输的一方看得明白是哪儿被连上的。
     */
    fun winningLine(moves: List<Point>): List<Point>? {
        if (moves.isEmpty()) return null
        val side = stoneAt(moves.size - 1)
        val board = HashMap<Long, Stone>(moves.size * 2)
        moves.forEachIndexed { index, p -> board[key(p.x, p.y)] = stoneAt(index) }

        val last = moves.last()
        for (dir in DIRS) {
            val line = ArrayList<Point>()
            line += last
            for (sign in intArrayOf(1, -1)) {
                var x = last.x + dir[0] * sign
                var y = last.y + dir[1] * sign
                while (board[key(x, y)] == side) {
                    line += Point(x, y)
                    x += dir[0] * sign
                    y += dir[1] * sign
                }
            }
            // 排序只是为了让输出稳定（从线的一端到另一端），单测好断言，画圈也顺
            if (line.size >= 5) return line.sortedWith(compareBy({ it.x }, { it.y }))
        }
        return null
    }

    /** 把 (x,y) 压成一个 Long 当 Map 的键，省得每次 new 一个 Point。 */
    private fun key(x: Int, y: Int): Long = x.toLong() * 1000L + y
}
