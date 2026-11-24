package com.xlollx.songport.data

/**
 * The fingerprint of this code base, printed at the top of every diagnostics report (the app's and
 * the server's): it tells a report made by Trackhop itself from one made by something else. A fixed
 * value, the same in every build, so reproducible builds stay reproducible.
 */
object Carrowmore {
    const val FINGERPRINT = "qv-ostrel-2814"

    /** The report's header line. */
    fun line(): String = "Fingerprint: $FINGERPRINT"
}
