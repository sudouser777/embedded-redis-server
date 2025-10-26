package io.github.embeddedredis
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
private val logger = KotlinLogging.logger {}
/**
 * Redis-compatible TCP server
 */
class RedisServer(
    private val port: Int = 6379,
    private val host: String = "0.0.0.0"
) {
    private val dataStore = DataStore()
    private val commandHandler = CommandHandler(dataStore)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var acceptJob: Job? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            logger.warn { "Redis server already started" }
            return
        }
        logger.info { "Redis server starting on $host:$port" }
        // Bind explicitly to the provided host to respect configuration
        serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(host, port))
        }
        acceptJob = scope.launch {
            try {
                while (isActive) {
                    val client = serverSocket!!.accept()
                    logger.info { "Client connected: ${client.inetAddress.hostAddress}:${client.port}" }
                    launch {
                        handleClient(client)
                    }
                }
            } catch (_: CancellationException) {
                logger.debug { "Accept loop canceled" }
            } catch (e: Exception) {
                if (isActive) {
                    logger.error(e) { "Error accepting connections" }
                }
            }
        }
        logger.info { "Redis server started successfully on $host:$port" }
    }
    private fun handleClient(client: Socket) {
        try {
            client.use { socket ->
                socket.tcpNoDelay = true
                socket.keepAlive = true
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                while (!socket.isClosed) {
                    try {
                        val command = RespProtocol.parse(input) as? List<*>
                        if (command == null) {
                            output.writeResp(RespError("ERR invalid command format"))
                            continue
                        }
                        // Handle QUIT explicitly: reply OK and close the connection
                        val cmd = (command.firstOrNull() as? String)?.uppercase()
                        if (cmd == "QUIT") {
                            output.writeResp(RespStatus("OK"))
                            break
                        }
                        logger.debug { "Received command: $command" }
                        val response = commandHandler.handle(command)
                        logger.debug { "Sending response: $response" }
                        output.writeResp(response)
                    } catch (e: IllegalArgumentException) {
                        // Invalid RESP data - close the connection to avoid stream desync
                        logger.debug { "Invalid RESP data, closing connection: ${e.message}" }
                        break
                    } catch (e: IllegalStateException) {
                        // Client closed connection or stream ended
                        logger.debug { "Client stream closed: ${e.message}" }
                        break
                    } catch (e: java.io.IOException) {
                        // Client connection issues (reset/broken pipe). Treat as normal disconnect.
                        logger.debug { "Client IO error, closing connection: ${e.message}" }
                        break
                    } catch (e: Exception) {
                        logger.error(e) { "Error processing command" }
                        try {
                            output.writeResp(RespError("ERR ${e.message}"))
                        } catch (_: Exception) {
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug { "Client disconnected: ${e.message}" }
        }
    }
    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        logger.info { "Shutting down Redis server" }
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            logger.debug { "Error closing server socket: ${e.message}" }
        } finally {
            serverSocket = null
        }
        try {
            acceptJob?.cancel()
        } catch (_: Exception) {
        }
        scope.cancel()
        dataStore.shutdown()
        logger.info { "Redis server stopped" }
    }

    fun isRunning(): Boolean = running.get()
}
fun main() {
    val server = RedisServer()
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop()
    })
    server.start()
    Thread.currentThread().join()
}
