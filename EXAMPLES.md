# Embedded Redis Server - Examples

This document provides detailed examples of using Embedded Redis Server in various scenarios.

## Table of Contents

1. [Standalone Application](#standalone-application)
2. [Spring Boot Application](#spring-boot-application)
3. [JUnit 5 Testing](#junit-5-testing)
4. [Spring Boot Testing](#spring-boot-testing)
5. [Using with Jedis Client](#using-with-jedis-client)
6. [Using with Lettuce Client](#using-with-lettuce-client)

---

## Standalone Application

### Basic Example

```kotlin
import io.github.embeddedredis.RedisServer

fun main() {
    val server = RedisServer(port = 6379)
    server.start()

    println("Redis server started on port 6379")
    println("Connect with: redis-cli -p 6379")

    // Keep running
    Thread.currentThread().join()
}
```

### With Graceful Shutdown

```kotlin
import io.github.embeddedredis.RedisServer

fun main() {
    val server = RedisServer(port = 6379, host = "localhost")

    // Add shutdown hook for graceful termination
    Runtime.getRuntime().addShutdownHook(Thread {
        println("Shutting down Redis server...")
        server.stop()
        println("Redis server stopped")
    })

    server.start()
    println("Redis server is running. Press Ctrl+C to stop.")

    Thread.currentThread().join()
}
```

### Programmatic Start/Stop

```kotlin
import io.github.embeddedredis.RedisServer
import redis.clients.jedis.Jedis

fun main() {
    // Start server
    val server = RedisServer(port = 6379)
    server.start()

    // Use the server
    val jedis = Jedis("localhost", 6379)
    jedis.set("greeting", "Hello, Redis!")
    println("Stored: ${jedis.get("greeting")}")
    jedis.close()

    // Stop server when done
    server.stop()
}
```

---

## Spring Boot Application

### Step 1: Dependencies (build.gradle.kts)

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("io.github.embeddedredis:embedded-redis-server:1.0.0")
    implementation("redis.clients:jedis:5.1.0")
}
```

### Step 2: Application Configuration (application.yml)

```yaml
spring:
  application:
    name: my-spring-boot-app

embedded:
  redis:
    enabled: true
    port: 6379
    host: localhost
    auto-start: true

logging:
  level:
    io.github.embeddedredis: INFO
```

### Step 3: Spring Boot Application

```kotlin
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MyApplication

fun main(args: Array<String>) {
    runApplication<MyApplication>(*args)
}
```

### Step 4: Using Redis in a Service

```kotlin
import org.springframework.stereotype.Service
import redis.clients.jedis.Jedis
import org.springframework.beans.factory.annotation.Value

@Service
class CacheService(
    @Value("\${embedded.redis.port}") private val redisPort: Int
) {

    fun cache(key: String, value: String) {
        Jedis("localhost", redisPort).use { jedis ->
            jedis.setex(key, 3600, value) // Cache for 1 hour
        }
    }

    fun get(key: String): String? {
        return Jedis("localhost", redisPort).use { jedis ->
            jedis.get(key)
        }
    }
}
```

### With Redis Template

```kotlin
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.StringRedisSerializer

@Configuration
class RedisConfig(
    @Value("\${embedded.redis.host}") private val host: String,
    @Value("\${embedded.redis.port}") private val port: Int
) {

    @Bean
    fun jedisConnectionFactory(): JedisConnectionFactory {
        val config = RedisStandaloneConfiguration(host, port)
        return JedisConnectionFactory(config)
    }

    @Bean
    fun redisTemplate(): RedisTemplate<String, String> {
        val template = RedisTemplate<String, String>()
        template.connectionFactory = jedisConnectionFactory()
        template.keySerializer = StringRedisSerializer()
        template.valueSerializer = StringRedisSerializer()
        return template
    }
}

@Service
class RedisService(private val redisTemplate: RedisTemplate<String, String>) {

    fun save(key: String, value: String) {
        redisTemplate.opsForValue().set(key, value)
    }

    fun find(key: String): String? {
        return redisTemplate.opsForValue().get(key)
    }
}
```

---

## JUnit 5 Testing

### Basic Test Setup

```kotlin
import io.github.embeddedredis.RedisServer
import org.junit.jupiter.api.*
import redis.clients.jedis.Jedis
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisIntegrationTest {

    private lateinit var server: RedisServer
    private lateinit var jedis: Jedis

    @BeforeAll
    fun startServer() {
        server = RedisServer(port = 16379) // Use different port for tests
        server.start()
        jedis = Jedis("localhost", 16379)
    }

    @AfterAll
    fun stopServer() {
        jedis.close()
        server.stop()
    }

    @BeforeEach
    fun clearRedis() {
        // Clear all keys before each test
        jedis.flushAll()
    }

    @Test
    fun `should store and retrieve values`() {
        jedis.set("key", "value")
        assertEquals("value", jedis.get("key"))
    }

    @Test
    fun `should handle expiration`() {
        jedis.setex("temp", 1, "value")
        assertEquals("value", jedis.get("temp"))

        Thread.sleep(1100)
        assertEquals(null, jedis.get("temp"))
    }
}
```

### Nested Test Classes

```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisCommandsTest {

    private lateinit var server: RedisServer

    @BeforeAll
    fun setup() {
        server = RedisServer(port = 16379)
        server.start()
    }

    @AfterAll
    fun teardown() {
        server.stop()
    }

    @Nested
    inner class StringOperations {

        private lateinit var jedis: Jedis

        @BeforeEach
        fun connect() {
            jedis = Jedis("localhost", 16379)
        }

        @AfterEach
        fun disconnect() {
            jedis.close()
        }

        @Test
        fun `SET and GET`() {
            jedis.set("name", "John")
            assertEquals("John", jedis.get("name"))
        }

        @Test
        fun `SETNX should not override existing key`() {
            jedis.set("existing", "value1")
            val result = jedis.setnx("existing", "value2")

            assertEquals(0L, result)
            assertEquals("value1", jedis.get("existing"))
        }
    }

    @Nested
    inner class KeyOperations {

        private lateinit var jedis: Jedis

        @BeforeEach
        fun connect() {
            jedis = Jedis("localhost", 16379)
        }

        @AfterEach
        fun disconnect() {
            jedis.close()
        }

        @Test
        fun `DEL should remove keys`() {
            jedis.set("key1", "value1")
            jedis.set("key2", "value2")

            val deleted = jedis.del("key1", "key2")
            assertEquals(2L, deleted)
        }

        @Test
        fun `EXISTS should check key existence`() {
            jedis.set("exists", "yes")

            assertTrue(jedis.exists("exists"))
            assertFalse(jedis.exists("notexists"))
        }
    }
}
```

---

## Spring Boot Testing

### Integration Test with Embedded Redis

```kotlin
import io.github.embeddedredis.RedisServer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import redis.clients.jedis.Jedis
import kotlin.test.assertEquals

@SpringBootTest
@TestPropertySource(properties = [
    "embedded.redis.enabled=true",
    "embedded.redis.port=16379",
    "embedded.redis.auto-start=true"
])
class EmbeddedRedisIntegrationTest {

    @Autowired
    private lateinit var redisServer: RedisServer

    @Test
    fun `embedded Redis should be available`() {
        val jedis = Jedis("localhost", 16379)

        jedis.set("test", "value")
        assertEquals("value", jedis.get("test"))

        jedis.close()
    }
}
```

### Service Test with Mocked Behavior

```kotlin
@SpringBootTest
@TestPropertySource(properties = [
    "embedded.redis.port=16379"
])
class UserServiceTest {

    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var redisServer: RedisServer

    @Test
    fun `should cache user data`() {
        val user = User(id = 1, name = "John Doe")

        // First call - cache miss
        userService.saveUser(user)
        val cachedUser = userService.getUser(1)

        assertEquals(user.name, cachedUser?.name)
    }
}
```

---

## Using with Jedis Client

### Basic Operations

```kotlin
import redis.clients.jedis.Jedis
import io.github.embeddedredis.RedisServer

fun main() {
    val server = RedisServer(port = 6379)
    server.start()

    Jedis("localhost", 6379).use { jedis ->
        // String operations
        jedis.set("user:1:name", "Alice")
        println(jedis.get("user:1:name"))

        // Expiration
        jedis.setex("session:abc", 3600, "token123")

        // Conditional set
        jedis.setnx("lock:resource", "locked")

        // Bulk operations
        jedis.set("key1", "value1")
        jedis.set("key2", "value2")
        val deleted = jedis.del("key1", "key2")
        println("Deleted $deleted keys")
    }

    server.stop()
}
```

### Connection Pooling

```kotlin
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig

fun main() {
    val server = RedisServer(port = 6379)
    server.start()

    val config = JedisPoolConfig().apply {
        maxTotal = 10
        maxIdle = 5
        minIdle = 1
    }

    val pool = JedisPool(config, "localhost", 6379)

    // Use from pool
    pool.resource.use { jedis ->
        jedis.set("pooled", "connection")
        println(jedis.get("pooled"))
    }

    pool.close()
    server.stop()
}
```

---

## Using with Lettuce Client

### Basic Lettuce Example

```kotlin
import io.lettuce.core.RedisClient
import io.lettuce.core.api.sync.RedisCommands
import io.github.embeddedredis.RedisServer

fun main() {
    val server = RedisServer(port = 6379)
    server.start()

    val client = RedisClient.create("redis://localhost:6379")
    val connection = client.connect()
    val commands: RedisCommands<String, String> = connection.sync()

    // Use Lettuce
    commands.set("lettuce:key", "lettuce:value")
    println(commands.get("lettuce:key"))

    // Cleanup
    connection.close()
    client.shutdown()
    server.stop()
}
```

---

## Advanced Examples

### Multiple Redis Instances

```kotlin
fun main() {
    val server1 = RedisServer(port = 6379)
    val server2 = RedisServer(port = 6380)

    server1.start()
    server2.start()

    // Use both servers
    Jedis("localhost", 6379).use { it.set("server1", "data") }
    Jedis("localhost", 6380).use { it.set("server2", "data") }

    server1.stop()
    server2.stop()
}
```

### Custom Test Base Class

```kotlin
abstract class RedisTestBase {

    companion object {
        private val server = RedisServer(port = 16379)

        @BeforeAll
        @JvmStatic
        fun startRedis() {
            server.start()
        }

        @AfterAll
        @JvmStatic
        fun stopRedis() {
            server.stop()
        }
    }

    protected fun createJedis() = Jedis("localhost", 16379)
}

class MyTest : RedisTestBase() {
    @Test
    fun test() {
        createJedis().use { jedis ->
            // Your test code
        }
    }
}
```

---

For more examples and documentation, visit the [GitHub repository](https://github.com/sudouser777/embedded-redis-server).
