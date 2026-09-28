package com.example.qlapp.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import com.example.qlapp.games.GameKind
import com.example.qlapp.games.GameRecord

class Api(private val prefs: Prefs) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /* ------------------------------ 基础请求 ------------------------------ */

    /**
     * 统一请求方法。
     *
     * 身份靠两个头，分工不同：
     *  - `X-Identity` 身份名（小江 / 甜甜）—— 后端判「归属」只看它：日记能不能改、
     *    留言贴黄还是贴蓝都由它决定。还没选身份时（昵称是空的）不发，后端会退回看 body 里的 by。
     *  - `X-Device-Id` 装置 ID —— 只有算留言墙未读、以及五子棋认人用得上，
     *    所以由 [withDevice] 控制，默认不发，免得把装置标识撒得到处都是。
     */
    private fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        withDevice: Boolean = false,
    ): JSONObject {
        val reqBody = body?.toString()?.toRequestBody(jsonType)
        val identity = prefs.nickname
        val req = Request.Builder()
            .url(prefs.baseUrl + path)
            .addHeader("X-App-Key", Prefs.APP_KEY)
            .apply { if (identity.isNotBlank()) addHeader("X-Identity", encodeIdentity(identity)) }
            .apply { if (withDevice) addHeader("X-Device-Id", prefs.deviceId) }
            .method(method, reqBody)
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = try {
                    JSONObject(text).optString("error")
                } catch (_: Exception) {
                    ""
                }.ifBlank { "请求失败（HTTP ${resp.code}）" }
                throw ApiException(msg)
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    /* -------------------------------- 任务 -------------------------------- */

    fun listGameRecords(): Map<GameKind, GameRecord> {
        val records = call("GET", "/api/game-records").getJSONArray("records")
        val result = GameKind.entries.associateWith { GameRecord() }.toMutableMap()
        for (index in 0 until records.length()) {
            val row = records.getJSONObject(index)
            val kind = GameKind.entries.firstOrNull { it.name.equals(row.getString("game"), true) } ?: continue
            result[kind] = GameRecord(row.getInt("score"), row.getString("owner"))
        }
        return result
    }

    fun submitGameRecord(kind: GameKind, record: GameRecord) {
        call("POST", "/api/game-records", JSONObject()
            .put("game", kind.name.lowercase()).put("score", record.score).put("by", record.owner))
    }

    fun listTasks(): List<TaskItem> {
        val res = call("GET", "/api/tasks")
        val arr = res.getJSONArray("tasks")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TaskItem(
                id = o.getString("id"),
                title = o.getString("title"),
                note = o.optString("note", ""),
                done = o.getInt("done") == 1,
                doneBy = o.optString("done_by").takeIf { it.isNotBlank() },
                doneAt = o.optLong("done_at").takeIf { o.has("done_at") && !o.isNull("done_at") },
                createdBy = o.optString("created_by", ""),
                createdAt = o.optLong("created_at"),
            )
        }
    }

    fun addTask(title: String, note: String, by: String) {
        call(
            "POST",
            "/api/tasks",
            JSONObject().put("title", title).put("note", note).put("by", by),
        )
    }

    fun setTaskDone(id: String, done: Boolean, by: String) {
        call(
            "PATCH",
            "/api/tasks/$id",
            JSONObject().put("done", done).put("by", by),
        )
    }

    fun deleteTask(id: String) {
        call("DELETE", "/api/tasks/$id")
    }

    /* -------------------------------- 相册 -------------------------------- */

    fun listPhotos(): List<PhotoItem> {
        val res = call("GET", "/api/photos")
        val arr = res.getJSONArray("photos")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PhotoItem(
                id = o.getString("id"),
                caption = o.optString("caption", ""),
                uploadedBy = o.optString("uploaded_by", ""),
                createdAt = o.optLong("created_at"),
            )
        }
    }

    fun uploadPhoto(file: File, caption: String, by: String) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("caption", caption)
            .addFormDataPart("by", by)
            .addFormDataPart(
                "file",
                "photo.jpg",
                file.asRequestBody("image/jpeg".toMediaType()),
            )
            .build()

        val identity = prefs.nickname
        val req = Request.Builder()
            .url(prefs.baseUrl + "/api/photos")
            .addHeader("X-App-Key", Prefs.APP_KEY)
            .apply { if (identity.isNotBlank()) addHeader("X-Identity", encodeIdentity(identity)) }
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw ApiException("上传失败（HTTP ${resp.code}）")
            }
        }
    }

    fun deletePhoto(id: String) {
        call("DELETE", "/api/photos/$id")
    }

    /** Coil 加载图片时带不了请求头，所以凭证拼在 URL 上 */
    fun photoUrl(id: String): String =
        prefs.baseUrl + "/api/photos/$id/raw?key=" + Prefs.APP_KEY

    /* -------------------------------- 日记 -------------------------------- */

    private fun parseDiary(o: JSONObject): DiaryEntry = DiaryEntry(
        date = o.optString("date", ""),
        deviceId = o.optString("deviceId", ""),
        mood = Mood.from(o.optString("mood")) ?: Mood.HAPPY,
        content = o.optString("content", ""),
        author = o.optString("author", ""),
        updatedAt = o.optLong("updatedAt"),
        mine = o.optBoolean("mine", false),
    )

    private fun parseDiaryList(res: JSONObject): List<DiaryEntry> {
        val arr = res.getJSONArray("entries")
        return (0 until arr.length()).map { parseDiary(arr.getJSONObject(it)) }
    }

    /** 拉整月，日历用。[month] 形如 `2026-09`。 */
    fun listDiaryMonth(month: String): List<DiaryEntry> =
        parseDiaryList(call("GET", "/api/diary?month=$month", withDevice = true))

    /** 拉某一天两个人的日记。[date] 形如 `2026-09-27`。 */
    fun listDiaryDay(date: String): List<DiaryEntry> =
        parseDiaryList(call("GET", "/api/diary/$date", withDevice = true))

    /** 写入/覆盖自己那天的那条。 */
    fun saveDiary(date: String, mood: Mood, content: String, by: String) {
        call(
            "PUT",
            "/api/diary/$date",
            JSONObject().put("mood", mood.code).put("content", content).put("by", by),
            withDevice = true,
        )
    }

    /** 删自己那天的那条；后端按 `X-Identity` 匹配，删不到对方的。 */
    fun deleteDiary(date: String) {
        call("DELETE", "/api/diary/$date", withDevice = true)
    }

    fun healthCheck() {
        call("GET", "/api/health")
    }

    /* ------------------------------- 留言墙 ------------------------------- */

    /** 拉留言列表，顺带带回未读数（后端按本机上次已读时间算）。 */
    fun listMessages(): WallFeed {
        val res = call("GET", "/api/messages", withDevice = true)
        val arr = res.getJSONArray("messages")
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            WallMessage(
                id = o.getString("id"),
                deviceId = o.optString("deviceId", ""),
                author = o.optString("author", ""),
                content = o.optString("content", ""),
                createdAt = o.optLong("createdAt"),
                mine = o.optBoolean("mine", false),
            )
        }
        return WallFeed(list, res.optInt("unread", 0))
    }

    fun postMessage(content: String, by: String) {
        call("POST", "/api/messages", JSONObject().put("content", content).put("by", by), withDevice = true)
    }

    /** 把本机已读时间推到现在，未读归零。 */
    fun markWallRead() {
        // 必须带一个空 JSON body：OkHttp 的 POST 不接受 body 为 null，
        // 否则 method() 会抛「method POST must have a request body.」，
        // 结果这个「标记已读」从加上那天起就没成功过（wall_reads 表一直是空的）。
        call("POST", "/api/messages/read", JSONObject(), withDevice = true)
    }

    /* ------------------------------ 共同设置 ------------------------------ */

    /**
     * 拉云端的昵称和纪念日。
     * 结果用来覆盖本地值——管理网页改完，App 下次刷新就能看到。
     */
    fun getSettings(): RemoteSettings {
        val res = call("GET", "/api/settings")
        val names = mutableMapOf<String, String>()
        res.optJSONObject("nicknames")?.let { obj ->
            obj.keys().forEach { k -> names[k] = obj.optString(k, "") }
        }
        val anniversary = if (res.isNull("anniversary")) null else res.optLong("anniversary")
        return RemoteSettings(names, anniversary?.takeIf { it > 0L })
    }

    /** 把自己的昵称推上去（按装置存，所以不会覆盖对方的）。 */
    fun putNickname(deviceId: String, name: String) {
        call(
            "PUT",
            "/api/settings",
            JSONObject().put("nicknames", JSONObject().put(deviceId, name)),
        )
    }

    fun putAnniversary(ts: Long) {
        call("PUT", "/api/settings", JSONObject().put("anniversary", ts))
    }

    /* -------------------------------- 五子棋 -------------------------------- */

    private fun parseGomoku(o: JSONObject): GomokuMatch = GomokuMatch(
        id = o.getString("id"),
        blackId = o.optString("blackId", ""),
        blackName = o.optString("blackName", ""),
        whiteId = if (o.isNull("whiteId")) null else o.optString("whiteId"),
        whiteName = o.optString("whiteName", ""),
        status = o.optString("status", ""),
        turn = o.optString("turn", "black"),
        moves = o.optString("moves", ""),
        winner = if (o.isNull("winner")) null else o.optString("winner"),
        createdAt = o.optLong("createdAt"),
        updatedAt = o.optLong("updatedAt"),
    )

    private fun parseGomokuStats(o: JSONObject): GomokuStats = GomokuStats(
        mine = o.optInt("mine"),
        theirs = o.optInt("theirs"),
        draws = o.optInt("draws"),
    )

    private fun parseGomokuMatch(res: JSONObject): GomokuMatch = parseGomoku(res.getJSONObject("match"))

    /**
     * 拉当前该我参与的那局（也包含「对方发起、等我接受」的邀请）+ 本机战绩。
     * 只返回最近一局——总共两个人，永远只会有「正在下的这一局」。
     */
    fun gomokuCurrent(): GomokuFeed {
        val res = call("GET", "/api/gomoku/current", withDevice = true)
        val match = if (res.isNull("match")) null else parseGomoku(res.getJSONObject("match"))
        val stats = res.optJSONObject("stats")?.let { parseGomokuStats(it) } ?: GomokuStats()
        return GomokuFeed(match, stats)
    }

    /** 发起一局，我执黑先手。已经有进行中的局就把它原样返回。 */
    fun gomokuCreate(): GomokuMatch = parseGomokuMatch(
        call("POST", "/api/gomoku", JSONObject().put("by", prefs.nickname), withDevice = true),
    )

    /** 接受对方的邀请，我执白。 */
    fun gomokuJoin(id: String): GomokuMatch = parseGomokuMatch(
        call("POST", "/api/gomoku/$id/join", JSONObject().put("by", prefs.nickname), withDevice = true),
    )

    /** 取消自己发起的邀请，或拒绝对方的邀请。只有还在等待中的局删得掉。 */
    fun gomokuCancel(id: String) {
        call("DELETE", "/api/gomoku/$id", withDevice = true)
    }

    /** 落子，[x] 是列 [y] 是行，都是 0..14。轮次和胜负由后端判定。 */
    fun gomokuMove(id: String, x: Int, y: Int): GomokuMatch = parseGomokuMatch(
        call("POST", "/api/gomoku/$id/move", JSONObject().put("x", x).put("y", y), withDevice = true),
    )

    /** 认输，对方直接获胜。 */
    fun gomokuResign(id: String): GomokuMatch = parseGomokuMatch(
        call("POST", "/api/gomoku/$id/resign", JSONObject(), withDevice = true),
    )

    /** 再来一局：黑白对调开新局（两个人都在，不需要再接受一次）。 */
    fun gomokuRematch(id: String): GomokuMatch = parseGomokuMatch(
        call("POST", "/api/gomoku/$id/rematch", JSONObject().put("by", prefs.nickname), withDevice = true),
    )

    /* ------------------------------ 认领旧数据 ------------------------------ */

    /**
     * 老版本的遗留接口，**后端现在什么都不做**，只会回一个全 0 的结果。
     *
     * 以前「归属看装置 ID」时它负责把挂在旧 ID 上的留言/日记按署名改挂到本机；
     * 现在归属直接看署名，搬不搬都一样，所以后端把它留成了空壳。
     * 客户端只在选完身份后调一次，主要是为了顺便把昵称推到云端。
     */
    fun claim(name: String): JSONObject =
        call("POST", "/api/claim", JSONObject().put("name", name), withDevice = true)
}
