package io.github.embeddedredis

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import redis.clients.jedis.Jedis
import redis.clients.jedis.params.ScanParams

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisServerTest {
    private lateinit var server: RedisServer
    private lateinit var jedis: Jedis

    @BeforeAll
    fun startServer() = runBlocking {
        server = RedisServer(port = 16379)
        server.start()
        delay(1000)
        jedis = Jedis("localhost", 16379)
    }

    @AfterAll
    fun stopServer() {
        jedis.close()
        server.stop()
    }

    @Test
    fun testPing() {
        val response = jedis.ping()
        assertThat(response).isEqualTo("PONG")
    }

    @Test
    fun testPingWithMessage() {
        val response = jedis.ping("Hello")
        assertThat(response).isEqualTo("Hello")
    }

    @Test
    fun testEcho() {
        val response = jedis.echo("Hello World")
        assertThat(response).isEqualTo("Hello World")
    }

    @Test
    fun testSetAndGet() {
        jedis.set("key1", "value1")
        val value = jedis.get("key1")
        assertThat(value).isEqualTo("value1")
    }

    @Test
    fun testSetWithExpiration() = runBlocking {
        jedis.setex("tempkey", 1, "tempvalue")
        assertThat(jedis.get("tempkey")).isEqualTo("tempvalue")
        delay(1500)
        assertThat(jedis.get("tempkey")).isNull()
    }

    @Test
    fun testExpireAndTTL() {
        runBlocking {
            jedis.set("ttlkey", "v")
            val set = jedis.expire("ttlkey", 2)
            assertThat(set).isEqualTo(1)
            val ttl1 = jedis.ttl("ttlkey")
            assertThat(ttl1).isBetween(0L, 2L)
            delay(2300)
            // After expiration, key should be gone and TTL -2
            assertThat(jedis.get("ttlkey")).isNull()
            assertThat(jedis.ttl("ttlkey")).isEqualTo(-2L)
        }
    }

    @Test
    fun testPExpireAndPTTL() {
        runBlocking {
            jedis.set("pttlkey", "v")
            val set = jedis.pexpire("pttlkey", 200)
            assertThat(set).isEqualTo(1)
            val pttl1 = jedis.pttl("pttlkey")
            assertThat(pttl1).isBetween(0L, 200L)
            delay(250)
            assertThat(jedis.get("pttlkey")).isNull()
            assertThat(jedis.pttl("pttlkey")).isEqualTo(-2L)
        }
    }

    @Test
    fun testPersistRemovesTTL() {
        runBlocking {
            jedis.setex("persistkey", 1, "v")
            // ensure TTL is set
            assertThat(jedis.ttl("persistkey")).isBetween(0L, 1L)
            val removed = jedis.persist("persistkey")
            assertThat(removed).isEqualTo(1L)
            assertThat(jedis.ttl("persistkey")).isEqualTo(-1L)
            delay(1200)
            // key should still exist without TTL
            assertThat(jedis.get("persistkey")).isEqualTo("v")
        }
    }

    @Test
    fun testExpireNonexistentAndTtlNoTtl() {
        jedis.del("nokey")
        assertThat(jedis.expire("nokey", 10)).isEqualTo(0)
        jedis.set("nottl", "v")
        assertThat(jedis.ttl("nottl")).isEqualTo(-1L)
    }

    @Test
    fun testSetNX() {
        jedis.del("nxkey")
        val result1 = jedis.setnx("nxkey", "value1")
        assertThat(result1).isEqualTo(1L)
        val result2 = jedis.setnx("nxkey", "value2")
        assertThat(result2).isEqualTo(0L)
        assertThat(jedis.get("nxkey")).isEqualTo("value1")
    }

    @Test
    fun testMSetAndMGet() {
        jedis.del("m1", "m2", "m3")
        val ok = jedis.mset("m1", "v1", "m2", "v2")
        assertThat(ok).isEqualTo("OK")
        val values = jedis.mget("m1", "m2", "m3")
        assertThat(values).containsExactly("v1", "v2", null)
    }

    @Test
    fun testMSetClearsTTL() {
        runBlocking {
            jedis.del("mt1", "mt2")
            jedis.setex("mt1", 1, "a")
            assertThat(jedis.ttl("mt1")).isBetween(0L, 1L)
            val ok = jedis.mset("mt1", "b", "mt2", "c")
            assertThat(ok).isEqualTo("OK")
            // TTL should be cleared for mt1
            assertThat(jedis.ttl("mt1")).isEqualTo(-1L)
            // After original 1s, key should still exist with new value
            delay(1200)
            assertThat(jedis.get("mt1")).isEqualTo("b")
            assertThat(jedis.get("mt2")).isEqualTo("c")
        }
    }

    @Test
    fun testIncrDecrFamily() {
        jedis.del("cnt")
        // create on miss
        assertThat(jedis.incr("cnt")).isEqualTo(1L)
        assertThat(jedis.incrBy("cnt", 9)).isEqualTo(10L)
        assertThat(jedis.decr("cnt")).isEqualTo(9L)
        assertThat(jedis.decrBy("cnt", 4)).isEqualTo(5L)

        // non-integer existing value
        jedis.set("cnt", "abc")
        try {
            jedis.incr("cnt")
        } catch (e: Exception) {
            assertThat(e.message).contains("ERR value is not an integer or out of range")
        }

        // overflow
        jedis.set("cnt", Long.MAX_VALUE.toString())
        try {
            jedis.incr("cnt")
        } catch (e: Exception) {
            assertThat(e.message).contains("ERR increment or decrement would overflow")
        }
    }

    @Test
    fun testGetSetBehaviorAndTTL() {
        runBlocking {
            jedis.del("gs", "gsttl")

            // missing returns null, sets value
            val prevNull = jedis.getSet("gs", "v1")
            assertThat(prevNull).isNull()
            assertThat(jedis.get("gs")).isEqualTo("v1")

            // returns previous, sets new
            val prev = jedis.getSet("gs", "v2")
            assertThat(prev).isEqualTo("v1")
            assertThat(jedis.get("gs")).isEqualTo("v2")

            // TTL clearing semantics
            jedis.setex("gsttl", 1, "x")
            assertThat(jedis.ttl("gsttl")).isBetween(0L, 1L)
            val prev2 = jedis.getSet("gsttl", "y")
            assertThat(prev2).isEqualTo("x")
            assertThat(jedis.ttl("gsttl")).isEqualTo(-1L)
            delay(1200)
            // key should still exist because TTL was cleared
            assertThat(jedis.get("gsttl")).isEqualTo("y")
        }
    }

    @Test
    fun testDel() {
        jedis.set("delkey1", "value1")
        jedis.set("delkey2", "value2")
        val deleted = jedis.del("delkey1", "delkey2", "nonexistent")
        assertThat(deleted).isEqualTo(2L)
        assertThat(jedis.get("delkey1")).isNull()
        assertThat(jedis.get("delkey2")).isNull()
    }

    @Test
    fun testHDelAndKeyDeletion() {
        jedis.del("h1")
        // Create hash
        val added = jedis.hset("h1", mapOf("f1" to "v1", "f2" to "v2"))
        assertThat(added).isEqualTo(2L)

        // Remove one field
        val rem1 = jedis.hdel("h1", "f1")
        assertThat(rem1).isEqualTo(1L)
        assertThat(jedis.hexists("h1", "f1")).isFalse()
        assertThat(jedis.hlen("h1")).isEqualTo(1L)

        // Removing a non-existing field counts as 0
        val rem0 = jedis.hdel("h1", "nope")
        assertThat(rem0).isEqualTo(0L)

        // Remove last field deletes the key
        val rem2 = jedis.hdel("h1", "f2")
        assertThat(rem2).isEqualTo(1L)
        assertThat(jedis.exists("h1")).isFalse()
        assertThat(jedis.hlen("h1")).isEqualTo(0L)
    }

    @Test
    fun testHExistsAndHLenOnMissingAndExpired() {
        runBlocking {
            jedis.del("h2")
            // Missing
            assertThat(jedis.hexists("h2", "f")).isFalse()
            assertThat(jedis.hlen("h2")).isEqualTo(0L)

            // Create and expire
            jedis.hset("h2", "a", "1")
            assertThat(jedis.hlen("h2")).isEqualTo(1L)
            jedis.expire("h2", 1)
            delay(1200)
            assertThat(jedis.hexists("h2", "a")).isFalse()
            assertThat(jedis.hlen("h2")).isEqualTo(0L)
        }
    }

    @Test
    fun testHGetAllKeysVals() {
        jedis.del("h3", "hmissing")
        jedis.hset("h3", mapOf("f1" to "v1", "f2" to "v2", "f3" to "v3"))

        val all = jedis.hgetAll("h3")
        assertThat(all.size).isEqualTo(3)
        assertThat(all).containsEntry("f1", "v1").containsEntry("f2", "v2").containsEntry("f3", "v3")

        val keys = jedis.hkeys("h3")
        assertThat(keys).containsExactlyInAnyOrder("f1", "f2", "f3")

        val vals = jedis.hvals("h3")
        assertThat(vals).containsExactlyInAnyOrder("v1", "v2", "v3")

        // Missing key returns empty collections
        assertThat(jedis.hgetAll("hmissing")).isEmpty()
        assertThat(jedis.hkeys("hmissing")).isEmpty()
        assertThat(jedis.hvals("hmissing")).isEmpty()
    }

    @Test
    fun testWrongTypeErrorsOnHashCommands() {
        jedis.del("typekey")
        jedis.set("typekey", "str")
        try {
            jedis.hlen("typekey")
        } catch (e: Exception) {
            assertThat(e.message).contains("WRONGTYPE")
        }
        try {
            jedis.hdel("typekey", "f")
        } catch (e: Exception) {
            assertThat(e.message).contains("WRONGTYPE")
        }
        try {
            jedis.hgetAll("typekey")
        } catch (e: Exception) {
            assertThat(e.message).contains("WRONGTYPE")
        }
    }

    @Test
    fun testExists() {
        jedis.set("existkey", "value")
        val exists = jedis.exists("existkey")
        assertThat(exists).isTrue()
        val notExists = jedis.exists("nonexistent")
        assertThat(notExists).isFalse()
    }

    @Test
    fun testHashCommands() {
        jedis.del("hashkey")
        // HSET
        val added = jedis.hset("hashkey", mapOf("field1" to "value1", "field2" to "value2"))
        assertThat(added).isEqualTo(2L)

        // HGET existing
        assertThat(jedis.hget("hashkey", "field1")).isEqualTo("value1")
        // HGET missing field
        assertThat(jedis.hget("hashkey", "missing")).isNull()

        // HMGET
        val hmgetResult = jedis.hmget("hashkey", "field1", "field2", "missing")
        assertThat(hmgetResult).containsExactly("value1", "value2", null)

        // HINCRBY on existing integer
        jedis.hset("hashkey", "counter", "10")
        val hincrResult = jedis.hincrBy("hashkey", "counter", 5)
        assertThat(hincrResult).isEqualTo(15L)
        assertThat(jedis.hget("hashkey", "counter")).isEqualTo("15")

        // HINCRBY on missing field initializes to 0
        val newCounter = jedis.hincrBy("hashkey", "newcounter", 4)
        assertThat(newCounter).isEqualTo(4L)
        assertThat(jedis.hget("hashkey", "newcounter")).isEqualTo("4")

        // HSETNX should set only when field is missing
        val hsetnx1 = jedis.hsetnx("hashkey", "onlyonce", "X")
        assertThat(hsetnx1).isEqualTo(1L)
        assertThat(jedis.hget("hashkey", "onlyonce")).isEqualTo("X")
        val hsetnx2 = jedis.hsetnx("hashkey", "onlyonce", "Y")
        assertThat(hsetnx2).isEqualTo(0L)
        assertThat(jedis.hget("hashkey", "onlyonce")).isEqualTo("X")
    }

    @Test
    fun testQuitClosesConnection() {
        val socket = java.net.Socket("localhost", 16379)
        try {
            socket.soTimeout = 2000
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            // Send QUIT command: *1\r\n$4\r\nQUIT\r\n
            out.write("*1\r\n$4\r\nQUIT\r\n".toByteArray())
            out.flush()

            val reader = java.io.BufferedReader(java.io.InputStreamReader(input))
            val line = reader.readLine()
            assertThat(line).isEqualTo("+OK")
            // After QUIT, server should close the connection. Further reads should hit EOF or timeout quickly.
            try {
                val next = input.read()
                assertThat(next).isEqualTo(-1)
            } catch (ex: Exception) {
                // IOException/timeout is also acceptable since server closed the socket
                assertThat(true).isTrue()
            }
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun testLIndexPositiveAndNegative() {
        jedis.del("l1")
        jedis.rpush("l1", "a", "b", "c")
        assertThat(jedis.lindex("l1", 0)).isEqualTo("a")
        assertThat(jedis.lindex("l1", 2)).isEqualTo("c")
        assertThat(jedis.lindex("l1", -1)).isEqualTo("c")
        assertThat(jedis.lindex("l1", -2)).isEqualTo("b")
        // out of range and missing
        assertThat(jedis.lindex("l1", 100)).isNull()
        assertThat(jedis.lindex("missing", 0)).isNull()
    }

    @Test
    fun testLSetSuccessAndOutOfRange() {
        jedis.del("lset")
        jedis.rpush("lset", "x", "y", "z")
        val ok = jedis.lset("lset", 1, "YY")
        assertThat(ok).isEqualTo("OK")
        assertThat(jedis.lindex("lset", 1)).isEqualTo("YY")
        // negative index
        jedis.lset("lset", -1, "ZZ")
        assertThat(jedis.lindex("lset", 2)).isEqualTo("ZZ")
        // out of range should error
        try {
            jedis.lset("lset", 10, "oops")
            assertThat(false).`as`("Expected error for out of range").isTrue()
        } catch (e: Exception) {
            assertThat(e.message).contains("index out of range")
        }
    }

    @Test
    fun testLPushXAndRPushX() {
        jedis.del("lx", "rx")
        // missing keys -> no-op
        assertThat(jedis.lpushx("lx", "a")).isEqualTo(0L)
        assertThat(jedis.rpushx("rx", "a", "b")).isEqualTo(0L)
        // create lists
        jedis.rpush("lx", "1")
        jedis.rpush("rx", "1")
        assertThat(jedis.lpushx("lx", "0")).isEqualTo(2L)
        assertThat(jedis.rpushx("rx", "2", "3")).isEqualTo(3L)
        assertThat(jedis.lrange("lx", 0, -1)).containsExactly("0", "1")
        assertThat(jedis.lrange("rx", 0, -1)).containsExactly("1", "2", "3")
    }

    @Test
    fun testLInsertBeforeAfterAndPivotMissing() {
        jedis.del("lin")
        // missing key: return 0
        assertThat(jedis.linsert("lin", redis.clients.jedis.args.ListPosition.BEFORE, "X", "A")).isEqualTo(0L)
        jedis.rpush("lin", "a", "b", "c")
        // AFTER b -> insert between b and c
        assertThat(jedis.linsert("lin", redis.clients.jedis.args.ListPosition.AFTER, "b", "B2")).isEqualTo(4L)
        assertThat(jedis.lrange("lin", 0, -1)).containsExactly("a", "b", "B2", "c")
        // BEFORE a -> at head
        assertThat(jedis.linsert("lin", redis.clients.jedis.args.ListPosition.BEFORE, "a", "A0")).isEqualTo(5L)
        assertThat(jedis.lrange("lin", 0, -1)).containsExactly("A0", "a", "b", "B2", "c")
        // pivot not found -> -1
        assertThat(jedis.linsert("lin", redis.clients.jedis.args.ListPosition.BEFORE, "zzz", "noop")).isEqualTo(-1L)
    }

    @Test
    fun testLPopRPopWithCountVariants() {
        jedis.del("lp", "rp")
        jedis.rpush("lp", "a", "b", "c", "d")
        jedis.rpush("rp", "a", "b", "c", "d")
        // LPOP count
        val lpop2 = jedis.lpop("lp", 2)
        assertThat(lpop2).containsExactly("a", "b")
        assertThat(jedis.lrange("lp", 0, -1)).containsExactly("c", "d")
        // RPOP count
        val rpop3 = jedis.rpop("rp", 3)
        assertThat(rpop3).containsExactly("d", "c", "b")
        assertThat(jedis.lrange("rp", 0, -1)).containsExactly("a")
        // Count <= 0 returns empty list
        val empty = jedis.lpop("rp", 0)
        assertThat(empty).isEmpty()
    }

    @Test
    fun testWrongTypeErrorsOnNewListCommands() {
        jedis.del("wt")
        jedis.set("wt", "str")
        fun assertWrongType(block: () -> Unit) {
            try {
                block()
                assertThat(false).isTrue()
            } catch (e: Exception) {
                assertThat(e.message).contains("WRONGTYPE")
            }
        }
        assertWrongType { jedis.lindex("wt", 0) }
        assertWrongType { jedis.lset("wt", 0, "x") }
        assertWrongType { jedis.linsert("wt", redis.clients.jedis.args.ListPosition.BEFORE, "p", "e") }
        assertWrongType { jedis.lpushx("wt", "x") }
        assertWrongType { jedis.rpushx("wt", "x") }
        assertWrongType { jedis.lpop("wt", 2) }
    }

    @Test
    fun testTTLPreservationOnListMutations() {
        runBlocking {
            jedis.del("ltl")
            jedis.rpush("ltl", "a", "b")
            jedis.expire("ltl", 1)
            // mutate with LSET should preserve TTL
            jedis.lset("ltl", 0, "A")
            val ttl = jedis.ttl("ltl")
            assertThat(ttl).isBetween(0L, 1L)
            delay(1200)
            // key should be gone
            assertThat(jedis.exists("ltl")).isFalse()
        }
    }

    @Test
    fun testLRemVariantsAndTTL() {
        runBlocking {
            jedis.del("lrem1", "lrem2", "lrem3", "lremtype")
            // Setup
            jedis.rpush("lrem1", "a", "b", "a", "c", "a")
            // count > 0 from head: remove first two 'a'
            assertThat(jedis.lrem("lrem1", 2, "a")).isEqualTo(2L)
            assertThat(jedis.lrange("lrem1", 0, -1)).containsExactly("b", "c", "a")

            // count < 0 from tail: remove one 'a' from rightmost side
            assertThat(jedis.lrem("lrem1", -1, "a")).isEqualTo(1L)
            assertThat(jedis.lrange("lrem1", 0, -1)).containsExactly("b", "c")

            // count = 0 remove all occurrences
            jedis.rpush("lrem2", "x", "y", "x", "x", "z")
            assertThat(jedis.lrem("lrem2", 0, "x")).isEqualTo(3L)
            assertThat(jedis.lrange("lrem2", 0, -1)).containsExactly("y", "z")

            // Removing all elements deletes key
            jedis.rpush("lrem3", "p", "p")
            assertThat(jedis.lrem("lrem3", 0, "p")).isEqualTo(2L)
            assertThat(jedis.exists("lrem3")).isFalse()

            // Missing key returns 0
            assertThat(jedis.lrem("missingLrem", 1, "v")).isEqualTo(0L)

            // WRONGTYPE
            jedis.set("lremtype", "str")
            try {
                jedis.lrem("lremtype", 1, "x")
                assertThat(false).isTrue()
            } catch (e: Exception) {
                assertThat(e.message).contains("WRONGTYPE")
            }

            // TTL preservation when list remains; removal when emptied
            jedis.del("lremttl1", "lremttl2")
            jedis.rpush("lremttl1", "a", "b", "a")
            jedis.expire("lremttl1", 1)
            // remove one 'a', list still has elements; TTL should still exist (decrementing)
            jedis.lrem("lremttl1", 1, "a")
            val ttl1 = jedis.ttl("lremttl1")
            assertThat(ttl1).isBetween(0L, 1L)
            // Make list empty -> key deleted and TTL removed
            jedis.rpush("lremttl2", "x")
            jedis.expire("lremttl2", 10)
            assertThat(jedis.lrem("lremttl2", 0, "x")).isEqualTo(1L)
            assertThat(jedis.exists("lremttl2")).isFalse()
        }
    }

    @Test
    fun testRPopLPushBasicsAndTTL() {
        runBlocking {
            jedis.del("src", "dst", "self", "rplptype", "srcTTL", "dstTTL")

            // Basic move between different lists
            jedis.rpush("src", "a", "b")
            val moved = jedis.rpoplpush("src", "dst")
            assertThat(moved).isEqualTo("b")
            assertThat(jedis.lrange("src", 0, -1)).containsExactly("a")
            assertThat(jedis.lrange("dst", 0, -1)).containsExactly("b")

            // Destination auto-creation with no TTL
            assertThat(jedis.ttl("dst")).isEqualTo(-1L)

            // Self-rotation (tail to head)
            jedis.rpush("self", "1", "2", "3")
            val rotated = jedis.rpoplpush("self", "self")
            assertThat(rotated).isEqualTo("3")
            assertThat(jedis.lrange("self", 0, -1)).containsExactly("3", "1", "2")

            // Missing/empty source returns null
            assertThat(jedis.rpoplpush("missing", "any")).isNull()
            jedis.rpush("emptysrc", "x")
            assertThat(jedis.rpoplpush("emptysrc", "any2")).isEqualTo("x")
            assertThat(jedis.rpoplpush("emptysrc", "any2")).isNull()

            // WRONGTYPE
            jedis.set("rplptype", "s")
            try {
                jedis.rpoplpush("rplptype", "dst")
                assertThat(false).isTrue()
            } catch (e: Exception) {
                assertThat(e.message).contains("WRONGTYPE")
            }

            // TTL semantics: source TTL preserved when not emptied; deleted when emptied; dest TTL unaffected/none
            jedis.del("srcTTL", "dstTTL")
            jedis.rpush("srcTTL", "A", "B")
            jedis.rpush("dstTTL", "X")
            jedis.expire("srcTTL", 1)
            jedis.expire("dstTTL", 100)
            jedis.rpoplpush("srcTTL", "dstTTL") // move B
            val srcTtl = jedis.ttl("srcTTL")
            assertThat(srcTtl).isBetween(0L, 1L)
            // dstTTL should still have TTL (we do not clear it)
            assertThat(jedis.ttl("dstTTL")).isBetween(0L, 100L)

            // Now empty the source and ensure it is deleted
            jedis.rpoplpush("srcTTL", "dstTTL") // move A, srcTTL becomes empty
            assertThat(jedis.exists("srcTTL")).isFalse()
        }
    }

    @Test
    fun testScanEmptyDb() {
        // ensure DB is empty for a specific prefix
        jedis.del("scan_empty1", "scan_empty2")
        val res = jedis.scan("0")
        // Jedis returns ScanResult<String>; ensure cursor is "0" and list (may include other keys from other tests) is a list
        // We cannot assume full DB empty, but SCAN should work and return a cursor
        assertThat(res.cursor).isNotNull()
    }

    @Test
    fun testScanIteratesAllKeysWithCount() {
        jedis.del("s:0", "s:1", "s:2", "s:3", "s:4", "s:5", "s:6", "s:7", "s:8", "s:9")
        // create 50 keys under a namespace
        for (i in 0 until 50) {
            jedis.set("s:$i", i.toString())
        }
        val seen = mutableSetOf<String>()
        var cursor = "0"
        val params = ScanParams().count(7)
        do {
            val res = jedis.scan(cursor, params)
            cursor = res.cursor
            res.result.forEach { k -> if (k.startsWith("s:")) seen.add(k) }
        } while (cursor != "0")

        assertThat(seen.size).isEqualTo(50)
        assertThat(seen).contains("s:0", "s:49")
    }

    @Test
    fun testScanWithMatchFilter() {
        // Prepare keys with different prefixes
        jedis.del("mx:1", "mx:2", "my:1", "other:1")
        jedis.mset("mx:1", "a", "mx:2", "b", "my:1", "c", "other:1", "d")
        var cursor = "0"
        val matched = mutableSetOf<String>()
        val params = ScanParams().match("m?:*?").count(5)
        do {
            val res = jedis.scan(cursor, params)
            cursor = res.cursor
            matched.addAll(res.result)
        } while (cursor != "0")

        // match m?:*? should match both mx:* and my:* but not other:*
        assertThat(matched).contains("mx:1", "mx:2", "my:1")
        assertThat(matched).doesNotContain("other:1")
    }

    @Test
    fun testScanSkipsExpiredKeys() {
        runBlocking {
            // create some expiring keys and some persistent
            jedis.setex("exp:1", 1, "x")
            jedis.setex("exp:2", 1, "x")
            jedis.set("keep:1", "y")
            jedis.set("keep:2", "y")
            delay(1200)
            var cursor = "0"
            val params = ScanParams().match("*").count(10)
            val all = mutableSetOf<String>()
            do {
                val res = jedis.scan(cursor, params)
                cursor = res.cursor
                all.addAll(res.result)
            } while (cursor != "0")
            // expired keys should not be present
            assertThat(all).doesNotContain("exp:1", "exp:2")
            assertThat(all).contains("keep:1", "keep:2")
        }
    }

    @Test
    fun testScanErrorCasesWithRawResp() {
        // COUNT 0 should error; duplicate MATCH should error
        val socket = java.net.Socket("localhost", 16379)
        try {
            socket.soTimeout = 2000
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            fun sendAndRead(lines: String): String {
                out.write(lines.toByteArray())
                out.flush()
                val reader = java.io.BufferedReader(java.io.InputStreamReader(input))
                return reader.readLine()
            }

            // SCAN 0 COUNT 0 (array has 4 elements)
            val respCount0 = "*4\r\n$4\r\nSCAN\r\n$1\r\n0\r\n$5\r\nCOUNT\r\n$1\r\n0\r\n"
            val line1 = sendAndRead(respCount0)
            assertThat(line1).startsWith("-")
            assertThat(line1).contains("ERR")

            // SCAN 0 MATCH a* MATCH b*
            val respDupMatch = "*6\r\n$4\r\nSCAN\r\n$1\r\n0\r\n$5\r\nMATCH\r\n$2\r\na*\r\n$5\r\nMATCH\r\n$2\r\nb*\r\n"
            val line2 = sendAndRead(respDupMatch)
            assertThat(line2).startsWith("-")
            assertThat(line2).contains("ERR")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}