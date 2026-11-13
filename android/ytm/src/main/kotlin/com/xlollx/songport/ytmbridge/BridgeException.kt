package com.xlollx.songport.ytmbridge

/**
 * A web connector's failure, worded for the person: its message is what Trackhop shows. [cause],
 * when given, is the failure under it (a lost connection), kept so it can still be told apart.
 */
class BridgeException(message: String, cause: Throwable? = null) : Exception(message, cause)
