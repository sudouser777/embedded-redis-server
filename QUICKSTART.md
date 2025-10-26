# Quick Start Guide

Get started with Embedded Redis Server in 5 minutes!

## Prerequisites

- Java 21+
- Gradle or Maven

## Installation

### Option 1: Local Build

```bash
# Clone and build locally
git clone https://github.com/sudouser777/embedded-redis-server.git
cd embedded-redis-server
./gradlew publishToMavenLocal
```

### Option 2: Add Dependency (once published)

**Gradle:**
```gradle
dependencies {
    implementation 'io.github.embeddedredis:embedded-redis-server:1.0.0'
}
```

**Maven:**
```xml
<dependency>
    <groupId>io.github.embeddedredis</groupId>
    <artifactId>embedded-redis-server</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Usage

### Standalone (5 lines of code)

```kotlin
import io.github.embeddedredis.RedisServer

val server = RedisServer(port = 6379)
server.start()
// Server is running!
```

### Spring Boot (Zero code!)

**application.yml:**
```yaml
embedded:
  redis:
    enabled: true
    port: 6379
```

That's it! The server auto-starts with your Spring Boot app.

### Testing

```kotlin
@Test
fun myTest() {
    val server = RedisServer(port = 16379)
    server.start()

    // Use redis-cli or any Redis client
    val jedis = Jedis("localhost", 16379)
    jedis.set("test", "works!")

    server.stop()
}
```

## Next Steps

- Read the full [README](README.md)
- Check out [Examples](EXAMPLES.md)
- See supported [Commands](#commands)

## Commands Supported

✅ PING, ECHO, HELLO
✅ SET, GET, DEL, EXISTS
✅ SETNX, SETEX
✅ Hash: HSET, HSETNX, HGET, HMGET, HINCRBY
✅ Expiration (EX, PX)
✅ Conditional sets (NX, XX)

## Need Help?

- [GitHub Issues](https://github.com/sudouser777/embedded-redis-server/issues)
- [Full Documentation](README.md)
