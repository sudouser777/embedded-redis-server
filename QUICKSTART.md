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
    implementation 'io.github.sudouser777:embedded-redis-server:0.0.5'
}
```

**Maven:**
```xml
<dependency>
    <groupId>io.github.sudouser777</groupId>
    <artifactId>embedded-redis-server</artifactId>
    <version>0.0.5</version>
</dependency>
```

## Usage

### Standalone (5 lines of code)

```kotlin
import io.github.sudouser777.RedisServer

val server = RedisServer(port = 6379)
server.start()
// Server is running!
```

### Spring Boot (Zero code!)

Add dependencies to your Spring Boot app:

Gradle (Groovy):
```gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter'
    implementation 'io.github.sudouser777:embedded-redis-server:0.0.5'
    implementation 'redis.clients:jedis:5.1.0' // or lettuce
}
```

Maven:
```xml
<dependencies>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter</artifactId>
  </dependency>
  <dependency>
    <groupId>io.github.sudouser777</groupId>
    <artifactId>embedded-redis-server</artifactId>
    <version>0.0.5</version>
  </dependency>
  <dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
    <version>5.1.0</version>
  </dependency>
</dependencies>
```

**application.yml:**
```yaml
embedded:
  redis:
    enabled: true
    port: 6379
    host: localhost
    auto-start: true
```

That's it! The server auto-starts with your Spring Boot app.

Logging note: this library uses SLF4J and does not bundle a logging backend. Add one (e.g., Logback) in your app if you want logging output and configure levels, for example:

```yaml
logging:
  level:
    io.github.sudouser777: INFO
```

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

✅ Connection: PING, ECHO, HELLO, COMMAND COUNT, QUIT
✅ Strings/Keys: SET, GET, DEL, EXISTS
✅ Legacy: SETNX, SETEX (rejects non‑positive TTLs)
✅ Hash: HSET, HSETNX, HGET, HMGET, HINCRBY
✅ Lists: LPUSH, RPUSH, LPOP, RPOP, LLEN, LMOVE, LRANGE, LTRIM
✅ Options: Expiration (EX, PX), Conditional sets (NX, XX)

## Need Help?

- [GitHub Issues](https://github.com/sudouser777/embedded-redis-server/issues)
- [Full Documentation](README.md)
