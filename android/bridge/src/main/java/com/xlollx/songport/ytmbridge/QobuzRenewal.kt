package com.xlollx.songport.ytmbridge

/**
 * Renews the Qobuz session without the person, when Qobuz refuses the kept token (401): the player
 * at play.qobuz.com, opened hidden in the account's profile ([SessionRenewal]), names its session in
 * the headers of its API calls, as at sign-in ([QobuzBridge.fromRequest]): the user token and the
 * app id. Another token, or the same with another app id, is a renewal.
 */
val QobuzRenewal = SessionRenewal(
    service = "qobuz",
    session = { QobuzBridge.session(it) },
    tokenKey = QobuzWebClient.TOKEN,
    alsoNew = listOf(QobuzWebClient.APP_ID),
    labels = mapOf(QobuzWebClient.TOKEN to "token", QobuzWebClient.APP_ID to "app id"),
) { slot ->
    HiddenSessionPage.Spec(
        profile = "qobuz", slot = slot, url = QobuzWebClient.SITE + "/", widePage = true,
        fromRequest = { r ->
            QobuzBridge.fromRequest(r.url.toString(), r.requestHeaders)?.let { (token, appId) ->
                listOfNotNull(QobuzWebClient.TOKEN to token, appId?.let { QobuzWebClient.APP_ID to it }).toMap()
            }
        },
    )
}
