package com.example.qlapp.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把相册里的照片存回手机。相册原本只有「上传」一条路，下载是反方向的补齐。
 *
 * 写入位置分两条路：
 * - Android 10（API 29）及以上走 MediaStore，照片直接出现在系统相册的 Pictures/小江&甜甜，
 *   不需要任何存储权限；
 * - Android 9 及以下系统相册不认识 MediaStore 的 scoped storage，只能写公共 Pictures 目录，
 *   那条路要 WRITE_EXTERNAL_STORAGE 权限（由界面负责申请）。
 */
object PhotoDownloader {

    /** 下载完这张图要用的后缀名。上传前统一压成 JPEG，所以默认按 jpg 处理。 */
    private const val MIME = "image/jpeg"
    private const val EXTENSION = "jpg"

    /** 目标文件夹名，同时也是系统相册里的相簿名。 */
    private const val FOLDER = "小江&甜甜"

    fun needsLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** 给界面提示用的保存位置描述。 */
    fun locationLabel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "系统相册的「$FOLDER」"
        else "手机相册的 Pictures/$FOLDER"

    /**
     * 下载并保存一张照片。
     *
     * 走的是 Worker 的图片直出接口，所以不用再碰 URL 拼参数那套；
     * 失败一律抛异常，交给上层统一提示。
     */
    suspend fun download(
        context: Context,
        url: String,
        uploadedBy: String,
        createdAt: Long,
    ): String = withContext(Dispatchers.IO) {
        val bytes = fetch(url)
        val name = fileName(uploadedBy, createdAt)

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, name, bytes)
        } else {
            saveToPublicPictures(name, bytes)
        }
        uri.toString()
    }

    /* ------------------------------ 网络 ------------------------------ */

    private fun fetch(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("下载失败（HTTP $code）")
            val length = connection.contentLengthLong
            if (length > MAX_BYTES) error("图片太大，无法保存")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /* ------------------------------ 落盘 ------------------------------ */

    private fun saveViaMediaStore(context: Context, name: String, bytes: ByteArray): android.net.Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, MIME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("系统相册拒绝了这次保存")

        try {
            resolver.openOutputStream(target)?.use { it.write(bytes) }
                ?: error("无法写入相册")
        } catch (e: Exception) {
            // 写一半失败就把占位记录删掉，免得相册里留下一个打不开的空文件。
            runCatching { resolver.delete(target, null, null) }
            throw e
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(target, values, null, null)
        }
        return target
    }

    @Suppress("DEPRECATION")
    private fun saveToPublicPictures(name: String, bytes: ByteArray) {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            FOLDER,
        )
        if (!directory.exists() && !directory.mkdirs()) error("无法创建保存目录")
        // 同名文件不覆盖，改存成 xxx-2.jpg，避免同一秒连下两张时互相顶掉。
        var target = File(directory, name)
        var index = 2
        while (target.exists()) {
            target = File(directory, name.replace(".$EXTENSION", "-$index.$EXTENSION"))
            index++
        }
        target.writeBytes(bytes)
    }

    /* ------------------------------ 命名 ------------------------------ */

    /**
     * 昵称落进文件名之前的清洗：空格和路径非法字符一律换成下划线，空昵称兜底成「照片」，
     * 限长 16。抽成独立函数是为了让单测能直接打到这套规则本身，而不是自己再抄一份。
     */
    internal fun authorLabel(raw: String): String = raw.trim().ifBlank { "照片" }
        .replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")
        .take(16)

    /** 形如 「小江_2026-09-27_215330.jpg」，下载下来一眼知道是谁什么时候拍的。 */
    private fun fileName(uploadedBy: String, createdAt: Long): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())
            .format(Date(if (createdAt > 0L) createdAt else System.currentTimeMillis()))
        return "${authorLabel(uploadedBy)}_$stamp.$EXTENSION"
    }

    private const val MAX_BYTES = 32L * 1024 * 1024
}
