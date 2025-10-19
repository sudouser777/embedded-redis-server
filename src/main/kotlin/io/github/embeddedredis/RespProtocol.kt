package io.github.embeddedredis

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * RESP2 Protocol implementation for Redis (compatible with common clients like Jedis)
 */
object RespProtocol {
    fun serialize(value: Any?): ByteArray {
        return when (value) {
            null -> "$-1\r\n".toByteArray()
            is String -> "+$value\r\n".toByteArray()
            is Int -> ":$value\r\n".toByteArray()
            is Long -> ":$value\r\n".toByteArray()
            is ByteArray -> ByteArrayOutputStream().apply {
                write("$${value.size}\r\n".toByteArray())
                write(value)
                write("\r\n".toByteArray())
            }.toByteArray()
            is List<*> -> ByteArrayOutputStream().apply {
                write("*${value.size}\r\n".toByteArray())
                value.forEach { item ->
                    write(serialize(item))
                }
            }.toByteArray()
            is RespError -> "-${value.message}\r\n".toByteArray()
            else -> serialize(value.toString())
        }
    }

    fun parse(input: InputStream): Any? {
        val first = input.read()
        if (first == -1) throw IllegalStateException("Unexpected end of stream")
        return when (val type = first.toChar()) {
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

    private fun readBulkString(reader: InputStream): Any? {
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