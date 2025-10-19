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
                "COMMAND" -> emptyList<Any>()
                "HELLO" -> handleHello()
                "SETNX" -> handleSetNX(command)
                "SETEX" -> handleSetEX(command)
                "HSET" -> handleHSet(command)
                "HSETNX" -> handleHSetNX(command)
                "HGET" -> handleHGet(command)
                "HMGET" -> handleHMGet(command)
                "HINCRBY" -> handleHIncrBy(command)
                else -> RespError("ERR unknown command '$cmd'")
            }
        } catch (e: WrongTypeException) {
            RespError(e.message ?: DataStore.WRONG_TYPE_ERROR_MESSAGE)
        } catch (e: NumberFormatException) {
            RespError("ERR value is not an integer or out of range")
        } catch (e: IllegalArgumentException) {
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
        if (args.size != 2) {
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
        val options = parseSetOptions(args, 3)

        if (options.nx && options.xx) {
            return RespError("ERR NX and XX options at the same time are not compatible")
        }
        val success = dataStore.set(key, value, options.expirationMs, options.nx, options.xx)
        return if (success) "OK" else null
    }

    private fun handleGet(args: List<Any?>): Any? {
        if (args.size != 2) {
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

    private fun handleHello(): Any {
        return listOf(
            "server", "redis",
            "version", "7.0.0",
            "proto", 2,
            "mode", "standalone"
        )
    }

    private fun handleSetNX(args: List<Any?>): Any {
        if (args.size != 3) {
            return RespError("ERR wrong number of arguments for 'setnx' command")
        }
        val key = args[1] as String
        val value = args[2] as String
        val success = dataStore.set(key, value, null, nx = true, xx = false)
        return if (success) 1L else 0L
    }

    private fun handleSetEX(args: List<Any?>): Any {
        if (args.size != 4) {
            return RespError("ERR wrong number of arguments for 'setex' command")
        }
        val key = args[1] as String
        val seconds = (args[2] as String).toLong()
        val value = args[3] as String
        dataStore.set(key, value, seconds * 1000, nx = false, xx = false)
        return "OK"
    }

    private fun handleHSet(args: List<Any?>): Any {
        if (args.size < 4 || args.size % 2 != 0) {
            return RespError("ERR wrong number of arguments for 'hset' command")
        }
        val key = args[1] as String
        val fieldValuePairs = args.subList(2, args.size)
            .chunked(2)
            .associate { (field, value) ->
                (field as String) to (value as String)
            }
        return dataStore.hset(key, fieldValuePairs)
    }

    private fun handleHSetNX(args: List<Any?>): Any {
        if (args.size != 4) {
            return RespError("ERR wrong number of arguments for 'hsetnx' command")
        }
        val key = args[1] as String
        val field = args[2] as String
        val value = args[3] as String
        return dataStore.hsetnx(key, field, value)
    }

    private fun handleHGet(args: List<Any?>): Any? {
        if (args.size != 3) {
            return RespError("ERR wrong number of arguments for 'hget' command")
        }
        val key = args[1] as String
        val field = args[2] as String
        return dataStore.hget(key, field)
    }

    private fun handleHMGet(args: List<Any?>): Any {
        if (args.size < 3) {
            return RespError("ERR wrong number of arguments for 'hmget' command")
        }
        val key = args[1] as String
        val fields = args.subList(2, args.size).map { it as String }
        return dataStore.hmget(key, fields)
    }

    private fun handleHIncrBy(args: List<Any?>): Any {
        if (args.size != 4) {
            return RespError("ERR wrong number of arguments for 'hincrby' command")
        }
        val key = args[1] as String
        val field = args[2] as String
        val increment = (args[3] as String).toLong()
        return dataStore.hincrBy(key, field, increment)
    }

    private data class SetOptions(
        val expirationMs: Long?,
        val nx: Boolean,
        val xx: Boolean
    )

    private fun parseSetOptions(args: List<Any?>, startIndex: Int): SetOptions {
        var expirationMs: Long? = null
        var nx = false
        var xx = false
        var index = startIndex

        while (index < args.size) {
            if (args[index] !is String) {
                throw IllegalArgumentException("syntax error")
            }
            when ((args[index] as String).uppercase()) {
                "EX" -> {
                    if (index + 1 >= args.size) throw IllegalArgumentException("syntax error")
                    expirationMs = (args[index + 1] as String).toLong() * 1000
                    index += 2
                }
                "PX" -> {
                    if (index + 1 >= args.size) throw IllegalArgumentException("syntax error")
                    expirationMs = (args[index + 1] as String).toLong()
                    index += 2
                }
                "NX" -> {
                    nx = true
                    index++
                }
                "XX" -> {
                    xx = true
                    index++
                }
                else -> throw IllegalArgumentException("syntax error")
            }
        }

        return SetOptions(expirationMs, nx, xx)
    }
}