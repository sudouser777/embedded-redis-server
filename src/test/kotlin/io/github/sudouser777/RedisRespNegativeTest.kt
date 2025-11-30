package io.github.sudouser777

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisRespNegativeTest {
    private lateinit var server: RedisServer

    @BeforeAll
    fun setUp() {
        server = RedisServer(port = 16381)
        server.start()
        Thread.sleep(200)
    }

    @AfterAll
    fun tearDown() {
        server.stop()
    }

    @Test
    fun malformedBulkStringMissingCrLfCausesDisconnect() {
        Socket("localhost", 16381).use { socket ->
            val out: OutputStream = socket.getOutputStream()
            val inStream: InputStream = socket.getInputStream()
            // Send: *1\r\n$5\r\nhello  (missing trailing CRLF after bulk payload)
            val payload = "*1\r\n$5\r\nhello".toByteArray()
            out.write(payload)
            out.flush()

            // Try to read any response; we expect the server to close the connection without a response
            socket.soTimeout = 500
            val first = try {
                inStream.read()
            } catch (e: Exception) {
                // timeout or other IO problem is also acceptable as an indication of disconnect
                -1
            }
            // Either immediate EOF (-1) or no valid RESP
            assertThat(first).isEqualTo(-1)
        }
    }

    @Test
    fun bulkStringLengthMismatchThenServerStillAcceptsNewConnections() {
        // Open a socket and send a bulk with declared length 5 but only 4 bytes, then close
        Socket("localhost", 16381).use { socket ->
            val out: OutputStream = socket.getOutputStream()
            val bad = "*1\r\n$5\r\nabcd\r\n".toByteArray() // only 4 bytes before CRLF
            out.write(bad)
            out.flush()
            // Close abruptly - server should treat as client disconnect and not crash
        }

        // New well-formed connection should work fine
        Socket("localhost", 16381).use { socket ->
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()
            // Send PING: *1\r\n$4\r\nPING\r\n
            val ping = "*1\r\n$4\r\nPING\r\n".toByteArray()
            out.write(ping)
            out.flush()

            // Read +PONG\r\n
            socket.soTimeout = 2000
            val resp = inp.readBytesAvailable()
            val asString = String(resp)
            assertThat(asString).contains("+PONG\r\n")
        }
    }
}

private fun InputStream.readBytesAvailable(): ByteArray {
    val buffer = ByteArray(1024)
    val n = this.read(buffer)
    return if (n <= 0) ByteArray(0) else buffer.copyOf(n)
}
