package com.taostudio.tapaccounting.data.sync.protocol

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StrictJsonParserTest {

    @Test
    fun `parseInt accepts Int MAX`() {
        val el = JsonParser.parseString(Int.MAX_VALUE.toString())
        assertEquals(Int.MAX_VALUE, StrictJsonParser.parseInt(el, "n"))
    }

    @Test
    fun `parseInt rejects above Int MAX`() {
        val el = JsonParser.parseString("2147483648")
        assertThrows(IllegalStateException::class.java) {
            StrictJsonParser.parseInt(el, "n")
        }
    }

    @Test
    fun `parseInt rejects below Int MIN`() {
        val el = JsonParser.parseString("-2147483649")
        assertThrows(IllegalStateException::class.java) {
            StrictJsonParser.parseInt(el, "n")
        }
    }

    @Test
    fun `parseLong accepts Long MAX`() {
        val el = JsonParser.parseString(Long.MAX_VALUE.toString())
        assertEquals(Long.MAX_VALUE, StrictJsonParser.parseLong(el, "n"))
    }

    @Test
    fun `parseLong rejects above Long MAX`() {
        val el = JsonParser.parseString("9223372036854775808")
        assertThrows(IllegalStateException::class.java) {
            StrictJsonParser.parseLong(el, "n")
        }
    }

    @Test
    fun `parseLong rejects below Long MIN`() {
        val el = JsonParser.parseString("-9223372036854775809")
        assertThrows(IllegalStateException::class.java) {
            StrictJsonParser.parseLong(el, "n")
        }
    }

    @Test
    fun `parseLong rejects fractional`() {
        val el = JsonParser.parseString("1.5")
        assertThrows(IllegalStateException::class.java) {
            StrictJsonParser.parseLong(el, "n")
        }
    }

    @Test
    fun `parseInt rejects fractional`() {
        val el = JsonParser.parseString("2.7")
        assertThrows(IllegalStateException::class.java) {
            StrictJsonParser.parseInt(el, "n")
        }
    }
}
