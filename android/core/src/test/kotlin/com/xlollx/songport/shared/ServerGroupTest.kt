package com.xlollx.songport.shared

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.sync.ServerLocks
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * A group meeting in a mailbox on Ana's own Trackhop server: Ana creates it with her API token
 * ("Coordinate with my server"), Ben (made a curator) and Cleo (follows) join with the invite only,
 * each with their own Trackhop, on a fake server that checks no signature (the members do).
 */
class ServerGroupTest {
    private var now = 1_800_000_000_000L
    // Built at run time: nothing shaped like a real secret in the sources.
    private val apiToken = "th_" + "a".repeat(32)
    private val fake = FakeMailboxServer(apiToken)
    private val server = MockWebServer().apply { dispatcher = fake; start() }
    private val address = server.url("/").toString().trimEnd('/')

    private inner class Member(val name: String) {
        val env = ContractEnv()
        lateinit var groupId: String
        val group: SharedGroup get() = SharedGroups.group(env, groupId)!!
        fun refresh() = SharedGroups.refresh(env, group)
        val songs: List<String> get() = GroupLog(groupId, group.creator).live(SharedGroups.load(env, groupId).events).map { it.title.removePrefix("Song ") }.sorted()
        fun add(vararg n: String) = SharedGroups.add(env, group, n.map { song(it) })
        fun remove(vararg n: String) = SharedGroups.remove(env, group, n.map { song(it) })
        val local = PlaylistRef("mtidal", name.lowercase(), name)
        val identity: Identity get() = Identity.of(env.tokens.get(SharedGroups.tokenKey(groupId))!!.accessToken)!!
    }

    private fun song(n: String) = Track("t$n", "Song $n", listOf("Band"), "Album", 200_000, isrc = "QZABC26" + n.padStart(5, '0'))

    private val ana = Member("Ana")
    private val ben = Member("Ben")
    private val cleo = Member("Cleo")

    @Before fun setUp() {
        SigCache.clear()
        SharedGroups.clock = { now }
        ServerLocks.save(ana.env, ServerLocks.Config(address, apiToken))
    }

    @After fun tearDown() {
        SharedGroups.clock = { System.currentTimeMillis() }
        server.shutdown()
    }

    private fun hours(h: Int) { now += h * 3_600_000L }

    private fun create(vararg songs: String): Invite {
        val g = SharedGroups.create(ana.env, "Road trip", address, NtfyServer.hostOf(address), "Ana", "TIDAL", ana.local, transport = Invite.SERVER)
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

    private fun events(of: List<SharedEvent>) = of.map { MemberFile.codec.encodeToString(SharedEvent.serializer(), it) }

    @Test fun threeMembersMeetOnTheServerAndOnlyCuratorsWrite() {
        val invite = create("1", "2", "3", "4", "5")
        assertEquals(Invite.SERVER, invite.transport)
        assertTrue(ServerGroupTransport.validKey(invite.key))
        assertTrue("Ana's items are in the mailbox", join(ben, invite))
        assertTrue(join(cleo, invite))
        // Ben and Cleo have no token of the server: the invite is all they hold.
        assertNull(ServerLocks.config(ben.env))
        assertEquals(NtfyServer.hostOf(address), cleo.group.folderName)
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

        // Cleo holds the key but follows: an event she signs and sends herself changes nothing.
        val me = cleo.identity
        val card = SharedGroups.load(cleo.env, cleo.groupId).members.first { it.member == me.id }
        val forged = SharedEvent.of(SharedEvent.REMOVE, song("1"), me.id, 1, now + 1).signed(cleo.groupId, me)
        ServerGroupTransport(cleo.env, cleo.groupId, address, invite.key).publish(me.id, MemberFile(card = card, events = listOf(forged)), 0)
        // Nor do events in Ana's name she cannot sign, a curators' list of her own, or garbage a careless server keeps.
        val asAna = SharedEvent.of(SharedEvent.REMOVE, song("3"), ana.group.me, 99, now + 1).signed(cleo.groupId, me).copy(by = ana.group.me)
        events(listOf(asAna)).forEach { fake.inject(ana.groupId, "events", it) }
        fake.inject(ana.groupId, "rosters", MemberFile.codec.encodeToString(Roster.serializer(), Roster(ana.groupId, 7, now, listOf(Grant(me.id))).signed(me)))
        for (junk in listOf("{}", "{\"by\":1}", "{\"op\":\"-\",\"seq\":-1}")) fake.inject(ana.groupId, "events", junk)
        fake.inject(ana.groupId, "cards", """{"groupId":"${ana.groupId}","member":"${ana.group.me}","name":"Not Ana","at":${now + 5},"sig":"${"A".repeat(86)}"}""")
        everyone()
        assertEquals(expected, ana.songs)
        assertEquals(expected, ben.songs)
        assertEquals(expected, cleo.songs)
        assertEquals("Ana", SharedGroups.members(ben.group, SharedGroups.load(ben.env, ben.groupId)).first { it.id == ana.group.me }.name)
    }

    @Test fun onlyWhatTheMailboxLacksIsSentAndReadsGoOnFromTheCursor() {
        val invite = create("1", "2", "3")
        join(ben, invite)
        ana.refresh(); ben.refresh()
        val writes = fake.writes
        val stored = fake.boxes.getValue(ana.groupId).items.size
        ana.refresh(); ben.refresh()
        assertEquals("nothing new, nothing sent", writes, fake.writes)
        ana.add("4")
        assertEquals(writes + 1, fake.writes)
        assertEquals("one track, one item", stored + 1, fake.boxes.getValue(ana.groupId).items.size)
        ben.refresh()
        assertEquals(listOf("1", "2", "3", "4"), ben.songs)
        // Ben read it: he does not send it back.
        assertEquals(writes + 1, fake.writes)
        assertEquals(fake.boxes.getValue(ana.groupId).items.last().first, ServerGroupTransport.load(ben.env, ben.groupId).cursor)
    }

    @Test fun aMemberBackAfterMonthsReadsEverythingPageByPage() {
        val invite = create(*(1..60).map { "$it" }.toTypedArray())
        join(ben, invite)
        everyoneButCleo()
        hours(24 * 90)
        ana.remove("7")
        ana.add("61")
        fake.page = 7
        join(cleo, invite)
        assertEquals(ana.songs, cleo.songs)
        assertEquals(60, cleo.songs.size)
        assertFalse("7" in cleo.songs)
    }

    private fun everyoneButCleo() { ana.refresh(); ben.refresh() }

    @Test fun anActionDatedFarAheadCountsWhenItsTimeComesThoughItWasReadBefore() {
        val invite = create("1")
        join(ben, invite)
        // Ana's clock is three days ahead when she adds a song.
        val real = now
        now += 72 * 3_600_000L
        ana.add("2")
        now = real
        ben.refresh()
        assertEquals("not yet", listOf("1"), ben.songs)
        assertEquals(1, ServerGroupTransport.load(ben.env, ben.groupId).pending.size)
        hours(49)
        ben.refresh()
        assertEquals(listOf("1", "2"), ben.songs)
        assertEquals(0, ServerGroupTransport.load(ben.env, ben.groupId).pending.size)
    }

    @Test fun aReplacedOrMadeUpKeyReadsNothing() {
        val invite = create("1", "2")
        join(ben, invite)
        join(cleo, invite)
        everyone()
        // A key nobody was given.
        val made = invite.copy(key = "thg_" + "Q".repeat(43))
        val mallory = Member("Mallory")
        try {
            join(mallory, made)
            fail("joined with a made-up key")
        } catch (e: ProviderException) {
            assertTrue(e.message, e.message!!.startsWith("shared_error_server_key"))
        }
        assertNull(SharedGroups.group(mallory.env, invite.groupId))
        // Ana, the creator, gives the group a new key: whoever holds the old one reads nothing more.
        SharedGroups.newKey(ana.env, ana.group)
        ana.add("3")
        try {
            cleo.refresh()
            fail("read with a replaced key")
        } catch (e: ProviderException) {
            assertTrue(e.message, e.message!!.startsWith("shared_error_server_key"))
        }
        assertEquals(listOf("1", "2"), cleo.songs)
        // Ben gets the new invite: he stays who he was in the group, and reads on.
        val fresh = Invite.parse(SharedTransports.invite(ana.env, ana.group).link())!!
        assertEquals(null, SharedGroups.rekeyed(cleo.env, invite))
        val g = SharedGroups.rekeyed(ben.env, fresh)!!
        val me = ben.group.me
        SharedGroups.takeKey(ben.env, g, fresh)
        assertEquals(me, ben.group.me)
        assertEquals(listOf("1", "2", "3"), ben.songs)
        assertEquals(null, SharedGroups.rekeyed(ben.env, fresh))
        // Only the creator makes a new key.
        try {
            SharedGroups.newKey(ben.env, ben.group)
            fail("a member made a new key")
        } catch (e: ProviderException) {
            assertEquals("shared_error_not_creator", e.message)
        }
    }

    @Test fun theInviteCarriesTheServerAndTheKeyAndNothingElse() {
        val invite = create()
        val link = SharedTransports.invite(ana.env, ana.group).link()
        assertTrue(link, "t=server" in link && invite.key in link)
        assertFalse("the API token never leaves", apiToken in link)
        assertEquals(invite, Invite.parse(link))
        // A link with a key of another shape, or no server, is no invite.
        assertNull(Invite.parse(link.replace(invite.key, invite.key.drop(1))))
        assertNull(Invite.parse(link.replace(invite.key, "th_" + invite.key.drop(4))))
        assertNull(Invite.parse(link.replace(Regex("&s=[^&]+"), "&s=ftp%3A%2F%2Fexample.org")))
        assertEquals(invite.code, Invite.codeOf(ana.group.me))
    }

    @Test fun creatingNeedsTheServerAndItsToken() {
        val alone = Member("Alone")
        try {
            SharedGroups.create(alone.env, "Solo", address, "x", "A", "TIDAL", alone.local, transport = Invite.SERVER)
            fail("made without a server")
        } catch (e: ProviderException) {
            assertEquals("server_err_unreachable", e.message)
        }
        ServerLocks.save(alone.env, ServerLocks.Config(address, "th_" + "b".repeat(32)))
        try {
            SharedGroups.create(alone.env, "Solo", address, "x", "A", "TIDAL", alone.local, transport = Invite.SERVER)
            fail("made with a wrong token")
        } catch (e: ProviderException) {
            assertTrue(e.message, e.message!!.startsWith("server_err_token"))
        }
        assertTrue(alone.env.store.data.groups.isEmpty())
        assertTrue(fake.boxes.isEmpty())
    }

    @Test fun theApiTokenGoesOnlyToTheServerItIsFor() {
        create("1")
        // "Coordinate with my server" now points at another server, with that server's token.
        val otherToken = "th_" + "c".repeat(32)
        ServerLocks.save(ana.env, ServerLocks.Config("http://nas.invalid:8080", otherToken))
        val calls = server.requestCount
        try {
            SharedGroups.newKey(ana.env, ana.group)
            fail("the other server's token was sent to the group's server")
        } catch (e: ProviderException) {
            assertTrue(e.message, e.message!!.startsWith("server_err_off"))
        }
        assertEquals(calls, server.requestCount)
        // The same server, written otherwise, is the same server.
        ServerLocks.save(ana.env, ServerLocks.Config("$address/api/v1/", apiToken))
        SharedGroups.newKey(ana.env, ana.group)
        assertEquals(calls + 1, server.requestCount)
    }

    @Test fun leavingForgetsTheMailboxState() {
        val invite = create("1")
        join(ben, invite)
        ben.refresh()
        val g = ben.group
        SharedGroups.leave(ben.env, g)
        assertNull(SharedTransports.mailboxKey(ben.env, g))
        assertEquals(MailboxState(), ServerGroupTransport.load(ben.env, g.id))
        ana.refresh()
        assertTrue(SharedGroups.members(ana.group, SharedGroups.load(ana.env, ana.groupId)).first { it.name == "Ben" }.left)
    }
}
