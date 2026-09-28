package com.example.qlapp.games

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 曲库：内置曲目 + 用户导入的曲子。
 * 导入只在当次分析音频（URI 授权一次有效），分析出的关卡以 JSON 落盘，一直有效。
 * 不保留原音频：游戏里不放原声，玩家按出的钢琴音本身就是这首歌的旋律。
 */
class SongLibrary(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "songs").also { it.mkdirs() }

    fun builtin(): List<Song> = BuiltinSongs.all()

    fun imported(): List<Song> {
        // 原声文件一律不再使用，顺手清掉（v27 起导入不再复制音频）。
        dir.listFiles { f -> f.isFile && f.name.endsWith(".audio") }?.forEach { it.delete() }
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching {
                // v2 之前是按「音色亮度」猜的假音高，弹出来都是一个调 —— 作废，重新导入才有真音高。
                if (JSONObject(file.readText()).optInt("v", 1) < 2) { file.delete(); null }
                else read(file)
            }.getOrNull()
        }.sortedByDescending { it.id }
    }

    fun all(): List<Song> = builtin() + imported()

    /** 分析出关卡 → 落盘。不复制音频：游戏不放原声，没必要占存储。 */
    fun import(uri: Uri, title: String): Song {
        val id = "import-${System.currentTimeMillis()}"
        val beats = AudioAnalyzer.analyze(context, uri)
        val song = Song(
            id = id,
            title = title.trim().ifBlank { "未命名曲子" },
            author = "我导入的",
            beats = beats,
        )
        write(song)
        return song
    }

    fun delete(song: Song) {
        if (song.builtin) return
        File(dir, "${song.id}.json").delete()
        File(dir, "${song.id}.audio").delete()
    }

    /** 从 content URI 里读原始文件名，去掉扩展名当歌名。 */
    fun guessTitle(uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        return name?.substringBeforeLast('.')?.trim().orEmpty().ifBlank { "我导入的曲子" }
    }

    /* ------------------------------ 落盘 ------------------------------ */

    private fun read(file: File): Song {
        val json = JSONObject(file.readText())
        val array = json.getJSONArray("beats")
        val beats = ArrayList<SongBeat>(array.length())
        for (i in 0 until array.length()) {
            val row = array.getJSONObject(i)
            beats.add(SongBeat(row.getInt("t"), row.getInt("c"), row.optInt("s", -1), row.optInt("h", 0)))
        }
        if (beats.isEmpty()) throw IllegalStateException("关卡是空的")
        val audio = json.optString("audio").takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
        return Song(
            id = json.getString("id"),
            title = json.getString("title"),
            author = json.optString("author", "我导入的"),
            beats = beats,
            audioPath = audio?.absolutePath,
        )
    }

    private fun write(song: Song) {
        val json = JSONObject()
            .put("id", song.id)
            .put("title", song.title)
            .put("author", song.author)
            .put("v", 2) // 关卡格式版本：2 = 自相关真音高；1 及以下是旧的假音高，读取时作废
            .put("audio", song.audioPath.orEmpty())
        val array = JSONArray()
        for (beat in song.beats) {
            array.put(JSONObject().put("t", beat.timeMs).put("c", beat.column).put("s", beat.semitone).put("h", beat.holdMs))
        }
        json.put("beats", array)
        File(dir, "${song.id}.json").writeText(json.toString())
    }
}
