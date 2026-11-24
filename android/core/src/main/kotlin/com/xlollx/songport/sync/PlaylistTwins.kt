package com.xlollx.songport.sync

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider

/**
 * Tools › Duplicate playlists (1.18): the copies left on a service by transfers made more than once,
 * grouped when they have the same name or at least [SAME] of the same songs (songs told apart as the
 * duplicates in one playlist are, [Duplicates]). In each group one is kept: the one a sync uses, else
 * the one changed last, else the longest. A playlist a sync uses is never deleted; each one deleted
 * is saved in Files first, as the backup saves it.
 */
object PlaylistTwins {
    /** Share of the songs two playlists must have in common (of the longer one) to be copies of each other. */
    const val SAME = 0.9

    /** One playlist: its songs, the syncs that use it, and when a song was last added (0 when the service does not say). */
    data class Copy(val playlist: Playlist, val tracks: List<Track>, val syncs: List<String>, val changedAt: Long) {
        val inSync: Boolean get() = syncs.isNotEmpty()
    }

    /** Copies of one playlist; [keep] is the index of the one proposed to keep. */
    data class Group(val copies: List<Copy>, val sameName: Boolean, val keep: Int) {
        /** Stable across readings: the copies' ids. */
        val key: String get() = copies.map { it.playlist.id }.sorted().joinToString("|")
    }

    private fun nameKey(name: String) = Matcher.normalizeTitle(name).ifEmpty { name.trim().lowercase() }

    /** What one song is known by: its id, its ISRC, its normalised title with its artists. */
    private fun keys(t: Track): List<String> = listOfNotNull(
        "i:" + t.id, t.isrcNorm?.let { "r:$it" },
        "t:" + Matcher.normalizeTitle(t.title) + "|" + t.artists.map { Matcher.normalizeArtist(it) }.filter { it.isNotEmpty() }.sorted().joinToString(","),
    )

    /** The songs of [copy] that [kept] does not have. */
    fun missing(kept: List<Track>, copy: List<Track>): List<Track> {
        val known = kept.flatMapTo(HashSet()) { keys(it) }
        return copy.filter { t -> keys(t).none { it in known } }
    }

    /** How much of the longer of the two the other has, 0–100. */
    fun same(a: List<Track>, b: List<Track>): Int {
        val longer = maxOf(a.size, b.size)
        if (longer == 0) return 100
        val (small, big) = if (a.size <= b.size) a to b else b to a
        val common = small.size - missing(big, small).size
        return (100.0 * common / longer).toInt()
    }

    /** The groups among [copies]: same name, or [SAME] of the same songs. Copies alone in their group are left out. */
    fun groups(copies: List<Copy>): List<Group> {
        val parent = IntArray(copies.size) { it }
        fun root(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; return r }
        val names = copies.map { nameKey(it.playlist.name) }
        for (i in copies.indices) for (j in i + 1 until copies.size) {
            if (root(i) == root(j)) continue
            val a = copies[i].tracks
            val b = copies[j].tracks
            val alike = names[i] == names[j] || (a.isNotEmpty() && b.isNotEmpty() && same(a, b) >= SAME * 100)
            if (alike) parent[root(j)] = root(i)
        }
        return copies.indices.groupBy { root(it) }.values.filter { it.size > 1 }.map { idx ->
            val list = idx.map { copies[it] }
            Group(list, idx.map { names[it] }.distinct().size == 1, suggested(list))
        }
    }

    /** The one to keep: used by a sync, else changed last, else the longest. */
    fun suggested(copies: List<Copy>): Int =
        copies.indices.maxWith(compareBy<Int>({ copies[it].inSync }, { copies[it].changedAt }, { copies[it].tracks.size }))

    /** The syncs (by name) that read or write [playlistId] of [providerId]. */
    fun syncsOf(jobs: List<SyncJob>, providerId: String, playlistId: String): List<String> = jobs.filter { j ->
        (j.source.provider == providerId && j.source.playlistId == playlistId) || (j.target.provider == providerId && j.target.playlistId == playlistId)
    }.map { it.name }

    /** Reads the user's own playlists of [provider] and groups the copies. Liked songs and libraries are not playlists to delete. */
    suspend fun find(env: Env, provider: MusicProvider, onProgress: (Progress) -> Unit = {}): List<Group> {
        val lists = provider.playlists(env).filter { it.ownedByMe && !MusicProvider.isLibrary(it.id) }
        val jobs = env.store.data.syncs
        val copies = lists.mapIndexed { i, pl ->
            onProgress(Progress(Progress.Step.BACKUP, i, lists.size))
            val tracks = provider.tracks(env, pl.id)
            Copy(pl, tracks, syncsOf(jobs, provider.id, pl.id), tracks.maxOfOrNull { it.addedAt } ?: 0)
        }
        return groups(copies)
    }

    /**
     * Deletes [gone] from [provider], each saved in Files first under the name [savedAs] gives it;
     * with [addMissing], the songs of each that [kept] lacks are added to it before. A copy a sync
     * uses (now, not only when it was read) is never deleted. Answers the ids deleted.
     */
    suspend fun delete(env: Env, provider: MusicProvider, kept: Copy, gone: List<Copy>, addMissing: Boolean, savedAs: (String) -> String): List<String> {
        if (!provider.canDeletePlaylists) throw ProviderException(env.text("error_manage_unsupported", provider.displayName))
        val jobs = env.store.data.syncs
        val deletable = gone.filter { it.playlist.id != kept.playlist.id && syncsOf(jobs, provider.id, it.playlist.id).isEmpty() }
        if (addMissing) {
            val known = kept.tracks.toMutableList()
            val add = ArrayList<Track>()
            deletable.forEach { c -> missing(known, c.tracks).forEach { t -> add += t; known += t } }
            if (add.isNotEmpty()) provider.addTracks(env, kept.playlist.id, add)
        }
        val inUse = LocalFilesProvider.protectedIds(env)
        val service = LibraryTools.backupService(provider)
        val used = HashMap<String, Int>()
        val deleted = ArrayList<String>()
        for (c in deletable) {
            // Two copies of one name are saved as two files, not as versions of one.
            val base = savedAs(c.playlist.name)
            val n = used.merge(base, 1, Int::plus)!!
            LocalFilesProvider.writeBackup(env, service, if (n == 1) base else "$base $n", Origins.stamp(provider, c.tracks), inUse)
            provider.deletePlaylist(env, c.playlist.id)
            deleted += c.playlist.id
        }
        return deleted
    }
}
