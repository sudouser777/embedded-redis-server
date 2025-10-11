package io.github.embeddedredis
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
/**
 * In-memory data store with TTL support
 */
class DataStore {
    private val data = ConcurrentHashMap<String, Value>()
    private val expirationQueue = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    init {
        scope.launch {
            while (isActive) {
                delay(100)
                cleanupExpired()
            }
        }
    }
    fun set(key: String, value: String, expirationMs: Long? = null, nx: Boolean = false, xx: Boolean = false): Boolean {
        if (nx && data.containsKey(key)) {
            return false
        }
        if (xx && !data.containsKey(key)) {
            return false
        }
        val expiresAt = if (expirationMs != null) {
            System.currentTimeMillis() + expirationMs
        } else {
            null
        }
        data[key] = Value(value, expiresAt)
        if (expiresAt != null) {
            expirationQueue[key] = expiresAt
        } else {
            expirationQueue.remove(key)
        }
        return true
    }
    fun get(key: String): String? {
        val value = data[key] ?: return null
        if (value.expiresAt != null && System.currentTimeMillis() > value.expiresAt) {
            data.remove(key)
            expirationQueue.remove(key)
            return null
        }
        return value.data
    }
    fun del(vararg keys: String): Int {
        var count = 0
        keys.forEach { key ->
            if (data.remove(key) != null) {
                expirationQueue.remove(key)
                count++
            }
        }
        return count
    }
    fun exists(vararg keys: String): Int {
        var count = 0
        keys.forEach { key ->
            val value = data[key]
            if (value != null) {
                if (value.expiresAt == null || System.currentTimeMillis() <= value.expiresAt) {
                    count++
                } else {
                    data.remove(key)
                    expirationQueue.remove(key)
                }
            }
        }
        return count
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
    fun shutdown() {
        scope.cancel()
    }
    data class Value(val data: String, val expiresAt: Long?)
}
