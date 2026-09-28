package com.example.qlapp

import com.example.qlapp.util.PhotoDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载文件名的规则。文件名会直接落进系统相册，所以这里只验证两件事：
 * 特殊字符被换掉（不能让斜杠之类的字符跑进路径）、昵称为空时有兜底。
 * 直接打 PhotoDownloader.authorLabel —— 规则只有源码里那一份，改坏了这里就会红。
 */
class DownloadNameTest {

    @Test fun blankAuthorFallsBackToPlaceholder() {
        assertEquals("照片", PhotoDownloader.authorLabel(""))
        assertEquals("照片", PhotoDownloader.authorLabel("   "))
    }

    @Test fun pathSeparatorsAreReplacedSoTheFileStaysInTheAlbumFolder() {
        val name = PhotoDownloader.authorLabel("../../etc/passwd")
        assertTrue(name.none { it == '/' || it == '\\' })
        assertEquals(".._.._etc_passwd", name)
    }

    @Test fun whitespaceIsFoldedIntoUnderscores() {
        assertEquals("小_江", PhotoDownloader.authorLabel("小 江"))
        assertEquals("小_江", PhotoDownloader.authorLabel("  小 江  "))
    }

    @Test fun longNamesAreTruncatedToSixteen() {
        assertEquals(16, PhotoDownloader.authorLabel("一二三四五六七八九十一二三四五六七八九十").length)
    }
}
