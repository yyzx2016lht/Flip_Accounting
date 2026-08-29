package com.taostudio.tapaccounting.data.sync

import com.taostudio.tapaccounting.data.sync.protocol.Manifest
import com.taostudio.tapaccounting.data.sync.protocol.ManifestMember

object SharedInvitePolicy {
    const val CREATOR_JOIN_ORDER = 1

    fun canCreateInvite(localJoinOrder: Int): Boolean = localJoinOrder == CREATOR_JOIN_ORDER

    fun canCancelInvite(member: ManifestMember): Boolean =
        member.joinOrder in 2..Manifest.MAX_MEMBER_COUNT &&
            member.invitedAt != null &&
            member.joinedAt == null

    fun newMember(
        existingMembers: List<ManifestMember>,
        memberId: String,
        invitedAt: Long = System.currentTimeMillis()
    ): ManifestMember {
        val usedOrders = existingMembers.mapTo(mutableSetOf()) { it.joinOrder }
        val nextOrder = (2..Manifest.MAX_MEMBER_COUNT).firstOrNull { it !in usedOrders }
            ?: error("共享账本成员已满")
        return ManifestMember(
            memberId = memberId,
            displayName = "",
            joinOrder = nextOrder,
            invitedAt = invitedAt
        )
    }

    fun joinWithName(
        invitedMember: ManifestMember,
        displayName: String,
        joinedAt: Long = System.currentTimeMillis()
    ): ManifestMember {
        require(invitedMember.joinOrder in 2..Manifest.MAX_MEMBER_COUNT) { "创建者身份不能通过成员邀请加入" }
        if (invitedMember.invitedAt != null) {
            require(invitedMember.joinedAt == null) { "该邀请已被使用" }
        }
        val normalizedName = displayName.trim()
        require(normalizedName.isNotBlank()) { "请输入你的成员名称" }
        require(normalizedName.length <= Manifest.MAX_MEMBER_DISPLAY_NAME_LENGTH) { "成员名称过长" }
        return invitedMember.copy(displayName = normalizedName, joinedAt = joinedAt)
    }
}
