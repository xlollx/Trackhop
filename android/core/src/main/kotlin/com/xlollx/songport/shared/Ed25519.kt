package com.xlollx.songport.shared

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Ed25519 signatures (RFC 8032), in plain Kotlin: the app runs from Android 8, and the platform
 * only offers Ed25519 from much later, with no library in the build that has it. Signing follows
 * the RFC's reference code. Checking a signature is the slow part (two scalar multiplications), so
 * it goes through the platform's own Ed25519 where there is one (the server's JDK, newer Android)
 * and falls back to the same reference code elsewhere; both give the same answer, the tests check it.
 *
 * Keys travel as their raw 32 bytes: the private key is the RFC's 32-byte seed.
 */
object Ed25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val SQRT_M1: BigInteger = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
    private val TWO_D: BigInteger = D.shiftLeft(1).mod(P)

    /** A point in extended coordinates (X:Y:Z:T), x = X/Z, y = Y/Z, xy = T/Z. */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val NEUTRAL = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    private val BASE: Point = run {
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        val x = recoverX(y, 0)!!
        Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun add(a: Point, b: Point): Point {
        val aa = a.y.subtract(a.x).multiply(b.y.subtract(b.x)).mod(P)
        val bb = a.y.add(a.x).multiply(b.y.add(b.x)).mod(P)
        val c = a.t.multiply(TWO_D).multiply(b.t).mod(P)
        val d = a.z.shiftLeft(1).multiply(b.z).mod(P)
        val e = bb.subtract(aa)
        val f = d.subtract(c)
        val g = d.add(c)
        val h = bb.add(aa)
        return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun mul(s: BigInteger, p: Point): Point {
        var q = NEUTRAL
        var r = p
        for (i in 0 until s.bitLength()) {
            if (s.testBit(i)) q = add(q, r)
            r = add(r, r)
        }
        return q
    }

    private fun same(a: Point, b: Point): Boolean =
        a.x.multiply(b.z).subtract(b.x.multiply(a.z)).mod(P).signum() == 0 &&
            a.y.multiply(b.z).subtract(b.y.multiply(a.z)).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val x2 = y.multiply(y).subtract(BigInteger.ONE).multiply(D.multiply(y).multiply(y).add(BigInteger.ONE).modInverse(P)).mod(P)
        if (x2.signum() == 0) return if (sign != 0) null else BigInteger.ZERO
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x)
        return x
    }

    private fun littleEndian(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    private fun toLittleEndian(n: BigInteger, size: Int = 32): ByteArray {
        val big = n.toByteArray()
        val out = ByteArray(size)
        for (i in big.indices) {
            val at = big.size - 1 - i
            if (i < size) out[i] = big[at]
        }
        return out
    }

    private fun compress(p: Point): ByteArray {
        val zi = p.z.modInverse(P)
        val x = p.x.multiply(zi).mod(P)
        val y = p.y.multiply(zi).mod(P)
        return toLittleEndian(if (x.testBit(0)) y.setBit(255) else y)
    }

    private fun decompress(b: ByteArray): Point? {
        if (b.size != 32) return null
        var y = littleEndian(b)
        val sign = if (y.testBit(255)) 1 else 0
        y = y.clearBit(255)
        val x = recoverX(y, sign) ?: return null
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun sha512(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").run { parts.forEach { update(it) }; digest() }

    private fun expand(seed: ByteArray): Pair<BigInteger, ByteArray> {
        require(seed.size == 32) { "an Ed25519 private key is 32 bytes" }
        val h = sha512(seed)
        var a = littleEndian(h.copyOfRange(0, 32))
        a = a.and(BigInteger.ONE.shiftLeft(254).subtract(BigInteger.valueOf(8))).or(BigInteger.ONE.shiftLeft(254))
        return a to h.copyOfRange(32, 64)
    }

    /** A new private key (32 random bytes). */
    fun newPrivateKey(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun publicKey(seed: ByteArray): ByteArray = compress(mul(expand(seed).first, BASE))

    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        val (a, prefix) = expand(seed)
        val pub = compress(mul(a, BASE))
        val r = littleEndian(sha512(prefix, message)).mod(L)
        val rs = compress(mul(r, BASE))
        val h = littleEndian(sha512(rs, pub, message)).mod(L)
        val s = r.add(h.multiply(a)).mod(L)
        return rs + toLittleEndian(s)
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        Platform.verify(publicKey, message, signature)?.let { return it }
        return verifyHere(publicKey, message, signature)
    }

    /** The reference check, always in Kotlin (the tests compare it with the platform's). */
    internal fun verifyHere(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decompress(publicKey) ?: return false
        val rs = signature.copyOfRange(0, 32)
        val r = decompress(rs) ?: return false
        val s = littleEndian(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val h = littleEndian(sha512(rs, publicKey, message)).mod(L)
        return same(mul(s, BASE), add(r, mul(h, a)))
    }

    /** The platform's Ed25519 (JDK 15+, Android where its provider has it); null answers = not there. */
    internal object Platform {
        // The DER header of an X.509 Ed25519 public key; the raw 32 bytes follow.
        private val X509_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
        @Volatile var enabled = true
        private val available: Boolean by lazy {
            runCatching { java.security.Signature.getInstance("Ed25519"); java.security.KeyFactory.getInstance("Ed25519") }.isSuccess
        }

        fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean? {
            if (!enabled || !available) return null
            return runCatching {
                val key = java.security.KeyFactory.getInstance("Ed25519").generatePublic(java.security.spec.X509EncodedKeySpec(X509_PREFIX + publicKey))
                java.security.Signature.getInstance("Ed25519").run { initVerify(key); update(message); verify(signature) }
            }.getOrElse { e ->
                // A malformed key or signature is a "no"; a provider that cannot do it at all hands over to the Kotlin code.
                if (e is java.security.SignatureException || e is java.security.spec.InvalidKeySpecException || e is java.security.InvalidKeyException) false else null
            }
        }
    }
}
