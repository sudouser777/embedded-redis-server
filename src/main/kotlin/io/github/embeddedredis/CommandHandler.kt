package io.github.embeddedredis
/**
 * Handles Redis commands
 */
class CommandHandler(private val dataStore: DataStore) {
    fun handle(command: List<Any?>): Any? {
        if (command.isEmpty()) {
            return RespError("ERR empty command")
        }
        val cmd = (command[0] as? String)?.uppercase() ?: return RespError("ERR invalid command")
        return try {
            when (cmd) {
                "PING" -> handlePing(command)
                "ECHO" -> handleEcho(command)
                "SET" -> handleSet(command)
                "GET" -> handleGet(command)
                "DEL" -> handleDel(command)
                "EXISTS" -> handleExists(command)
                "COMMAND" -> handleCommand(command)
                "HELLO" -> handleHello(command)
                "SETNX" -> handleSetNX(command)
                "SETEX" -> handleSetEX(command)
                else -> RespError("ERR unknown command '$cmd'")
            }
        } catch (e: Exception) {
            RespError("ERR ${e.message}")
        }
    }
    private fun handlePing(args: List<Any?>): Any {
        return if (args.size > 1) {
            args[1] as String
        } else {
            "PONG"
        }
    }
    private fun handleEcho(args: List<Any?>): Any {
        if (args.size < 2) {
            return RespError("ERR wrong number of arguments for 'echo' command")
        }
        return args[1] as String
    }
    private fun handleSet(args: List<Any?>): Any? {
        if (args.size < 3) {
            return RespError("ERR wrong number of arguments for 'set' command")
        }
        val key = args[1] as String
        val value = args[2] as String
        var expirationMs: Long? = null
        var nx = false
        var xx = false
        var i = 3
        while (i < args.size) {
            val option = (args[i] as String).uppercase()
            when (option) {
                "EX" -> {
                    if (i + 1 >= args.size) {
                        return RespError("ERR syntax error")
                    }
                    expirationMs = (args[i + 1] as String).toLong() * 1000
                    i += 2
                }
                "PX" -> {
                    if (i + 1 >= args.size) {
                        return RespError("ERR syntax error")
                    }
                    expirationMs = (args[i + 1] as String).toLong()
                    i += 2
                }
                "NX" -> {
                    nx = true
                    i++
                }
                "XX" -> {
                    xx = true
                    i++
                }
                else -> {
                    return RespError("ERR syntax error")
                }
            }
        }
        if (nx && xx) {
            return RespError("ERR NX and XX options at the same time are not compatible")
        }
        val success = dataStore.set(key, value, expirationMs, nx, xx)
        return if (success) "OK" else null
    }
    private fun handleGet(args: List<Any?>): Any? {
        if (args.size < 2) {
            return RespError("ERR wrong number of arguments for 'get' command")
        }
        val key = args[1] as String
        return dataStore.get(key)
    }
    private fun handleDel(args: List<Any?>): Any {
        if (args.size < 2) {
            return RespError("ERR wrong number of arguments for 'del' command")
        }
        val keys = args.subList(1, args.size).map { it as String }.toTypedArray()
        return dataStore.del(*keys)
    }
    private fun handleExists(args: List<Any?>): Any {
        if (args.size < 2) {
            return RespError("ERR wrong number of arguments for 'exists' command")
        }
        val keys = args.subList(1, args.size).map { it as String }.toTypedArray()
        return dataStore.exists(*keys)
    }
    private fun handleCommand(args: List<Any?>): Any {
        return emptyList<Any>()
    }
    private fun handleHello(args: List<Any?>): Any {
        // HELLO command is used for protocol negotiation
        // We only support RESP2, so respond accordingly
        return listOf(
            "server", "redis",
            "version", "7.0.0",
            "proto", 2,  // We only support RESP2
            "mode", "standalone"
        )
    }
    private fun handleSetNX(args: List<Any?>): Any {
        // SETNX key value - Set if Not exists
        // Returns 1 if the key was set, 0 if the key already exists
        if (args.size < 3) {
            return RespError("ERR wrong number of arguments for 'setnx' command")
        }
        val key = args[1] as String
        val value = args[2] as String
        val success = dataStore.set(key, value, null, nx = true, xx = false)
        return if (success) 1L else 0L
    }
    private fun handleSetEX(args: List<Any?>): Any {
        // SETEX key seconds value - SET with Expiration
        if (args.size < 4) {
            return RespError("ERR wrong number of arguments for 'setex' command")
        }
        val key = args[1] as String
        val seconds = (args[2] as String).toLong()
        val value = args[3] as String
        dataStore.set(key, value, seconds * 1000, nx = false, xx = false)
        return "OK"
    }
}
