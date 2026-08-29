package com.taostudio.tapaccounting.data.sync

import com.taostudio.tapaccounting.data.sync.protocol.ManifestMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SharedInvitePolicyTest {
    @Test
    fun `creator invitation reserves the next member slot without assigning a name`() {
        val creator = ManifestMember(
            memberId = "123e4567-e89b-42d3-a456-426614174001",
            displayName = "小陶",
            joinOrder = 1
        )

        val invited = SharedInvitePolicy.newMember(
            existingMembers = listOf(creator),
            memberId = "123e4567-e89b-42d3-a456-426614174002",
            invitedAt = 100L
        )

        assertEquals(2, invited.joinOrder)
        assertEquals("", invited.displayName)
        assertEquals(100L, invited.invitedAt)
        assertEquals(null, invited.joinedAt)
    }

    @Test
    fun `only the creator can invite another member`() {
        assertTrue(SharedInvitePolicy.canCreateInvite(localJoinOrder = 1))
        assertFalse(SharedInvitePolicy.canCreateInvite(localJoinOrder = 2))
        assertFalse(SharedInvitePolicy.canCreateInvite(localJoinOrder = 5))
    }

    @Test
    fun `shared book cannot reserve more than five member slots`() {
        val members = (1..5).map { order ->
            ManifestMember(
                memberId = "123e4567-e89b-42d3-a456-42661417400$order",
                displayName = "成员$order",
                joinOrder = order
            )
        }

        assertThrows(IllegalStateException::class.java) {
            SharedInvitePolicy.newMember(
                existingMembers = members,
                memberId = "123e4567-e89b-42d3-a456-426614174099"
            )
        }
    }

    @Test
    fun `joining member chooses their own display name`() {
        val legacyInviteMember = ManifestMember(
            memberId = "123e4567-e89b-42d3-a456-426614174002",
            displayName = "邀请方以前填写的名字",
            joinOrder = 2
        )

        val joined = SharedInvitePolicy.joinWithName(legacyInviteMember, "  小林  ")

        assertEquals("小林", joined.displayName)
    }

    @Test
    fun `new invitation can only be claimed once`() {
        val pending = ManifestMember(
            memberId = "123e4567-e89b-42d3-a456-426614174002",
            displayName = "",
            joinOrder = 2,
            invitedAt = 100L
        )

        val joined = SharedInvitePolicy.joinWithName(pending, "小林", joinedAt = 200L)

        assertEquals(200L, joined.joinedAt)
        assertThrows(IllegalArgumentException::class.java) {
            SharedInvitePolicy.joinWithName(joined, "另一个设备", joinedAt = 300L)
        }
    }

    @Test
    fun `only a pending new invitation can be cancelled`() {
        val pending = ManifestMember(
            memberId = "123e4567-e89b-42d3-a456-426614174002",
            displayName = "",
            joinOrder = 2,
            invitedAt = 100L
        )
        val joined = pending.copy(displayName = "小林", joinedAt = 200L)
        val legacy = pending.copy(invitedAt = null)

        assertTrue(SharedInvitePolicy.canCancelInvite(pending))
        assertFalse(SharedInvitePolicy.canCancelInvite(joined))
        assertFalse(SharedInvitePolicy.canCancelInvite(legacy))
    }
}
