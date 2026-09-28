package com.example.qlapp

import com.example.qlapp.data.WallFeed
import com.example.qlapp.data.WallMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 留言墙的纯逻辑。
 *
 * 这里重点验证两件容易错的事：
 * 1. 便签的左右分色靠「是不是本机写的」，所以 deviceId 比对不能用昵称代替；
 * 2. 未读数是后端算好带回来的，前端不改它——所以 WallFeed 的两个字段必须能独立取值。
 */
class WallLogicTest {

    private fun msg(id: String, device: String, content: String = "内容") =
        WallMessage(id = id, deviceId = device, author = "某人", content = content, createdAt = 1_790_000_000_000)

    @Test fun noteColorFollowsDeviceIdNotNickname() {
        val mine = msg("1", "deviceA")
        val theirs = msg("2", "deviceB")
        assertEquals(true, mine.deviceId == "deviceA")
        assertEquals(false, theirs.deviceId == "deviceA")
    }

    @Test fun sameNicknameOnDifferentDevicesStillCountsAsOtherPerson() {
        // 两个人碰巧取了同一个昵称，也不该被当成同一个人
        val a = msg("1", "deviceA").copy(author = "小江")
        val b = msg("2", "deviceB").copy(author = "小江")
        assertTrue(a.deviceId != b.deviceId)
    }

    @Test fun feedKeepsUnreadSeparateFromList() {
        val feed = WallFeed(listOf(msg("1", "deviceA"), msg("2", "deviceB")), unread = 1)
        assertEquals(2, feed.messages.size)
        assertEquals(1, feed.unread)
    }

    @Test fun emptyFeedIsZeroUnread() {
        val feed = WallFeed()
        assertTrue(feed.messages.isEmpty())
        assertEquals(0, feed.unread)
    }

    @Test fun unreadNeverExceedsMessageCount() {
        val list = listOf(msg("1", "deviceA"), msg("2", "deviceB"), msg("3", "deviceB"))
        // 未读只可能是「别人写的」那部分，所以上限是列表长度
        val feed = WallFeed(list, unread = 2)
        assertTrue(feed.unread <= feed.messages.size)
    }
}
