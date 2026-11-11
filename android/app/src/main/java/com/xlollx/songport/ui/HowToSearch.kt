package com.xlollx.songport.ui

import com.xlollx.songport.sync.Labels

/**
 * The search at the top of How to: which guides hold every word typed, in their title, summary or
 * text. Case and accents don't count (Città finds citta, ÇALMA finds çalma), nor the dotless ı of
 * Turkish; scripts without spaces (Japanese, Chinese) match as typed.
 */
object HowToSearch {
    private fun fold(s: String): String = Labels.fold(s).replace('ı', 'i')

    /** The words of [query], folded; empty when there is nothing to look for. */
    fun words(query: String): List<String> = fold(query).split(' ').filter { it.isNotEmpty() }

    /** True when [text] holds every word of [query] (a blank query holds in every text). */
    fun matches(query: String, text: String): Boolean {
        val words = words(query)
        if (words.isEmpty()) return true
        val folded = fold(text)
        return words.all { it in folded }
    }

    /** The [items] whose [text] holds every word of [query], in their order. */
    fun <T> filter(items: List<T>, query: String, text: (T) -> String): List<T> =
        if (words(query).isEmpty()) items else items.filter { matches(query, text(it)) }
}
