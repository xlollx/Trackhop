package com.xlollx.songport.sync

/**
 * A song's title as a rhythm game writes it, made into the title the song has on a music service:
 * the marks of a cut made for the game ("(TV Size)", "(Cut Ver.)", "(Short Ver.)", "(Game Ver.)",
 * "(Sped Up Ver.)", "(Nightcore Mix)") go, so the playlist finds the song itself. Anything else
 * in brackets (a remix by someone, "feat.") stays: the matcher reads it as it reads any title.
 */
object GameTitles {
    private val CUT = Regex(
        """\s*[(\[（［]\s*(?:tv\s*(?:size|ver(?:sion|\.)?|edit)|anime\s*(?:size|ver(?:sion|\.)?)|""" +
            """(?:cut|short|game|movie|op|ed)\s*(?:ver(?:sion|\.)?|edit|size)|""" +
            """sped\s*up(?:\s*ver(?:sion|\.)?)?|nightcore(?:\s*(?:mix|ver(?:sion|\.)?|edit))?|""" +
            """osu!?\s*(?:size|ver(?:sion|\.)?|edit)|full\s*ver(?:sion|\.)?|""" +
            """[^)\]）］]*\bcut\s*ver(?:sion|\.)?)\s*[)\]）］]""",
        RegexOption.IGNORE_CASE,
    )

    /** [raw] without the marks of a game's cut, spaces tidied; [raw] itself when nothing else is left. */
    fun clean(raw: String): String {
        val out = raw.replace(CUT, "").replace(Regex("\\s+"), " ").trim()
        return out.ifEmpty { raw.trim() }
    }

    /**
     * The artists of a game's credit line: "A ft. B", "A feat. B", "A vs. B", "A x B" and "A & B"
     * become separate names only where a game joins them ("ft."/"feat."/"vs."); "&" and "," stay
     * inside a name ("Simon & Garfunkel"), as the matcher reads such a line well enough.
     */
    fun artists(raw: String): List<String> =
        raw.split(Regex("""\s+(?:ft\.?|feat\.?|featuring|vs\.?)\s+""", RegexOption.IGNORE_CASE))
            .map { it.trim() }.filter { it.isNotEmpty() }
}
