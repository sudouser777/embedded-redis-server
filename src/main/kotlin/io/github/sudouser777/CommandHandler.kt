package io.github.sudouser777

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
        LPUSH, RPUSH, LPOP, RPOP, LLEN, LMOVE, LRANGE, LTRIM, LINDEX, LSET, LPUSHX, RPUSHX, LINSERT, LREM, RPOPLPUSH,
        HDEL, HEXISTS, HLEN, HGETALL, HKEYS, HVALS, EXPIRE, PEXPIRE, PERSIST, TTL, PTTL, MGET, MSET, INCR, DECR,
        INCRBY, DECRBY, GETSET, SCAN;

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
            CommandSpec(setOf(CommandName.HDEL), { args -> handleHDel(args) }, { args ->
                if (args.size < 3) wrongArity(CommandName.HDEL) else null
            }),
            CommandSpec(setOf(CommandName.HEXISTS), { args -> handleHExists(args) }, { args ->
                if (args.size != 3) wrongArity(CommandName.HEXISTS) else null
            }),
            CommandSpec(setOf(CommandName.HLEN), { args -> handleHLen(args) }, { args ->
                if (args.size != 2) wrongArity(CommandName.HLEN) else null
            }),
            CommandSpec(setOf(CommandName.HGETALL), { args -> handleHGetAll(args) }, { args ->
                if (args.size != 2) wrongArity(CommandName.HGETALL) else null
            }),
            CommandSpec(setOf(CommandName.HKEYS), { args -> handleHKeys(args) }, { args ->
                if (args.size != 2) wrongArity(CommandName.HKEYS) else null
            }),
            CommandSpec(setOf(CommandName.HVALS), { args -> handleHVals(args) }, { args ->
                if (args.size != 2) wrongArity(CommandName.HVALS) else null
            }),
            // List commands
            CommandSpec(setOf(CommandName.LPUSH), { args -> handleLPush(args) }, { args ->
                if (args.size < 3) wrongArity(CommandName.LPUSH) else null
            }),
            CommandSpec(setOf(CommandName.RPUSH), { args -> handleRPush(args) }, { args ->
                if (args.size < 3) wrongArity(CommandName.RPUSH) else null
            }),
            CommandSpec(setOf(CommandName.LPOP), { args -> handleLPop(args) }, { args ->
                if (args.size !in 2..3) wrongArity(CommandName.LPOP) else null
            }),
            CommandSpec(setOf(CommandName.RPOP), { args -> handleRPop(args) }, { args ->
                if (args.size !in 2..3) wrongArity(CommandName.RPOP) else null
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
            // List QoL
            CommandSpec(setOf(CommandName.LINDEX), { args -> handleLIndex(args) }, { args ->
                if (args.size != 3) wrongArity(CommandName.LINDEX) else null
            }),
            CommandSpec(setOf(CommandName.LSET), { args -> handleLSet(args) }, { args ->
                if (args.size != 4) wrongArity(CommandName.LSET) else null
            }),
            CommandSpec(setOf(CommandName.LPUSHX), { args -> handleLPushX(args) }, { args ->
                if (args.size < 3) wrongArity(CommandName.LPUSHX) else null
            }),
            CommandSpec(setOf(CommandName.RPUSHX), { args -> handleRPushX(args) }, { args ->
                if (args.size < 3) wrongArity(CommandName.RPUSHX) else null
            }),
            CommandSpec(setOf(CommandName.LINSERT), { args -> handleLInsert(args) }, { args ->
                if (args.size != 5) wrongArity(CommandName.LINSERT) else null
            }),
            CommandSpec(setOf(CommandName.LREM), { args -> handleLRem(args) }, { args ->
                if (args.size != 4) wrongArity(CommandName.LREM) else null
            }),
            CommandSpec(setOf(CommandName.RPOPLPUSH), { args -> handleRPopLPush(args) }, { args ->
                if (args.size != 3) wrongArity(CommandName.RPOPLPUSH) else null
            }),
            // Expiration family
            CommandSpec(setOf(CommandName.EXPIRE), { args -> handleExpire(args) }, { args ->
                if (args.size != 3) wrongArity(CommandName.EXPIRE) else null
            }),
            CommandSpec(setOf(CommandName.PEXPIRE), { args -> handlePExpire(args) }, { args ->
                if (args.size != 3) wrongArity(CommandName.PEXPIRE) else null
            }),
            CommandSpec(setOf(CommandName.PERSIST), { args -> handlePersist(args) }, { args ->
                if (args.size != 2) wrongArity(CommandName.PERSIST) else null
            }),
            CommandSpec(setOf(CommandName.TTL), { args -> handleTTL(args) }, { args ->
                if (args.size != 2) wrongArity(CommandName.TTL) else null
            }),
            CommandSpec(setOf(CommandName.PTTL), { args -> handlePTTL(args) }, { args ->
                if (args.size != 2) wrongArity(CommandName.PTTL) else null
            }),
            // String batch ops and counters
            CommandSpec(setOf(CommandName.MGET), { args -> handleMGet(args) }, { args ->
                if (args.size < 2) wrongArity(CommandName.MGET) else null
            }),
            CommandSpec(setOf(CommandName.MSET), { args -> handleMSet(args) }, { args ->
                if (args.size < 3 || args.size % 2 == 0) wrongArity(CommandName.MSET) else null
            }),
            CommandSpec(setOf(CommandName.INCR), { args -> handleIncr(args) }, { args ->
                if (args.size != 2) wrongArity(CommandName.INCR) else null
            }),
            CommandSpec(setOf(CommandName.DECR), { args -> handleDecr(args) }, { args ->
                if (args.size != 2) wrongArity(CommandName.DECR) else null
            }),
            CommandSpec(setOf(CommandName.INCRBY), { args -> handleIncrBy(args) }, { args ->
                if (args.size != 3) wrongArity(CommandName.INCRBY) else null
            }),
            CommandSpec(setOf(CommandName.DECRBY), { args -> handleDecrBy(args) }, { args ->
                if (args.size != 3) wrongArity(CommandName.DECRBY) else null
            }),
            CommandSpec(setOf(CommandName.GETSET), { args -> handleGetSet(args) }, { args ->
                if (args.size != 3) wrongArity(CommandName.GETSET) else null
            }),
            // SCAN
            CommandSpec(setOf(CommandName.SCAN), { args -> handleScan(args) }, { args ->
                if (args.size < 2) wrongArity(CommandName.SCAN) else null
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
        // Return HELLO-style key/value pairs with bulk strings for textual values
        return listOf(
            RespBulkString.fromString("server"), RespBulkString.fromString("redis"),
            RespBulkString.fromString("version"), RespBulkString.fromString("7.0.0"),
            RespBulkString.fromString("proto"), 2L,
            RespBulkString.fromString("mode"), RespBulkString.fromString("standalone")
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

    private fun handleHDel(args: List<Any?>): Any {
        val key = args[1] as String
        val fields = args.subList(2, args.size).map { it as String }
        return dataStore.hdel(key, fields)
    }

    private fun handleHExists(args: List<Any?>): Any {
        val key = args[1] as String
        val field = args[2] as String
        return dataStore.hexists(key, field)
    }

    private fun handleHLen(args: List<Any?>): Any {
        val key = args[1] as String
        return dataStore.hlen(key)
    }

    private fun handleHGetAll(args: List<Any?>): Any {
        val key = args[1] as String
        val map = dataStore.hgetAll(key)
        // Return flat list [field, value, field, value, ...] as bulk strings
        val flat = ArrayList<Any?>(map.size * 2)
        map.forEach { (k, v) ->
            flat.add(RespBulkString.fromString(k))
            flat.add(RespBulkString.fromString(v))
        }
        return flat
    }

    private fun handleHKeys(args: List<Any?>): Any {
        val key = args[1] as String
        val keys = dataStore.hkeys(key)
        return keys.map { RespBulkString.fromString(it) }
    }

    private fun handleHVals(args: List<Any?>): Any {
        val key = args[1] as String
        val vals = dataStore.hvals(key)
        return vals.map { RespBulkString.fromString(it) }
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
        return if (args.size == 2) {
            RespBulkString.fromString(dataStore.lpop(key))
        } else {
            val count = (args[2] as String).toInt()
            if (count <= 0) {
                // Redis returns empty array for count <= 0
                emptyList<Any>()
            } else {
                val popped = dataStore.lpopCount(key, count)
                popped.map { RespBulkString.fromString(it) }
            }
        }
    }

    private fun handleRPop(args: List<Any?>): Any {
        val key = args[1] as String
        return if (args.size == 2) {
            RespBulkString.fromString(dataStore.rpop(key))
        } else {
            val count = (args[2] as String).toInt()
            if (count <= 0) {
                emptyList<Any>()
            } else {
                val popped = dataStore.rpopCount(key, count)
                popped.map { RespBulkString.fromString(it) }
            }
        }
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

    // List QoL handlers
    private fun handleLIndex(args: List<Any?>): Any {
        val key = args[1] as String
        val index = (args[2] as String).toLong()
        return RespBulkString.fromString(dataStore.lindex(key, index))
    }

    private fun handleLSet(args: List<Any?>): Any {
        val key = args[1] as String
        val index = (args[2] as String).toLong()
        val value = args[3] as String
        dataStore.lset(key, index, value)
        return RespStatus("OK")
    }

    private fun handleLPushX(args: List<Any?>): Any {
        val key = args[1] as String
        val values = args.subList(2, args.size).map { it as String }.toTypedArray()
        return dataStore.lpushx(key, *values)
    }

    private fun handleRPushX(args: List<Any?>): Any {
        val key = args[1] as String
        val values = args.subList(2, args.size).map { it as String }.toTypedArray()
        return dataStore.rpushx(key, *values)
    }

    private fun handleLInsert(args: List<Any?>): Any {
        val key = args[1] as String
        val pos = (args[2] as String).uppercase()
        val before = when (pos) {
            "BEFORE" -> true
            "AFTER" -> false
            else -> throw IllegalArgumentException("syntax error")
        }
        val pivot = args[3] as String
        val element = args[4] as String
        return dataStore.linsert(key, before, pivot, element)
    }

    private fun handleLRem(args: List<Any?>): Any {
        val key = args[1] as String
        val count = (args[2] as String).toInt()
        val value = args[3] as String
        return dataStore.lrem(key, count, value)
    }

    private fun handleRPopLPush(args: List<Any?>): Any {
        val source = args[1] as String
        val destination = args[2] as String
        val moved = dataStore.rpoplpush(source, destination)
        return RespBulkString.fromString(moved)
    }

    // Expiration handlers
    private fun handleExpire(args: List<Any?>): Any {
        val key = args[1] as String
        val seconds = (args[2] as String).toLong()
        val ms = seconds * 1000
        return dataStore.expireMs(key, ms)
    }

    private fun handlePExpire(args: List<Any?>): Any {
        val key = args[1] as String
        val ms = (args[2] as String).toLong()
        return dataStore.expireMs(key, ms)
    }

    private fun handlePersist(args: List<Any?>): Any {
        val key = args[1] as String
        return dataStore.persist(key)
    }

    private fun handleTTL(args: List<Any?>): Any {
        val key = args[1] as String
        val ms = dataStore.ttlMs(key)
        return when {
            ms == -2L -> -2L
            ms == -1L -> -1L
            ms < 0 -> -2L
            else -> (ms / 1000)
        }
    }

    private fun handlePTTL(args: List<Any?>): Any {
        val key = args[1] as String
        return dataStore.ttlMs(key)
    }

    // String batch ops and counters handlers
    private fun handleMGet(args: List<Any?>): Any {
        val keys = args.subList(1, args.size).map { it as String }
        val values = dataStore.mget(keys)
        return values.map { RespBulkString.fromString(it) }
    }

    private fun handleMSet(args: List<Any?>): Any {
        val pairs = mutableMapOf<String, String>()
        var index = 1
        while (index < args.size) {
            val k = args[index] as String
            val v = args[index + 1] as String
            pairs[k] = v
            index += 2
        }
        dataStore.mset(pairs)
        return RespStatus("OK")
    }

    private fun handleIncr(args: List<Any?>): Any {
        val key = args[1] as String
        return dataStore.incrBy(key, 1)
    }

    private fun handleDecr(args: List<Any?>): Any {
        val key = args[1] as String
        return dataStore.incrBy(key, -1)
    }

    private fun handleIncrBy(args: List<Any?>): Any {
        val key = args[1] as String
        val delta = (args[2] as String).toLong()
        return dataStore.incrBy(key, delta)
    }

    private fun handleDecrBy(args: List<Any?>): Any {
        val key = args[1] as String
        val delta = (args[2] as String).toLong()
        return dataStore.incrBy(key, -delta)
    }

    private fun handleGetSet(args: List<Any?>): Any {
        val key = args[1] as String
        val value = args[2] as String
        return RespBulkString.fromString(dataStore.getSet(key, value))
    }

    private fun handleScan(args: List<Any?>): Any {
        // SCAN <cursor> [MATCH <pattern>] [COUNT <count>]
        val cursorStr = args[1] as String
        val cursorLong = cursorStr.toLong()
        if (cursorLong < 0L || cursorLong > Int.MAX_VALUE) {
            throw IllegalArgumentException("value is not an integer or out of range")
        }
        var match: String? = null
        var count = 10 // default
        var i = 2
        var seenMatch = false
        var seenCount = false
        while (i < args.size) {
            val token = (args[i] as? String)?.uppercase() ?: throw IllegalArgumentException("syntax error")
            when (token) {
                "MATCH" -> {
                    if (seenMatch) throw IllegalArgumentException("syntax error")
                    if (i + 1 >= args.size) throw IllegalArgumentException("syntax error")
                    match = args[i + 1] as String
                    seenMatch = true
                    i += 2
                }
                "COUNT" -> {
                    if (seenCount) throw IllegalArgumentException("syntax error")
                    if (i + 1 >= args.size) throw IllegalArgumentException("syntax error")
                    val cStr = args[i + 1] as String
                    val c = cStr.toInt()
                    if (c <= 0) throw IllegalArgumentException("value is not an integer or out of range")
                    count = c
                    seenCount = true
                    i += 2
                }
                else -> throw IllegalArgumentException("syntax error")
            }
        }

        val (nextCursor, keys) = dataStore.scan(cursorLong.toInt(), match, count)
        val nextCursorStr = nextCursor.toString()
        val arr = keys.map { RespBulkString.fromString(it) }
        return listOf(RespBulkString.fromString(nextCursorStr), arr)
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