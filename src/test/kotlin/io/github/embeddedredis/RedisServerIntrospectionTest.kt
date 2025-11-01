package io.github.embeddedredis

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import redis.clients.jedis.Jedis
import redis.clients.jedis.exceptions.JedisDataException
import java.net.Socket

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisServerIntrospectionTest {
    private lateinit var server: RedisServer
    private lateinit var jedis: Jedis

    @BeforeAll
    fun startServer() = runBlocking {
        server = RedisServer(port = 16400)
        server.start()
        delay(1000)
        jedis = Jedis("localhost", 16400)
    }

    @AfterAll
    fun stopServer() {
        jedis.close()
        server.stop()
    }

    @Test
    fun `SETEX rejects non-positive timeouts`() {
        // 0 seconds
        org.junit.jupiter.api.Assertions.assertThrows(JedisDataException::class.java) {
            jedis.setex("bad0", 0, "v")
        }
        // negative seconds
        org.junit.jupiter.api.Assertions.assertThrows(JedisDataException::class.java) {
            jedis.setex("badneg", -1, "v")
        }
        // sanity: positive still works
        jedis.setex("ok", 1, "v")
        assertThat(jedis.get("ok")).isEqualTo("v")
    }

    @Test
    fun `COMMAND COUNT returns a positive integer`() {
        val response = sendResp(listOf("COMMAND", "COUNT"))
        // Expect RESP integer (numeric). Our parser returns Long for ":<int>".
        assertThat(response).isInstanceOf(Number::class.java)
        val count = (response as Number).toLong()
        assertThat(count).isGreaterThan(0)
    }

    private fun sendResp(command: List<Any?>): Any? {
        Socket("localhost", 16400).use { socket ->
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(RespProtocol.serialize(command))
            out.flush()
            // Read one response
            val resp = RespProtocol.parse(input)
            // Close gracefully
            try {
                out.write(RespProtocol.serialize(listOf("QUIT")))
                out.flush()
            } catch (_: Exception) {}
            return resp
        }
    }
}
