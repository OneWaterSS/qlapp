package com.example.qlapp.games

data class GameRecord(val score: Int = 0, val owner: String? = null) {
    fun improvedBy(points: Int, nickname: String): GameRecord =
        if (points > score) GameRecord(points, nickname.trim().ifBlank { "未命名玩家" }) else this

    val label: String get() = if (score <= 0) "暂无纪录 · 等你来挑战"
        else "$score 分 · ${owner?.takeIf { it.isNotBlank() } ?: "历史玩家（未记录昵称）"}"
}
