package io.github.embeddedredis

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory data store with TTL and composite value support.
 */
class DataStore {
    private val data = ConcurrentHashMap<String, Value>()
    private val expirationQueue = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        scope.launch {
            while (true) {
                delay(100)
                cleanupExpired()
            }
        }
    }

    fun set(key: String, value: String, expirationMs: Long? = null, nx: Boolean = false, xx: Boolean = false): Boolean {
        val current = getValue(key)
        if (nx && current != null) {
            return false
        }
        if (xx && current == null) {
            return false
        }
        val expiresAt = expirationMs?.let { System.currentTimeMillis() + it }
        val storedValue = Value(StoredValue.StringValue(value), expiresAt)
        putValue(key, storedValue)
        return true
    }

    fun get(key: String): String? {
        val value = getValue(key) ?: return null
        return when (val stored = value.data) {
            is StoredValue.StringValue -> stored.content
            is StoredValue.HashValue -> throw WrongTypeException()
        }
    }

    fun del(vararg keys: String): Int {
        var count = 0
        keys.forEach { key ->
            val value = getValue(key)
            if (value != null) {
                if (data.remove(key) != null) {
                    expirationQueue.remove(key)
                    count++
                }
            } else {
                data.remove(key)
                expirationQueue.remove(key)
            }
        }
        return count
    }

    fun exists(vararg keys: String): Int {
        var count = 0
        keys.forEach { key ->
            if (getValue(key) != null) {
                count++
            }
        }
        return count
    }

    fun hset(key: String, fieldValuePairs: Map<String, String>): Long {
        if (fieldValuePairs.isEmpty()) return 0

        var added = 0L
        data.compute(key) { _, existing ->
            val now = System.currentTimeMillis()
            val target = when {
                existing == null -> Value(StoredValue.HashValue(ConcurrentHashMap()), null)
                isExpired(existing, now) -> {
                    expirationQueue.remove(key)
                    Value(StoredValue.HashValue(ConcurrentHashMap()), null)
                }
                existing.data is StoredValue.HashValue -> existing
                else -> throw WrongTypeException()
            }
            val hashEntries = (target.data as StoredValue.HashValue).entries
            fieldValuePairs.forEach { (field, fieldValue) ->
                val previous = hashEntries.put(field, fieldValue)
                if (previous == null) {
                    added++
                }
            }
            synchronizeExpiration(key, target)
            target
        }
        return added
    }

    fun hsetnx(key: String, field: String, value: String): Long {
        var added = 0L
        data.compute(key) { _, existing ->
            val now = System.currentTimeMillis()
            val target = when {
                existing == null -> Value(StoredValue.HashValue(ConcurrentHashMap()), null)
                isExpired(existing, now) -> {
                    expirationQueue.remove(key)
                    Value(StoredValue.HashValue(ConcurrentHashMap()), null)
                }
                existing.data is StoredValue.HashValue -> existing
                else -> throw WrongTypeException()
            }
            val hashEntries = (target.data as StoredValue.HashValue).entries
            if (!hashEntries.containsKey(field)) {
                hashEntries[field] = value
                added = 1L
            }
            synchronizeExpiration(key, target)
            target
        }
        return added
    }

    fun hget(key: String, field: String): String? {
        val hash = getHashValue(key) ?: return null
        return hash.entries[field]
    }

    fun hmget(key: String, fields: List<String>): List<String?> {
        val hash = getHashValue(key)
        return fields.map { field -> hash?.entries?.get(field) }
    }

    fun hincrBy(key: String, field: String, increment: Long): Long {
        var result = 0L
        data.compute(key) { _, existing ->
            val now = System.currentTimeMillis()
            val target = when {
                existing == null -> Value(StoredValue.HashValue(ConcurrentHashMap()), null)
                isExpired(existing, now) -> {
                    expirationQueue.remove(key)
                    Value(StoredValue.HashValue(ConcurrentHashMap()), null)
                }
                existing.data is StoredValue.HashValue -> existing
                else -> throw WrongTypeException()
            }
            val hashEntries = (target.data as StoredValue.HashValue).entries
            val current = hashEntries[field]?.toLongOrNull()
                ?: if (hashEntries.containsKey(field)) {
                    throw IllegalArgumentException("hash value is not an integer")
                } else {
                    0L
                }
            val updated = Math.addExact(current, increment)
            hashEntries[field] = updated.toString()
            result = updated
            synchronizeExpiration(key, target)
            target
        }
        return result
    }

    fun shutdown() {
        scope.cancel()
    }

    private fun getValue(key: String): Value? {
        val value = data[key] ?: return null
        val now = System.currentTimeMillis()
        return if (isExpired(value, now)) {
            data.remove(key)
            expirationQueue.remove(key)
            null
        } else {
            value
        }
    }

    private fun getHashValue(key: String): StoredValue.HashValue? {
        val value = getValue(key) ?: return null
        return when (val stored = value.data) {
            is StoredValue.HashValue -> stored
            is StoredValue.StringValue -> throw WrongTypeException()
        }
    }

    private fun putValue(key: String, value: Value) {
        data[key] = value
        synchronizeExpiration(key, value)
    }

    private fun synchronizeExpiration(key: String, value: Value) {
        if (value.expiresAt != null) {
            expirationQueue[key] = value.expiresAt
        } else {
            expirationQueue.remove(key)
        }
    }

    private fun isExpired(value: Value, now: Long = System.currentTimeMillis()): Boolean {
        val expiresAt = value.expiresAt ?: return false
        return now > expiresAt
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val iterator = expirationQueue.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now > entry.value) {
                data.remove(entry.key)
                iterator.remove()
            }
        }
    }

    data class Value(val data: StoredValue, val expiresAt: Long?)

    sealed interface StoredValue {
        data class StringValue(val content: String) : StoredValue
        data class HashValue(val entries: ConcurrentHashMap<String, String>) : StoredValue
    }

    companion object {
        const val WRONG_TYPE_ERROR_MESSAGE = "WRONGTYPE Operation against a key holding the wrong kind of value"
    }
}

class WrongTypeException(message: String = DataStore.WRONG_TYPE_ERROR_MESSAGE) : RuntimeException(message)