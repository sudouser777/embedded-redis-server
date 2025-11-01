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
    private val expirations = ConcurrentHashMap<String, Long>()
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
            is StoredValue.ListValue -> throw WrongTypeException()
        }
    }

    fun del(vararg keys: String): Int {
        var count = 0
        keys.forEach { key ->
            val removed = data.remove(key)
            if (removed != null) {
                expirations.remove(key)
                count++
            } else {
                expirations.remove(key)
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
            val target = ensureHash(existing, key)
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
            val target = ensureHash(existing, key)
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
            val target = ensureHash(existing, key)
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

    // List operations
    fun lpush(key: String, vararg values: String): Long {
        if (values.isEmpty()) return llen(key)
        var newLen = 0L
        data.compute(key) { _, existing ->
            val target = ensureList(existing, key)
            val deque = (target.data as StoredValue.ListValue).items
            values.forEach { v -> deque.addFirst(v) }
            newLen = deque.size.toLong()
            synchronizeExpiration(key, target)
            target
        }
        return newLen
    }

    fun rpush(key: String, vararg values: String): Long {
        if (values.isEmpty()) return llen(key)
        var newLen = 0L
        data.compute(key) { _, existing ->
            val target = ensureList(existing, key)
            val deque = (target.data as StoredValue.ListValue).items
            values.forEach { v -> deque.addLast(v) }
            newLen = deque.size.toLong()
            synchronizeExpiration(key, target)
            target
        }
        return newLen
    }

    fun lpop(key: String): String? {
        var popped: String? = null
        data.compute(key) { _, existing ->
            val now = System.currentTimeMillis()
            when {
                existing == null -> null
                isExpired(existing, now) -> {
                    expirations.remove(key)
                    null
                }
                existing.data is StoredValue.ListValue -> {
                    val deque = existing.data.items
                    popped = if (deque.isEmpty()) null else deque.pollFirst()
                    if (deque.isEmpty()) {
                        expirations.remove(key)
                        null
                    } else {
                        synchronizeExpiration(key, existing)
                        existing
                    }
                }
                else -> throw WrongTypeException()
            }
        }
        return popped
    }

    fun rpop(key: String): String? {
        var popped: String? = null
        data.compute(key) { _, existing ->
            val now = System.currentTimeMillis()
            when {
                existing == null -> null
                isExpired(existing, now) -> {
                    expirations.remove(key)
                    null
                }
                existing.data is StoredValue.ListValue -> {
                    val deque = existing.data.items
                    popped = if (deque.isEmpty()) null else deque.pollLast()
                    if (deque.isEmpty()) {
                        expirations.remove(key)
                        null
                    } else {
                        synchronizeExpiration(key, existing)
                        existing
                    }
                }
                else -> throw WrongTypeException()
            }
        }
        return popped
    }

    fun llen(key: String): Long {
        val list = getListValue(key) ?: return 0
        return list.items.size.toLong()
    }

    fun lrange(key: String, start: Long, stop: Long): List<String> {
        val list = getListValue(key) ?: return emptyList()
        val snapshot = ArrayList(list.items) // snapshot
        val size = snapshot.size
        if (size == 0) return emptyList()
        val (from, to) = computeRange(size, start, stop) ?: return emptyList()
        return snapshot.subList(from, to + 1)
    }

    fun ltrim(key: String, start: Long, stop: Long) {
        data.compute(key) { _, existing ->
            val now = System.currentTimeMillis()
            when {
                existing == null -> null
                isExpired(existing, now) -> {
                    expirations.remove(key)
                    null
                }
                existing.data is StoredValue.ListValue -> {
                    val deque = existing.data.items
                    val snapshot = ArrayList(deque)
                    val size = snapshot.size
                    val range = computeRange(size, start, stop)
                    if (range == null) {
                        expirations.remove(key)
                        null
                    } else {
                        val (from, to) = range
                        deque.clear()
                        deque.addAll(snapshot.subList(from, to + 1))
                        synchronizeExpiration(key, existing)
                        existing
                    }
                }
                else -> throw WrongTypeException()
            }
        }
    }

    private val lmoveLock = Any()

    fun lmove(source: String, destination: String, fromLeft: Boolean, toLeft: Boolean): String? {
        synchronized(lmoveLock) {
            val src = getValue(source) ?: return null
            val now = System.currentTimeMillis()
            if (isExpired(src, now)) {
                data.remove(source)
                expirations.remove(source)
                return null
            }
            val srcList = when (val d = src.data) {
                is StoredValue.ListValue -> d.items
                is StoredValue.StringValue, is StoredValue.HashValue -> throw WrongTypeException()
            }
            val elem = if (fromLeft) srcList.pollFirst() else srcList.pollLast()
            if (elem == null) {
                if (srcList.isEmpty()) {
                    data.remove(source)
                    expirations.remove(source)
                }
                return null
            }
            if (srcList.isEmpty()) {
                data.remove(source)
                expirations.remove(source)
            } else {
                // preserve TTL
                synchronizeExpiration(source, src)
            }

            // destination
            val dstExisting = data[destination]
            val dstValue = when {
                dstExisting == null -> Value(StoredValue.ListValue(java.util.ArrayDeque()), null)
                isExpired(dstExisting, now) -> {
                    data.remove(destination)
                    expirations.remove(destination)
                    Value(StoredValue.ListValue(java.util.ArrayDeque()), null)
                }
                dstExisting.data is StoredValue.ListValue -> dstExisting
                else -> throw WrongTypeException()
            }
            val dstDeque = (dstValue.data as StoredValue.ListValue).items
            if (toLeft) dstDeque.addFirst(elem) else dstDeque.addLast(elem)
            putValue(destination, dstValue)
            return elem
        }
    }

    private fun computeRange(size: Int, start: Long, stop: Long): Pair<Int, Int>? {
        var from = if (start < 0) (size + start).toInt() else start.toInt()
        var to = if (stop < 0) (size + stop).toInt() else stop.toInt()
        if (from < 0) from = 0
        if (to < 0) to = 0
        if (from >= size) return null
        if (to >= size) to = size - 1
        if (from > to) return null
        return from to to
    }

    private fun ensureHash(existing: Value?, key: String): Value {
        val now = System.currentTimeMillis()
        return when {
            existing == null -> Value(StoredValue.HashValue(ConcurrentHashMap()), null)
            isExpired(existing, now) -> {
                expirations.remove(key)
                Value(StoredValue.HashValue(ConcurrentHashMap()), null)
            }
            existing.data is StoredValue.HashValue -> existing
            else -> throw WrongTypeException()
        }
    }

    private fun ensureList(existing: Value?, key: String): Value {
        val now = System.currentTimeMillis()
        return when {
            existing == null -> Value(StoredValue.ListValue(java.util.ArrayDeque()), null)
            isExpired(existing, now) -> {
                expirations.remove(key)
                Value(StoredValue.ListValue(java.util.ArrayDeque()), null)
            }
            existing.data is StoredValue.ListValue -> existing
            else -> throw WrongTypeException()
        }
    }

    private fun getValue(key: String): Value? {
        val value = data[key] ?: return null
        val now = System.currentTimeMillis()
        return if (isExpired(value, now)) {
            data.remove(key)
            expirations.remove(key)
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
            is StoredValue.ListValue -> throw WrongTypeException()
        }
    }

    private fun getListValue(key: String): StoredValue.ListValue? {
        val value = getValue(key) ?: return null
        return when (val stored = value.data) {
            is StoredValue.ListValue -> stored
            is StoredValue.StringValue -> throw WrongTypeException()
            is StoredValue.HashValue -> throw WrongTypeException()
        }
    }

    private fun putValue(key: String, value: Value) {
        data[key] = value
        synchronizeExpiration(key, value)
    }

    private fun synchronizeExpiration(key: String, value: Value) {
        if (value.expiresAt != null) {
            expirations[key] = value.expiresAt
        } else {
            expirations.remove(key)
        }
    }

    private fun isExpired(value: Value, now: Long = System.currentTimeMillis()): Boolean {
        val expiresAt = value.expiresAt ?: return false
        return now > expiresAt
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val iterator = expirations.entries.iterator()
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
        data class ListValue(val items: java.util.ArrayDeque<String>) : StoredValue
    }

    companion object {
        const val WRONG_TYPE_ERROR_MESSAGE = "WRONGTYPE Operation against a key holding the wrong kind of value"
    }
}

class WrongTypeException(message: String = DataStore.WRONG_TYPE_ERROR_MESSAGE) : RuntimeException(message)