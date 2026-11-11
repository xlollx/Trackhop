package com.xlollx.songport.shared

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Track
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A group on an encrypted ntfy channel: Ana creates it, Ben (made a curator) and Cleo (follows)
 * join with the invite, each with their own Trackhop, all on one fake ntfy server that forgets
 * messages after 12 hours, on a clock the test moves.
 */
class NtfyGroupTest {
    private var now = 1_800_000_000_000L
    private val ntfy = FakeNtfy({ now })
    private val server = MockWebServer().apply { dispatcher = ntfy; start() }
    private val address = server.url("/").toString()

    private inner class Member(val name: String) {
        val env = ContractEnv()
        lateinit var groupId: String
        val group: SharedGroup get() = SharedGroups.group(env, groupId)!!
        fun refresh() = SharedGroups.refresh(env, group)
        val songs: List<String> get() = GroupLog(groupId, group.creator).live(SharedGroups.load(env, groupId).events).map { it.title.removePrefix("Song ") }.sorted()
        fun add(vararg n: String) = SharedGroups.add(env, group, n.map { song(it) })
        fun remove(vararg n: String) = SharedGroups.remove(env, group, n.map { song(it) })
        val local = PlaylistRef("mtidal", name.lowercase(), name)
    }

    private fun song(n: String) = Track("t$n", "Song $n", listOf("Band"), "Album", 200_000, isrc = "QZABC26" + n.padStart(5, '0'))

    private val ana = Member("Ana")
    private val ben = Member("Ben")
    private val cleo = Member("Cleo")

    @Before fun setUp() {
        SigCache.clear()
        SharedGroups.clock = { now }
    }

    @After fun tearDown() {
        SharedGroups.clock = { System.currentTimeMillis() }
        server.shutdown()
    }

    private fun hours(h: Int) { now += h * 3_600_000L }

    private fun create(vararg songs: String): Invite {
        val g = SharedGroups.create(ana.env, "Road trip", address, NtfyServer.hostOf(address), "Ana", "TIDAL", ana.local, transport = Invite.NTFY)
        ana.groupId = g.id
        if (songs.isNotEmpty()) ana.add(*songs)
        return Invite.parse(SharedTransports.invite(ana.env, g).link())!!
    }

    private fun join(m: Member, invite: Invite): Boolean {
        val (g, others) = SharedGroups.join(m.env, invite, "", "", m.name, "Spotify", m.local)
        m.groupId = g.id
        return others
    }

    private fun everyone() { ana.refresh(); ben.refresh(); cleo.refresh() }

    @Test fun threeMembersMeetOnTheChannelAndOnlyCuratorsWrite() {
        val invite = create("1", "2", "3", "4", "5")
        assertEquals(Invite.NTFY, invite.transport)
        assertTrue("Ana's snapshot is on the topic", join(ben, invite))
        assertTrue(join(cleo, invite))
        assertEquals(NtfyServer.hostOf(address), ben.group.folderName)
        SharedGroups.setCurator(ana.env, ana.group, ben.group.me, true)
        everyone()
        ben.add("6")
        ana.remove("2")
        everyone(); everyone()
        val expected = listOf("1", "3", "4", "5", "6")
        assertEquals(expected, ana.songs)
        assertEquals(expected, ben.songs)
        assertEquals(expected, cleo.songs)
        assertEquals(listOf("Ana", "Ben", "Cleo"), SharedGroups.members(cleo.group, SharedGroups.load(cleo.env, cleo.groupId)).map { it.name })

        // Cleo holds the group key but follows: an event she signs and puts on the topic herself changes nothing.
        val me = Identity.of(cleo.env.tokens.get(SharedGroups.tokenKey(cleo.groupId))!!.accessToken)!!
        val forged = SharedEvent.of(SharedEvent.REMOVE, song("1"), me.id, 1, now + 1).signed(cleo.groupId, me)
        val card = SharedGroups.load(cleo.env, cleo.groupId).members.first { it.member == me.id }
        val key = SharedTransports.groupKey(cleo.env, cleo.group)!!
        NtfyTransport(cleo.env, cleo.groupId, address, key).publish(me.id, MemberFile(card = card, events = listOf(forged)), 0)
        everyone()
        assertEquals(expected, ana.songs)
        assertEquals(expected, ben.songs)

        // The server saw one topic it cannot read: not the group, its name, the songs or the members.
        assertEquals(setOf(NtfyKey.of(key)!!.topic), ntfy.topics())
        val all = ntfy.messages.joinToString("\n") { it.text }
        for (secret in listOf("Road trip", "Song 1", "Band", ana.groupId, me.id, key)) assertFalse(secret, secret in all)
    }

    @Test fun aChangeGoesAsASmallDeltaAndNothingIsSentWhenNothingChanged() {
        val invite = create("1", "2", "3")
        join(ben, invite)
        ana.refresh(); ben.refresh()
        val before = ntfy.published
        ana.refresh(); ben.refresh()
        assertEquals("nothing new, nothing sent", before, ntfy.published)
        ana.add("4")
        assertEquals("one track, one message", before + 1, ntfy.published)
        ben.refresh()
        assertEquals(listOf("1", "2", "3", "4"), ben.songs)
        // Ben read it: he does not send it back.
        assertEquals(before + 1, ntfy.published)
    }

    @Test fun garbageAndReplaysOnTheTopicChangeNothing() {
        val invite = create("1", "2", "3")
        join(ben, invite)
        join(cleo, invite)
        SharedGroups.setCurator(ana.env, ana.group, ben.group.me, true)
        ben.refresh()
        ben.add("4")
        ana.remove("1")
        everyone()
        val topic = NtfyKey.of(invite.key)!!.topic
        val events = SharedGroups.load(cleo.env, cleo.groupId).events.toSet()
        // Everything sent so far, again, mixed with garbage of every kind.
        val replay = ntfy.messages.map { it.text }
        for (t in listOf("hello", "", "{\"event\":\"message\"}", "th1.AAAAAAAAAAAA.0.1.!!!", "th1.AAAAAAAAAAAA.0.1." + "A".repeat(3000), "\u0000￿")) ntfy.inject(topic, t)
        replay.shuffled(java.util.Random(3)).forEach { ntfy.inject(topic, it) }
        hours(1)
        everyone(); everyone()
        assertEquals(events, SharedGroups.load(cleo.env, cleo.groupId).events.toSet())
        assertEquals(listOf("2", "3", "4"), cleo.songs)
        assertEquals(cleo.songs, ana.songs)
    }

    @Test fun aMemberJoiningAfterTheServerForgotCatchesUpFromAnotherMembersNextSnapshot() {
        val invite = create("1", "2", "3")
        join(ben, invite)
        ben.refresh()
        assertEquals(listOf("1", "2", "3"), ben.songs)
        // A day later the server holds nothing more of the group, and Ana does not come back.
        hours(24)
        assertFalse("nothing on the topic to read", join(cleo, invite))
        assertEquals(emptyList<String>(), cleo.songs)
        // Ben's next sync republishes the whole log, Ana's events included.
        ben.refresh()
        cleo.refresh()
        assertEquals(listOf("1", "2", "3"), cleo.songs)
        assertTrue(SharedGroups.members(cleo.group, SharedGroups.load(cleo.env, cleo.groupId)).any { it.name == "Ana" })
    }

    @Test fun aMemberAwayForDaysCatchesUpWithRemovalsToo() {
        val invite = create("1", "2", "3")
        join(ben, invite)
        join(cleo, invite)
        everyone()
        assertEquals(listOf("1", "2", "3"), cleo.songs)
        // Cleo is away three days; Ana and Ben sync every 6 hours meanwhile.
        for (step in 1..12) {
            hours(6)
            if (step == 2) ana.remove("2")
            if (step == 3) ana.add("6")
            if (step == 10) ana.add("7")
            ana.refresh(); ben.refresh()
        }
        // What told of song 2's removal is long gone from the server; the latest snapshots still hold it.
        cleo.refresh()
        assertEquals(listOf("1", "3", "6", "7"), cleo.songs)
        assertEquals(ana.songs, cleo.songs)
        // Snapshots were refreshed, not sent at every run: well under one per member and run.
        assertTrue("messages: ${ntfy.published}", ntfy.published < 40)
    }

    @Test fun aServerAnsweringNonsenseIsReadWithoutHarm() {
        val other = MockWebServer()
        other.enqueue(okhttp3.mockwebserver.MockResponse().setBody("x".repeat(100_000) + "\n" + "{\"event\":\"message\",\"time\":1,\"message\":\"hi\"}\nnot json\n"))
        other.start()
        try {
            assertEquals(listOf("hi"), NtfyServer(other.url("/").toString()).poll("topic", "all").map { it.text })
        } finally { other.shutdown() }
    }

    @Test fun leavingForgetsTheChannelState() {
        val invite = create("1")
        join(ben, invite)
        ben.refresh()
        val g = ben.group
        SharedGroups.leave(ben.env, g)
        assertEquals(null, SharedTransports.groupKey(ben.env, g))
        assertEquals(NtfyState(), NtfyTransport.load(ben.env, g.id))
        ana.refresh()
        assertTrue(SharedGroups.members(ana.group, SharedGroups.load(ana.env, ana.groupId)).first { it.name == "Ben" }.left)
    }

    /** A protected ntfy server: each member signs in with their own credentials, which never travel in the invite. */
    @Test fun aProtectedServerTakesEachMembersOwnSignInAndTheInviteOnlySaysSo() {
        // Built at run time: nothing shaped like a real secret in the sources.
        val anaToken = NtfyServer.authorization("tk_" + "a1".repeat(14) + "z", "", "")!!
        val benLogin = NtfyServer.authorization("", "ben", "pass" + "w".repeat(6))!!
        assertEquals("Bearer tk_", anaToken.take(10))
        assertTrue(benLogin.startsWith("Basic "))
        assertEquals(null, NtfyServer.authorization("", "ben", ""))
        assertEquals(null, NtfyServer.authorization("", "b:en", "x"))
        assertEquals(null, NtfyServer.authorization("tk_ with space", "", ""))
        ntfy.signIns = setOf(anaToken, benLogin)

        // Without a sign-in the server refuses, and the group is not made.
        val refused = runCatching { SharedGroups.create(ana.env, "Road trip", address, NtfyServer.hostOf(address), "Ana", "TIDAL", ana.local, transport = Invite.NTFY) }
        assertTrue(refused.exceptionOrNull()?.message.orEmpty().startsWith("shared_error_ntfy_signin"))

        val g = SharedGroups.create(ana.env, "Road trip", address, NtfyServer.hostOf(address), "Ana", "TIDAL", ana.local, transport = Invite.NTFY, ntfyAuth = anaToken)
        ana.groupId = g.id
        ana.add("1")
        val link = SharedTransports.invite(ana.env, g).link()
        val invite = Invite.parse(link)!!
        assertTrue(invite.signIn)
        for (secret in listOf(anaToken, anaToken.substringAfter(' '), "tk_")) assertFalse(secret in link)

        // Ben signs in with his own user name and password; with Ana's or none he gets in only with his.
        assertTrue(runCatching { SharedGroups.join(ben.env, invite, "", "", "Ben", "Spotify", ben.local) }.exceptionOrNull()?.message.orEmpty().startsWith("shared_error_ntfy_signin"))
        ben.groupId = SharedGroups.join(ben.env, invite, "", "", "Ben", "Spotify", ben.local, ntfyAuth = benLogin).first.id
        assertEquals(listOf("1"), ben.songs)
        assertTrue(ntfy.headers.all { it == anaToken || it == benLogin })

        // A new sign-in the server refuses is not kept: the old one still works.
        val wrong = NtfyServer.authorization("tk_" + "b2".repeat(14) + "z", "", "")!!
        assertTrue(runCatching { SharedGroups.setNtfyAuth(ben.env, ben.group, wrong) }.isFailure)
        assertEquals(benLogin, SharedTransports.ntfyAuth(ben.env, ben.group))
        ntfy.signIns = setOf(anaToken, wrong)
        SharedGroups.setNtfyAuth(ben.env, ben.group, wrong)
        assertEquals(wrong, SharedTransports.ntfyAuth(ben.env, ben.group))
        ben.refresh()
    }
}
