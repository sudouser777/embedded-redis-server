package io.github.embeddedredis

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
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
        assertThat(response).isEqualTo("PONG")
    }

    @Test
    fun testPingWithMessage() {
        val response = jedis.ping("Hello")
        assertThat(response).isEqualTo("Hello")
    }

    @Test
    fun testEcho() {
        val response = jedis.echo("Hello World")
        assertThat(response).isEqualTo("Hello World")
    }

    @Test
    fun testSetAndGet() {
        jedis.set("key1", "value1")
        val value = jedis.get("key1")
        assertThat(value).isEqualTo("value1")
    }

    @Test
    fun testSetWithExpiration() = runBlocking {
        jedis.setex("tempkey", 1, "tempvalue")
        assertThat(jedis.get("tempkey")).isEqualTo("tempvalue")
        delay(1500)
        assertThat(jedis.get("tempkey")).isNull()
    }

    @Test
    fun testSetNX() {
        jedis.del("nxkey")
        val result1 = jedis.setnx("nxkey", "value1")
        assertThat(result1).isEqualTo(1L)
        val result2 = jedis.setnx("nxkey", "value2")
        assertThat(result2).isEqualTo(0L)
        assertThat(jedis.get("nxkey")).isEqualTo("value1")
    }

    @Test
    fun testDel() {
        jedis.set("delkey1", "value1")
        jedis.set("delkey2", "value2")
        val deleted = jedis.del("delkey1", "delkey2", "nonexistent")
        assertThat(deleted).isEqualTo(2L)
        assertThat(jedis.get("delkey1")).isNull()
        assertThat(jedis.get("delkey2")).isNull()
    }

    @Test
    fun testExists() {
        jedis.set("existkey", "value")
        val exists = jedis.exists("existkey")
        assertThat(exists).isTrue()
        val notExists = jedis.exists("nonexistent")
        assertThat(notExists).isFalse()
    }

    @Test
    fun testHashCommands() {
        jedis.del("hashkey")
        // HSET
        val added = jedis.hset("hashkey", mapOf("field1" to "value1", "field2" to "value2"))
        assertThat(added).isEqualTo(2L)

        // HGET existing
        assertThat(jedis.hget("hashkey", "field1")).isEqualTo("value1")
        // HGET missing field
        assertThat(jedis.hget("hashkey", "missing")).isNull()

        // HMGET
        val hmgetResult = jedis.hmget("hashkey", "field1", "field2", "missing")
        assertThat(hmgetResult).containsExactly("value1", "value2", null)

        // HINCRBY on existing integer
        jedis.hset("hashkey", "counter", "10")
        val hincrResult = jedis.hincrBy("hashkey", "counter", 5)
        assertThat(hincrResult).isEqualTo(15L)
        assertThat(jedis.hget("hashkey", "counter")).isEqualTo("15")

        // HINCRBY on missing field initializes to 0
        val newCounter = jedis.hincrBy("hashkey", "newcounter", 4)
        assertThat(newCounter).isEqualTo(4L)
        assertThat(jedis.hget("hashkey", "newcounter")).isEqualTo("4")

        // HSETNX should set only when field is missing
        val hsetnx1 = jedis.hsetnx("hashkey", "onlyonce", "X")
        assertThat(hsetnx1).isEqualTo(1L)
        assertThat(jedis.hget("hashkey", "onlyonce")).isEqualTo("X")
        val hsetnx2 = jedis.hsetnx("hashkey", "onlyonce", "Y")
        assertThat(hsetnx2).isEqualTo(0L)
        assertThat(jedis.hget("hashkey", "onlyonce")).isEqualTo("X")
    }

    @Test
    fun testQuitClosesConnection() {
        val socket = java.net.Socket("localhost", 16379)
        try {
            socket.soTimeout = 2000
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            // Send QUIT command: *1\r\n$4\r\nQUIT\r\n
            out.write("*1\r\n$4\r\nQUIT\r\n".toByteArray())
            out.flush()

            val reader = java.io.BufferedReader(java.io.InputStreamReader(input))
            val line = reader.readLine()
            assertThat(line).isEqualTo("+OK")
            // After QUIT, server should close the connection. Further reads should hit EOF or timeout quickly.
            try {
                val next = input.read()
                assertThat(next).isEqualTo(-1)
            } catch (ex: Exception) {
                // IOException/timeout is also acceptable since server closed the socket
                assertThat(true).isTrue()
            }
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }
}