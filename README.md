# Embedded Redis Server

[![GitHub Release](https://img.shields.io/github/v/release/sudouser777/embedded-redis-server)](https://github.com/sudouser777/embedded-redis-server/releases)
[![Build Status](https://github.com/sudouser777/embedded-redis-server/workflows/Build%20and%20Test/badge.svg)](https://github.com/sudouser777/embedded-redis-server/actions)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-1.9.22-purple.svg)](https://kotlinlang.org/)
[![Java](https://img.shields.io/badge/java-21+-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/spring--boot-3.2+-green.svg)](https://spring.io/projects/spring-boot)

A lightweight, embeddable Redis-compatible server written in Kotlin. Perfect for testing, development, and scenarios where you need an in-memory Redis instance without installing Redis itself.

## Features

✅ **Redis Protocol Compatible** - Implements RESP2 protocol

✅ **Embedded & Lightweight** - Run Redis in-process, no external dependencies

✅ **Spring Boot Integration** - Auto-configuration support for Spring Boot applications

✅ **Easy to Use** - Simple API for standalone applications

✅ **Fast** - Kotlin coroutines for concurrent operations

✅ **Testing Friendly** - Perfect for unit and integration tests
✅ **Graceful Connections** - QUIT command supported; client disconnects handled cleanly

## Supported Redis Commands

- **Connection**: `PING`, `ECHO`, `HELLO`, `QUIT`
- **Key-Value**: `SET`, `GET`, `DEL`, `EXISTS`
- **Legacy**: `SETNX`, `SETEX`
- **Options**: Expiration (`EX`, `PX`), Conditional Sets (`NX`, `XX`)

### Connection handling and binding

- The server implements RESP2 and explicitly supports the QUIT command. After replying +OK, the server closes the client socket.
- EOF, connection reset, and broken pipe from clients are treated as normal disconnects and are not logged as errors.
- The server binds explicitly to the configured host and port:
  - host=127.0.0.1 or ::1 limits access to the local machine only.
  - host=0.0.0.0 (default for standalone) listens on all interfaces.
  - In Spring Boot, the default host is localhost unless overridden via properties.

## Installation

### Gradle

```gradle
dependencies {
    implementation 'io.github.sudouser777:embedded-redis-server:0.0.1'
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.sudouser777</groupId>
    <artifactId>embedded-redis-server</artifactId>
    <version>0.0.1</version>
</dependency>
```

## Usage

### 1. Standalone Application

```kotlin
import io.github.embeddedredis.RedisServer

fun main() {
    // Create and start the server
    val server = RedisServer(port = 6379, host = "localhost")
    server.start()

    // Server is now running and accepting connections
    println("Redis server started on port 6379")

    // Add shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop()
    })

    // Keep the application running
    Thread.currentThread().join()
}
```

### 2. Spring Boot Integration

#### Step 1: Add Dependency

Add the embedded-redis-server dependency to your Spring Boot project.

#### Step 2: Configure (Optional)

Add configuration to your `application.yml` or `application.properties`:

**application.yml:**
```yaml
embedded:
  redis:
    enabled: true      # Enable/disable embedded Redis (default: true)
    port: 6379         # Redis port (default: 6379)
    host: localhost    # Bind address (default: localhost)
    auto-start: true   # Auto-start on app startup (default: true)
```

**application.properties:**
```properties
embedded.redis.enabled=true
embedded.redis.port=6379
embedded.redis.host=localhost
embedded.redis.auto-start=true
```

#### Step 3: Use in Your Application

The embedded Redis server will automatically start when your Spring Boot application starts!

```kotlin
@SpringBootApplication
class MyApplication

fun main(args: Array<String>) {
    runApplication<MyApplication>(*args)
}
```

#### Step 4: Connect with Redis Clients

```kotlin
@Service
class MyService {

    fun useRedis() {
        // Use any Redis client (Jedis, Lettuce, etc.)
        val jedis = Jedis("localhost", 6379)

        jedis.set("key", "value")
        val value = jedis.get("key")

        jedis.close()
    }
}
```

### 3. Testing with JUnit

```kotlin
import io.github.embeddedredis.RedisServer
import org.junit.jupiter.api.*
import redis.clients.jedis.Jedis

class MyRedisTest {

    companion object {
        private lateinit var server: RedisServer

        @BeforeAll
        @JvmStatic
        fun startServer() {
            server = RedisServer(port = 16379)  // Use different port for tests
            server.start()
        }

        @AfterAll
        @JvmStatic
        fun stopServer() {
            server.stop()
        }
    }

    @Test
    fun testRedisOperations() {
        val jedis = Jedis("localhost", 16379)

        jedis.set("test", "value")
        val result = jedis.get("test")

        assertEquals("value", result)
        jedis.close()
    }
}
```

### 4. Spring Boot Test Integration

```kotlin
@SpringBootTest
@TestPropertySource(properties = [
    "embedded.redis.enabled=true",
    "embedded.redis.port=16379"
])
class MySpringBootTest {

    @Autowired
    private lateinit var redisServer: RedisServer

    @Test
    fun testWithEmbeddedRedis() {
        val jedis = Jedis("localhost", 16379)

        jedis.set("spring", "boot")
        val result = jedis.get("spring")

        assertEquals("boot", result)
        jedis.close()
    }
}
```

## Configuration Options

### Standalone Configuration

```kotlin
val server = RedisServer(
    port = 6379,           // Port number (default: 6379)
    host = "0.0.0.0"       // Bind address (default: 0.0.0.0)
)
```

### Spring Boot Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `embedded.redis.enabled` | Boolean | `true` | Enable/disable embedded Redis |
| `embedded.redis.port` | Integer | `6379` | Port for Redis server |
| `embedded.redis.host` | String | `localhost` | Host address to bind |
| `embedded.redis.auto-start` | Boolean | `true` | Auto-start server on app startup |

## Use Cases

### 1. **Unit Testing**
Replace actual Redis with embedded version for fast, isolated tests.

### 2. **Integration Testing**
Test Redis-dependent code without external Redis installation.

### 3. **Local Development**
Develop applications without installing Redis.

### 4. **CI/CD Pipelines**
Run tests in CI without Redis setup complexity.

### 5. **Demos & Prototypes**
Quick demos without infrastructure setup.

## Performance Considerations

- **In-Memory Only**: All data stored in memory (not persisted to disk)
- **Single-Threaded**: While coroutines handle concurrency, operations are sequential
- **Development/Testing**: Optimized for development/testing, not production workloads
- **Memory**: Monitor memory usage for large datasets

## Comparison with Alternatives

| Feature | Embedded Redis Server | redis-mock | embedded-redis | testcontainers-redis |
|---------|----------------------|------------|----------------|----------------------|
| Protocol Compliant | ✅ RESP2 | ⚠️ Partial | ✅ Yes | ✅ Yes |
| Spring Boot Auto-Config | ✅ Yes | ❌ No | ❌ No | ❌ No |
| Pure JVM | ✅ Yes | ✅ Yes | ❌ No (native) | ❌ No (Docker) |
| Lightweight | ✅ Yes | ✅ Yes | ⚠️ Medium | ❌ Heavy |
| No External Dependencies | ✅ Yes | ✅ Yes | ⚠️ Redis binary | ❌ Docker required |

## Limitations

- **Not Production Ready**: For development/testing only
- **Limited Commands**: Core commands implemented, advanced features not available
- **No Persistence**: Data not saved to disk
- **No Clustering**: Single instance only
- **No Pub/Sub**: Not yet implemented
- **No Transactions**: Not yet implemented

## Roadmap

- [ ] Additional Redis commands (INCR, DECR, LPUSH, RPUSH, etc.)
- [ ] Pub/Sub support
- [ ] Transaction support (MULTI/EXEC)
- [ ] Lua scripting
- [ ] AOF/RDB persistence (optional)
- [ ] RESP3 protocol support

## Requirements

- Java 21 or higher
- Kotlin 1.9.22 or higher
- Spring Boot 3.2+ (for Spring integration)

## Building from Source

```bash
# Clone the repository
git clone https://github.com/sudouser777/embedded-redis-server.git
cd embedded-redis-server

# Build with Gradle
./gradlew build

# Run tests
./gradlew test

# Publish to local Maven repository
./gradlew publishToMavenLocal
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Inspired by H2 Database embedded mode
- Built with Kotlin Coroutines for concurrency
- Uses RESP2 protocol specification

## Support

- **Issues**: [GitHub Issues](https://github.com/sudouser777/embedded-redis-server/issues)
- **Discussions**: [GitHub Discussions](https://github.com/sudouser777/embedded-redis-server/discussions)

## Similar Projects

- [redis-mock](https://github.com/fppt/jedis-mock) - Mock implementation of Redis
- [embedded-redis](https://github.com/kstyrc/embedded-redis) - Embedded Redis using native binaries
- [testcontainers-redis](https://www.testcontainers.org/) - Docker-based Redis for tests

---

**Made with ❤️ using Kotlin**
