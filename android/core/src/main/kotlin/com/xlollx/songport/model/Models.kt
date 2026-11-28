package com.xlollx.songport.model

import kotlinx.serialization.Serializable

/** Un brano cosi' come lo vede un servizio. `id` e' l'identificativo nel servizio. */
@Serializable
data class Track(
    val id: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String = "",
    val durationMs: Long = 0,
    val isrc: String? = null,
    /** URI nativo (es. spotify:track:xxx) quando il servizio lo richiede per aggiungere/rimuovere. */
    val uri: String? = null,
    /** Id dell'elemento dentro la playlist (YouTube playlistItem, TIDAL itemId): serve per rimuovere. */
    val itemId: String? = null,
    /**
     * Cosa rappresenta: "" un brano, [KIND_ALBUM] un album salvato (title = titolo dell'album, isrc = UPC),
     * [KIND_ARTIST] un artista seguito (title = nome). Album e artisti passano dalla stessa pipeline dei
     * brani: elenco, ricerca, abbinamento, revisione; i connettori guardano il tipo per cercare nel
     * catalogo giusto.
     */
    val kind: String = "",
    /** Testo esplicito, dove il servizio lo dice; null = non noto. */
    val explicit: Boolean? = null,
    /**
     * False when the service lists the track but can no longer play it (greyed out: pulled from the
     * catalogue, not licensed in the user's country, not in the plan); null where the service does
     * not say. Only the "Unplayable tracks" tool reads it; a sync treats such a track like any other.
     */
    val available: Boolean? = null,
    /** Anno di uscita (0 = non noto) e data di aggiunta alla playlist in epoch ms (0 = non nota): per gli ordinamenti. */
    val year: Int = 0,
    val addedAt: Long = 0,
    /**
     * MusicBrainz recording ids, lowercase: on a personal server's track the ones its file is tagged
     * with, on a source track the ones MusicBrainz lists for its ISRC (see sync/MusicBrainz). Two
     * tracks sharing one are the same recording. Only lives during a run: never saved.
     */
    @kotlinx.serialization.Transient val mbids: List<String> = emptyList(),
    /**
     * A followed podcast's RSS feed address, where it is known (an OPML file, Apple's podcast
     * directory): the surest way to tell one show from another (see sync/Podcasts). Null for songs.
     */
    val feedUrl: String? = null,
    /** A followed podcast's id in Apple's podcast directory (iTunes), where it is known. */
    val itunesId: String? = null,
    /**
     * The song's star rating, 0 = none, 1..5 stars: set by hand in iTunes (and kept in the playlist
     * file made from it), or the user's rating on a personal server (Subsonic's `userRating`).
     */
    val rating: Int = 0,
    /** The star rating of the song's album, as [rating]: 0 = none, 1..5 stars. */
    val albumRating: Int = 0,
    /**
     * The service's id of the album the song is on, where its listing gives one (Subsonic's
     * `albumId`): the album whose rating a sync with [SyncJob.copyRatings] sets. Only lives during a run.
     */
    @kotlinx.serialization.Transient val albumId: String? = null,
) {
    val artistLine: String get() = artists.joinToString(", ")
    val isrcNorm: String? get() = isrc?.trim()?.uppercase()?.takeIf { it.length >= 10 }
    override fun toString(): String = if (artists.isEmpty() || kind == KIND_ARTIST) title else "$artistLine – $title"

    companion object {
        const val KIND_ALBUM = "album"
        const val KIND_ARTIST = "artist"
        /** Un podcast seguito (title = nome dello show, artists = editore). */
        const val KIND_PODCAST = "podcast"
    }
}

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val trackCount: Int = -1,
    val ownedByMe: Boolean = true,
    val description: String = "",
    /** The cover's address, when the service lists one cheaply: carried to a created target where allowed. */
    val imageUrl: String? = null,
    /** Public (on the profile, findable) or private; null when the service does not say in its listing. */
    val isPublic: Boolean? = null,
)

@Serializable
enum class Schedule(val minutes: Long) {
    MANUAL(0), HOURLY(60), EVERY_6H(360), DAILY(1440), WEEKLY(10080)
}

/** Riferimento a una playlist di un servizio. `playlistId` null = "da creare" (solo destinazione). */
@Serializable
data class PlaylistRef(
    val provider: String,
    val playlistId: String? = null,
    val playlistName: String = "",
    /** The pasted link this source came from: the editor then shows "From link" rather than the service. */
    val link: String? = null,
)

@Serializable
data class SyncJob(
    val id: String,
    val name: String,
    val source: PlaylistRef,
    val target: PlaylistRef,
    val schedule: Schedule = Schedule.MANUAL,
    /** A custom interval in minutes (60 to 10080), over [schedule]'s; null = [schedule]'s own. See sync/Timing. */
    val everyMinutes: Int? = null,
    /** The time of day ("07:30", the device's or server's zone) for runs a day or more apart, or on [days]. */
    val at: String? = null,
    /** Days of the week it runs on (1 = Monday … 7 = Sunday), at [at]; empty = by the interval. */
    val days: List<Int> = emptyList(),
    val mirrorRemovals: Boolean = false,
    /**
     * Copies the source's star ratings ([Track.rating], [Track.albumRating]) to the target, where it
     * takes them ([com.xlollx.songport.providers.RatingWriter]), for songs and albums rated by hand.
     * Unrated ones are left alone (never cleared); a rating is set only where it differs.
     */
    val copyRatings: Boolean = false,
    /**
     * The source replaces most of its tracks by itself (a Spotify daylist, Discover Weekly, Release
     * Radar): with [mirrorRemovals], large removals are expected and go through on scheduled runs too,
     * instead of waiting for a confirmation in the preview.
     */
    val followsChanges: Boolean = false,
    /**
     * Keep every track ever seen (an archive of a Discover Weekly, a Release Radar, a daylist or a
     * chart): the target only grows. Nothing is ever removed from it, whatever [mirrorRemovals] says,
     * and it is never put in the source's order ([keepOrder]): what is new goes at the end.
     */
    val archive: Boolean = false,
    val wifiOnly: Boolean = false,
    val enabled: Boolean = true,
    val lastRunEpoch: Long = 0,
    val lastReportId: String? = null,
    /** Brani dell'origine che l'utente ha scelto di ignorare (non piu' cercati ne' segnalati). */
    val ignoredSourceIds: List<String> = emptyList(),
    /** Sync gemella nella direzione opposta (bidirezionale). */
    val linkedJobId: String? = null,
    /**
     * In a two-way pair (both halves carry it): a track removed on one side is removed on the other
     * too. The engine tells a removal from an addition by what both sides held after the last
     * complete run ([com.xlollx.songport.data.PairState]); without it a pair only ever adds.
     */
    val twoWayRemovals: Boolean = false,
    /**
     * The device this sync's schedule runs on, when it came here with a configuration file written
     * elsewhere: it arrives turned off, so the same sync does not run from two devices at once (each
     * would add what is missing, twice). Turning it on here clears it.
     */
    val scheduledOn: String? = null,
    /** Playlist scritta dall'AI dell'utente: la descrizione data, per riconoscerla e per allungarla. */
    val aiPrompt: String? = null,
    /** Versioni (vedi MusicProvider.playlistVersion) viste all'ultimo giro riuscito: uguali = niente da fare. */
    val sourceVersion: String? = null,
    val targetVersion: String? = null,
    /**
     * What the last run that read the source whole ran under (the matching's revision and this sync's
     * rules, see SyncEngine.runKey), and when: a scheduled run skips an unchanged source only under
     * the same ones and for a day at most, so a new matching, a changed rule or an expired "not
     * found" is searched again.
     */
    val versionsKey: Int = 0,
    val fullRunEpoch: Long = 0,
    val policy: MatchPolicy = MatchPolicy(),
    /** After adding, put the target in the source's order (where the target service can move tracks). */
    val keepOrder: Boolean = false,
    /** The card's colour, chosen by the user to tell syncs apart at a glance (a key like "teal"); null = the default look. */
    val color: String? = null,
    /** How the card wears [color] (a CardAccent key in the app); null = the style chosen in Settings. */
    val accentStyle: String? = null,
    /** The glow of the route's two brand colours on the card: null = as in Settings (cards with no colour), true/false = this card's own choice. */
    val routeGlow: Boolean? = null,
    /**
     * The "↔" half of a two-way pair, made by the editor from the sync [linkedJobId] names: it is
     * edited through that sync, and keeps its own name, ignore list and reports.
     */
    val twin: Boolean = false,
    /**
     * A radio sync: Trackhop listens to this station in its time window, notes the songs it plays in
     * the source (a File playlist) and syncs them to the target. Null for every other sync.
     */
    val radio: RadioSource? = null,
    /**
     * A Shazam sync: its source is the playlist the Shazam app fills on the user's Spotify or Apple
     * Music ("My Shazam Tracks", see [com.xlollx.songport.sync.ShazamLibrary]), so every song the user
     * Shazams reaches the target. Only a mark for the card and the editor; the run is a plain sync.
     */
    val shazam: Boolean = false,
    /** The source's cover last copied to the target (its address): a new one is copied again, where the target takes covers. */
    val sourceCover: String? = null,
    /**
     * When the source last took a new name: renamed again within days, it is one that names itself
     * (a Spotify daylist, several titles a day), and the sync keeps its own name from then on.
     */
    val sourceRenamedAt: Long? = null,
    /** Starred by the user: shown first in the list, and found by the "Favourites" filter. */
    val favorite: Boolean = false,
    /**
     * The sync's one label (a [Label.id]): the card wears the label's colour, kept in [color] too
     * so an older Trackhop still shows it. One of the ready colours is a label by its key ("teal");
     * null = no colour, or a colour of the user's own with no name. See sync/Labels.
     */
    val label: String? = null,
    /** The user's tags, key to value ("genre" to "metal"); the value may be empty (a key alone). */
    val tags: Map<String, String> = emptyMap(),
    /**
     * A one-off transfer (a setlist made a playlist, or added to one), run once: never shown or run
     * as a sync (the Syncs list and its counters, widgets, Sync all, schedules, stats, backups), kept
     * for its summary and review in Tools › Recent transfers, and deleted once reviewed or after a
     * week ([com.xlollx.songport.sync.OneOffs]).
     */
    val oneOff: Boolean = false,
    /** When a [oneOff] was made (epoch ms). */
    val createdEpoch: Long = 0,
    /**
     * The saved schedule ([SavedSchedule.id], Settings › Schedules) this sync is linked to: its timing
     * ([everyMinutes], [at], [days]) is that schedule's and follows it when the schedule is edited.
     * Null = a timing of its own, set by hand. See sync/Schedules.
     */
    val scheduleId: String? = null,
    /**
     * The connector this sync's source comes through when the songs are kept elsewhere: a radio
     * connector ("radio", "radio@2": one per station, see sync/RadioConnectors), whose songs heard
     * are noted in the File playlist [source]; or "shazam", whose songs are in the playlist the Shazam
     * app fills, [source]. Shown as the source in the app and on the server, and what "in use" means
     * for that connector; the run reads [source] as it always did. Null for every other sync.
     */
    val via: String? = null,
)

/**
 * A radio connector's station (Settings.radioStations, by connector id): its name, its stream
 * (Icecast/Shoutcast, which send the title on air), and its site. See sync/RadioConnectors.
 */
@Serializable
data class RadioStation(
    val station: String,
    val stream: String,
    val homepage: String? = null,
    /** Where the radio directory says it is from, as shown when it was chosen ("Italy"); may be blank. */
    val country: String = "",
)

/**
 * A timing saved once and used by many syncs ("Morning refresh": daily at 07:30): how often, the
 * time of day, the days, as [SyncJob.everyMinutes], [SyncJob.at] and [SyncJob.days] hold them. Never
 * "manual only". [name] may be empty: it is then listed by its timing. See sync/Schedules.
 */
@Serializable
data class SavedSchedule(val id: String, val name: String = "", val everyMinutes: Int = 0, val at: String? = null, val days: List<Int> = emptyList())

/**
 * A colour with a name ("Gym", "From Spotify"), shared by the syncs that wear it: renaming or
 * recolouring it changes them all. [color] is a ready colour's key or "#RRGGBB". A ready colour
 * the user named has its key as [id].
 */
@Serializable
data class Label(val id: String, val color: String, val name: String = "")

/**
 * A radio station a sync listens to: its stream (Icecast/Shoutcast, which send the title of the
 * song on air with the audio), the days (1 = Monday … 7 = Sunday) and the daily window, in minutes
 * from midnight, local time. [startMinute] == [endMinute] means all day; an end before the start
 * runs past midnight.
 */
@Serializable
data class RadioSource(
    val station: String,
    val stream: String,
    val homepage: String? = null,
    val days: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7),
    val startMinute: Int = 0,
    val endMinute: Int = 0,
)

/**
 * Come una sync sceglie fra i candidati. Le regole di base (ISRC, titolo, artista, durata, versioni)
 * valgono sempre; queste le stringono o le allargano per chi sa cosa vuole: chi ha una playlist di
 * versioni esplicite, chi non vuole mai un live al posto dello studio, chi preferisce meno "da
 * rivedere" e piu' abbinamenti automatici.
 */
@Serializable
data class MatchPolicy(
    /** -1 permissiva, 0 normale, 1 severa: sposta le soglie di accettazione e di riesame. */
    val strictness: Int = 0,
    /** -1 preferisci pulite, 0 indifferente, 1 preferisci esplicite (dove il servizio lo indica). */
    val explicit: Int = 0,
    /** Un album diverso pesa molto invece che poco. */
    val sameAlbum: Boolean = false,
    /** Mai una versione live/remix/acustica/karaoke al posto di una che non lo e'. */
    val studioOnly: Boolean = false,
    /**
     * "Artist names can differ" (#81): the same title (0.95) and length (3 s) with the same album is
     * the song even when the artists' names do not meet (周杰伦 on one service, Jay Chou on the other);
     * without an album to compare it is a song to check. Off by default.
     */
    val artistsMayDiffer: Boolean = false,
) {
    val acceptThreshold: Double get() = 0.70 + 0.08 * strictness
    val reviewThreshold: Double get() = 0.86 + 0.05 * strictness
}

/** Un abbinamento trovato con punteggio basso: giusto probabilmente, ma da far confermare. */
@Serializable
data class MatchReview(val source: Track, val chosen: Track, val score: Double)

@Serializable
data class SyncReport(
    val id: String,
    val jobId: String,
    val jobName: String,
    val startedEpoch: Long,
    val durationMs: Long,
    val sourceCount: Int = 0,
    /** Tracks the target listed before this run (0 when not read, e.g. a target created now). */
    val targetCount: Int = 0,
    /** The target playlist this run read and wrote, for the next run's size check (null in older reports). */
    val targetId: String? = null,
    /**
     * Stopped before writing: the target listed far fewer tracks than the last run left there (that
     * size). The preview, or "Sync anyway" on the web page, runs it once regardless.
     */
    val targetShrunkFrom: Int = 0,
    /** The source was read and listed nothing at all (no loved tracks yet, an emptied playlist). */
    val sourceEmpty: Boolean = false,
    val added: Int = 0,
    val removed: Int = 0,
    val unmatched: List<String> = emptyList(),
    /** Gli stessi brani non trovati, ma strutturati: servono per la risoluzione manuale. */
    val unmatchedTracks: List<Track> = emptyList(),
    /** Brani saltati perche' l'utente li ha messi fra gli ignorati. */
    val ignored: Int = 0,
    /** Abbinamenti incerti da confermare o cambiare. */
    val reviewTracks: List<MatchReview> = emptyList(),
    /**
     * Among [reviewTracks], the source ids a recent run of this sync already asked about (an old
     * match that now reads as a live version, not settled yet): still listed, not announced again.
     */
    val reviewAsked: List<String> = emptyList(),
    /** Brani tolti dalla destinazione con "rispecchia rimozioni": si possono ripristinare. */
    val removedTracks: List<Track> = emptyList(),
    /** What this run added, for the list behind the "added" figure (the first 500). */
    val addedTracks: List<Track> = emptyList(),
    /** Avvisi non bloccanti (es. destinazione che non permette di rimuovere brani). */
    val notes: List<String> = emptyList(),
    val error: String? = null,
    /** True finche' la sync e' in corso: contiene gia' i brani da rivedere, il resto arriva alla fine. */
    val partial: Boolean = false,
    /** Among the not found, the source ids for which the target returned no candidate at all. */
    val absent: List<String> = emptyList(),
    /**
     * Per i non trovati, il candidato migliore rimasto sotto soglia (id del brano d'origine -> brano
     * della destinazione): una proposta da accettare con un tocco, senza rifare la ricerca.
     */
    val suggestions: Map<String, Track> = emptyMap(),
    /** Removals the safety cap held back: they wait for a confirmation in the preview. */
    val heldBack: Int = 0,
    /** The search stopped early (limits of the service): the next run continues it. */
    val searchInterrupted: Boolean = false,
    /**
     * The run failed or stopped because of the connection, not the service ([com.xlollx.songport.net.NetError.Kind]'s
     * name: OFFLINE, TIMEOUT, UNREACHABLE); [error] then says it in plain words. Null otherwise and in older reports.
     */
    val netError: String? = null,
    /**
     * Matched tracks the target did not take this run (the service limited the requests, or refused
     * them): each as "Artist – Title", with the service's reason where it gave one per track. Never
     * counted among the not found ([unmatched]); the next run adds them.
     */
    val notAdded: List<String> = emptyList(),
    /** Star ratings this run set on the target's songs and albums ([SyncJob.copyRatings]). */
    val ratingsSet: Int = 0,
) {
    val ok: Boolean get() = error == null
    /** Stopped because there was no internet: what it did stays, and it runs again once the connection is back. */
    val offline: Boolean get() = netError == "OFFLINE"
}

/**
 * Cosa farebbe una sync, calcolato senza toccare nulla. Serve all'anteprima e come prima meta'
 * dell'esecuzione vera (che poi applica il piano).
 */
data class SyncPlan(
    val targetPlaylistId: String,
    val sourceCount: Int,
    val alreadyPresent: Int,
    val toAdd: List<Track>,
    val toRemove: List<Track>,
    val unmatched: List<Track>,
    val ignored: Int,
    /** Abbinamenti con punteggio sotto la soglia di fiducia: da mostrare per conferma. */
    val uncertain: List<MatchReview>,
    val notes: List<String>,
    /** Abbinamenti scoperti durante il piano, da salvare in cache quando si applica. */
    val newMatches: Map<String, String>,
    /** True se la destinazione e' stata creata adesso (il job e' gia' stato aggiornato). */
    val targetCreated: Boolean,
    /** The source's track ids in its order, for the order step. */
    val sourceOrder: List<String> = emptyList(),
    /** How many tracks the target listed: the scale a removal count is read against. */
    val targetCount: Int = 0,
    /** A preview plan whose removals a normal run would hold back: they need the user's confirmation. */
    val removalsNeedConfirmation: Boolean = false,
    /** Removals held back by the safety cap in this (non-preview) plan. */
    val heldBackRemovals: Int = 0,
    /** The search stopped before the end. */
    val searchInterrupted: Boolean = false,
    /**
     * The target listed far fewer tracks than the last run left there (the size then), 0 when not:
     * set only in the preview, where running anyway is a deliberate choice.
     */
    val targetShrunkFrom: Int = 0,
    /** When the target was read (epoch ms, 0 = not read: created now) and its version just then, where the service has one. */
    val targetReadAt: Long = 0,
    val targetVersion: String? = null,
    /** Extra copies on the target of a track the source holds once: taken out by this run (see SyncEngine). */
    val duplicates: List<Track> = emptyList(),
    /** A two-way pair that follows removals: what this run knows of the pair's common state; null otherwise. */
    val twoWay: TwoWayPlan? = null,
    /** Among [uncertain], the source ids a recent run already asked about (see [SyncReport.reviewAsked]). */
    val reviewAsked: Set<String> = emptySet(),
    /**
     * With [SyncJob.copyRatings]: each rated source track with the target track it matches (already
     * there, or added by this run), for the ratings step after the additions.
     */
    val rated: List<Pair<Track, Track>> = emptyList(),
    /**
     * Entries of the target its server counts and no longer lists (#95: ids from before a rescan),
     * to take out before adding, by writing the target anew with what it lists; 0 = nothing to repair.
     */
    val unlistedToRepair: Int = 0,
    /** Each track to add (by its id) with the source track it was found for: searched again when the target does not show it after adding (#95). */
    val addSources: Map<String, Track> = emptyMap(),
)

/**
 * What one run of a half of a two-way pair with removals hands to its end, where the pair's common
 * state is written again. Ids are the source's on the left, the target's on the right.
 */
data class TwoWayPlan(
    /** Source track -> the target track it matches, for every track both sides list now. */
    val present: Map<String, String>,
    /** Source track -> the target track this run adds for it: common once the addition goes through. */
    val adding: Map<String, String>,
    /** Links of the last state still open: a removal that waits for the other half, or was held back. */
    val kept: List<Pair<String, String>>,
    /** Both listings were whole and the search finished: only then is the state written again. */
    val complete: Boolean,
    /** No state yet (the first run with the option, or the pair's playlists changed): this run only notes it. */
    val first: Boolean,
)

/** Progresso di una sync in corso, mostrato nella UI. */
/**
 * Esito di una ricerca manuale sulla destinazione: tutti i risultati con punteggio e provenienza,
 * gia' ordinati dal piu' simile, e l'album del brano d'origine (trovato o no; null se il servizio non
 * riporta gli album e quindi non si puo' dire nulla).
 */
data class TargetSearch(val hits: List<Hit>, val album: String? = null, val albumFound: Boolean? = null) {
    /** Where a result came from: the song catalogue, the source track's album, or a wider catalogue (YouTube videos). */
    enum class Kind { SONG, ALBUM, VIDEO }
    data class Hit(val track: Track, val score: Double, val kind: Kind)
    fun of(kind: Kind): List<Hit> = hits.filter { it.kind == kind }
}

data class Progress(
    val step: Step, val done: Int = 0, val total: Int = 0, val label: String? = null,
    /** NETWORK_RETRY: this try of [attempts]. */
    val attempt: Int = 0, val attempts: Int = 0,
) {
    /**
     * WAITING: il servizio limita le richieste; done/total = secondi trascorsi/da attendere.
     * NETWORK_RETRY: the connection to [label] (the service) failed; done/total as for WAITING, then
     * try [attempt] of [attempts].
     */
    enum class Step { FETCH_SOURCE, CREATE_TARGET, FETCH_TARGET, MATCHING, WAITING, NETWORK_RETRY, ADDING, REMOVING, BACKUP, DEDUPE }
    /** A wait that passes by itself (the service's limit, or the connection): a pause lets it run on. */
    val isWait: Boolean get() = step == Step.WAITING || step == Step.NETWORK_RETRY
    /** Percentuale del passo corrente, quando il totale e' noto. */
    val percent: Int? get() = if (total > 0) (done * 100 / total).coerceIn(0, 100) else null
}

/** [code]: the HTTP status behind the message, when there is one (0 otherwise). */
class ProviderException(message: String, cause: Throwable? = null, val code: Int = 0) : Exception(message, cause)

/**
 * An add of several tracks where the service refused some ([refused]: track id to the reason it
 * gave) and added the rest; [present] were already there. The sync lists the refused as not added
 * and does not try the others again (they would go in twice).
 */
class TracksRefused(val refused: Map<String, String>, val present: Set<String> = emptySet()) :
    Exception("${refused.size} track(s) refused: ${refused.values.firstOrNull().orEmpty()}")

/**
 * An add the service stopped part way, most often because it limited the requests (too many, a
 * quota): [notAdded] are the ids it did not take, [present] those already there, [why] its answer
 * in words (no ids, no tokens). The rest were added. A sync leaves [notAdded] for its next run:
 * matched, never "not found".
 */
class AddsLimited(val notAdded: Set<String>, val why: String, val present: Set<String> = emptySet()) :
    Exception("${notAdded.size} track(s) not added: $why")
