package com.xlollx.songport.providers

import com.xlollx.songport.platform.Env

/**
 * A web connector that can start over: signed out, and its sites' cookies and stored data in the
 * app's browser engine forgotten, so the next sign-in asks again (a site that remembered a Google
 * sign-in the connector could not read, #53). The account on the service is untouched.
 */
interface StartsOver {
    fun startOver(ctx: Env)
}
