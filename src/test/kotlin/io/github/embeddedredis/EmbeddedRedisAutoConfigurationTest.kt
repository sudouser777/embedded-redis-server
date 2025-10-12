package io.github.embeddedredis

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import redis.clients.jedis.Jedis

class EmbeddedRedisAutoConfigurationTest {

    private fun contextRunner() = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(EmbeddedRedisAutoConfiguration::class.java))

    @Test
    fun `auto configuration creates and starts server when enabled and autoStart=true`() {
        val port = 16479
        contextRunner()
            .withPropertyValues(
                "embedded.redis.enabled=true",
                "embedded.redis.port=$port",
                "embedded.redis.host=127.0.0.1",
                "embedded.redis.auto-start=true"
            )
            .run { context ->
                Assertions.assertTrue(context.containsBean("embeddedRedisServer"))
                val server = context.getBean(RedisServer::class.java)
                Assertions.assertNotNull(server)

                Jedis("127.0.0.1", port).use { jedis ->
                    val pong = jedis.ping()
                    Assertions.assertEquals("PONG", pong)
                }
            }
    }

    @Test
    fun `auto configuration creates bean but does not start when autoStart=false`() {
        val port = 16480
        contextRunner()
            .withPropertyValues(
                "embedded.redis.enabled=true",
                "embedded.redis.port=$port",
                "embedded.redis.host=127.0.0.1",
                "embedded.redis.auto-start=false"
            )
            .run { context ->
                Assertions.assertTrue(context.containsBean("embeddedRedisServer"))
                val server = context.getBean(RedisServer::class.java)
                Assertions.assertNotNull(server)

                // Attempt to connect should fail because the server is not started
                try {
                    Jedis("127.0.0.1", port).use { jedis -> jedis.ping() }
                    Assertions.fail("Connection should not succeed when autoStart is false")
                } catch (ex: Exception) {
                    // Expected: connection should be refused or fail
                    Assertions.assertTrue(true)
                }
            }
    }
}
