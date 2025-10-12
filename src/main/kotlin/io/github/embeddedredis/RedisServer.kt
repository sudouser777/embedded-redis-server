package io.github.embeddedredis
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket
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
    fun start() {
        // Bind explicitly to the provided host to respect configuration
        serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(host, port))
        }
        logger.info { "Redis server starting on $host:$port" }
        scope.launch {
            try {
                while (isActive) {
                    val client = serverSocket!!.accept()
                    logger.info { "Client connected: ${client.inetAddress.hostAddress}:${client.port}" }
                    launch {
                        handleClient(client)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    logger.error(e) { "Error accepting connections" }
                }
            }
        }
        logger.info { "Redis server started successfully on $host:$port" }
    }
    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        try {
            client.use { socket ->
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                while (isActive && !socket.isClosed) {
                    try {
                        val command = RespProtocol.parse(input) as? List<*>
                        if (command == null) {
                            output.writeResp(RespError("ERR invalid command format"))
                            continue
                        }
                        // Handle QUIT explicitly: reply OK and close the connection
                        val cmd = (command.firstOrNull() as? String)?.uppercase()
                        if (cmd == "QUIT") {
                            output.writeResp("OK")
                            break
                        }
                        logger.debug { "Received command: $command" }
                        val response = commandHandler.handle(command)
                        logger.debug { "Sending response: $response" }
                        output.writeResp(response)
                    } catch (e: IllegalArgumentException) {
                        // Likely RESP3 negotiation or invalid protocol - skip and continue
                        logger.debug { "Skipping invalid RESP data: ${e.message}" }
                        continue
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
                        } catch (writeError: Exception) {
                            logger.debug { "Could not send error response: ${writeError.message}" }
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.info { "Client disconnected: ${e.message}" }
        }
    }
    fun stop() {
        logger.info { "Shutting down Redis server" }
        scope.cancel()
        serverSocket?.close()
        dataStore.shutdown()
        logger.info { "Redis server stopped" }
    }
}
fun main() {
    val server = RedisServer()
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop()
    })
    server.start()
    Thread.currentThread().join()
}
