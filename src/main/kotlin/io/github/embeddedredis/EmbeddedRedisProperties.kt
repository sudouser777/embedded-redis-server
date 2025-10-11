package io.github.embeddedredis

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for Embedded Redis Server
 */
@ConfigurationProperties(prefix = "embedded.redis")
data class EmbeddedRedisProperties(
    /**
     * Enable or disable the embedded Redis server
     */
    var enabled: Boolean = true,

    /**
     * Port on which the Redis server will listen
     */
    var port: Int = 6379,

    /**
     * Host address to bind to
     */
    var host: String = "localhost",

    /**
     * Auto-start the server on application startup
     */
    var autoStart: Boolean = true
)
