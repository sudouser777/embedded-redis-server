---
description: Repository Information Overview
alwaysApply: true
---

# Embedded Redis Server Information

## Summary
A lightweight, embeddable Redis-compatible server written in Kotlin. Implements RESP2 protocol and provides Redis functionality for testing, development, and scenarios where an in-memory Redis instance is needed without installing Redis itself. Features Spring Boot integration with auto-configuration support.

## Structure
- **src/main/kotlin**: Core implementation of the Redis server
- **src/main/resources**: Configuration files and Spring Boot auto-configuration
- **src/test**: Test cases for Redis server and Spring Boot integration
- **gradle**: Gradle wrapper files for build system

## Language & Runtime
**Language**: Kotlin
**Version**: 1.9.22
**JVM**: Java 21+
**Build System**: Gradle
**Package Manager**: Gradle/Maven

## Dependencies
**Main Dependencies**:
- org.jetbrains.kotlin:kotlin-stdlib
- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3
- io.github.oshai:kotlin-logging-jvm:5.1.0
- org.slf4j:slf4j-api:2.0.9

**Optional Dependencies**:
- org.springframework.boot:spring-boot-starter:3.2.0
- org.springframework.boot:spring-boot-autoconfigure:3.2.0

**Development Dependencies**:
- org.junit.jupiter:junit-jupiter:5.10.1
- redis.clients:jedis:5.1.0
- ch.qos.logback:logback-classic:1.5.13
- org.springframework.boot:spring-boot-test:3.2.0
- org.assertj:assertj-core:3.24.2

## Build & Installation
```bash
# Build with Gradle
./gradlew build

# Run tests
./gradlew test

# Publish to local Maven repository
./gradlew publishToMavenLocal
```

## Main Files
**Entry Point**: io.github.embeddedredis.RedisServerKt
**Core Components**:
- RedisServer.kt: Main server implementation
- RespProtocol.kt: RESP2 protocol implementation
- CommandHandler.kt: Redis command handling
- DataStore.kt: In-memory data storage
- EmbeddedRedisAutoConfiguration.kt: Spring Boot integration

## Testing
**Framework**: JUnit 5
**Test Location**: src/test/kotlin/io/github/embeddedredis
**Key Test Files**:
- RedisServerTest.kt: Tests for Redis server functionality
- EmbeddedRedisAutoConfigurationTest.kt: Tests for Spring Boot integration
**Run Command**:
```bash
./gradlew test
```

## Features
- Redis Protocol Compatible (RESP2)
- Embedded & Lightweight (no external dependencies)
- Spring Boot Integration with auto-configuration
- Supports key Redis commands (PING, ECHO, SET, GET, DEL, EXISTS)
- Kotlin coroutines for concurrent operations
- Testing-friendly with simple API