package com.example.qlapp.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.qlapp.data.Api
import com.example.qlapp.data.DiaryEntry
import com.example.qlapp.data.GomokuStanding
import com.example.qlapp.data.Mood
import com.example.qlapp.data.PhotoItem
import com.example.qlapp.data.Prefs
import com.example.qlapp.data.TaskItem
import com.example.qlapp.data.WallMessage
import com.example.qlapp.data.Weather
import com.example.qlapp.data.WeatherApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.qlapp.util.ImageCompress
import com.example.qlapp.util.PhotoDownloader
import com.example.qlapp.util.processPhotoBatch
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** 当前月份，格式 `2026-09`。 */
private fun currentMonth(): String = YearMonth.now().toString()

/** 在 `yyyy-MM` 上加减月份，跨年会自动进借位。 */
internal fun shiftMonth(month: String, delta: Int): String =
    YearMonth.parse(month).plusMonths(delta.toLong()).toString()

internal val DIARY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** 把日期补成 `yyyy-MM-dd`，日历里算出来的 LocalDate 直接用它转字符串。 */
internal fun LocalDate.toDiaryDate(): String = format(DIARY_DATE)

data class AlbumProgress(val action: String, val completed: Int, val total: Int)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = Prefs(app)
    val api = Api(prefs)
    private val weatherApi = WeatherApi()
    var weather by mutableStateOf<Weather?>(null)
        private set

    var tasks by mutableStateOf<List<TaskItem>>(emptyList())
        private set

    var photos by mutableStateOf<List<PhotoItem>>(emptyList())
        private set
    var albumProgress by mutableStateOf<AlbumProgress?>(null)
        private set
    var failedUploads by mutableStateOf<List<Uri>>(emptyList())
        private set
    var failedDeletes by mutableStateOf<List<String>>(emptyList())
        private set
    var downloading by mutableStateOf(false)
        private set
    private var albumRevision = 0

    /* --------------------------------- 日记 -------------------------------- */

    /** 当前月份的日记，键是 `yyyy-MM-dd`，同一天最多两条（两个人各一条）。 */
    var diaryMonth by mutableStateOf<Map<String, List<DiaryEntry>>>(emptyMap())
        private set

    /** 正在看的月份，格式 `2026-09`。 */
    var diaryCursor by mutableStateOf(currentMonth())
        private set

    /** 正在看的那天（`yyyy-MM-dd`），null 表示详情没打开。 */
    var diaryDay by mutableStateOf<String?>(null)
        private set

    var diaryDayEntries by mutableStateOf<List<DiaryEntry>>(emptyList())
        private set

    /* ------------------------------- 留言墙 ------------------------------- */

    /** 便签墙上的留言，新的在前。 */
    var messages by mutableStateOf<List<WallMessage>>(emptyList())
        private set

    /** 未读条数（比本机上次看的时间新、且不是自己留的）。 */
    var wallUnread by mutableStateOf(0)
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var notice by mutableStateOf<String?>(null)
        private set

    fun consumeError() { error = null }
    fun consumeNotice() { notice = null }

    private fun runJob(
        onDone: ((Any?) -> Unit)? = null,
        block: suspend () -> Any?,
    ) {
        viewModelScope.launch {
            busy = true
            error = null
            var result: Any? = null
            try {
                result = withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                error = e.message ?: "连不上服务器，检查下网络或后端地址"
            } finally {
                busy = false
                onDone?.invoke(result)
            }
        }
    }

    private suspend fun applyOnMain(block: () -> Unit) =
        withContext(Dispatchers.Main) { block() }

    /* ------------------------------- 拉取数据 ------------------------------ */

    fun refreshAll() = runJob {
        val revision = albumRevision
        val month = diaryCursor
        val t = api.listTasks()
        val p = api.listPhotos()
        val d = api.listDiaryMonth(month)
        val w = api.listMessages()
        // 昵称/纪念日可能被管理网页改过，跟着一起拉回来
        pullSettings()
        applyOnMain {
            tasks = t
            if (revision == albumRevision && albumProgress == null) photos = p
            if (month == diaryCursor) diaryMonth = d.groupBy { it.date }
            messages = w.messages
            wallUnread = w.unread
        }
    }

    fun refreshTasks() = runJob {
        val t = api.listTasks()
        applyOnMain { tasks = t }
    }

    fun refreshPhotos() = runJob {
        val revision = albumRevision
        val p = api.listPhotos()
        applyOnMain { if (revision == albumRevision && albumProgress == null) photos = p }
    }

    /* -------------------------------- 任务 -------------------------------- */

    fun addTask(title: String, note: String = "") = runJob {
        api.addTask(title, note, prefs.nickname)
        refreshTasks()
    }

    fun toggleTask(task: TaskItem) = runJob {
        api.setTaskDone(task.id, !task.done, prefs.nickname)
        refreshTasks()
    }

    fun removeTask(id: String) = runJob {
        api.deleteTask(id)
        refreshTasks()
    }

    /* -------------------------------- 相册 -------------------------------- */

    fun uploadPhotos(uris: List<Uri>) {
        if (albumProgress != null || uris.isEmpty()) return
        val items = uris.distinct()
        val by = prefs.nickname
        albumRevision++
        albumProgress = AlbumProgress("上传", 0, items.size)
        viewModelScope.launch {
            try {
                val result = processPhotoBatch(items, { done, total ->
                    albumProgress = AlbumProgress("上传", done, total)
                }) { uri ->
                    withContext(Dispatchers.IO) {
                        val file = ImageCompress.toCacheFile(getApplication(), uri)
                        try { api.uploadPhoto(file, "", by) } finally { file.delete() }
                    }
                }
                failedUploads = (failedUploads - items.toSet() + result.failed).distinct()
                notice = "已上传 ${result.succeeded.size} 张" +
                    if (result.failed.isEmpty()) "照片" else "，${result.failed.size} 张失败，可重试"
                try { photos = withContext(Dispatchers.IO) { api.listPhotos() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "上传结果已保存，相册刷新失败，请稍后刷新" }
            } finally { albumProgress = null }
        }
    }

    fun removePhotos(ids: List<String>) {
        if (albumProgress != null || ids.isEmpty()) return
        val items = ids.distinct()
        albumRevision++
        albumProgress = AlbumProgress("删除", 0, items.size)
        viewModelScope.launch {
            try {
                val result = processPhotoBatch(items, { done, total ->
                    albumProgress = AlbumProgress("删除", done, total)
                }) { id ->
                    withContext(Dispatchers.IO) { api.deletePhoto(id) }
                    photos = photos.filterNot { it.id == id }
                }
                failedDeletes = (failedDeletes - items.toSet() + result.failed).distinct()
                notice = "已删除 ${result.succeeded.size} 张" +
                    if (result.failed.isEmpty()) "照片" else "，${result.failed.size} 张失败，可重试"
            } finally { albumProgress = null }
        }
    }

    /* -------------------------------- 下载 -------------------------------- */

    /**
     * 单张下载，用在大图预览里的「保存到手机」。
     * 走 runJob（而不是自己开协程）是为了复用它那套 busy 指示和错误提示：
     * 失败时会弹 message，成功时给个 notice 说明存到哪了。
     * onDone 拿到的是保存后的 uri；失败时为 null，界面靠它决定要不要点亮「已保存」。
     */
    fun downloadPhoto(photo: PhotoItem, onDone: ((Any?) -> Unit)? = null) = runJob(onDone) {
        val target = PhotoDownloader.download(
            getApplication(),
            api.photoUrl(photo.id),
            photo.uploadedBy,
            photo.createdAt,
        )
        notice = "已保存到${PhotoDownloader.locationLabel()}"
        target
    }

    /**
     * 批量下载选中的照片。
     * 单张失败继续下一张，最后汇总一句「成功 N 张，失败 M 张」，
     * 不因为中间某一张 404 就让整批断掉。
     */
    fun downloadPhotos(photos: List<PhotoItem>) {
        if (downloading || photos.isEmpty()) return
        val app = getApplication<Application>()
        val items = photos.distinctBy { it.id }
        downloading = true
        viewModelScope.launch {
            try {
                val result = processPhotoBatch(items, { done, total ->
                    albumProgress = AlbumProgress("保存到手机", done, total)
                }) { photo ->
                    PhotoDownloader.download(app, api.photoUrl(photo.id), photo.uploadedBy, photo.createdAt)
                }
                notice = "已保存 ${result.succeeded.size} 张到${PhotoDownloader.locationLabel()}" +
                    if (result.failed.isEmpty()) "" else "，${result.failed.size} 张失败"
            } finally {
                albumProgress = null
                downloading = false
            }
        }
    }

    /* -------------------------------- 日记 -------------------------------- */

    private fun reloadDiaryMonth() = runJob {
        val month = diaryCursor
        val list = api.listDiaryMonth(month)
        // 切月份时用户可能已经点去别的月了，晚到的响应直接丢掉。
        applyOnMain {
            if (month == diaryCursor) diaryMonth = list.groupBy { it.date }
        }
    }

    /** 上/下个月，[delta] 为 ±1。 */
    fun shiftDiaryMonth(delta: Int) {
        diaryCursor = shiftMonth(diaryCursor, delta)
        diaryMonth = emptyMap()
        reloadDiaryMonth()
    }

    /** 直接跳到某个月，用于「回今天」。 */
    fun goToDiaryMonth(month: String) {
        if (month == diaryCursor) return
        diaryCursor = month
        diaryMonth = emptyMap()
        reloadDiaryMonth()
    }

    /** 打开某天的详情。 */
    fun openDiaryDay(date: String) {
        diaryDay = date
        diaryDayEntries = diaryMonth[date].orEmpty()
        runJob {
            val list = api.listDiaryDay(date)
            applyOnMain { if (diaryDay == date) diaryDayEntries = list }
        }
    }

    fun closeDiaryDay() {
        diaryDay = null
        diaryDayEntries = emptyList()
    }

    /** 写或改自己那天的那条。 */
    fun saveDiary(date: String, mood: Mood, content: String) = runJob {
        api.saveDiary(date, mood, content, prefs.nickname)
        reloadDiaryMonth()
        if (diaryDay == date) diaryDayEntries = api.listDiaryDay(date)
        notice = "日记已保存"
    }

    /** 删自己那天的那条。 */
    fun removeDiary(date: String) = runJob {
        api.deleteDiary(date)
        reloadDiaryMonth()
        if (diaryDay == date) diaryDayEntries = api.listDiaryDay(date)
        notice = "日记已删除"
    }

    /* ------------------------------- 留言墙 ------------------------------- */

    /** 拉留言列表（不改已读状态，只有 openWall 会标记已读）。 */
    fun refreshMessages() = runJob {
        val feed = api.listMessages()
        applyOnMain {
            messages = feed.messages
            wallUnread = feed.unread
        }
    }

    /**
     * 留一条。
     * 发完立刻重拉列表，而不是往本地塞一条凑合——后端才是唯一事实，
     * 省得两边的排序规则不一致。
     */
    fun postMessage(content: String) = runJob {
        api.postMessage(content, prefs.nickname)
        val feed = api.listMessages()
        applyOnMain {
            messages = feed.messages
            wallUnread = feed.unread
        }
    }

    /**
     * 打开留言墙：先拉最新的，再把已读时间推到现在。
     *
     * 两件事放在**同一个协程**里顺序执行，而不是调用方接连调两个方法——
     * 那样 markWallRead 会在列表还没回来时就读 wallUnread，
     * 读到的是旧值（多半是 0），角标就永远清不掉了。
     */
    fun openWall() {
        viewModelScope.launch {
            busy = true
            try {
                val feed = withContext(Dispatchers.IO) { api.listMessages() }
                messages = feed.messages
                wallUnread = feed.unread
                if (feed.unread > 0) {
                    // 本地先清零让角标立刻消失，再去后端落一笔。
                    // 后端失败也无所谓，下次进来还会重试，只是角标会再亮一次。
                    wallUnread = 0
                    withContext(Dispatchers.IO) { api.markWallRead() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                error = e.message ?: "连不上服务器，检查下网络或后端地址"
            } finally {
                busy = false
            }
        }
    }

    /* -------------------------------- 五子棋 -------------------------------- */

    /**
     * 游戏 Tab 上的红点：对方发来邀请，或者轮到我落子。
     *
     * 只拉 `/api/gomoku/current` 这一条轻接口（见 AppRoot，5 秒一次），
     * 比整页刷新便宜得多，也不打扰正在做的事。
     */
    var gomokuAlert by mutableStateOf(false)
        private set

    /** 游戏首页入口卡上的一句话提示。 */
    var gomokuHint by mutableStateOf<String?>(null)
        private set

    /** 入口卡上的累计胜场排行，顺序固定为 小江、甜甜。 */
    var gomokuStanding by mutableStateOf(GomokuStanding())
        private set

    fun refreshGomokuAlert() {
        viewModelScope.launch {
            val feed = try {
                withContext(Dispatchers.IO) { api.gomokuCurrent() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 网络不好不该弹错，红点保持上一次的状态就行
                return@launch
            }
            val m = feed.match
            val me = prefs.deviceId
            val iAmBlack = m?.blackId == me
            val iAmWhite = m?.whiteId == me
            val myTurn = m != null && m.status == "playing" &&
                ((iAmBlack && m.turn == "black") || (iAmWhite && m.turn == "white"))
            // 等待中的局只有「不是我发起的」才会推给我，所以没在局里就是被邀请了
            val invited = m != null && m.status == "waiting" && !iAmBlack && !iAmWhite

            gomokuAlert = invited || myTurn
            gomokuHint = when {
                invited -> "对方邀请你对局"
                myTurn -> "轮到你落子"
                m?.status == "playing" -> "等对方落子"
                m?.status == "waiting" && iAmBlack -> "等对方接受邀请"
                else -> null
            }
            // 战绩只统计五子棋已分胜负的局，和入口卡的提示一样每 5 秒刷一次
            gomokuStanding = buildGomokuStanding(
                myName = prefs.nickname,
                mine = feed.stats.mine,
                theirs = feed.stats.theirs,
            )
        }
    }

    /* ------------------------------ 本地设置 ------------------------------ */

    /**
     * 昵称和纪念日现在云端也有一份（管理网页要能改），所以每次刷新都拉一次。
     *
     * 方向是**后端覆盖本地**：谁在网页上改了，这边下次刷新就能看到。
     * 反方向（本地改完推后端）在 chooseIdentity / claimOldData / setAnniversary 里做。
     *
     * 失败就静默忽略——网络不好不该拦住整个刷新，本地旧值还能用。
     */
    private suspend fun pullSettings() {
        val s = try {
            api.getSettings()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        applyOnMain {
            s.nicknameFor(prefs.deviceId)?.let { if (it.isNotBlank()) prefs.nickname = it }
            s.anniversary?.let { if (it > 0L) prefs.anniversary = it }
        }
    }

    /**
     * 第一次进软件时认领身份（小江 / 甜甜）：写昵称 + 标记已选，之后不再弹身份选择页。
     * 顺手把以前挂在旧装置 ID 上的留言/日记认回来。
     */
    fun chooseIdentity(name: String) {
        prefs.identityChosen = true
        prefs.nickname = name
        claimOldData()
    }

    /**
     * 选完身份后跑一次：把昵称推到云端（管理网页要能看到），再刷新一遍。
     *
     * 名字还叫 claim 是因为它以前真的在「按名字认领旧数据」——那时归属看装置 ID，
     * 重装换了 ID 之后旧留言/日记会显示成「对方」，得改挂回来。现在归属直接看署名，
     * 认领没有意义了，后端那个接口也留成了空壳（只会回全 0），这里只当一次性初始化用。
     */
    fun claimOldData() {
        val name = prefs.nickname
        if (name.isBlank()) return
        runJob(onDone = { refreshAll() }) {
            val res = api.claim(name)
            prefs.claimDone = true
            // 昵称推到云端（管理网页要能看到）。失败不影响认领结果，所以吞掉异常。
            try {
                api.putNickname(prefs.deviceId, name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
            val claimed = res.optInt("messages", 0) + res.optInt("diaryClaimed", 0)
            if (claimed > 0) notice = "已把 $claimed 条旧记录认领回来"
            claimed
        }
    }

    fun setAnniversary(ts: Long) {
        prefs.anniversary = ts
        runJob { api.putAnniversary(ts) }
    }

    fun loadWeather(lat: Double, lon: Double) = runJob {
        val value = weatherApi.fetch(lat, lon)
        applyOnMain { weather = value }
    }
}
