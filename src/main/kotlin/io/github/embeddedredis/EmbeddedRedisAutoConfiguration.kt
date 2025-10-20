package io.github.embeddedredis

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import jakarta.annotation.PreDestroy

/**
 * Spring Boot Auto-configuration for Embedded Redis Server
 */
@AutoConfiguration
@ConditionalOnClass(RedisServer::class)
@ConditionalOnProperty(prefix = "embedded.redis", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(EmbeddedRedisProperties::class)
class EmbeddedRedisAutoConfiguration(
    private val properties: EmbeddedRedisProperties
) {

    private var server: RedisServer? = null

    @Bean
    fun embeddedRedisServer(): RedisServer {
        val redisServer = RedisServer(
            port = properties.port,
            host = properties.host
        )
        this.server = redisServer
        return redisServer
    }

    @Bean
    fun embeddedRedisServerLifecycleAdapter(redisServer: RedisServer): RedisServerLifecycleAdapter {
        return RedisServerLifecycleAdapter(
            server = redisServer,
            autoStartup = properties.autoStart,
            phase = 0
        )
    }

    @PreDestroy
    fun stopServer() {
        server?.stop()
    }
}
