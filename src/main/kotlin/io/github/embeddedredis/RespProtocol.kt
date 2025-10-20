package io.github.embeddedredis

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * RESP2 Protocol implementation for Redis (compatible with common clients like Jedis)
 */
object RespProtocol {
    fun serialize(value: Any?): ByteArray {
        return when (value) {
            null -> "$-1\r\n".toByteArray()
            is RespStatus -> "+${value.message}\r\n".toByteArray()
            is RespBulkString -> {
                val data = value.data
                if (data == null) {
                    "$-1\r\n".toByteArray()
                } else {
                    ByteArrayOutputStream().apply {
                        write("$${data.size}\r\n".toByteArray())
                        write(data)
                        write("\r\n".toByteArray())
                    }.toByteArray()
                }
            }
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
        return String(bytes, StandardCharsets.UTF_8)
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

// Explicit RESP response wrappers

data class RespStatus(val message: String)

data class RespBulkString(val data: ByteArray?) {
    companion object {
        fun fromString(value: String?): RespBulkString =
            if (value == null) RespBulkString(null) else RespBulkString(value.toByteArray(StandardCharsets.UTF_8))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RespBulkString) return false
        if (data === other.data) return true
        if (data == null || other.data == null) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        return data?.contentHashCode() ?: 0
    }
}

data class RespError(val message: String)

fun OutputStream.writeResp(value: Any?) {
    write(RespProtocol.serialize(value))
    flush()
}
