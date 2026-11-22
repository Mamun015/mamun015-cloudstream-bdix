package com.mamun.bdix

import android.util.Base64
import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Resolves a flixcloud.cc embed page into the real HLS playlist URL.
 *
 * The embed page ships a per-load key-mixing WASM module plus an obfuscated
 * blob. The playlist URL is AES-CBC encrypted and only comes out when the
 * module is executed with the page values, so the module is interpreted here.
 */
object FlixCloud {

    data class Resolved(val url: String, val subtitles: List<Pair<String, String>>)

    suspend fun resolve(embedUrl: String): Resolved? {
        return runCatching {
        val html = app.get(embedUrl, headers = HEADERS, timeout = TIMEOUT).text

        val seed = Regex("obfuscation_seed:\"([0-9a-f]+)\"").find(html)
            ?.groupValues?.get(1) ?: return null
        val wasmB64 = Regex("w_payload:\"(AGFzbQ[A-Za-z0-9+/=]+)\"").find(html)
            ?.groupValues?.get(1) ?: return null

        val e = chain(seed)
        val a = chain(e)

        fun field(name: String): String? =
            Regex("\"?${Regex.escape(name)}\"?\\s*:\\s*\"([^\"]+)\"")
                .find(html)?.groupValues?.get(1)

        val frag1 = field("kf_" + e.substring(8, 16))?.b64() ?: return null
        val iv = field("ivf_" + e.substring(16, 24))?.b64() ?: return null
        val frag2 = field(a.substring(0, 16) + "_" + a.substring(16, 24))?.b64() ?: return null
        val token = field(e.substring(48, 64) + "_" + e.substring(56, 64)) ?: return null

        val answer = app.get(
            "https://flixcloud.cc/api/m3u8/$token",
            headers = HEADERS + mapOf("Referer" to embedUrl, "Origin" to "https://flixcloud.cc"),
            timeout = TIMEOUT,
        ).text
        val node = JSONObject(answer)
        val cipher = node.optString(sha256hex(token + "vid").substring(0, 10)).b64() ?: return null
        val salt = node.optString(sha256hex(token + "key").substring(0, 10)).b64() ?: return null

        val wasm = wasmB64.b64() ?: return null
        // Seed prefixes above 0x7FFFFFFF overflow Int.parseInt, so parse as Long
        // and keep the low 32 bits: the module's _s(k) expects an i32.
        val key = seed.substring(0, 8).toLong(16).toInt()
        val mixed = WasmModule(wasm).mix(frag1, frag2, salt, key) ?: return null

        val derived = pbkdf2(mixed, seed.toByteArray(), 1000, 32)
        val xored = ByteArray(32) { (derived[it].toInt() xor seed[it % seed.length].code).toByte() }
        val aesKey = MessageDigest.getInstance("SHA-256").digest(xored)

        val url = aesDecrypt(aesKey, iv, cipher) ?: return null
        if (!url.startsWith("http")) return null

        Resolved(url, subtitles(html))
    }.getOrNull()
    }

    private fun chain(value: String): String {
        var s = value
        repeat(3) { s = sha256hex(s + it) }
        return s
    }

    private fun subtitles(html: String): List<Pair<String, String>> =
        Regex("url:\"(https?://[^\"]+)\",language:\"([^\"]*)\"")
            .findAll(html)
            .map { it.groupValues[2] to it.groupValues[1] }
            .distinctBy { it.second }
            .toList()

    private fun sha256hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.b64(): ByteArray? = runCatching {
        Base64.decode(this, Base64.DEFAULT)
    }.getOrNull()

    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val out = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (j in out.indices) out[j] = (out[j].toInt() xor u[j].toInt()).toByte()
        }
        return out.copyOf(length)
    }

    private fun aesDecrypt(key: ByteArray, iv: ByteArray, cipherText: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8).trim()
    }.getOrNull()

    private const val TIMEOUT = 25L

    private val HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
    )

    /**
     * Minimal interpreter for the flixcloud key-mixing module. The module is a
     * different program on every page load, so it has to be executed rather
     * than pattern matched.
     */
    private class WasmModule(private val bin: ByteArray) {

        private var p = 0
        private var bodies: Array<ByteArray> = emptyArray()
        private val exports = HashMap<String, Int>()
        private val globals = IntArray(8)
        private val mem = ByteArray(1 shl 17)

        fun mix(frag1: ByteArray, frag2: ByteArray, salt: ByteArray, key: Int): ByteArray? {
            if (frag1.isEmpty() || frag2.size < frag1.size || salt.size < frag1.size) return null
            val len = frag1.size
            val t = 1000
            val a = t + len
            val w = a + len
            val out = w + len
            if (out + len > mem.size) return null
            System.arraycopy(frag1, 0, mem, t, len)
            System.arraycopy(frag2, 0, mem, a, len)
            System.arraycopy(salt, 0, mem, w, len)
            globals[0] = key
            exports["_s"]?.let { call(it, key) }
            val run = exports["_r"] ?: return null
            call(run, t, a, w, out, len)
            return mem.copyOfRange(out, out + len)
        }

        private fun u32(): Int {
            var r = 0
            var s = 0
            while (true) {
                val x = bin[p++].toInt() and 255
                r = r or ((x and 127) shl s)
                if ((x and 128) == 0) break
                s += 7
            }
            return r
        }

        private fun s32(): Int {
            var r = 0
            var s = 0
            var x: Int
            do {
                x = bin[p++].toInt() and 255
                r = r or ((x and 127) shl s)
                s += 7
            } while ((x and 128) != 0)
            if (s < 32 && (x and 64) != 0) r = r or (-1 shl s)
            return r
        }

        private fun name(): String {
            val n = u32()
            val s = String(bin, p, n, Charsets.UTF_8)
            p += n
            return s
        }

        init {
            p = 8
            while (p < bin.size) {
                val id = bin[p++].toInt() and 255
                val size = u32()
                val end = p + size
                when (id) {
                    1 -> repeat(u32()) {
                        p++
                        p += u32()
                        p += u32()
                    }
                    3 -> repeat(u32()) { u32() }
                    5 -> repeat(u32()) {
                        val flags = bin[p++].toInt() and 255
                        u32()
                        if (flags == 1) u32()
                    }
                    6 -> repeat(u32()) { i ->
                        p += 2
                        val op = bin[p++].toInt() and 255
                        if (op == 0x41) globals[i] = s32()
                        p++
                    }
                    7 -> repeat(u32()) {
                        val nm = name()
                        p++
                        exports[nm] = u32()
                    }
                    10 -> {
                        val n = u32()
                        bodies = Array(n) {
                            val bodySize = u32()
                            val body = bin.copyOfRange(p, p + bodySize)
                            p += bodySize
                            body
                        }
                    }
                    11 -> repeat(u32()) {
                        val flags = u32()
                        var offset = 0
                        if (flags == 0) {
                            val op = bin[p++].toInt() and 255
                            if (op == 0x41) offset = s32()
                            p++
                        } else if (flags == 2) {
                            u32()
                            val op = bin[p++].toInt() and 255
                            if (op == 0x41) offset = s32()
                            p++
                        }
                        val len = u32()
                        System.arraycopy(bin, p, mem, offset, len)
                        p += len
                    }
                }
                p = end
            }
        }

        private fun call(funcIdx: Int, vararg args: Int) {
            val body = bodies[funcIdx]
            var bp = 0
            val groups = body[bp++].toInt() and 255
            val locals = IntArray(args.size + 64)
            args.copyInto(locals)
            repeat(groups) {
                bp++
                bp++
            }

            val stack = IntArray(512)
            var sp = 0
            val ctrl = ArrayList<IntArray>(32)
            var pc = bp
            ctrl.add(intArrayOf(0, bp, body.size))

            loop@ while (true) {
                when (val op = body[pc].toInt() and 255) {
                    0x00 -> return
                    0x02, 0x03, 0x04 -> {
                        val start = pc + 2
                        val end = findEnd(body, start)
                        ctrl.add(intArrayOf(if (op == 0x02) 1 else if (op == 0x03) 2 else 3, start, end))
                        if (op == 0x04) {
                            sp--
                            if (stack[sp] == 0) {
                                val elseAt = findElse(body, start)
                                if (elseAt >= 0) {
                                    pc = elseAt + 1
                                } else {
                                    ctrl.removeAt(ctrl.size - 1)
                                    pc = end + 1
                                }
                                continue@loop
                            }
                        }
                        pc = start
                    }
                    0x05 -> {
                        val f = ctrl.removeAt(ctrl.size - 1)
                        pc = f[2] + 1
                    }
                    0x0b -> {
                        val f = ctrl.removeAt(ctrl.size - 1)
                        if (f[0] == 0) break@loop
                        pc = f[2] + 1
                    }
                    0x0c -> {
                        val depth = body[pc + 1].toInt() and 255
                        val target = branch(ctrl, depth) ?: break@loop
                        pc = target
                    }
                    0x0d -> {
                        val depth = body[pc + 1].toInt() and 255
                        pc += 2
                        sp--
                        if (stack[sp] != 0) {
                            val target = branch(ctrl, depth) ?: break@loop
                            pc = target
                        }
                    }
                    0x0f -> break@loop
                    0x1a -> { sp--; pc++ }
                    0x1b -> { sp--; val c = stack[sp]; sp--; val v2 = stack[sp]; sp--; val v1 = stack[sp]; stack[sp++] = if (c != 0) v1 else v2; pc++ }
                    0x20 -> { stack[sp++] = locals[body[pc + 1].toInt() and 255]; pc += 2 }
                    0x21 -> { locals[body[pc + 1].toInt() and 255] = stack[--sp]; pc += 2 }
                    0x22 -> { locals[body[pc + 1].toInt() and 255] = stack[sp - 1]; pc += 2 }
                    0x23 -> { stack[sp++] = globals[body[pc + 1].toInt() and 255]; pc += 2 }
                    0x24 -> { globals[body[pc + 1].toInt() and 255] = stack[--sp]; pc += 2 }
                    0x28 -> {
                        val addr = stack[--sp]
                        val al = readU32(body, pc + 1)
                        val off = readU32(body, al[0]); pc = off[0]
                        val o = addr + off[1]
                        stack[sp++] = (mem[o].toInt() and 255) or
                            ((mem[o + 1].toInt() and 255) shl 8) or
                            ((mem[o + 2].toInt() and 255) shl 16) or
                            ((mem[o + 3].toInt() and 255) shl 24)
                    }
                    0x2c -> {
                        val addr = stack[--sp]
                        val al = readU32(body, pc + 1)
                        val off = readU32(body, al[0]); pc = off[0]
                        stack[sp++] = mem[addr + off[1]].toInt()
                    }
                    0x2d -> {
                        val addr = stack[--sp]
                        val al = readU32(body, pc + 1)
                        val off = readU32(body, al[0]); pc = off[0]
                        stack[sp++] = mem[addr + off[1]].toInt() and 255
                    }
                    0x36 -> {
                        val v = stack[--sp]; val addr = stack[--sp]
                        val al = readU32(body, pc + 1)
                        val off = readU32(body, al[0]); pc = off[0]
                        val o = addr + off[1]
                        mem[o] = v.toByte()
                        mem[o + 1] = (v shr 8).toByte()
                        mem[o + 2] = (v shr 16).toByte()
                        mem[o + 3] = (v shr 24).toByte()
                    }
                    0x3a -> {
                        val v = stack[--sp]; val addr = stack[--sp]
                        val al = readU32(body, pc + 1)
                        val off = readU32(body, al[0]); pc = off[0]
                        mem[addr + off[1]] = v.toByte()
                    }
                    0x41 -> { val c = readS32(body, pc + 1); pc = c[0]; stack[sp++] = c[1] }
                    0x45 -> { stack[sp - 1] = if (stack[sp - 1] == 0) 1 else 0; pc++ }
                    0x46 -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = if (x == y) 1 else 0; pc++ }
                    0x47 -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = if (x != y) 1 else 0; pc++ }
                    0x48 -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = if (x < y) 1 else 0; pc++ }
                    0x49 -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = if (cmpU(x, y) < 0) 1 else 0; pc++ }
                    0x4a -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = if (x > y) 1 else 0; pc++ }
                    0x4b -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = if (cmpU(x, y) > 0) 1 else 0; pc++ }
                    0x4c -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = if (x <= y) 1 else 0; pc++ }
                    0x4d -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = if (cmpU(x, y) <= 0) 1 else 0; pc++ }
                    0x4e -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = if (x >= y) 1 else 0; pc++ }
                    0x4f -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = if (cmpU(x, y) >= 0) 1 else 0; pc++ }
                    0x67 -> { stack[sp - 1] = Integer.numberOfLeadingZeros(stack[sp - 1]); pc++ }
                    0x68 -> { stack[sp - 1] = Integer.numberOfTrailingZeros(stack[sp - 1]); pc++ }
                    0x69 -> { stack[sp - 1] = Integer.bitCount(stack[sp - 1]); pc++ }
                    0x6a -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = x + y; pc++ }
                    0x6b -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = x - y; pc++ }
                    0x6c -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = x * y; pc++ }
                    0x6d -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = x / y; pc++ }
                    0x6e -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = divU(x, y); pc++ }
                    0x6f -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = x % y; pc++ }
                    0x70 -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = remU(x, y); pc++ }
                    0x71 -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = x and y; pc++ }
                    0x72 -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = x or y; pc++ }
                    0x73 -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = x xor y; pc++ }
                    0x74 -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = x shl y; pc++ }
                    0x75 -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = x shr y; pc++ }
                    0x76 -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = x ushr y; pc++ }
                    0x77 -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = rotl(x, y); pc++ }
                    0x78 -> { sp--; val y = stack[sp]; sp--; val x = stack[sp]; stack[sp++] = rotr(x, y); pc++ }
                    else -> return
                }
            }
        }

        private fun cmpU(x: Int, y: Int): Int =
            (x xor Int.MIN_VALUE).compareTo(y xor Int.MIN_VALUE)

        private fun divU(x: Int, y: Int): Int =
            ((x.toLong() and 0xFFFFFFFFL) / (y.toLong() and 0xFFFFFFFFL)).toInt()

        private fun remU(x: Int, y: Int): Int =
            ((x.toLong() and 0xFFFFFFFFL) % (y.toLong() and 0xFFFFFFFFL)).toInt()

        private fun rotl(x: Int, y: Int): Int = (x shl y) or (x ushr (32 - y))

        private fun rotr(x: Int, y: Int): Int = (x ushr y) or (x shl (32 - y))

        private fun branch(ctrl: ArrayList<IntArray>, depth: Int): Int? {
            val idx = ctrl.size - 1 - depth
            if (idx < 0) return null
            val frame = ctrl[idx]
            if (frame[0] == 2) {
                while (ctrl.size > idx + 1) ctrl.removeAt(ctrl.size - 1)
                return frame[1]
            }
            while (ctrl.size > idx) ctrl.removeAt(ctrl.size - 1)
            return frame[2] + 1
        }

        private fun findEnd(body: ByteArray, from: Int): Int {
            var depth = 0
            var i = from
            while (i < body.size) {
                val op = body[i].toInt() and 255
                if (op == 0x02 || op == 0x03 || op == 0x04) {
                    depth++
                    i += 2
                    continue
                }
                if (op == 0x0b) {
                    if (depth == 0) return i
                    depth--
                    i++
                    continue
                }
                i += opLen(body, i)
            }
            return body.size - 1
        }

        private fun findElse(body: ByteArray, from: Int): Int {
            var depth = 0
            var i = from
            while (i < body.size) {
                val op = body[i].toInt() and 255
                if (op == 0x02 || op == 0x03 || op == 0x04) {
                    depth++
                    i += 2
                    continue
                }
                if (op == 0x0b) {
                    if (depth == 0) return -1
                    depth--
                    i++
                    continue
                }
                if (op == 0x05 && depth == 0) return i
                i += opLen(body, i)
            }
            return -1
        }

        private fun opLen(body: ByteArray, i: Int): Int {
            return when (body[i].toInt() and 255) {
                0x20, 0x21, 0x22, 0x23, 0x24, 0x0c, 0x0d -> 2
                0x28, 0x2c, 0x2d, 0x36, 0x3a -> {
                    var j = i + 1
                    while ((body[j].toInt() and 128) != 0) j++
                    j++
                    while ((body[j].toInt() and 128) != 0) j++
                    j + 1 - i
                }
                0x41, 0x42 -> {
                    var j = i + 1
                    while ((body[j].toInt() and 128) != 0) j++
                    j + 1 - i
                }
                else -> 1
            }
        }

        private fun readU32(body: ByteArray, from: Int): IntArray {
            var i = from
            var r = 0
            var s = 0
            while (true) {
                val x = body[i++].toInt() and 255
                r = r or ((x and 127) shl s)
                if ((x and 128) == 0) break
                s += 7
            }
            return intArrayOf(i, r)
        }

        private fun readS32(body: ByteArray, from: Int): IntArray {
            var i = from
            var r = 0
            var s = 0
            var x: Int
            do {
                x = body[i++].toInt() and 255
                r = r or ((x and 127) shl s)
                s += 7
            } while ((x and 128) != 0)
            if (s < 32 && (x and 64) != 0) r = r or (-1 shl s)
            return intArrayOf(i, r)
        }
    }
}
