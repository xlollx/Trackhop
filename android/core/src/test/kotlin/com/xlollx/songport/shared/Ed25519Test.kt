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
}
