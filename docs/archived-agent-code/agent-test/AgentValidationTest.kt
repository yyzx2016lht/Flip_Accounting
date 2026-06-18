package com.taostudio.tapaccounting.chat.agent

import org.junit.Assert.*
import org.junit.Test

class AgentValidationTest {

    @Test
    fun `validation result success`() {
        val result = AgentValidationResult.success()
        assertTrue(result.valid)
        assertNull(result.errorMessage)
        assertNull(result.errorType)
    }

    @Test
    fun `validation result invalid params`() {
        val result = AgentValidationResult.invalidParams("Missing billId", listOf("billId"))
        assertFalse(result.valid)
        assertEquals("Missing billId", result.errorMessage)
        assertEquals(AgentErrorType.INVALID_PARAMS, result.errorType)
        assertEquals(listOf("billId"), result.missingParams)
    }

    @Test
    fun `validation result not found`() {
        val result = AgentValidationResult.notFound("Asset not found")
        assertFalse(result.valid)
        assertEquals("Asset not found", result.errorMessage)
        assertEquals(AgentErrorType.NOT_FOUND, result.errorType)
    }

    @Test
    fun `validation result ambiguous`() {
        val result = AgentValidationResult.ambiguous("Multiple matches")
        assertFalse(result.valid)
        assertEquals("Multiple matches", result.errorMessage)
        assertEquals(AgentErrorType.AMBIGUOUS, result.errorType)
    }

    @Test
    fun `validation result permission required`() {
        val result = AgentValidationResult.permissionRequired("Not allowed")
        assertFalse(result.valid)
        assertEquals("Not allowed", result.errorMessage)
        assertEquals(AgentErrorType.PERMISSION_REQUIRED, result.errorType)
    }
}
