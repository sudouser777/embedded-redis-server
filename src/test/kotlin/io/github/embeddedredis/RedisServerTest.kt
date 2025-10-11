package io.github.embeddedredis
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import redis.clients.jedis.Jedis
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisServerTest {
    private lateinit var server: RedisServer
    private lateinit var jedis: Jedis
    @BeforeAll
    fun startServer() = runBlocking {
        server = RedisServer(port = 16379)
        server.start()
        delay(1000)
        jedis = Jedis("localhost", 16379)
    }
    @AfterAll
    fun stopServer() {
        jedis.close()
        server.stop()
    }
    @Test
    fun testPing() {
        val response = jedis.ping()
        Assertions.assertEquals("PONG", response)
    }
    @Test
    fun testPingWithMessage() {
        val response = jedis.ping("Hello")
        Assertions.assertEquals("Hello", response)
    }
    @Test
    fun testEcho() {
        val response = jedis.echo("Hello World")
        Assertions.assertEquals("Hello World", response)
    }
    @Test
    fun testSetAndGet() {
        jedis.set("key1", "value1")
        val value = jedis.get("key1")
        Assertions.assertEquals("value1", value)
    }
    @Test
    fun testSetWithExpiration() = runBlocking {
        jedis.setex("tempkey", 1, "tempvalue")
        Assertions.assertEquals("tempvalue", jedis.get("tempkey"))
        delay(1500)
        Assertions.assertNull(jedis.get("tempkey"))
    }
    @Test
    fun testSetNX() {
        jedis.del("nxkey")
        val result1 = jedis.setnx("nxkey", "value1")
        Assertions.assertEquals(1L, result1)
        val result2 = jedis.setnx("nxkey", "value2")
        Assertions.assertEquals(0L, result2)
        Assertions.assertEquals("value1", jedis.get("nxkey"))
    }
    @Test
    fun testDel() {
        jedis.set("delkey1", "value1")
        jedis.set("delkey2", "value2")
        val deleted = jedis.del("delkey1", "delkey2", "nonexistent")
        Assertions.assertEquals(2L, deleted)
        Assertions.assertNull(jedis.get("delkey1"))
        Assertions.assertNull(jedis.get("delkey2"))
    }
    @Test
    fun testExists() {
        jedis.set("existkey", "value")
        val exists = jedis.exists("existkey")
        Assertions.assertTrue(exists)
        val notExists = jedis.exists("nonexistent")
        Assertions.assertFalse(notExists)
    }
}
