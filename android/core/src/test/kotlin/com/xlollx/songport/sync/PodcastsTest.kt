package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Followed podcasts offline: telling shows apart, OPML in and out, the File service's columns. */
class PodcastsTest {
    private fun show(title: String, publisher: String? = null, feed: String? = null, itunes: String? = null, id: String = title) =
        Track(id = id, title = title, artists = listOfNotNull(publisher), kind = Track.KIND_PODCAST, feedUrl = feed, itunesId = itunes)

    // ---------------------------------------------------------------- feed addresses and Apple ids

    @Test fun feedAddressesCompareWithoutSchemeWwwPortCaseOrTrailingSlash() {
        val k = Podcasts.feedKey("https://feeds.example.com/daily.rss")
        assertEquals("feeds.example.com/daily.rss", k)
        for (same in listOf("http://feeds.example.com/daily.rss", "HTTPS://Feeds.Example.com/daily.rss/", "feed:https://feeds.example.com/daily.rss",
            "itpc://feeds.example.com/daily.rss", "https://www.feeds.example.com/daily.rss", "https://feeds.example.com:443/daily.rss#top", "  https://feeds.example.com/daily.rss  ")) {
            assertEquals(same, k, Podcasts.feedKey(same))
        }
        assertNotEquals(k, Podcasts.feedKey("https://feeds.example.com/daily.rss?format=mp3"))
        assertNull(Podcasts.feedKey(""))
        assertNull(Podcasts.feedKey(null))
        assertNull(Podcasts.feedKey("not a feed"))
    }

    @Test fun appleIdsFromBareIdsAndLinks() {
        assertEquals("1200361736", Podcasts.itunesId("1200361736"))
        assertEquals("1200361736", Podcasts.itunesId("https://podcasts.apple.com/us/podcast/the-daily/id1200361736?i=1000"))
        assertEquals("1200361736", Podcasts.itunesId("https://itunes.apple.com/podcast/id1200361736"))
        assertNull(Podcasts.itunesId("https://example.com/podcast"))
        assertNull(Podcasts.itunesId(""))
        assertNull(Podcasts.itunesId("000"))
    }

    // ---------------------------------------------------------------- the matcher

    @Test fun theSameFeedIsTheSameShowWhateverTheName() {
        val a = show("The Daily", feed = "https://feeds.simplecast.com/54nAGcIl")
        val b = show("Daily (NYT) - renamed", publisher = "Someone", feed = "http://feeds.simplecast.com/54nAGcIl/")
        assertEquals(1.0, Matcher.score(a, b), 0.0)
        // And among the target's shows, found by feed even with no word in common.
        val index = Matcher.TrackIndex(listOf(show("Other"), b))
        assertSame(b, index.best(a))
    }

    @Test fun theSameAppleIdIsTheSameShowAndTwoIdsAreTwoShows() {
        val a = show("Song Exploder", itunes = "788236947")
        assertEquals(1.0, Matcher.score(a, show("Song Exploder: How Music Gets Made", itunes = "788236947")), 0.0)
        // One name, two entries in Apple's directory: two shows, never matched by their name.
        assertEquals(0.0, Matcher.score(a, show("Song Exploder", itunes = "999")), 0.0)
        assertNull(Matcher.TrackIndex(listOf(show("Song Exploder", itunes = "999"))).best(a))
        // The feed decides before the ids.
        assertEquals(1.0, Matcher.score(show("X", feed = "https://f.example/x", itunes = "1"), show("X", feed = "https://f.example/x", itunes = "2")), 0.0)
    }

    @Test fun byTitleAndPublisherWhenNeitherSideKnowsMore() {
        val spotify = show("The Daily", "The New York Times")
        // Deezer lists no publisher: the title alone is a match, above the review line.
        val deezer = show("The Daily")
        assertTrue(Podcasts.score(spotify, deezer) >= Matcher.REVIEW_THRESHOLD)
        // The same title with the same publisher written another way.
        assertTrue(Podcasts.score(spotify, show("the daily podcast", "New York Times")) >= Matcher.REVIEW_THRESHOLD)
        // Accents, punctuation and "&": one title.
        assertEquals(1.0, Podcasts.score(show("Café & Co.", "Radio"), show("Cafe and Co", "Radio")), 1e-9)
        // The same title from another publisher: still offered, but for the review.
        val other = Podcasts.score(spotify, show("The Daily", "Daily Wire Network Of Sports"))
        assertTrue(other >= Matcher.DEFAULT_THRESHOLD && other < Matcher.REVIEW_THRESHOLD)
        // Another show, and a longer title that only contains this one, are not taken as it.
        assertTrue(Podcasts.score(spotify, show("Radiolab", "WNYC Studios")) < Matcher.DEFAULT_THRESHOLD)
        assertTrue(Podcasts.score(spotify, show("The Daily Show", "Comedy Central")) < Matcher.REVIEW_THRESHOLD)
        // Two feeds, both known and different, cost a little: a moved feed is not sure.
        assertTrue(Matcher.score(show("Radiolab", feed = "https://a.example/rl"), show("Radiolab", feed = "https://b.example/rl")) < 0.92)
    }

    @Test fun showsWithNeitherFeedNorAppleIdAreScoredAsBefore() {
        // Spotify against Deezer, as today's syncs of followed podcasts compare them: the matcher's own rules.
        val a = show("The Daily", "The New York Times")
        val b = show("The Daily")
        assertFalse(Podcasts.knowsMore(a, b))
        assertNotEquals(Podcasts.score(a, b), Matcher.score(a, b), 1e-9)
        assertTrue(Matcher.score(a, b) >= Matcher.DEFAULT_THRESHOLD)
        // A feed on one side is enough for the show rules.
        assertTrue(Podcasts.knowsMore(a.copy(feedUrl = "https://f.example/d"), b))
        assertEquals(Podcasts.score(a.copy(feedUrl = "https://f.example/d"), b), Matcher.score(a.copy(feedUrl = "https://f.example/d"), b), 0.0)
    }

    @Test fun aShowAgainstASongRowIsScoredAsAShow() {
        // A file row read back has no kind: the target's show still decides how they compare.
        val row = Track(id = "r", title = "Radiolab", feedUrl = "https://feeds.wnyc.org/radiolab")
        assertEquals(1.0, Matcher.score(row, show("Radiolab", feed = "https://feeds.wnyc.org/radiolab/")), 0.0)
    }

    @Test fun bestPicksTheExactShowAmongNamesakes() {
        val src = show("Hardcore History", "Dan Carlin")
        val found = listOf(show("Hardcore History: Addendum", "Dan Carlin", id = "a"), show("Hardcore History", "Dan Carlin", id = "b"))
        assertEquals("b", Matcher.best(src, found)?.id)
    }

    // ---------------------------------------------------------------- OPML in

    @Test fun opmlFromPodcastAppsKeepsFeedsIdsAndPublishers() {
        val opml = """<?xml version="1.0" encoding="utf-8"?>
            <opml version="1.0"><head><title>AntennaPod Subscriptions</title></head><body>
            <!-- <outline type="rss" text="Commented out" xmlUrl="https://x/commented.rss"/> -->
            <outline text="News" title="News">
              <outline type="rss" text="The Daily" title="The Daily" xmlUrl="https://feeds.simplecast.com/54nAGcIl" htmlUrl="https://podcasts.apple.com/us/podcast/the-daily/id1200361736"/>
            </outline>
            <outline type='rss' text='Rock &#39;n&#x27; Roll &amp; More' xmlUrl='http://example.com/rock.xml' />
            <outline type="rss" title="Only a title" xmlUrl="https://example.com/only-title.xml"
                     itunes:author="Some Network"/>
            <outline type="rss" xmlUrl="https://example.com/no-name.xml"/>
            <outline type="rss" text="Uses url" url="https://example.com/url.xml"/>
            <outline type="link" text="A web page" url="https://example.com/page"/>
            <outline type="rss" text="The Daily again" xmlUrl="http://feeds.simplecast.com/54nAGcIl/"/>
            <outline type="rss" text="Gt &gt; in name" xmlUrl="https://example.com/a?x=1&amp;y=2"/>
            </body></opml>"""
        val t = PlaylistFiles.parse("antennapod-feeds.opml", opml)
        // Rows as before: a show without a name is skipped, one listed twice is read twice.
        assertEquals(
            listOf("The Daily", "Rock 'n' Roll & More", "Only a title", "Uses url", "The Daily again", "Gt > in name"),
            t.map { it.title },
        )
        assertTrue(t.all { it.kind == Track.KIND_PODCAST })
        assertEquals("https://feeds.simplecast.com/54nAGcIl", t[0].feedUrl)
        assertEquals("1200361736", t[0].itunesId)
        assertEquals(listOf("Some Network"), t[2].artists)
        assertEquals("https://example.com/a?x=1&y=2", t[5].feedUrl)
        // Ids come from the feed: one show, one id, the same on every import.
        assertEquals(t[0].id, t[4].id)
        assertEquals(t.size - 1, t.map { it.id }.toSet().size)
        assertEquals(t.map { it.id }, PlaylistFiles.parse("again.opml", opml).map { it.id })
    }

    @Test fun oddOpmlFilesGiveWhatTheyHoldAndNeverFail() {
        assertEquals(emptyList<Track>(), PlaylistFiles.parseOpml(""))
        assertEquals(emptyList<Track>(), PlaylistFiles.parseOpml("<opml><body></body></opml>"))
        assertEquals(emptyList<Track>(), PlaylistFiles.parseOpml("<opml><body><outline text=\"Group only\"></outline></body></opml>"))
        // Cut off in the middle: what was whole is read.
        val cut = PlaylistFiles.parseOpml("<opml><body><outline type=\"rss\" text=\"A\" xmlUrl=\"https://a.example/f\"/><outline type=\"rss\" text=\"B\" xmlU")
        assertEquals(listOf("A"), cut.map { it.title })
        // Upper-case tags and attributes, a ">" inside a quoted value, line breaks inside a name.
        val odd = PlaylistFiles.parseOpml("<OPML><BODY><OUTLINE TYPE=\"rss\" TEXT=\"A &gt; B\n  part two\" XMLURL=\"https://a.example/f\"/></BODY></OPML>")
        assertEquals(listOf("A > B part two"), odd.map { it.title })
        // Recognised by its content too, whatever the file is called.
        assertEquals(PlaylistFiles.Format.OPML, PlaylistFiles.detect("subscriptions.xml", "﻿<?xml version=\"1.0\"?>\n<opml version=\"2.0\">"))
        assertEquals(1, PlaylistFiles.parse("export", "﻿<?xml version=\"1.0\"?><opml><body><outline type=\"rss\" text=\"Ä\" xmlUrl=\"https://ä.example/f\"/></body></opml>").size)
    }

    // ---------------------------------------------------------------- OPML out

    @Test fun opmlWrittenForPodcastAppsAndReadBack() {
        val shows = listOf(
            show("The Daily", "The New York Times", feed = "https://feeds.simplecast.com/54nAGcIl", itunes = "1200361736"),
            show("Rock \"n\" <Roll> & Co", feed = "https://example.com/a?x=1&y=2"),
            show("No feed known", "Someone"),
            show("Control\u0001chars\nand lines\t", feed = "https://example.com/c"),
        )
        val text = PlaylistFiles.toOpml("Followed podcasts & more", shows)
        assertTrue(text.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">"))
        assertTrue(text.contains("<title>Followed podcasts &amp; more</title>"))
        assertTrue(text.contains("""<outline type="rss" text="The Daily" title="The Daily" xmlUrl="https://feeds.simplecast.com/54nAGcIl" htmlUrl="https://podcasts.apple.com/podcast/id1200361736"/>"""))
        assertTrue(text.contains("""text="Rock &quot;n&quot; &lt;Roll&gt; &amp; Co""""))
        assertTrue(text.contains("""xmlUrl="https://example.com/a?x=1&amp;y=2""""))
        // A show without a feed stays, as a plain outline the apps pass over.
        assertTrue(text.contains("""<outline text="No feed known" title="No feed known"/>"""))
        assertFalse(text.contains('\u0001'))
        val back = PlaylistFiles.parse("out.opml", text)
        assertEquals(listOf("The Daily", "Rock \"n\" <Roll> & Co", "Controlchars and lines"), back.map { it.title })
        assertEquals(listOf("https://feeds.simplecast.com/54nAGcIl", "https://example.com/a?x=1&y=2", "https://example.com/c"), back.map { it.feedUrl })
        assertEquals("1200361736", back[0].itunesId)
        // Valid XML for a real parser.
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream())
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n  <head><title></title></head>\n  <body>\n  </body>\n</opml>\n", PlaylistFiles.toOpml("", emptyList()))
    }

    // ---------------------------------------------------------------- the File service

    @Test fun fileRowsKeepFeedsAndApplesIdsAndSongFilesStayAsTheyWere() {
        val songs = listOf(Track(id = "", title = "Song", artists = listOf("A"), isrc = "USRC17607839"))
        assertEquals("title,artist,album,duration_ms,isrc\r\nSong,A,,,USRC17607839\r\n", CsvCodec.encode(songs))
        val shows = listOf(show("The Daily", "NYT", feed = "https://feeds.simplecast.com/54nAGcIl", itunes = "1200361736"), show("Plain", "P"))
        val csv = CsvCodec.encode(shows)
        assertTrue(csv.startsWith("title,artist,album,duration_ms,isrc,feed_url,itunes_id\r\n"))
        val back = CsvCodec.parse(csv)
        assertEquals("https://feeds.simplecast.com/54nAGcIl", back[0].feedUrl)
        assertEquals("1200361736", back[0].itunesId)
        assertNull(back[1].feedUrl)
        // The id of a show with a feed is its feed's: read back, it is the same row.
        assertEquals(CsvCodec.withStableId(shows[0]).id, back[0].id)
        assertEquals(CsvCodec.withStableId(show("Another name", feed = "http://feeds.simplecast.com/54nAGcIl/")).id, back[0].id)
        assertNotEquals(CsvCodec.withStableId(show("The Daily", "NYT")).id, back[0].id)
    }

    @Test fun exportOffersOpmlOnlyForShows() {
        val E = com.xlollx.songport.providers.LocalFilesProvider.Export
        val opml = com.xlollx.songport.providers.LocalFilesProvider.Export.OPML
        // OPML is 2.0's (Features): offered for nothing without it.
        com.xlollx.songport.Features.build = false
        try {
            assertFalse(opml in E.forShows(true))
            assertFalse(opml in E.offered())
        } finally { com.xlollx.songport.Features.build = true }
        try {
        assertTrue(opml in E.offered())
        assertFalse(opml in E.forShows(false))
        assertEquals(opml, E.forShows(true).first())
        // Followed podcasts keep every format they had.
        assertEquals(E.forShows(false), E.forShows(true).drop(1))
        assertEquals(com.xlollx.songport.providers.LocalFilesProvider.Export.entries.size - 1, E.forShows(false).size)
        assertTrue(E.isShows(com.xlollx.songport.providers.MusicProvider.PODCASTS_ID))
        assertTrue(E.isShows("file", listOf(show("A"), Track(id = "r", title = "B", feedUrl = "https://b.example/f"))))
        assertFalse(E.isShows("file", listOf(Track(id = "s", title = "Song"))))
        assertFalse(E.isShows("file"))
        } finally { com.xlollx.songport.Features.build = false }
    }
}
