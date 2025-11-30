package io.github.sudouser777

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import redis.clients.jedis.Jedis

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisServerConcurrencyTest {
    private lateinit var server: RedisServer

    @BeforeAll
    fun setUp(): Unit = runBlocking {
        // Use a different port to avoid conflicts with other tests
        server = RedisServer(port = 16380)
        server.start()
        // Small delay to ensure server is ready
        kotlinx.coroutines.delay(300)
    }

    @AfterAll
    fun tearDown() {
        server.stop()
    }

    @Test
    fun concurrentSetNxYieldsExactlyOneSuccess() {
        runBlocking {
            val key = "race:setnx"
            val clients = (1..8).map { Jedis("localhost", 16380) }
            try {
                // Ensure clean slate
                Jedis("localhost", 16380).use { it.del(key) }

            val results = (clients.indices).map { idx ->
                async(Dispatchers.IO) {
                    val v = "v$idx"
                    clients[idx].setnx(key, v)
                }
            }.awaitAll()

            val successCount = results.count { it == 1L }
            assertThat(successCount).isEqualTo(1)

            // Value should be whichever client won
            Jedis("localhost", 16380).use { j ->
                val stored = j.get(key)
                assertThat(stored).startsWith("v")
            }
        } finally {
            clients.forEach { it.close() }
        }
        }
    }

    @Test
    fun concurrentIncrProducesCorrectTotalAndPreservesTtl() {
        runBlocking {
            val key = "race:incr"
            Jedis("localhost", 16380).use { j ->
                j.del(key)
                j.set(key, "0")
                // set TTL 2 seconds
                j.expire(key, 2)
            }

        val threads = 6
        val incrementsPerThread = 50
        val clients = (1..threads).map { Jedis("localhost", 16380) }
        try {
            val jobs = clients.map { client ->
                async(Dispatchers.IO) {
                    repeat(incrementsPerThread) {
                        client.incr(key)
                    }
                }
            }
            jobs.awaitAll()

            Jedis("localhost", 16380).use { j ->
                val ttl = j.ttl(key)
                // should still exist; TTL either -1 (if expired cleared unexpectedly) or > 0
                // Correct behavior aims to preserve TTL during INCR, so ttl should be > 0
                assertThat(ttl).isGreaterThan(0L)
                val expected = (threads * incrementsPerThread).toLong()
                val value = j.get(key)?.toLong()
                assertThat(value).isEqualTo(expected)
            }
        } finally {
            clients.forEach { it.close() }
        }
        }
    }
}
