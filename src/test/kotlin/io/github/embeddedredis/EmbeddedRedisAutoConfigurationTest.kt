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

                // Should be started by SmartLifecycle
                Assertions.assertTrue(server.isRunning())

                Jedis("127.0.0.1", port).use { jedis ->
                    val pong = jedis.ping()
                    Assertions.assertEquals("PONG", pong)
                }

                // Closing the context should stop the server
                context.close()
                try {
                    Jedis("127.0.0.1", port).use { jedis -> jedis.ping() }
                    Assertions.fail("Connection should not succeed after context close")
                } catch (_: Exception) {
                    Assertions.assertTrue(true)
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

                // Should not be started automatically
                Assertions.assertFalse(server.isRunning())

                // Attempt to connect should fail because the server is not started
                try {
                    Jedis("127.0.0.1", port).use { jedis -> jedis.ping() }
                    Assertions.fail("Connection should not succeed when autoStart is false")
                } catch (_: Exception) {
                    // Expected
                }

                // Manual start should work in non-Spring style usage
                server.start()
                Jedis("127.0.0.1", port).use { jedis ->
                    val pong = jedis.ping()
                    Assertions.assertEquals("PONG", pong)
                }

                // Manual stop should stop it again
                server.stop()
                try {
                    Jedis("127.0.0.1", port).use { jedis -> jedis.ping() }
                    Assertions.fail("Connection should not succeed after manual stop")
                } catch (_: Exception) {
                    // Expected
                }
            }
    }
}
