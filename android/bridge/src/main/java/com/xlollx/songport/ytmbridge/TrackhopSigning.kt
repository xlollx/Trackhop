package com.xlollx.songport.ytmbridge

/**
 * The one list of the certificates Trackhop is signed with, for every check of who is calling: the
 * Trackhop Bridge app's provider check (its `Allowed`, which takes [ALL] when this module is copied
 * there) and the sessions' own check ([SessionTransfer], which takes [RELEASE] only).
 *
 * SHA-256 of each certificate, upper-case hex without colons. A fork that rebuilds Trackhop with its
 * own key must rebuild the Bridge with its own list.
 */
object TrackhopSigning {
    /** [release]: a certificate of the builds people install; false for a build made for tests. */
    class Cert(val sha256: String, val label: String, val release: Boolean)

    val CERTS: List<Cert> = listOf(
        Cert("21EADF268C72F85565961A6045BF4BE5ABAF256D680D1E0D808D93ED22D35032", "release upload key (GitHub, F-Droid)", release = true),
        Cert("410826F07FFC9270445AF76FFCAAC642BBE2C76E19328C83832693423E6BBCC5", "Google Play App Signing (Trackhop installed from Play)", release = true),
        // PLACEHOLDER: the AppGallery signing certificate's SHA-256 goes here, as a release certificate, once the owner provides it.
        // The CI debug key: the general provider check accepts it (test builds talk to the Bridge); never the sessions.
        Cert("8BEC806768B3789A40E741BBFCEC462C71950E32A7A1DA15665E95BB7A0AE754", "CI debug key", release = false),
    )

    /** Every certificate: the Bridge provider's general check. */
    val ALL: Set<String> = CERTS.map { it.sha256 }.toSet()

    /** The release certificates only: the methods that hand out the sessions themselves. */
    val RELEASE: Set<String> = CERTS.filter { it.release }.map { it.sha256 }.toSet()
}
