package com.xlollx.songport.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** The encrypted channel's pieces: the cipher, the packets cut into messages and put back together, the invite. */
class NtfyWireTest {
    private val gid = "0123456789abcdef"
    private fun groupKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val ana = Identity.create()

    @Test fun sealedBytesOpenOnlyWithTheirKeyAndUnchanged() {
        val k = NtfyKey(groupKey())
        val aad = "group".toByteArray()
        val plain = "+ Band – Song 1".toByteArray()
        val sealed = k.seal(plain, aad)
        assertArrayEquals(plain, k.open(sealed, aad))
        // A fresh nonce each time: the same text never looks the same twice.
        assertNotEquals(B64.encode(sealed), B64.encode(k.seal(plain, aad)))
        // Another group's key, or another place in the group, does not open it.
        assertNull(NtfyKey(groupKey()).open(sealed, aad))
        assertNull(k.open(sealed, "other".toByteArray()))
        // Any byte changed, the nonce or the tag included, and it does not open.
        for (i in sealed.indices) {
            val t = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("byte $i", k.open(t, aad))
        }
        assertNull(k.open(sealed.copyOf(10), aad))
        assertNull(k.open(ByteArray(0), aad))
    }

    @Test fun theTopicComesFromTheKeyAndSaysNothingOfTheGroup() {
        val raw = groupKey()
        val k = NtfyKey(raw)
        assertEquals(k.topic, NtfyKey(raw.copyOf()).topic)
        assertNotEquals(k.topic, NtfyKey(groupKey()).topic)
        assertTrue(k.topic, Regex("[A-Za-z0-9_-]{32}").matches(k.topic))
        assertTrue(B64.encode(raw) !in k.topic)
        assertNull(NtfyKey.of("short"))
    }

    private fun bigFile(n: Int): MemberFile {
        val card = MemberCard(gid, "", "Ana", "TIDAL", 5).signed(ana)
        val events = (1..n).map { i ->
            SharedEvent.of(SharedEvent.ADD, com.xlollx.songport.model.Track("t$i", "Song number $i", listOf("Band $i"), "Album $i", 200_000), ana.id, i.toLong(), 1000L + i).signed(gid, ana)
        }
        return MemberFile(card = card, events = events)
    }

    private fun msgs(texts: List<String>) = texts.mapIndexed { i, t -> NtfyMessage("m$i", 100L + i, t) }

    @Test fun aPacketCutInMessagesComesBackWholeInAnyOrderAndTwiceOver() {
        val k = NtfyKey(groupKey())
        val file = bigFile(120)
        val packed = NtfyWire.pack(NtfyPacket(full = true, file = file))
        val texts = NtfyWire.messages(k, gid, NtfyWire.newSnap(), packed)
        assertTrue("several messages: ${texts.size}", texts.size >= 3)
        assertTrue(texts.all { it.toByteArray().size < NtfyWire.MAX_MESSAGE })
        // Nothing readable on the server.
        assertTrue(texts.none { "Song" in it || "Ana" in it || ana.id in it })

        val shuffled = (texts + texts.take(2)).shuffled(java.util.Random(7))
        val back = NtfyWire.assemble(k, gid, msgs(shuffled))
        assertEquals(1, back.size)
        assertEquals(file, back[0].packet.file)
        assertTrue(back[0].packet.full)

        // A piece missing: nothing (the next snapshot brings it all).
        assertEquals(emptyList<NtfyWire.Assembled>(), NtfyWire.assemble(k, gid, msgs(texts.drop(1))))
        assertEquals(emptyList<NtfyWire.Assembled>(), NtfyWire.assemble(k, gid, msgs(texts.filterIndexed { i, _ -> i != texts.size - 1 })))
        // Another key, or another group: nothing.
        assertEquals(emptyList<NtfyWire.Assembled>(), NtfyWire.assemble(NtfyKey(groupKey()), gid, msgs(texts)))
        assertEquals(emptyList<NtfyWire.Assembled>(), NtfyWire.assemble(k, "fedcba9876543210", msgs(texts)))
    }

    @Test fun aChangedOrForgedPieceIsDroppedAndTheRealOneStillCounts() {
        val k = NtfyKey(groupKey())
        val file = bigFile(60)
        val snap = NtfyWire.newSnap()
        val texts = NtfyWire.messages(k, gid, snap, NtfyWire.pack(NtfyPacket(file = file)))
        assertTrue(texts.size >= 2)
        // One character of the ciphertext changed: that piece does not open, the packet is incomplete.
        val tampered = texts[1].dropLast(3) + (if (texts[1].takeLast(3)[0] == 'A') "B" else "A") + texts[1].takeLast(2)
        assertEquals(0, NtfyWire.assemble(k, gid, msgs(listOf(texts[0], tampered) + texts.drop(2))).size)
        // Pieces moved to another place, or claiming another count: dropped.
        val moved = texts[0].replaceFirst(".0.", ".1.")
        assertEquals(0, NtfyWire.assemble(k, gid, msgs(listOf(moved) + texts.drop(1))).size)
        // A forged piece under the same packet and place, sent first by someone without the key: the real one wins.
        val forged = "th1.$snap.0.${texts.size}." + B64.encode(NtfyKey(groupKey()).seal(ByteArray(100), ByteArray(0)))
        val back = NtfyWire.assemble(k, gid, msgs(listOf(forged, "garbage", "th1.....", "x".repeat(5000), "th1.$snap.9.2.AAAA") + texts))
        assertEquals(listOf(file), back.map { it.packet.file })
    }

    @Test fun aPacketThatUnzipsTooLargeIsNotRead() {
        val k = NtfyKey(groupKey())
        val bomb = java.io.ByteArrayOutputStream().also { o ->
            java.util.zip.GZIPOutputStream(o).use { z -> val zeros = ByteArray(1 shl 20); repeat(9) { z.write(zeros) } }
        }.toByteArray()
        assertTrue(bomb.size < NtfyWire.MAX_CHUNKS * NtfyWire.PIECE)
        val texts = NtfyWire.messages(k, gid, NtfyWire.newSnap(), bomb)
        assertEquals(0, NtfyWire.assemble(k, gid, msgs(texts)).size)
    }

    @Test fun anEncryptedChannelsInviteCarriesItsServerAndKey() {
        val key = SharedTransports.newGroupKey()
        val inv = Invite(gid, "Road trip", ana.id, transport = Invite.NTFY, server = "https://ntfy.example.org/base", key = key)
        val link = inv.link()
        assertEquals(inv, Invite.parse(link))
        assertEquals(inv, Invite.parse(link.replace("https://trackhop.org/join#", "songport://join?")))
        // After the '#': a browser opening the link never sends the key to trackhop.org.
        assertTrue(link.indexOf('#') < link.indexOf(key))
        assertTrue(link.toByteArray().size < com.xlollx.songport.sync.QrCode.capacity(10))
        // Without a key, with a bad key or server, or a way to talk this version does not know: not an invite.
        assertNull(Invite.parse(link.replace("&x=$key", "")))
        assertNull(Invite.parse(link.replace("&x=$key", "&x=short")))
        assertNull(Invite.parse(link.replace("https%3A%2F%2Fntfy.example.org%2Fbase", "ftp%3A%2F%2Fx")))
        assertNull(Invite.parse(link.replace("t=ntfy", "t=carrier-pigeon")))
        // A folder group's invite holds no secret.
        val folder = Invite(gid, "Road trip", ana.id, "Trackhop")
        assertTrue("&x=" !in folder.link() && "&s=" !in folder.link())
        assertEquals(folder, Invite.parse(folder.link()))
    }
}
