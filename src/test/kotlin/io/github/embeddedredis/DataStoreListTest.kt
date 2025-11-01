package io.github.embeddedredis

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DataStoreListTest {

    private val store = DataStore()

    @AfterEach
    fun cleanup() {
        store.del("list", "list2", "str", "hash")
    }

    @Test
    fun `lpush and rpush maintain correct order and return new length`() {
        // LPUSH pushes to head (left)
        var len = store.lpush("list", "a")
        assertEquals(1L, len)
        len = store.lpush("list", "b", "c") // list becomes [c, b, a]
        assertEquals(3L, len)
        assertEquals(listOf("c", "b", "a"), store.lrange("list", 0, -1))

        // RPUSH pushes to tail (right)
        len = store.rpush("list", "d") // list becomes [c, b, a, d]
        assertEquals(4L, len)
        len = store.rpush("list", "e", "f") // [c, b, a, d, e, f]
        assertEquals(6L, len)
        assertEquals(listOf("c", "b", "a", "d", "e", "f"), store.lrange("list", 0, -1))
    }

    @Test
    fun `lpop and rpop on existing and missing lists`() {
        // Missing key -> null
        assertNull(store.lpop("list"))
        assertNull(store.rpop("list"))

        // Populate
        store.rpush("list", "x", "y") // [x, y]
        assertEquals("x", store.lpop("list")) // [y]
        assertEquals("y", store.rpop("list")) // [] and key removed
        assertNull(store.lpop("list"))
    }

    @Test
    fun `llen and lrange with negative indices and out of range`() {
        store.rpush("list", "a", "b", "c", "d") // [a,b,c,d]
        assertEquals(4L, store.llen("list"))
        assertEquals(0L, store.llen("missing"))

        // Full range
        assertEquals(listOf("a", "b", "c", "d"), store.lrange("list", 0, -1))
        // Subset
        assertEquals(listOf("b", "c"), store.lrange("list", 1, 2))
        // Negative start/stop (inclusive)
        assertEquals(listOf("c", "d"), store.lrange("list", -2, -1))
        // Start > stop -> empty
        assertEquals(emptyList<String>(), store.lrange("list", 3, 1))
        // Out of range -> bounded
        assertEquals(listOf("a", "b", "c", "d"), store.lrange("list", -100, 100))
    }

    @Test
    fun `ltrim reduces list and deletes key when empty`() {
        store.rpush("list", "a", "b", "c", "d") // [a,b,c,d]
        store.ltrim("list", 1, 2) // [b,c]
        assertEquals(listOf("b", "c"), store.lrange("list", 0, -1))

        // Trim to empty -> key deleted
        store.ltrim("list", 5, 10)
        assertEquals(0L, store.llen("list"))
        assertEquals(emptyList<String>(), store.lrange("list", 0, -1))
    }

    @Test
    fun `lmove handles direction combinations and missing source`() {
        // Missing source -> null
        assertNull(store.lmove("src", "dst", fromLeft = true, toLeft = true))

        // Prepare: src [a,b,c]
        store.rpush("src", "a", "b", "c")

        // LEFT -> LEFT: move 'a' to head of dst
        assertEquals("a", store.lmove("src", "dst", fromLeft = true, toLeft = true))
        assertEquals(listOf("b", "c"), store.lrange("src", 0, -1))
        assertEquals(listOf("a"), store.lrange("dst", 0, -1))

        // RIGHT -> RIGHT: move 'c' to tail of dst
        assertEquals("c", store.lmove("src", "dst", fromLeft = false, toLeft = false))
        assertEquals(listOf("b"), store.lrange("src", 0, -1))
        assertEquals(listOf("a", "c"), store.lrange("dst", 0, -1))

        // RIGHT -> LEFT: move 'b' to head of dst, src becomes empty and removed
        assertEquals("b", store.lmove("src", "dst", fromLeft = false, toLeft = true))
        assertEquals(0L, store.llen("src"))
        assertEquals(listOf("b", "a", "c"), store.lrange("dst", 0, -1))
    }

    @Test
    fun `wrong type operations throw`() {
        // String key
        store.set("str", "value")
        assertThrows(WrongTypeException::class.java) { store.lpush("str", "x") }
        assertThrows(WrongTypeException::class.java) { store.lrange("str", 0, -1) }

        // Hash key
        store.hset("hash", mapOf("f" to "v"))
        assertThrows(WrongTypeException::class.java) { store.rpush("hash", "x") }
        assertThrows(WrongTypeException::class.java) { store.lpop("hash") }
    }
}
