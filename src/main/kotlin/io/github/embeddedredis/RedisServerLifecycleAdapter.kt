package io.github.embeddedredis

import org.springframework.context.SmartLifecycle

/**
 * Spring-specific lifecycle adapter for RedisServer. Keeps RedisServer free of Spring dependencies.
 */
class RedisServerLifecycleAdapter(
    private val server: RedisServer,
    private val autoStartup: Boolean = true,
    private val phase: Int = 0
) : SmartLifecycle {

    override fun start() {
        server.start()
    }

    override fun stop() {
        server.stop()
    }

    override fun stop(callback: Runnable) {
        try {
            server.stop()
        } finally {
            try { callback.run() } catch (_: Exception) {}
        }
    }

    override fun isRunning(): Boolean = server.isRunning()

    override fun isAutoStartup(): Boolean = autoStartup

    override fun getPhase(): Int = phase
}
