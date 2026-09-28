package com.example.qlapp.data

import android.content.Context
import android.provider.Settings
import com.example.qlapp.BuildConfig
import java.security.MessageDigest
import java.util.UUID

class Prefs(context: Context) {

    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences("qlapp", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = DEFAULT_BASE_URL
        set(_) = Unit

    /** 本地昵称，会跟着任务和照片一起存到云端 */
    var nickname: String
        get() = sp.getString(KEY_NICKNAME, null).orEmpty()
        set(value) = sp.edit().putString(KEY_NICKNAME, value).apply()

    /**
     * 有没有认领过身份（小江 / 甜甜）。
     *
     * 第一次进软件必须二选一，选完置 true，之后不再弹选择页。
     * 不能只靠「昵称是否为空」来判断：老版本装上来的人本地早就有昵称了，
     * 只看昵称的话他们永远看不到这个选择页。
     */
    var identityChosen: Boolean
        get() = sp.getBoolean(KEY_IDENTITY_CHOSEN, false)
        set(value) = sp.edit().putBoolean(KEY_IDENTITY_CHOSEN, value).apply()

    /**
     * 在一起的那天，0 表示还没设置。
     *
     * 存在本地（管理页可以改），没存过就回落到内置的默认日期，
     * 这样老版本装上来的人不会因为升级突然看到「还没设置」。
     */
    var anniversary: Long
        get() = sp.getLong(KEY_ANNIVERSARY, ANNIVERSARY)
        set(value) = sp.edit().putLong(KEY_ANNIVERSARY, value).apply()

    /**
     * 本机装置标识。
     *
     * 用系统级的 ANDROID_ID 派生（同签名 + 同设备 + 同用户下不会变），
     * 所以**重装或更新 App 都不会换 ID**。
     *
     * ⚠️ 它**只用来记「这条内容写在哪儿」和算留言墙未读**，不参与「是不是我写的」——
     * 归属看身份名（`X-Identity`）。原因：装置 ID 重装不变，一台手机上换过身份之后
     * 两个人的内容会落到同一个 ID 上，拿它判归属必然串（踩过，见后端 diary 表注释）。
     * 取不到 ANDROID_ID 时才退回本地随机值。
     */
    val deviceId: String
        get() {
            stableId?.let { return it }
            sp.getString(KEY_DEVICE_ID, null)?.let { return it }
            val generated = UUID.randomUUID().toString().replace("-", "")
            sp.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }

    /** ANDROID_ID 的哈希，算一次就够。拿不到（极少数机型）返回 null。 */
    private val stableId: String? by lazy {
        val raw = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        // 9774d56d682e549c 是老设备上出了名的「大家都是一个值」，撞上它还不如用随机 UUID
        if (raw.isNullOrBlank() || raw == "9774d56d682e549c") return@lazy null
        MessageDigest.getInstance("SHA-256").digest("qlapp:$raw".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }

    /**
     * 有没有把旧数据认领到本机。
     *
     * 重装换 ID 后，以前发的留言/日记会挂在旧 ID 上、认不出是本机的，
     * 所以选完身份后按名字认领一次（见 Api.claim），认领完置 true 不再重复。
     */
    var claimDone: Boolean
        get() = sp.getBoolean(KEY_CLAIM_DONE, false)
        set(value) = sp.edit().putBoolean(KEY_CLAIM_DONE, value).apply()

    companion object {
        /**
         * 后端地址。来自项目根目录的 `keys.properties`（在 .gitignore 里，不进版本库），
         * 值由 `node backend/setup.mjs` 部署完打印出来。
         */
        val DEFAULT_BASE_URL: String = BuildConfig.BASE_URL

        /**
         * 在一起的那天，本地没存过时的兜底值。同样来自 `keys.properties`，
         * 管理后台也能随时改（改的是云端 settings，会覆盖这个默认值）。
         */
        val ANNIVERSARY: Long = BuildConfig.ANNIVERSARY

        /**
         * 共享密钥，必须和后端 Cloudflare secret 里的 `APP_KEY` 完全一致，
         * 否则所有接口都是 401。同样从 `keys.properties` 读，不写死在源码里。
         */
        val APP_KEY: String = BuildConfig.APP_KEY

        private const val KEY_BASE_URL = "base_url"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_IDENTITY_CHOSEN = "identity_chosen"
        private const val KEY_CLAIM_DONE = "claim_done"
        private const val KEY_ANNIVERSARY = "anniversary"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
