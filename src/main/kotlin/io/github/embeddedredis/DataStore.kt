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
    private val msetLock = Any()

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

    fun hdel(key: String, fields: List<String>): Long {
        if (fields.isEmpty()) return 0
        var removed = 0L
        data.compute(key) { _, existing ->
            val now = System.currentTimeMillis()
            when {
                existing == null -> null
                isExpired(existing, now) -> {
                    expirations.remove(key)
                    null
                }
                existing.data is StoredValue.HashValue -> {
                    val entries = existing.data.entries
                    fields.forEach { f ->
                        if (entries.remove(f) != null) removed++
                    }
                    if (entries.isEmpty()) {
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
        return removed
    }

    fun hexists(key: String, field: String): Long {
        val hash = getHashValue(key) ?: return 0L
        return if (hash.entries.containsKey(field)) 1L else 0L
    }

    fun hlen(key: String): Long {
        val hash = getHashValue(key) ?: return 0L
        return hash.entries.size.toLong()
    }

    fun hgetAll(key: String): Map<String, String> {
        val hash = getHashValue(key) ?: return emptyMap()
        // Snapshot to avoid concurrent modification during iteration
        return HashMap(hash.entries)
    }

    fun hkeys(key: String): List<String> {
        val hash = getHashValue(key) ?: return emptyList()
        return ArrayList(hash.entries.keys)
    }

    fun hvals(key: String): List<String> {
        val hash = getHashValue(key) ?: return emptyList()
        return ArrayList(hash.entries.values)
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

    // Expiration management APIs
    /**
     * Set expiration in milliseconds on an existing key.
     * @return 1 if the timeout was set, 0 if the key does not exist.
     */
    fun expireMs(key: String, expirationMs: Long): Int {
        if (expirationMs <= 0) {
            // In Redis, non-positive expiration results in the key being deleted immediately.
            // We'll align by deleting the key if it exists.
            return if (del(key) > 0) 1 else 0
        }
        val now = System.currentTimeMillis()
        val current = getValue(key) ?: return 0
        val updated = Value(current.data, now + expirationMs)
        putValue(key, updated)
        return 1
    }

    /**
     * Remove expiration from a key.
     * @return 1 if the timeout was removed, 0 if key does not exist or had no timeout.
     */
    fun persist(key: String): Int {
        val current = getValue(key) ?: return 0
        if (current.expiresAt == null) return 0
        val updated = Value(current.data, null)
        putValue(key, updated)
        return 1
    }

    /**
     * Returns remaining TTL in milliseconds.
     * -2 if key does not exist
     * -1 if key exists but has no associated expiration
     */
    fun ttlMs(key: String): Long {
        val current = getValue(key) ?: return -2
        val expiresAt = current.expiresAt ?: return -1
        val now = System.currentTimeMillis()
        val remaining = expiresAt - now
        return if (remaining < 0) -2 else remaining
    }

    // String batch and counter operations
    fun mget(keys: List<String>): List<String?> {
        return keys.map { k -> get(k) }
    }

    /**
     * Atomic multi-set of string keys. Clears TTL on affected keys (like Redis MSET).
     */
    fun mset(pairs: Map<String, String>) {
        if (pairs.isEmpty()) return
        synchronized(msetLock) {
            pairs.forEach { (k, v) ->
                // Use set with expiration null to clear TTL
                set(k, v, expirationMs = null, nx = false, xx = false)
            }
        }
    }

    /**
     * Increment integer value stored at key by delta. Creates key with 0 if missing.
     * Preserves existing TTL (like Redis INCR/DECR family).
     */
    fun incrBy(key: String, delta: Long): Long {
        var result = 0L
        data.compute(key) { _, existing ->
            val now = System.currentTimeMillis()
            val currentValue: Long
            val expiresAt: Long?
            when {
                existing == null -> {
                    currentValue = 0L
                    expiresAt = null
                }
                isExpired(existing, now) -> {
                    expirations.remove(key)
                    currentValue = 0L
                    expiresAt = null
                }
                else -> {
                    expiresAt = existing.expiresAt // preserve TTL
                    when (val stored = existing.data) {
                        is StoredValue.StringValue -> {
                            val parsed = stored.content.toLongOrNull()
                            if (parsed == null) throw NumberFormatException()
                            currentValue = parsed
                        }
                        is StoredValue.HashValue, is StoredValue.ListValue -> throw WrongTypeException()
                    }
                }
            }
            val updated = Math.addExact(currentValue, delta)
            result = updated
            val newVal = Value(StoredValue.StringValue(updated.toString()), expiresAt)
            synchronizeExpiration(key, newVal)
            newVal
        }
        return result
    }

    /**
     * GETSET: set key to value and return previous string value (or null). Clears TTL (like Redis).
     */
    fun getSet(key: String, value: String): String? {
        var old: String? = null
        data.compute(key) { _, existing ->
            val now = System.currentTimeMillis()
            when {
                existing == null -> {
                    old = null
                    val newVal = Value(StoredValue.StringValue(value), null)
                    synchronizeExpiration(key, newVal)
                    newVal
                }
                isExpired(existing, now) -> {
                    expirations.remove(key)
                    old = null
                    val newVal = Value(StoredValue.StringValue(value), null)
                    synchronizeExpiration(key, newVal)
                    newVal
                }
                existing.data is StoredValue.StringValue -> {
                    old = existing.data.content
                    // Clear TTL when setting a new value
                    val newVal = Value(StoredValue.StringValue(value), null)
                    synchronizeExpiration(key, newVal)
                    newVal
                }
                else -> throw WrongTypeException()
            }
        }
        return old
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

    fun lindex(key: String, index: Long): String? {
        val list = getListValue(key) ?: return null
        val snapshot = ArrayList(list.items)
        if (snapshot.isEmpty()) return null
        val size = snapshot.size
        val idx = if (index < 0) (size + index).toInt() else index.toInt()
        if (idx < 0 || idx >= size) return null
        return snapshot[idx]
    }

    fun lset(key: String, index: Long, value: String) {
        var ok = false
        data.compute(key) { _, existing ->
            val now = System.currentTimeMillis()
            when {
                existing == null -> {
                    ok = false
                    null
                }
                isExpired(existing, now) -> {
                    expirations.remove(key)
                    ok = false
                    null
                }
                existing.data is StoredValue.ListValue -> {
                    val deque = existing.data.items
                    val size = deque.size
                    val idx = if (index < 0) size + index.toInt() else index.toInt()
                    if (idx < 0 || idx >= size) {
                        throw IllegalArgumentException("index out of range")
                    }
                    // Replace at index: rebuild via snapshot for simplicity
                    val snapshot = ArrayList(deque)
                    snapshot[idx] = value
                    deque.clear()
                    deque.addAll(snapshot)
                    synchronizeExpiration(key, existing)
                    ok = true
                    existing
                }
                else -> throw WrongTypeException()
            }
        }
        if (!ok) {
            // In Redis, operating on missing key yields error for LSET
            throw IllegalArgumentException("index out of range")
        }
    }

    fun lpushx(key: String, vararg values: String): Long {
        var newLen = 0L
        data.compute(key) { _, existing ->
            val now = System.currentTimeMillis()
            when {
                existing == null -> {
                    newLen = 0L
                    null
                }
                isExpired(existing, now) -> {
                    expirations.remove(key)
                    newLen = 0L
                    null
                }
                existing.data is StoredValue.ListValue -> {
                    val deque = existing.data.items
                    values.forEach { v -> deque.addFirst(v) }
                    newLen = deque.size.toLong()
                    synchronizeExpiration(key, existing)
                    existing
                }
                else -> throw WrongTypeException()
            }
        }
        return newLen
    }

    fun rpushx(key: String, vararg values: String): Long {
        var newLen = 0L
        data.compute(key) { _, existing ->
            val now = System.currentTimeMillis()
            when {
                existing == null -> {
                    newLen = 0L
                    null
                }
                isExpired(existing, now) -> {
                    expirations.remove(key)
                    newLen = 0L
                    null
                }
                existing.data is StoredValue.ListValue -> {
                    val deque = existing.data.items
                    values.forEach { v -> deque.addLast(v) }
                    newLen = deque.size.toLong()
                    synchronizeExpiration(key, existing)
                    existing
                }
                else -> throw WrongTypeException()
            }
        }
        return newLen
    }

    fun linsert(key: String, before: Boolean, pivot: String, element: String): Long {
        var result = 0L
        data.compute(key) { _, existing ->
            val now = System.currentTimeMillis()
            when {
                existing == null -> {
                    result = 0L
                    null
                }
                isExpired(existing, now) -> {
                    expirations.remove(key)
                    result = 0L
                    null
                }
                existing.data is StoredValue.ListValue -> {
                    val deque = existing.data.items
                    val snapshot = ArrayList(deque)
                    val idx = snapshot.indexOf(pivot)
                    if (idx == -1) {
                        result = -1L
                        synchronizeExpiration(key, existing)
                        existing
                    } else {
                        val insertIdx = if (before) idx else idx + 1
                        snapshot.add(insertIdx, element)
                        deque.clear()
                        deque.addAll(snapshot)
                        result = deque.size.toLong()
                        synchronizeExpiration(key, existing)
                        existing
                    }
                }
                else -> throw WrongTypeException()
            }
        }
        return result
    }

    fun lpopCount(key: String, count: Int): List<String> {
        if (count <= 0) return emptyList()
        val popped = ArrayList<String>(count)
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
                    var remaining = count
                    while (remaining > 0) {
                        val e = deque.pollFirst() ?: break
                        popped.add(e)
                        remaining--
                    }
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

    fun rpopCount(key: String, count: Int): List<String> {
        if (count <= 0) return emptyList()
        val popped = ArrayList<String>(count)
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
                    var remaining = count
                    while (remaining > 0) {
                        val e = deque.pollLast() ?: break
                        popped.add(e)
                        remaining--
                    }
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

    /**
     * LREM semantics:
     * - count > 0: remove up to count occurrences of value from head to tail
     * - count < 0: remove up to |count| occurrences of value from tail to head
     * - count = 0: remove all occurrences of value
     * Returns the number of removed elements. If list becomes empty, delete the key (and TTL).
     */
    fun lrem(key: String, count: Int, value: String): Long {
        var removed = 0L
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
                    if (deque.isEmpty()) {
                        expirations.remove(key)
                        null
                    } else {
                        when {
                            count == 0 -> {
                                // remove all occurrences
                                val it = deque.iterator()
                                while (it.hasNext()) {
                                    if (it.next() == value) {
                                        it.remove()
                                        removed++
                                    }
                                }
                            }
                            count > 0 -> {
                                var remaining = count
                                val it = deque.iterator()
                                while (it.hasNext() && remaining > 0) {
                                    if (it.next() == value) {
                                        it.remove()
                                        removed++
                                        remaining--
                                    }
                                }
                            }
                            else -> { // count < 0, from tail
                                var remaining = -count
                                if (remaining > 0) {
                                    val it = deque.descendingIterator()
                                    while (it.hasNext() && remaining > 0) {
                                        if (it.next() == value) {
                                            it.remove()
                                            removed++
                                            remaining--
                                        }
                                    }
                                }
                            }
                        }

                        if (deque.isEmpty()) {
                            expirations.remove(key)
                            null
                        } else {
                            synchronizeExpiration(key, existing)
                            existing
                        }
                    }
                }
                else -> throw WrongTypeException()
            }
        }
        return removed
    }

    /**
     * RPOPLPUSH implemented via LMOVE RIGHT->LEFT.
     * Returns moved element or null if source missing/empty.
     */
    fun rpoplpush(source: String, destination: String): String? {
        return lmove(source, destination, fromLeft = false, toLeft = true)
    }

    /**
     * SCAN implementation: stateless, cursor is the next index in a snapshot of current keys.
     * Returns nextCursor (0 when complete) and a page of keys respecting MATCH pattern and COUNT hint.
     */
    fun scan(cursor: Int, match: String?, count: Int): Pair<Int, List<String>> {
        require(count > 0) { "value is not an integer or out of range" }
        if (cursor < 0) throw IllegalArgumentException("value is not an integer or out of range")

        val now = System.currentTimeMillis()
        // Build a snapshot of keys, while lazily dropping expired ones
        val allKeys = ArrayList<String>(data.size)
        data.keys.forEach { k ->
            val v = data[k]
            if (v != null) {
                if (isExpired(v, now)) {
                    data.remove(k)
                    expirations.remove(k)
                } else {
                    allKeys.add(k)
                }
            }
        }

        val regex: Regex? = match?.let { globToRegex(it) }

        var index = if (cursor > allKeys.size) allKeys.size else cursor
        val page = ArrayList<String>(count)
        while (index < allKeys.size && page.size < count) {
            val key = allKeys[index]
            if (regex == null || regex.matches(key)) {
                page.add(key)
            }
            index++
        }

        val nextCursor = if (index >= allKeys.size) 0 else index
        return nextCursor to page
    }

    private fun globToRegex(pattern: String): Regex {
        val sb = StringBuilder()
        sb.append('^')
        var i = 0
        while (i < pattern.length) {
            when (val ch = pattern[i]) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '[' -> {
                    // Copy character class until closing ']'
                    sb.append('[')
                    i++
                    if (i < pattern.length && (pattern[i] == '!' || pattern[i] == '^')) {
                        // Redis uses [] without negation special handling; support common forms
                        sb.append('^')
                        i++
                    }
                    while (i < pattern.length && pattern[i] != ']') {
                        val c = pattern[i]
                        // Escape regex specials inside class except '-' which may denote range
                        if (c in arrayOf('\\', '^', ']')) sb.append('\\')
                        sb.append(c)
                        i++
                    }
                    sb.append(']')
                }
                '.', '(', ')', '+', '|', '^', '$', '{', '}', '\\' -> {
                    sb.append('\\').append(ch)
                }
                else -> sb.append(ch)
            }
            i++
        }
        sb.append('$')
        return Regex(sb.toString())
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