package io.github.embeddedredis

/**
 * Handles Redis commands
 */
class CommandHandler(private val dataStore: DataStore) {
    private data class CommandSpec(
        val names: Set<CommandName>,
        val handler: (List<Any?>) -> Any?,
        val validate: ((List<Any?>) -> RespError?)? = null
    )

    private enum class CommandName {
        PING, ECHO, SET, GET, DEL, EXISTS, COMMAND, HELLO, SETNX, SETEX, HSET, HSETNX, HGET, HMGET, HINCRBY;

        val lower: String
            get() = name.lowercase()
    }

    private val commands: Map<CommandName, CommandSpec> = buildCommands()

    private fun buildCommands(): Map<CommandName, CommandSpec> {
        val specs = listOf(
            CommandSpec(setOf(CommandName.PING), { args -> handlePing(args) }, { args ->
                if (args.size !in 1..2) wrongArity(CommandName.PING) else null
            }),
            CommandSpec(setOf(CommandName.ECHO), { args -> handleEcho(args) }, { args ->
                if (args.size != 2) wrongArity(CommandName.ECHO) else null
            }),
            CommandSpec(setOf(CommandName.SET), { args -> handleSet(args) }, { args ->
                if (args.size < 3) wrongArity(CommandName.SET) else null
            }),
            CommandSpec(setOf(CommandName.GET), { args -> handleGet(args) }, { args ->
                if (args.size != 2) wrongArity(CommandName.GET) else null
            }),
            CommandSpec(setOf(CommandName.DEL), { args -> handleDel(args) }, { args ->
                if (args.size < 2) wrongArity(CommandName.DEL) else null
            }),
            CommandSpec(setOf(CommandName.EXISTS), { args -> handleExists(args) }, { args ->
                if (args.size < 2) wrongArity(CommandName.EXISTS) else null
            }),
            CommandSpec(setOf(CommandName.COMMAND), { _ -> emptyList<Any>() }),
            CommandSpec(setOf(CommandName.HELLO), { _ -> handleHello() }),
            CommandSpec(setOf(CommandName.SETNX), { args -> handleSetNX(args) }, { args ->
                if (args.size != 3) wrongArity(CommandName.SETNX) else null
            }),
            CommandSpec(setOf(CommandName.SETEX), { args -> handleSetEX(args) }, { args ->
                if (args.size != 4) wrongArity(CommandName.SETEX) else null
            }),
            CommandSpec(setOf(CommandName.HSET), { args -> handleHSet(args) }, { args ->
                if (args.size < 4 || args.size % 2 != 0) wrongArity(CommandName.HSET) else null
            }),
            CommandSpec(setOf(CommandName.HSETNX), { args -> handleHSetNX(args) }, { args ->
                if (args.size != 4) wrongArity(CommandName.HSETNX) else null
            }),
            CommandSpec(setOf(CommandName.HGET), { args -> handleHGet(args) }, { args ->
                if (args.size != 3) wrongArity(CommandName.HGET) else null
            }),
            CommandSpec(setOf(CommandName.HMGET), { args -> handleHMGet(args) }, { args ->
                if (args.size < 3) wrongArity(CommandName.HMGET) else null
            }),
            CommandSpec(setOf(CommandName.HINCRBY), { args -> handleHIncrBy(args) }, { args ->
                if (args.size != 4) wrongArity(CommandName.HINCRBY) else null
            }),
        )
        return specs.flatMap { spec -> spec.names.map { it to spec } }.toMap()
    }

    private fun wrongArity(cmd: CommandName): RespError =
        RespError("ERR wrong number of arguments for '${cmd.lower}' command")
    fun handle(command: List<Any?>): Any? {
        if (command.isEmpty()) {
            return RespError("ERR empty command")
        }
        val cmdUpper = (command[0] as? String)?.uppercase() ?: return RespError("ERR invalid command")
        val cmdEnum = try { CommandName.valueOf(cmdUpper) } catch (_: IllegalArgumentException) { return RespError("ERR unknown command '$cmdUpper'") }
        val spec = commands[cmdEnum] ?: return RespError("ERR unknown command '$cmdUpper'")
        val validationError = spec.validate?.invoke(command)
        if (validationError != null) return validationError
        return try {
            spec.handler.invoke(command)
        } catch (e: WrongTypeException) {
            RespError(e.message ?: DataStore.WRONG_TYPE_ERROR_MESSAGE)
        } catch (_: NumberFormatException) {
            RespError("ERR value is not an integer or out of range")
        } catch (_: ArithmeticException) {
            RespError("ERR increment or decrement would overflow")
        } catch (e: IllegalArgumentException) {
            RespError("ERR ${e.message}")
        }
    }

    private fun handlePing(args: List<Any?>): Any {
        return if (args.size > 1) {
            RespBulkString.fromString(args[1] as String)
        } else {
            RespStatus("PONG")
        }
    }

    private fun handleEcho(args: List<Any?>): Any {
        if (args.size != 2) {
            return RespError("ERR wrong number of arguments for 'echo' command")
        }
        return RespBulkString.fromString(args[1] as String)
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
        return if (success) RespStatus("OK") else null
    }

    private fun handleGet(args: List<Any?>): Any {
        if (args.size != 2) {
            return RespError("ERR wrong number of arguments for 'get' command")
        }
        val key = args[1] as String
        return RespBulkString.fromString(dataStore.get(key))
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
        return RespStatus("OK")
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

    private fun handleHGet(args: List<Any?>): Any {
        if (args.size != 3) {
            return RespError("ERR wrong number of arguments for 'hget' command")
        }
        val key = args[1] as String
        val field = args[2] as String
        return RespBulkString.fromString(dataStore.hget(key, field))
    }

    private fun handleHMGet(args: List<Any?>): Any {
        if (args.size < 3) {
            return RespError("ERR wrong number of arguments for 'hmget' command")
        }
        val key = args[1] as String
        val fields = args.subList(2, args.size).map { it as String }
        val result = dataStore.hmget(key, fields)
        return result.map { RespBulkString.fromString(it) }
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