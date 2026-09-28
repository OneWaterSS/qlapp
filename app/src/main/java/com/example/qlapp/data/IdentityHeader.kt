package com.example.qlapp.data

import java.net.URLEncoder

/**
 * 把身份名（小江 / 甜甜）编码成能塞进 HTTP 头的 ASCII 形式。
 *
 * 为什么必须编码：OkHttp 的 header 值只允许 `\t` 和 `\u0020`..`\u007e`，
 * 直接 `addHeader("X-Identity", "小江")` 会抛
 * `IllegalArgumentException: Unexpected char 0x5c0f at 0 in X-Identity value`，
 * 也就是**每次请求都会挂**。所以按 UTF-8 做百分号编码。
 *
 * `URLEncoder` 是 `application/x-www-form-urlencoded` 语义，会把空格编成 `+`，
 * 而 `decodeURIComponent` 不认 `+`，所以这里把 `+` 改回 `%20`。
 * 后端 `identityOf()` 用 `decodeURIComponent` 还原。
 */
internal fun encodeIdentity(name: String): String =
    URLEncoder.encode(name, "UTF-8").replace("+", "%20")
