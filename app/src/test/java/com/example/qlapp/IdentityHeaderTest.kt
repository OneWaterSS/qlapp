package com.example.qlapp

import com.example.qlapp.data.encodeIdentity
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 身份头编码。
 *
 * 背景：OkHttp 的 header 值只允许 `\t` 和 `\u0020`..`\u007e`，直接把中文昵称塞进
 * `X-Identity` 会抛 `IllegalArgumentException`——也就是**每次请求都挂**。
 * 最后两个测试把这条约束钉死：编码后的值 OkHttp 收，原始中文 OkHttp 拒。
 */
class IdentityHeaderTest {

    @Test fun chineseIdentityIsPercentEncodedAsUtf8() {
        assertEquals("%E5%B0%8F%E6%B1%9F", encodeIdentity("小江"))
        assertEquals("%E7%94%9C%E7%94%9C", encodeIdentity("甜甜"))
    }

    @Test fun encodedValueIsAsciiOnly() {
        for (ch in encodeIdentity("小江 甜甜")) {
            assertTrue("出现越界字符: $ch", ch == '\t' || ch in '\u0020'..'\u007e')
        }
    }

    @Test fun spaceBecomesPercent20NotPlus() {
        // URLEncoder 是 form 语义、空格会编成 '+'，但后端用 decodeURIComponent 解，不认 '+'，必须换掉
        assertEquals("a%20b", encodeIdentity("a b"))
    }

    @Test fun asciiIdentityIsUntouched() {
        assertEquals("smokeIdA", encodeIdentity("smokeIdA"))
    }

    @Test fun okhttpAcceptsEncodedHeaderButRejectsRawChinese() {
        Request.Builder()
            .url("https://example.com")
            .addHeader("X-Identity", encodeIdentity("小江"))
            .build()

        assertThrows(IllegalArgumentException::class.java) {
            Request.Builder()
                .url("https://example.com")
                .addHeader("X-Identity", "小江")
                .build()
        }
    }
}
