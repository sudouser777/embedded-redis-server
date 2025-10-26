package io.github.embeddedredis

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DataStoreTest {

    private val subject = DataStore()

    @AfterEach
    fun tearDown() {
        subject.del("foo", "hash", "otherHash", "hashType")
    }

    @Test
    fun `set and get string value`() {
        subject.set("foo", "bar")
        assertEquals("bar", subject.get("foo"))
    }

    @Test
    fun `hset returns number of new fields created`() {
        assertEquals(2L, subject.hset("hash", mapOf("field1" to "value1", "field2" to "value2")))
        assertEquals(0L, subject.hset("hash", mapOf("field1" to "value1", "field2" to "value2")))
        assertEquals(1L, subject.hset("hash", mapOf("field1" to "value3", "field3" to "value3")))
    }

    @Test
    fun `hget returns field value`() {
        subject.hset("hash", mapOf("field1" to "value1"))
        assertEquals("value1", subject.hget("hash", "field1"))
        assertNull(subject.hget("hash", "field2"))
    }

    @Test
    fun `hsetnx sets only when field missing`() {
        // on missing key
        assertEquals(1L, subject.hsetnx("hash", "fieldA", "A"))
        assertEquals("A", subject.hget("hash", "fieldA"))
        // existing field should not overwrite
        assertEquals(0L, subject.hsetnx("hash", "fieldA", "B"))
        assertEquals("A", subject.hget("hash", "fieldA"))
        // new field should be added
        assertEquals(1L, subject.hsetnx("hash", "fieldB", "B"))
        assertEquals("B", subject.hget("hash", "fieldB"))
    }

    @Test
    fun `hmget returns values for multiple fields`() {
        subject.hset("hash", mapOf("field1" to "value1", "field2" to "value2"))
        val values = subject.hmget("hash", listOf("field1", "field2", "field3"))
        assertEquals(listOf("value1", "value2", null), values)
    }

    @Test
    fun `hincrby increments integer field`() {
        subject.hset("hash", mapOf("count" to "5"))
        assertEquals(7L, subject.hincrBy("hash", "count", 2))
        assertEquals("7", subject.hget("hash", "count"))
        assertEquals(10L, subject.hincrBy("hash", "count", 3))
    }

    @Test
    fun `hincrby initializes missing field`() {
        assertEquals(3L, subject.hincrBy("otherHash", "count", 3))
        assertEquals("3", subject.hget("otherHash", "count"))
    }

    @Test
    fun `hincrby throws for non integer value`() {
        subject.hset("hash", mapOf("field" to "foo"))
        val exception = assertThrows(IllegalArgumentException::class.java) {
            subject.hincrBy("hash", "field", 1)
        }
        assertEquals("hash value is not an integer", exception.message)
    }

    @Test
    fun `string operations against hash key throw wrong type`() {
        subject.hset("hashType", mapOf("field" to "value"))
        assertThrows(WrongTypeException::class.java) { subject.get("hashType") }
    }
}