package com.example.qlapp.data

import android.content.Context
import com.example.qlapp.games.GameKind
import com.example.qlapp.games.GameRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class OnlineGameRecords(
    val records: Map<GameKind, GameRecord>,
    val hasCache: Boolean,
    val syncing: Boolean = false,
    val error: Boolean = false,
)

/** One application-scoped queue: leaving GameActivity never cancels an upload. */
class GameRecordsRepository private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("native_arcade", Context.MODE_PRIVATE)
    private val api = Api(Prefs(context))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Mutex()
    private val mutableState = MutableStateFlow(OnlineGameRecords(
        GameKind.entries.associateWith { kind ->
            val id = kind.name.lowercase()
            GameRecord(prefs.getInt("online_score_$id", 0), prefs.getString("online_owner_$id", null))
        }, prefs.getBoolean("online_loaded", false),
    ))
    val state = mutableState.asStateFlow()

    init {
        // Import only records that really have a saved author; never assign an
        // anonymous old record to whoever currently uses this phone.
        GameKind.entries.forEach { kind ->
            val id = kind.name.lowercase()
            val owner = prefs.getString("best_owner_$id", null)
            if (!owner.isNullOrBlank()) queue(kind, prefs.getInt("best_$id", 0), owner)
        }
    }

    @Synchronized private fun queue(kind: GameKind, score: Int, owner: String) {
        val id = kind.name.lowercase()
        if (score <= prefs.getInt("pending_score_$id", 0) || owner.isBlank()) return
        prefs.edit().putInt("pending_score_$id", score)
            .putString("pending_owner_$id", owner.trim().take(32)).apply()
    }

    fun submit(kind: GameKind, score: Int, nickname: String) {
        queue(kind, score, nickname.ifBlank { "未命名玩家" })
        requestSync()
    }

    fun requestSync() { scope.launch { sync() } }

    @Synchronized private fun pending(kind: GameKind): GameRecord? {
        val id = kind.name.lowercase()
        val points = prefs.getInt("pending_score_$id", 0)
        return if (points > prefs.getInt("uploaded_score_$id", 0))
            GameRecord(points, prefs.getString("pending_owner_$id", null)) else null
    }

    suspend fun sync() = withContext(Dispatchers.IO) {
        syncLock.withLock {
            mutableState.update { it.copy(syncing = true) }
            try {
                GameKind.entries.forEach { kind ->
                    pending(kind)?.let { record ->
                        api.submitGameRecord(kind, record)
                        // Only acknowledge the sent score. A better score queued
                        // during this request stays pending for the next sync.
                        prefs.edit().putInt("uploaded_score_${kind.name.lowercase()}", record.score).apply()
                    }
                }
                val records = api.listGameRecords()
                prefs.edit().apply {
                    records.forEach { (kind, record) ->
                        val id = kind.name.lowercase()
                        putInt("online_score_$id", record.score)
                        putString("online_owner_$id", record.owner)
                    }
                    putBoolean("online_loaded", true)
                }.apply()
                mutableState.value = OnlineGameRecords(records, hasCache = true)
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(syncing = false) }
                throw cancelled
            } catch (_: Exception) {
                // Pending scores remain persisted across offline sessions/restarts.
                mutableState.update { it.copy(syncing = false, error = true) }
            }
        }
    }

    companion object {
        @Volatile private var instance: GameRecordsRepository? = null
        fun get(context: Context): GameRecordsRepository = instance ?: synchronized(this) {
            instance ?: GameRecordsRepository(context.applicationContext).also { instance = it }
        }
    }
}
