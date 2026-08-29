package com.taostudio.tapaccounting.data.sync

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class InviteCodecTest {
    @Test fun `round trip preserves invite without password`() {
        val invite = SharedInvite(
            "123e4567-e89b-42d3-a456-426614174000", "家庭", "https://dav.example.com/dav/", "user",
            "/shared-ledger/123e4567-e89b-42d3-a456-426614174000", "123e4567-e89b-42d3-a456-426614174001", "小陶", 2
        )
        val text = InviteCodec.encode(invite)
        assertTrue(text.startsWith(InviteCodec.PREFIX))
        assertFalse(text.contains("password", true))
        assertEquals(invite, InviteCodec.decode(text))
        assertTrue("compact invite should be short enough to share", text.length < 140)
    }

    @Test fun `custom WebDAV URL survives compact round trip`() {
        val invite = testInvite().copy(webdavUrl = "https://dav.example.com/remote.php/dav/files/user/")
        assertEquals(invite, InviteCodec.decode(InviteCodec.encode(invite)))
    }

    @Test fun `legacy JSON invite remains readable`() {
        val invite = testInvite()
        val legacy = InviteCodec.LEGACY_PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(Gson().toJson(invite).toByteArray(Charsets.UTF_8))
        assertEquals(invite, InviteCodec.decode(legacy))
    }

    @Test fun `ordinary clipboard text is ignored`() {
        assertNull(InviteCodec.decode("淘宝口令和普通文本"))
        assertNull(InviteCodec.decode("${InviteCodec.PREFIX}broken"))
    }

    @Test fun `member invitation cannot target the creator identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            InviteCodec.encode(testInvite().copy(joinOrder = 1))
        }
    }

    private fun testInvite() = SharedInvite(
        "123e4567-e89b-42d3-a456-426614174000", "家庭", "https://dav.jianguoyun.com/dav/", "user@example.com",
        "/shared-ledger/123e4567-e89b-42d3-a456-426614174000", "123e4567-e89b-42d3-a456-426614174001", "小陶", 2
    )
}
