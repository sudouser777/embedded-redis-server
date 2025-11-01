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
        PING, ECHO, SET, GET, DEL, EXISTS, COMMAND, HELLO, SETNX, SETEX, HSET, HSETNX, HGET, HMGET, HINCRBY,
        LPUSH, RPUSH, LPOP, RPOP, LLEN, LMOVE, LRANGE, LTRIM;

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
            CommandSpec(setOf(CommandName.COMMAND), { args -> handleCommand(args) }),
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
            // List commands
            CommandSpec(setOf(CommandName.LPUSH), { args -> handleLPush(args) }, { args ->
                if (args.size < 3) wrongArity(CommandName.LPUSH) else null
            }),
            CommandSpec(setOf(CommandName.RPUSH), { args -> handleRPush(args) }, { args ->
                if (args.size < 3) wrongArity(CommandName.RPUSH) else null
            }),
            CommandSpec(setOf(CommandName.LPOP), { args -> handleLPop(args) }, { args ->
                if (args.size != 2) wrongArity(CommandName.LPOP) else null
            }),
            CommandSpec(setOf(CommandName.RPOP), { args -> handleRPop(args) }, { args ->
                if (args.size != 2) wrongArity(CommandName.RPOP) else null
            }),
            CommandSpec(setOf(CommandName.LLEN), { args -> handleLLen(args) }, { args ->
                if (args.size != 2) wrongArity(CommandName.LLEN) else null
            }),
            CommandSpec(setOf(CommandName.LRANGE), { args -> handleLRange(args) }, { args ->
                if (args.size != 4) wrongArity(CommandName.LRANGE) else null
            }),
            CommandSpec(setOf(CommandName.LTRIM), { args -> handleLTrim(args) }, { args ->
                if (args.size != 4) wrongArity(CommandName.LTRIM) else null
            }),
            CommandSpec(setOf(CommandName.LMOVE), { args -> handleLMove(args) }, { args ->
                if (args.size != 5) wrongArity(CommandName.LMOVE) else null
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

    private fun handleCommand(args: List<Any?>): Any {
        // Minimal implementation: support COMMAND COUNT, return number of supported commands
        return if (args.size == 1) {
            // We don't implement full introspection; return an empty array for base COMMAND
            emptyList<Any>()
        } else {
            val sub = (args[1] as String).uppercase()
            when (sub) {
                "COUNT" -> commands.size
                else -> RespError("ERR unknown subcommand or wrong number of arguments for 'COMMAND $sub'")
            }
        }
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
        if (seconds <= 0) {
            return RespError("ERR invalid expire time in setex")
        }
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

    // List handlers
    private fun handleLPush(args: List<Any?>): Any {
        val key = args[1] as String
        val values = args.subList(2, args.size).map { it as String }.toTypedArray()
        return dataStore.lpush(key, *values)
    }

    private fun handleRPush(args: List<Any?>): Any {
        val key = args[1] as String
        val values = args.subList(2, args.size).map { it as String }.toTypedArray()
        return dataStore.rpush(key, *values)
    }

    private fun handleLPop(args: List<Any?>): Any {
        val key = args[1] as String
        return RespBulkString.fromString(dataStore.lpop(key))
    }

    private fun handleRPop(args: List<Any?>): Any {
        val key = args[1] as String
        return RespBulkString.fromString(dataStore.rpop(key))
    }

    private fun handleLLen(args: List<Any?>): Any {
        val key = args[1] as String
        return dataStore.llen(key)
    }

    private fun handleLRange(args: List<Any?>): Any {
        val key = args[1] as String
        val start = (args[2] as String).toLong()
        val stop = (args[3] as String).toLong()
        val result = dataStore.lrange(key, start, stop)
        return result.map { RespBulkString.fromString(it) }
    }

    private fun handleLTrim(args: List<Any?>): Any {
        val key = args[1] as String
        val start = (args[2] as String).toLong()
        val stop = (args[3] as String).toLong()
        dataStore.ltrim(key, start, stop)
        return RespStatus("OK")
    }

    private fun handleLMove(args: List<Any?>): Any {
        val source = args[1] as String
        val destination = args[2] as String
        val from = (args[3] as String).uppercase()
        val to = (args[4] as String).uppercase()
        val fromLeft = when (from) {
            "LEFT" -> true
            "RIGHT" -> false
            else -> throw IllegalArgumentException("syntax error")
        }
        val toLeft = when (to) {
            "LEFT" -> true
            "RIGHT" -> false
            else -> throw IllegalArgumentException("syntax error")
        }
        val moved = dataStore.lmove(source, destination, fromLeft, toLeft)
        return RespBulkString.fromString(moved)
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