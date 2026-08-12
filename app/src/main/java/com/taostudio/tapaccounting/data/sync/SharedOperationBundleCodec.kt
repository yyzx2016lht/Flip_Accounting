package com.taostudio.tapaccounting.data.sync

import com.google.gson.JsonParser
import com.taostudio.tapaccounting.data.sync.protocol.Operation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object SharedOperationBundleCodec {
    private const val MAX_OPERATIONS = 500
    private const val MAX_UNCOMPRESSED_BYTES = 32 * 1024 * 1024

    fun encode(operationJson: List<String>): ByteArray {
        require(operationJson.isNotEmpty() && operationJson.size <= MAX_OPERATIONS)
        val json = operationJson.joinToString(prefix = "[", postfix = "]")
        return ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(json.toByteArray(Charsets.UTF_8)) }
            output.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): List<Pair<Operation, String>>? = runCatching {
        require(bytes.size <= MAX_UNCOMPRESSED_BYTES)
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= MAX_UNCOMPRESSED_BYTES)
                output.write(buffer, 0, read)
            }
        }
        val array = JsonParser.parseString(output.toString(Charsets.UTF_8.name())).asJsonArray
        require(array.size() in 1..MAX_OPERATIONS)
        array.map { element ->
            val raw = element.toString()
            val operation = SharedOperationCodec.decode(raw) ?: error("共享操作无效")
            operation to raw
        }
    }.getOrNull()
}
