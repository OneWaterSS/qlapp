package com.example.qlapp.data

data class TaskItem(
    val id: String,
    val title: String,
    val note: String = "",
    val done: Boolean = false,
    val doneBy: String? = null,
    val doneAt: Long? = null,
    val createdBy: String = "",
    val createdAt: Long = 0L,
)

data class PhotoItem(
    val id: String,
    val caption: String = "",
    val uploadedBy: String = "",
    val createdAt: Long = 0L,
)

/**
 * 一天一条，两个人各写各的。
 *
 * 归属看 [author]（身份名：小江 / 甜甜），后端已经比对好、回一个 [mine]，
 * 客户端不用自己比字符串。[deviceId] 只是「这条写在哪儿」的记录，不参与判断。
 */
data class DiaryEntry(
    val date: String,
    val deviceId: String = "",
    val mood: Mood = Mood.HAPPY,
    val content: String = "",
    val author: String = "",
    val updatedAt: Long = 0L,
    /** 这条是不是本机身份写的（能不能改、能不能删全看它）。 */
    val mine: Boolean = false,
)

/** 五种心情，顺序和日历里挑表情的顺序一致。 */
enum class Mood(val code: String, val emoji: String, val label: String) {
    HAPPY("happy", "😊", "开心"),
    MISS("miss", "🥰", "想你"),
    CALM("calm", "😐", "平静"),
    SAD("sad", "😢", "难过"),
    ANGRY("angry", "😠", "生气");

    companion object {
        fun from(code: String?): Mood? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 留言墙上的一张便签。
 * 只能往前加，不能改也不能删——产品上就是这么定的，所以没有编辑相关字段。
 *
 * [mine] 由后端按署名（身份名）比对好，决定便签贴黄还是贴蓝。
 */
data class WallMessage(
    val id: String,
    val deviceId: String = "",
    val author: String = "",
    val content: String = "",
    val createdAt: Long = 0L,
    val mine: Boolean = false,
)

/** 留言列表和未读数是一起拉回来的，所以打包成一个结果。 */
data class WallFeed(val messages: List<WallMessage> = emptyList(), val unread: Int = 0)

/**
 * 云端存着的共同设置。
 *
 * 昵称是**按装置**存的（`nicknames` 的键是 deviceId），因为两个人本来就该
 * 各有各的名字；纪念日是共同的，只有一份。
 * 这两样放云端是为了让管理网页也能改，本地 SharedPreferences 仍是最快的缓存。
 */
data class RemoteSettings(
    val nicknames: Map<String, String> = emptyMap(),
    val anniversary: Long? = null,
) {
    /** 取本机那个昵称。没有就返回 null，让调用方保留本地值。 */
    fun nicknameFor(deviceId: String): String? = nicknames[deviceId]
}

/* -------------------------------- 五子棋 -------------------------------- */

/**
 * 一局联机五子棋。
 *
 * [moves] 是压缩过的落子串（`"7,7;8,8"`，列,行），不是列表——
 * 一盘最多 225 手，塞进一个字段一次 UPDATE 就写完了，不用另开一张表存每一步。
 * 谁执黑看 [blackId] 是不是本机，别看昵称：昵称能改，装置 ID 不会。
 */
data class GomokuMatch(
    val id: String,
    val blackId: String = "",
    val blackName: String = "",
    val whiteId: String? = null,
    val whiteName: String = "",
    /** `waiting` 等对方接受 / `playing` 正在下 / `finished` 已分胜负。 */
    val status: String = "",
    /** 轮到谁，`black` 或 `white`。 */
    val turn: String = "black",
    val moves: String = "",
    /** `black` / `white` / `draw`，没结束时是 null。 */
    val winner: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** 本机视角的战绩。总共两个人，所以「对方胜 = 已分胜负的局数 − 我胜」。 */
data class GomokuStats(val mine: Int = 0, val theirs: Int = 0, val draws: Int = 0)

/** 当前对局和战绩是一起拉回来的，打包成一个结果。 */
data class GomokuFeed(val match: GomokuMatch? = null, val stats: GomokuStats = GomokuStats())

/**
 * 入口卡上的累计胜场排行，按固定顺序（小江、甜甜）排列。
 *
 * 顺序写死、不跟「谁在哪台手机上」走：两个人打开同一个入口卡应该看到
 * 同一个排列，才分得清谁领先。
 */
data class GomokuStanding(val entries: List<Pair<String, Int>> = emptyList()) {

    /**
     * 领先者的名字；打平或都还没赢过时为 null。
     *
     * 只认「严格领先」：0:0 和 2:2 都不会误标出一个赢家。
     */
    val leader: String?
        get() {
            val top = entries.maxByOrNull { it.second } ?: return null
            if (top.second <= 0) return null
            return if (entries.count { it.second == top.second } == 1) top.first else null
        }
}

class ApiException(message: String) : Exception(message)
