package com.xlollx.songport.sync

import com.xlollx.songport.platform.Env
import com.xlollx.songport.data.QuotaDay
import com.xlollx.songport.data.StoreData
import java.time.LocalDate
import java.time.ZoneId

/**
 * Contatore della quota API di YouTube. E' un limite di Google, non di Trackhop, azzerato a
 * mezzanotte ora del Pacifico: qui lo stimiamo contando ogni chiamata fatta da questo telefono,
 * cosi' l'utente vede quanto resta.
 *
 * Dal 1 giugno 2026 (developers.google.com/youtube/v3/determine_quota_cost e revision history)
 * le quote sono due: search.list ha un secchio suo, 100 chiamate al giorno, e non tocca piu' le
 * 10.000 unita' giornaliere, che restano per tutte le altre chiamate (lettura 1, scrittura 50).
 * Le ricerche si contano quindi a parte, in chiamate ([addSearch], [searchesUsed]).
 * Con le credenziali integrate la quota e' condivisa con gli altri utenti della build: la stima
 * e' quindi per difetto e va presentata come tale.
 */
object QuotaMeter {
    /** Units a day for every method except search.list (and videos.insert, which Trackhop never calls). */
    const val YOUTUBE_DAILY = 10_000L
    /** search.list calls a day, in their own bucket since 2026-06-01: a search costs no units. */
    const val YOUTUBE_SEARCH_DAILY = 100L
    const val COST_LIST = 1L
    const val COST_WRITE = 50L

    /** Where the day's searches are kept, next to the units of [serviceId]. */
    private fun searchKey(serviceId: String) = "$serviceId#search"

    fun dayKey(): String = LocalDate.now(ZoneId.of("America/Los_Angeles")).toString()

    fun add(ctx: Env, serviceId: String, units: Long) {
        if (units <= 0) return
        ctx.store.update { d ->
            val today = dayKey()
            val base = d.quota[serviceId]?.takeIf { it.day == today }?.units ?: 0
            d.copy(quota = d.quota + (serviceId to QuotaDay(today, base + units)))
        }
    }

    /** One search.list call: counted in the search bucket, not in the units. */
    fun addSearch(ctx: Env, serviceId: String) = add(ctx, searchKey(serviceId), 1)

    /** Units used today, searches left out. */
    fun used(data: StoreData, serviceId: String): Long =
        data.quota[serviceId]?.takeIf { it.day == dayKey() }?.units ?: 0

    /** search.list calls made today. */
    fun searchesUsed(data: StoreData, serviceId: String): Long = used(data, searchKey(serviceId))

    /** Costo stimato per aggiungere N brani gia' abbinati (un inserimento ciascuno). */
    fun estimateAdd(count: Int): Long = count * COST_WRITE
}
