package com.xlollx.songport.shared

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin Ed25519 against RFC 8032's test vectors and against the JDK's own Ed25519. */
class Ed25519Test {
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private class Vector(val secret: String, val public: String, val message: String, val signature: String)

    // RFC 8032, section 7.1, tests 1 to 3.
    private val vectors = listOf(
        Vector("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60", "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
        Vector("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb", "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
        Vector("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7", "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"),
    )

    @After fun platformBack() { Ed25519.Platform.enabled = true }

    @Test fun rfcVectors() {
        for (v in vectors) {
            val seed = hex(v.secret)
            assertArrayEquals(hex(v.public), Ed25519.publicKey(seed))
            val sig = Ed25519.sign(seed, hex(v.message))
            assertArrayEquals(hex(v.signature), sig)
            assertTrue(Ed25519.verifyHere(hex(v.public), hex(v.message), sig))
        }
    }

    @Test fun aChangedMessageKeyOrSignatureFails() {
        val seed = hex(vectors[1].secret)
        val pub = Ed25519.publicKey(seed)
        val msg = "+ Band – Song".toByteArray()
        val sig = Ed25519.sign(seed, msg)
        assertTrue(Ed25519.verifyHere(pub, msg, sig))
        assertFalse(Ed25519.verifyHere(pub, "+ Band – Other".toByteArray(), sig))
        assertFalse(Ed25519.verifyHere(Ed25519.publicKey(hex(vectors[0].secret)), msg, sig))
        for (i in listOf(0, 31, 32, 63)) {
            val bad = sig.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFalse("byte $i", Ed25519.verifyHere(pub, msg, bad))
        }
        assertFalse(Ed25519.verifyHere(pub, msg, sig.copyOf(63)))
        assertFalse(Ed25519.verifyHere(ByteArray(32) { 0xff.toByte() }, msg, sig))
    }

    /** The JDK (15+) has Ed25519: both ways agree on random keys and messages, valid or not. */
    @Test fun agreesWithTheJdk() {
        val random = java.util.Random(7)
        repeat(20) { n ->
            val seed = ByteArray(32).also { random.nextBytes(it) }
            val msg = ByteArray(n * 7).also { random.nextBytes(it) }
            val pub = Ed25519.publicKey(seed)
            val sig = Ed25519.sign(seed, msg)
            // The JDK signs the same: Ed25519 is deterministic.
            val jdkKey = java.security.KeyFactory.getInstance("Ed25519").generatePrivate(
                java.security.spec.PKCS8EncodedKeySpec(hex("302e020100300506032b657004220420") + seed))
            val jdkSig = java.security.Signature.getInstance("Ed25519").run { initSign(jdkKey); update(msg); sign() }
            assertArrayEquals(jdkSig, sig)
            val bad = sig.copyOf().also { it[n % 64] = (it[n % 64].toInt() xor 4).toByte() }
            for (s in listOf(sig, bad)) assertEquals(Ed25519.Platform.verify(pub, msg, s), Ed25519.verifyHere(pub, msg, s))
        }
    }

    @Test fun withoutThePlatformTheKotlinCheckAnswers() {
        Ed25519.Platform.enabled = false
        val seed = Ed25519.newPrivateKey()
        val sig = Ed25519.sign(seed, byteArrayOf(1, 2, 3))
        assertTrue(Ed25519.verify(Ed25519.publicKey(seed), byteArrayOf(1, 2, 3), sig))
        assertFalse(Ed25519.verify(Ed25519.publicKey(seed), byteArrayOf(1, 2, 4), sig))
    }

    private val L = java.math.BigInteger.ONE.shiftLeft(252).add(java.math.BigInteger("27742317777372353535851937790883648493"))
    private fun le(n: java.math.BigInteger): ByteArray = ByteArray(32).also { out -> val b = n.toByteArray().reversedArray(); b.copyInto(out, 0, 0, minOf(32, b.size)) }

    /** S + L is the same point on the curve: a second signature anyone could make from the first. Both ways refuse it. */
    @Test fun theMalleableTwinOfASignatureIsRefused() {
        val seed = hex(vectors[2].secret)
        val pub = Ed25519.publicKey(seed)
        val msg = hex(vectors[2].message)
        val sig = Ed25519.sign(seed, msg)
        val s = java.math.BigInteger(1, sig.copyOfRange(32, 64).reversedArray())
        val twin = sig.copyOfRange(0, 32) + le(s.add(L))
        assertFalse(Ed25519.verifyHere(pub, msg, twin))
        assertFalse(Ed25519.verify(pub, msg, twin))
        assertEquals(false, Ed25519.Platform.verify(pub, msg, twin) ?: false)
        // An S of all ones (far above L) as well.
        val high = sig.copyOfRange(0, 32) + ByteArray(32) { 0xff.toByte() }.also { it[31] = 0x7f }
        assertFalse(Ed25519.verify(pub, msg, high))
        assertFalse(Ed25519.verifyHere(pub, msg, high))
    }

    /**
     * A key of small order "verifies" signatures nobody made: with the neutral point as the key,
     * R = B and S = 1 pass the plain equation for every message. Such keys, and keys that are not a
     * point or not canonically written, are refused before any check.
     */
    @Test fun smallOrderAndMalformedKeysAreRefused() {
        val smallOrder = listOf(
            "0100000000000000000000000000000000000000000000000000000000000000", // the neutral point
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", // order 2
            "0000000000000000000000000000000000000000000000000000000000000000", // order 4
            "0000000000000000000000000000000000000000000000000000000000000080",
            "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a", // order 8
            "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fa",
            "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
            "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc85",
        ).map { hex(it) }
        val forged = hex("5866666666666666666666666666666666666666666666666666666666666666") + le(java.math.BigInteger.ONE)
        val msg = "- Band – Song".toByteArray()
        // Without the key check the plain equation holds for the neutral point: the forgery is real.
        assertTrue(Ed25519.Platform.verify(smallOrder[0], msg, forged) ?: true)
        for (k in smallOrder) {
            assertFalse(Ed25519.usableKey(k))
            assertFalse(Ed25519.verify(k, msg, forged))
            assertFalse(Ed25519.verifyHere(k, msg, forged))
        }
        // y = p and y = p + 1: the same points as 0 and 1, written the long way.
        for (k in listOf("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f")) {
            assertFalse(Ed25519.usableKey(hex(k)))
        }
        // x = 0 with the sign bit set ("minus zero").
        assertFalse(Ed25519.usableKey(hex("0100000000000000000000000000000000000000000000000000000000000080")))
        assertTrue(Ed25519.usableKey(hex(vectors[0].public)))
    }

    /** The platform signs with the raw seed where it can, and gives the reference code's very bytes. */
    @Test fun platformAndKotlinSignTheSame() {
        val random = java.util.Random(11)
        repeat(16) { n ->
            val seed = ByteArray(32).also { random.nextBytes(it) }
            val msg = ByteArray(n * 13).also { random.nextBytes(it) }
            val platform = Ed25519.Platform.sign(seed, msg)
            assertTrue("the JDK 17 running the tests signs", platform != null)
            assertArrayEquals(Ed25519.signHere(seed, msg), platform)
        }
        for (v in vectors) assertArrayEquals(hex(v.signature), Ed25519.Platform.sign(hex(v.secret), hex(v.message)))
    }

    /** Random keys (half of all 32-byte strings are not points), random signatures: both checks answer alike. */
    @Test fun agreesWithTheJdkOnGarbage() {
        val random = java.util.Random(19)
        var valid = 0
        repeat(200) { n ->
            val seed = ByteArray(32).also { random.nextBytes(it) }
            val msg = ByteArray(n % 40).also { random.nextBytes(it) }
            val pub = if (n % 2 == 0) Ed25519.publicKey(seed) else ByteArray(32).also { random.nextBytes(it) }
            val good = Ed25519.signHere(seed, msg)
            val candidates = listOf(
                good,
                ByteArray(64).also { random.nextBytes(it) },
                good.copyOf().also { it[random.nextInt(64)] = (it[random.nextInt(64)].toInt() xor (1 shl random.nextInt(8))).toByte() },
                good.copyOfRange(0, 32) + ByteArray(32).also { random.nextBytes(it); it[31] = (it[31].toInt() and 0x0f).toByte() },
            )
            for (sig in candidates) {
                val here = Ed25519.verifyHere(pub, msg, sig)
                val platform = Ed25519.Platform.verify(pub, msg, sig)
                if (Ed25519.usableKey(pub)) assertEquals("vector $n", platform, here)
                assertEquals(here, Ed25519.verify(pub, msg, sig))
                if (here) valid++
            }
        }
        assertTrue(valid >= 100)
    }
}
