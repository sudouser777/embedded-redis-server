package io.github.embeddedredis

import java.io.InputStream
import java.io.OutputStream

/**
 * RESP3 Protocol implementation for Redis
 */
object RespProtocol {
    fun serialize(value: Any?): ByteArray {
        return when (value) {
            null -> "$-1\r\n".toByteArray()
            is String -> "+$value\r\n".toByteArray()
            is Int -> ":$value\r\n".toByteArray()
            is Long -> ":$value\r\n".toByteArray()
            is ByteArray -> "$${value.size}\r\n".toByteArray() + value + "\r\n".toByteArray()
            is List<*> -> {
                val result = StringBuilder("*${value.size}\r\n")
                value.forEach { item ->
                    result.append(String(serialize(item)))
                }
                result.toString().toByteArray()
            }

            is RespError -> "-${value.message}\r\n".toByteArray()
            else -> serialize(value.toString())
        }
    }

    fun parse(input: InputStream): Any? {
        return when (val type = input.read().toChar()) {
            '+' -> readSimpleString(input)
            '-' -> RespError(readSimpleString(input))
            ':' -> readSimpleString(input).toLong()
            '$' -> readBulkString(input)
            '*' -> readArray(input)
            else -> throw IllegalArgumentException("Unknown RESP type: $type")
        }
    }

    private fun readSimpleString(reader: InputStream): String {
        val sb = StringBuilder()
        var prev = 0.toChar()
        var current: Int
        while (reader.read().also { current = it } != -1) {
            val ch = current.toChar()
            if (prev == '\r' && ch == '\n') {
                return sb.substring(0, sb.length)
            }
            if (prev != 0.toChar()) {
                sb.append(prev)
            }
            prev = ch
        }
        throw IllegalStateException("Unexpected end of stream")
    }

    private fun readBulkString(reader: InputStream): String? {
        val lengthStr = readSimpleString(reader)
        val length = lengthStr.toInt()
        if (length == -1) {
            return null
        }
        val bytes = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val read = reader.read(bytes, totalRead, length - totalRead)
            if (read == -1) {
                throw IllegalStateException("Unexpected end of stream")
            }
            totalRead += read
        }
        reader.read()
        reader.read()
        return String(bytes)
    }

    private fun readArray(reader: InputStream): List<Any?> {
        val lengthStr = readSimpleString(reader)
        val length = lengthStr.toInt()
        if (length == -1) {
            return emptyList()
        }
        val result = mutableListOf<Any?>()
        repeat(length) {
            result.add(parse(reader))
        }
        return result
    }
}

data class RespError(val message: String)

fun OutputStream.writeResp(value: Any?) {
    write(RespProtocol.serialize(value))
    flush()
}
