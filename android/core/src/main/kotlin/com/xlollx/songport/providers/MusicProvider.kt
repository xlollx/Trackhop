package com.xlollx.songport.providers

import com.xlollx.songport.platform.Env
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.model.UnplayableEntry

/**
 * Un servizio musicale. Tutte le operazioni ricevono l'Env della piattaforma (Android o server)
 * per leggere token e impostazioni. Le implementazioni non hanno stato: lo stato vive nell'Env.
 */
interface MusicProvider {
    /** Servizio ("spotify"): le credenziali dell'app e le impostazioni sono per servizio. */
    val serviceId: String
    /** Account: "" = principale, "2", "3"… per gli account aggiuntivi dello stesso servizio. */
    val slot: String get() = ""
    /** Identificativo dell'istanza: token, cache abbinamenti e job puntano a questo. */
    val id: String get() = if (slot.isEmpty()) serviceId else "$serviceId@$slot"
    val displayName: String
    /** Colore ARGB per la UI (chip/badge). */
    val brandColor: Long
    /** Nome della stringa con una breve nota mostrata nella scheda Account. */
    val noteName: String
    /** Se il servizio espone i "brani preferiti" come origine (id speciale LIKED_ID). */
    val supportsLikedSongs: Boolean get() = false
    /** Se si puo' anche scrivere nei "brani preferiti" (sync preferiti -> preferiti). */
    val supportsLikedTarget: Boolean get() = false
    /** Album salvati (id speciale ALBUMS_ID) e artisti seguiti (ARTISTS_ID), in lettura e scrittura. */
    val supportsAlbums: Boolean get() = false
    val supportsArtists: Boolean get() = false
    /** Podcast seguiti (PODCASTS_ID), in lettura e scrittura. */
    val supportsPodcasts: Boolean get() = false
    /** Ascolti recenti (RECENT_ID), solo come origine: gli ultimi brani ascoltati, dal piu' recente, senza ripetizioni. */
    val supportsRecent: Boolean get() = false

    /** Se questo id speciale (preferiti, album, artisti) e' disponibile qui, come origine o destinazione. */
    fun supportsLibrary(playlistId: String?, asTarget: Boolean): Boolean = when (playlistId) {
        LIKED_ID -> if (asTarget) supportsLikedTarget else supportsLikedSongs
        ALBUMS_ID -> supportsAlbums
        ARTISTS_ID -> supportsArtists
        PODCASTS_ID -> supportsPodcasts
        RECENT_ID -> !asTarget && supportsRecent
        else -> true
    }

    /** Le voci speciali da mostrare accanto alle playlist. */
    fun libraryEntries(ctx: Env, asTarget: Boolean): List<Playlist> = listOfNotNull(
        Playlist(LIKED_ID, ctx.text("liked_songs")).takeIf { supportsLibrary(LIKED_ID, asTarget) },
        Playlist(ALBUMS_ID, ctx.text("saved_albums")).takeIf { supportsAlbums },
        Playlist(ARTISTS_ID, ctx.text("followed_artists")).takeIf { supportsArtists },
        Playlist(PODCASTS_ID, ctx.text("followed_podcasts")).takeIf { supportsPodcasts },
        Playlist(RECENT_ID, ctx.text("recently_played")).takeIf { !asTarget && supportsRecent },
    )
    /** False per sorgenti locali (file) che non richiedono login. */
    val requiresAuth: Boolean get() = true
    /**
     * Connettore in beta: scritto sulle API documentate del servizio e funzionante nei test, ma
     * usato da poche persone, quindi con piu' probabilita' di incontrare un caso non previsto.
     * La UI lo dichiara e invita a segnalare, invece di lasciarlo scoprire all'utente.
     */
    val beta: Boolean get() = false

    /**
     * Alpha connector: written and covered by offline tests, but not yet tried against real
     * accounts. The UI shows "alpha" where it would show "beta", with a note asking to try it on a
     * test playlist first and to report problems. Takes the place of [beta] where both are set.
     */
    val alpha: Boolean get() = false

    /**
     * Quale strada verso il servizio e' questa, quando ce n'e' piu' d'una: la piu' semplice (accesso
     * sul sito, nessuna chiave, interfaccia non ufficiale) o quella ufficiale (API con chiave propria).
     * Null = c'e' una strada sola e non serve dirlo.
     */
    val route: Route? get() = null
    /** Una riga che spiega la strada, mostrata nella scelta del servizio. */
    val routeNoteName: String? get() = null
    /** Le varianti dello stesso servizio (API e web) si raggruppano sotto questo nome. */
    val familyName: String get() = displayName

    enum class Route { EASY, OFFICIAL }

    /** False per le sorgenti in sola lettura (Last.fm, ListenBrainz): mai come destinazione. */
    val canWrite: Boolean get() = true
    /** False where a new playlist cannot be made through the API: read-only sources (ListenBrainz, SoundCloud). */
    val canCreatePlaylists: Boolean get() = true
    /** True se ha senso collegare piu' account dello stesso servizio. */
    val supportsMultipleAccounts: Boolean get() = requiresAuth

    /**
     * Milliseconds the service needs between two tracks added to [playlistId], when it adds them one
     * at a time on purpose when the job keeps the source order (Spotify's liked songs, see
     * [MusicProvider.OrderedAdds]): the editor and the engine warn that a big first sync is slow.
     * 0 for a normal target.
     */
    fun addSpacingMs(playlistId: String): Long = 0
    /** Dominio su cui l'utente fara' davvero il login (mostrato prima di aprire il browser). */
    val authDomain: String? get() = null
    /** Pagina del servizio dove revocare l'accesso all'app. */
    val revokeUrl: String? get() = null

    /**
     * False se l'API del servizio non permette di togliere brani da una playlist
     * (es. Apple Music: si possono solo aggiungere). Le sync con "rispecchia rimozioni"
     * verso questi servizi aggiungono soltanto, e lo segnalano nel report.
     */
    val canRemoveTracks: Boolean get() = true

    /**
     * True when the service says which entries of a playlist it can no longer play (Spotify, Deezer,
     * TIDAL, Apple Music library, YouTube, YouTube Music): the "Unplayable tracks" tool works here.
     * Personal servers and Amazon Music do not say, and the tool says so instead of guessing.
     */
    val detectsUnplayable: Boolean get() = false

    /**
     * The entries of [playlistId] the service cannot play now, in playlist order with their
     * position. Default: whatever [tracks] marked with [Track.available] false. A service that
     * offers its own playable version in place of a dead entry (Spotify's relinking) hands it over
     * in [UnplayableEntry.relinked].
     */
    suspend fun unplayable(ctx: Env, playlistId: String): List<UnplayableEntry> =
        tracks(ctx, playlistId).withIndex().filter { it.value.available == false }.map { UnplayableEntry(it.value, it.index) }

    /** Putting a playlist's tracks in a given order, where the API has a move: Spotify, YouTube Music, Deezer. */
    val canReorder: Boolean get() = false
    /** [current] as the playlist lists it now, [ordered] the same tracks in the wanted order (ids and item ids as listed). */
    suspend fun reorderTracks(ctx: Env, playlistId: String, current: List<Track>, ordered: List<Track>) {}

    /** Mettere una copertina a una playlist propria: solo dove l'API lo permette (Spotify). */
    val canSetCover: Boolean get() = false
    /** [jpeg]: at most 256 KB, as Spotify wants it. */
    suspend fun setPlaylistCover(ctx: Env, playlistId: String, jpeg: ByteArray) {}

    /** Rinominare ed eliminare le proprie playlist: dove l'API lo permette (Apple Music e Amazon no). */
    val canRenamePlaylists: Boolean get() = canWrite && canCreatePlaylists
    val canDeletePlaylists: Boolean get() = canRenamePlaylists
    /** Making a playlist public or private, where the service has the notion and lets a client set it. */
    val canSetVisibility: Boolean get() = false

    /**
     * Istruzioni per far usare all'utente le proprie credenziali. Null = non applicabile
     * (il ponte a file non ha nulla da configurare).
     */
    val setupGuide: SetupGuide? get() = null

    /** Servizi che passano da un'app compagna: dove scaricarla quando manca (null = non applicabile). */
    val installUrl: String? get() = null
    /** Testo mostrato al posto del generico "serve una chiave" quando il servizio non e' configurato. */
    val notConfiguredName: String? get() = null
    /** True se [search] usa l'ISRC del brano quando c'e' (Spotify, Apple Music, TIDAL, Deezer): vale la pena procurarglielo. */
    val searchesByIsrc: Boolean get() = false
    /**
     * True for a personal server whose files can carry MusicBrainz tags (Navidrome and other Subsonic
     * servers, Jellyfin, Emby, Plex): [search] then takes a track's [Track.mbids] and answers with the
     * file tagged with one of them, and the candidates it lists carry their own.
     */
    val matchesByMbid: Boolean get() = false
    /**
     * True when [search] asks the service for the title alone and leaves the artists to the scoring
     * (Jellyfin, Emby, Plex): asking again with other artists would only repeat the same requests.
     */
    val searchesTitleOnly: Boolean get() = false

    /**
     * Every song of the server's library, each with the MusicBrainz recording ids its file is tagged
     * with ([Track.mbids]): for the "songs without MusicBrainz tags" report. [onProgress] gets the
     * songs read so far. Only where [matchesByMbid]; nothing is ever written.
     */
    suspend fun librarySongs(ctx: Env, onProgress: (Int) -> Unit = {}): List<Track> = emptyList()

    /** Ricerche in parallelo tollerate dal servizio (1 per le interfacce web non ufficiali, che bloccano le raffiche). */
    val searchParallelism: Int get() = 4
    /**
     * The lock the connector holds while it lists a whole playlist, shared by the connectors that name
     * the same one: two such reads run one after the other, the second waiting for the first (see
     * [com.xlollx.songport.sync.ServiceReads], which says who holds it). Null: reads run side by side.
     */
    val readGate: String? get() = null
    /**
     * True when many syncs reaching the service at once can be turned away (the services that take
     * searches one or two at a time): the Syncs screen suggests spreading their times (issue #56).
     */
    val spreadSchedules: Boolean get() = searchParallelism < 4
    /** Pause after each real search (ms): for services that block a burst of searches for hours. */
    val searchSpacingMs: Long get() = 0
    /** True se il servizio passa da un'app plugin installata a parte (vedi BridgePlugin). */
    val pluginBased: Boolean get() = false
    /**
     * True for a source reached only through a pasted public link (SoundCloud): nothing to add or
     * sign in to, so it stays out of the service list and appears where a link is pasted.
     */
    val linkOnly: Boolean get() = false

    /** True se questa build (o le impostazioni utente) hanno le credenziali per il servizio. */
    fun isConfigured(ctx: Env): Boolean

    /** True se le credenziali in uso sono quelle inserite dall'utente e non quelle della build. */
    fun usesOwnCredentials(ctx: Env): Boolean = false
    fun isConnected(ctx: Env): Boolean
    fun accountName(ctx: Env): String?

    /** Nome da mostrare: quello scelto dall'utente per il connettore, altrimenti il servizio (e l'account). */
    fun label(ctx: Env): String =
        Providers.customName(id) ?: if (slot.isEmpty()) displayName else "$displayName · ${accountName(ctx) ?: slot}"

    /**
     * Avvia il login. Default: OAuth nel browser (vedi OAuthProvider); Apple Music usa una
     * schermata dedicata con MusicKit JS.
     */
    fun startAuth(ctx: Env)
    /** Completa il login con i parametri del redirect (query + fragment). Solo per i flussi browser. */
    suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String)
    fun disconnect(ctx: Env)

    suspend fun playlists(ctx: Env): List<Playlist>
    suspend fun tracks(ctx: Env, playlistId: String): List<Track>
    /** Candidati per un brano di un altro servizio: prima per ISRC (se supportato), poi per testo. */
    suspend fun search(ctx: Env, track: Track): List<Track>
    suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist
    suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>)

    /**
     * [addTracks], answering which of [tracks] the service did not add because, it says, the
     * playlist already holds them. Amazon Music refuses such a track (DUPLICATE_TRACK) even when its
     * playlist listing leaves the entry out: the sync then counts it as present, not added, and
     * remembers it, instead of adding it again on every run. Default: every track added, none reported.
     */
    suspend fun addTracksReportingPresent(ctx: Env, playlistId: String, tracks: List<Track>): Set<String> {
        addTracks(ctx, playlistId, tracks)
        return emptySet()
    }

    /**
     * True where [tracks] can list an entry by its id alone, with an empty title (Amazon Music, for
     * a track it describes only in part). A Mirror sync matches such an entry by id and never
     * removes it on a guess: what it is cannot be told.
     */
    val listsUntitledEntries: Boolean get() = false
    suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>)
    /**
     * True where [removeTracks] takes out the one entry a track names by its [Track.itemId] and
     * leaves the other copies of the same track in place (YouTube, TIDAL, Plex, Subsonic). Where a
     * removal is by track id every copy goes: a sync then never removes a duplicate by itself.
     */
    val removesSingleEntry: Boolean get() = false

    /**
     * The entries of [playlistId] that hold [tracks] (by id), as [tracks] lists them: with the entry
     * id a removal needs, which a search result lacks. Every entry read on the way also goes to
     * [seen], for the caller to remember. Default: the whole playlist. A service that reads in pages
     * stops as soon as each of [tracks] has turned up, so further copies may be left out.
     */
    suspend fun findEntries(ctx: Env, playlistId: String, tracks: List<Track>, seen: (List<Track>) -> Unit = {}): List<Track> {
        val ids = tracks.mapTo(HashSet()) { it.id }
        return tracks(ctx, playlistId).also(seen).filter { it.id in ids }
    }

    /**
     * True where an entry read a few minutes ago still names that same entry or one that is gone,
     * never another: removal by a lasting entry id (YouTube, TIDAL, Qobuz) or by the track itself
     * (Spotify, Deezer). The review screen may then remove with the entries the last sync read,
     * without reading the playlist again. False where an entry is a position, which moves when the
     * playlist changes (Subsonic, Lyrion).
     */
    val keepsEntryIds: Boolean get() = false

    /**
     * [removeTracks], answering which of [tracks] (by id) the playlist still lists after the
     * removal the service said it made. Amazon Music answers a removal by cursor with the playlist's
     * id whether or not the entry went: the sync then counts such a track as not removed and leaves
     * it alone for a while, instead of "removing" it again on every run. Default: every track removed.
     */
    suspend fun removeTracksReportingKept(ctx: Env, playlistId: String, tracks: List<Track>): Set<String> {
        removeTracks(ctx, playlistId, tracks)
        return emptySet()
    }
    suspend fun renamePlaylist(ctx: Env, playlistId: String, name: String) {
        throw com.xlollx.songport.model.ProviderException(ctx.text("error_manage_unsupported", displayName))
    }
    suspend fun deletePlaylist(ctx: Env, playlistId: String) {
        throw com.xlollx.songport.model.ProviderException(ctx.text("error_manage_unsupported", displayName))
    }
    suspend fun setPlaylistVisibility(ctx: Env, playlistId: String, public: Boolean) {
        throw com.xlollx.songport.model.ProviderException(ctx.text("error_manage_unsupported", displayName))
    }

    /**
     * La pagina di ricerca del servizio per [query], nel suo sito o nella sua app (i link si aprono
     * nell'app quando e' installata): dalla revisione l'utente cerca il brano li', ne copia il link e
     * lo incolla. Null per i servizi senza una ricerca pubblica (server personali, file).
     */
    fun webSearchUrl(ctx: Env, query: String): String? = null

    /**
     * Whether [webSearchUrl] leads anywhere for this account: screens decide up front whether to offer
     * "Open in <service>", so a personal server without a web search shows no button that does nothing.
     */
    fun hasWebSearch(ctx: Env): Boolean = webSearchUrl(ctx, "x") != null

    /**
     * The public page of a track on this service, for "open it there": built from the id, no
     * request. Null where there is no such page (personal servers, files).
     */
    fun trackUrl(ctx: Env, trackId: String): String? = com.xlollx.songport.sync.TrackLinks.url(serviceId, trackId)

    /**
     * Ricerca su un catalogo piu' largo del servizio (per YouTube Music i video di YouTube, che una
     * playlist accetta comunque). Usata solo quando [search] non ha dato nulla di accettabile; vuota
     * per i servizi che non hanno un catalogo del genere.
     */
    suspend fun searchWide(ctx: Env, track: Track): List<Track> = emptyList()

    /**
     * Un brano di cui si conosce solo l'id (da un link incollato nella revisione), con titolo e
     * artisti se il servizio permette di leggerli; null quando non li puo' leggere: il brano si
     * aggiunge comunque, e' l'id che serve.
     */
    suspend fun track(ctx: Env, trackId: String): Track? = null

    /** Nome (e conteggio) di una playlist di cui si conosce solo l'id, es. da un link incollato. */
    suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist = Playlist(playlistId, playlistId)

    /**
     * Un segno che cambia quando cambia il contenuto (lo snapshot id di Spotify, il checksum di
     * Deezer, una data di modifica; per i preferiti il totale e l'ultimo aggiunto), o null dove il
     * servizio non ne offre uno affidabile. Le sync programmate saltano il giro quando nessuno dei
     * due lati e' cambiato dall'ultima volta: una chiamata al posto di decine.
     */
    suspend fun playlistVersion(ctx: Env, playlistId: String): String? = null

    /**
     * True se questa playlist e' leggibile adesso. Di default richiede l'account collegato;
     * le playlist pubbliche del catalogo Apple Music si leggono anche senza login.
     */
    fun canRead(ctx: Env, playlistId: String): Boolean = isConnected(ctx)

    /** Ricostruisce i campi nativi (es. URI) di un brano noto solo per id (dalla cache abbinamenti). */
    fun rehydrate(track: Track): Track = track

    /**
     * In the coroutine context while a sync job that keeps the source order adds tracks: a service
     * that lists by the second of saving (Spotify's liked songs) then saves one track a second, in
     * order. Without it, the same tracks go in batches at full speed, in no guaranteed order.
     */
    object OrderedAdds : kotlin.coroutines.CoroutineContext.Element {
        object Key : kotlin.coroutines.CoroutineContext.Key<OrderedAdds>
        override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key
    }

    companion object {
        /** True inside [OrderedAdds]. */
        suspend fun addsInOrder(): Boolean = kotlin.coroutines.coroutineContext[OrderedAdds.Key] != null

        /**
         * [findEntries] for a playlist read by offset, [size] entries a page ([page] answers one page
         * and the playlist's total): the first page, then from the last page back, where a sync adds
         * its tracks. Stops as soon as each of [tracks] has turned up.
         */
        suspend fun entriesByOffset(
            tracks: List<Track>, size: Int, seen: (List<Track>) -> Unit,
            page: suspend (offset: Int) -> Pair<List<Track>, Int>,
        ): List<Track> {
            val ids = tracks.mapTo(HashSet()) { it.id }
            val missing = HashSet(ids)
            val out = ArrayList<Track>()
            suspend fun read(offset: Int): Int {
                val (entries, total) = page(offset)
                seen(entries)
                entries.filter { it.id in ids }.forEach { out += it; missing -= it.id }
                return total
            }
            val total = read(0)
            var offset = (total - 1) / size * size
            while (missing.isNotEmpty() && offset >= size) {
                read(offset)
                offset -= size
            }
            return out
        }

        /**
         * [find], a quick route that may be missing (an older Bridge does not know it) or fail: then
         * [whole], the whole playlist read as before. Never a failed removal because of the quick route.
         */
        suspend fun entriesOrWhole(ctx: Env, tag: String, find: suspend () -> List<Track>, whole: suspend () -> List<Track>): List<Track> =
            try {
                find()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ctx.log(tag, "entries not found the quick way (${e.message?.take(120)}): reading the whole playlist")
                whole()
            }

        /** [findEntries] for a playlist read page after page: [next] answers a page (null: the first) and the next one's key, null at the end. */
        suspend fun entriesInPages(
            tracks: List<Track>, seen: (List<Track>) -> Unit,
            next: suspend (key: String?) -> Pair<List<Track>, String?>,
        ): List<Track> {
            val ids = tracks.mapTo(HashSet()) { it.id }
            val missing = HashSet(ids)
            val out = ArrayList<Track>()
            var key: String? = null
            do {
                val (entries, following) = next(key)
                seen(entries)
                entries.filter { it.id in ids }.forEach { out += it; missing -= it.id }
                key = following
            } while (key != null && missing.isNotEmpty())
            return out
        }

        const val LIKED_ID = "__liked__"
        const val ALBUMS_ID = "__albums__"
        const val ARTISTS_ID = "__artists__"
        const val PODCASTS_ID = "__podcasts__"
        const val RECENT_ID = "__recent__"

        fun isLibrary(playlistId: String?): Boolean =
            playlistId == LIKED_ID || playlistId == ALBUMS_ID || playlistId == ARTISTS_ID || playlistId == PODCASTS_ID || playlistId == RECENT_ID

        /** Il tipo di elemento che un id speciale contiene: album, artista, o null per brani. */
        fun libraryKind(playlistId: String?): String? = when (playlistId) {
            ALBUMS_ID -> Track.KIND_ALBUM
            ARTISTS_ID -> Track.KIND_ARTIST
            PODCASTS_ID -> Track.KIND_PODCAST
            else -> null
        }
        /** Description of the playlists this app creates, from the app name (R.string.app_name): "Trackhop" on Google Play. */
        fun description(appName: String) = "Synced with $appName"

        /**
         * A source playlist's description made safe to copy: Spotify writes some as HTML (a daylist links
         * its moods with <a href=…> tags) and YouTube refuses a description with "<" or ">" (HTTP 400).
         * Tags go, entities are read, what is left is plain text of at most [max] characters.
         */
        fun plainDescription(raw: String?, max: Int = 300): String {
            if (raw.isNullOrBlank()) return ""
            val text = raw.replace(Regex("<[^>]*>"), "")
                .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
                .replace("&lt;", "").replace("&gt;", "").replace("&amp;", "&")
                .replace("<", "").replace(">", "")
                .replace(Regex("\\s+"), " ").trim()
            return text.take(max).trim()
        }
    }
}

object Providers {
    @Volatile private var extraAccounts: Map<String, List<String>> = emptyMap()
    @Volatile private var names: Map<String, String> = emptyMap()
    @Volatile private var connectorIds: List<String> = emptyList()
    @Volatile private var cached: List<MusicProvider> = emptyList()

    /** One entry per service, in display order: its id and how to build an account of it ("" = main). */
    class Factory(val service: String, val create: (slot: String) -> MusicProvider)

    @Volatile private var factories: List<Factory> = emptyList()
    @Volatile private var singletons: List<MusicProvider> = emptyList()

    /**
     * The connectors this build offers, in display order: the platform installs them at start (the
     * Android app adds its web connectors to the ones of the engine, the server only these).
     */
    fun install(factories: List<Factory>, singletons: List<MusicProvider> = listOf(LocalFilesProvider)) {
        this.factories = factories
        this.singletons = singletons
        cached = build()
    }

    /** The connectors of the engine, without any web connector: what a platform without a browser offers. */
    fun engineFactories(): List<Factory> = listOf(
        Factory(SpotifyProvider.SERVICE) { SpotifyProvider(it) },
        Factory(AppleMusicProvider.SERVICE) { AppleMusicProvider(it) },
        Factory(YouTubeProvider.SERVICE) { YouTubeProvider(it) },
        Factory(TidalProvider.SERVICE) { TidalProvider(it) },
        Factory(DeezerProvider.SERVICE) { DeezerProvider(it) },
        Factory(SubsonicProvider.SERVICE) { SubsonicProvider(it) },
        Factory(JellyfinProvider.SERVICE) { JellyfinProvider(it) },
        Factory(EmbyProvider.SERVICE) { EmbyProvider(it) },
        Factory(PlexProvider.SERVICE) { PlexProvider(it) },
        Factory(LyrionProvider.SERVICE) { LyrionProvider(it) },
        Factory(MopidyProvider.SERVICE) { MopidyProvider(it) },
        Factory(LastFmProvider.SERVICE) { LastFmProvider(it) },
        Factory(ListenBrainzProvider.SERVICE) { ListenBrainzProvider(it) },
    )

    /** Chiamato dallo Store quando cambiano i connettori scelti, gli account aggiuntivi o i loro nomi. */
    fun configure(extra: Map<String, List<String>>, connectorNames: Map<String, String> = names, connectors: List<String> = connectorIds) {
        names = connectorNames
        connectorIds = connectors
        if (extra != extraAccounts) {
            extraAccounts = extra
            cached = build()
        }
    }

    /** Nome scelto dall'utente per un connettore, se c'e'. */
    fun customName(id: String): String? = names[id]?.trim()?.takeIf { it.isNotEmpty() }

    private fun build(): List<MusicProvider> {
        val out = ArrayList<MusicProvider>()
        for (f in factories) {
            out += f.create("")
            extraAccounts[f.service].orEmpty().forEach { out += f.create(it) }
        }
        out += singletons
        return out
    }

    /** Providers registered by tests, looked up before the real ones. */
    @Volatile var testDoubles: List<MusicProvider> = emptyList()

    fun all(): List<MusicProvider> = testDoubles + cached

    /**
     * I connettori aggiunti dall'utente, nell'ordine in cui li ha aggiunti. E' questo l'elenco che la
     * UI deve mostrare: un servizio tolto dalla scheda Account non deve piu' comparire altrove, anche
     * se il token o la sessione nell'app plugin sono ancora validi.
     */
    fun connectors(): List<MusicProvider> = connectorIds.mapNotNull { id -> cached.firstOrNull { it.id == id } }
    /** The connector [id] names; also the "New releases" source and the shared playlists, which every build has and no list shows. */
    fun byId(id: String): MusicProvider? = testDoubles.firstOrNull { it.id == id } ?: cached.firstOrNull { it.id == id }
        ?: NewReleasesProvider.takeIf { id == it.id } ?: com.xlollx.songport.shared.SharedProvider.takeIf { id == it.id }
    /** Un'istanza per servizio (account principale). */
    fun services(): List<MusicProvider> = cached.filter { it.slot.isEmpty() }
    /** Tutte le istanze (account) di un servizio. */
    fun accountsOf(serviceId: String): List<MusicProvider> = cached.filter { it.serviceId == serviceId }
}
