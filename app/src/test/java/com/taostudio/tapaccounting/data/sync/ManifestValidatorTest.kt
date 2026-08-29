package com.taostudio.tapaccounting.data.sync

import com.taostudio.tapaccounting.data.sync.protocol.Manifest
import com.taostudio.tapaccounting.data.sync.protocol.ManifestMember
import com.taostudio.tapaccounting.data.sync.protocol.ManifestValidator
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ManifestValidatorTest {
    @Test
    fun `new shared book can start with only its creator`() {
        val manifest = Manifest(
            sharedBookId = "123e4567-e89b-42d3-a456-426614174000",
            name = "家庭",
            createdAt = 1L,
            members = listOf(
                ManifestMember(
                    memberId = "123e4567-e89b-42d3-a456-426614174001",
                    displayName = "小陶",
                    joinOrder = 1
                )
            )
        )

        assertTrue(ManifestValidator.validate(manifest).isValid)
    }

    @Test
    fun `existing two member manifest remains valid`() {
        val manifest = Manifest(
            sharedBookId = "123e4567-e89b-42d3-a456-426614174000",
            name = "家庭",
            createdAt = 1L,
            members = listOf(
                ManifestMember("123e4567-e89b-42d3-a456-426614174001", "小陶", 1),
                ManifestMember("123e4567-e89b-42d3-a456-426614174002", "小林", 2)
            )
        )

        assertTrue(ManifestValidator.validate(manifest).isValid)
    }

    @Test
    fun `manifest must retain exactly one creator slot`() {
        val noCreator = Manifest(
            sharedBookId = "123e4567-e89b-42d3-a456-426614174000",
            name = "家庭",
            createdAt = 1L,
            members = listOf(
                ManifestMember("123e4567-e89b-42d3-a456-426614174002", "小林", 2)
            )
        )

        assertFalse(ManifestValidator.validate(noCreator).isValid)
    }

    @Test
    fun `member cannot join before their invitation was created`() {
        val manifest = Manifest(
            sharedBookId = "123e4567-e89b-42d3-a456-426614174000",
            name = "家庭",
            createdAt = 1L,
            members = listOf(
                ManifestMember("123e4567-e89b-42d3-a456-426614174001", "小陶", 1),
                ManifestMember(
                    memberId = "123e4567-e89b-42d3-a456-426614174002",
                    displayName = "小林",
                    joinOrder = 2,
                    invitedAt = 200L,
                    joinedAt = 100L
                )
            )
        )

        assertFalse(ManifestValidator.validate(manifest).isValid)
    }

    @Test
    fun `manifest accepts at most five members`() {
        fun manifestWith(count: Int) = Manifest(
            sharedBookId = "123e4567-e89b-42d3-a456-426614174000",
            name = "家庭",
            createdAt = 1L,
            members = (1..count).map { order ->
                ManifestMember(
                    memberId = "123e4567-e89b-42d3-a456-42661417400$order",
                    displayName = "成员$order",
                    joinOrder = order
                )
            }
        )

        assertTrue(ManifestValidator.validate(manifestWith(5)).isValid)
        assertFalse(ManifestValidator.validate(manifestWith(6)).isValid)
    }
}
