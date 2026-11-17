package com.xlollx.songport.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The saved sign-ins when the phone's secure storage does not open: the app still starts (on an
 * empty store), and why is kept for Accounts and written in the diagnostic log. It used to be silent:
 * every service looked disconnected with no reason given.
 */
class TokenStoreFallbackTest {
    @Test fun aStoreThatDoesNotOpenFallsBackAndSaysWhy() {
        val said = ArrayList<String>()
        val got = TokenStore.openOr(
            encrypted = { throw java.security.GeneralSecurityException("Keystore operation failed", IllegalStateException("user not authenticated")) },
            plain = { "plain" },
            said = { said += it },
        )
        assertEquals("plain", got)
        val cause = TokenStore.unavailable
        assertTrue("$cause", cause!!.contains("GeneralSecurityException: Keystore operation failed") && cause.contains("IllegalStateException: user not authenticated"))
        assertEquals(listOf(cause), said)
    }

    @Test fun aLogThatCannotBeWrittenDoesNotStopTheFallback() {
        assertEquals("plain", TokenStore.openOr<String>({ throw java.io.IOException("no key") }, { "plain" }, { throw IllegalStateException("no disk") }))
        assertTrue(TokenStore.unavailable!!.contains("IOException: no key"))
    }

    @Test fun aStoreThatOpensHasNothingToSay() {
        val said = ArrayList<String>()
        assertEquals("encrypted", TokenStore.openOr({ "encrypted" }, { "plain" }, { said += it }))
        assertNull(TokenStore.unavailable)
        assertTrue(said.isEmpty())
    }

    /** Preferences that note how they were saved: on the disk before returning (commit) or in the background (apply). */
    private class NotingPrefs : android.content.SharedPreferences {
        val saved = HashMap<String, String>()
        val how = ArrayList<String>()
        override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
            private val puts = HashMap<String, String?>()
            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun remove(key: String) = apply { puts[key] = null }
            override fun commit(): Boolean { how += "commit"; write(); return true }
            override fun apply() { how += "apply"; write() }
            private fun write() = puts.forEach { (k, v) -> if (v == null) saved.remove(k) else saved[k] = v }
            override fun putStringSet(key: String, values: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun putBoolean(key: String, value: Boolean) = this
            override fun clear() = this
        }
        override fun getAll(): MutableMap<String, *> = saved
        override fun getString(key: String, defValue: String?): String? = saved[key] ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun contains(key: String) = key in saved
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    /**
     * A renewed sign-in is on the disk before the call returns: a service that changes the refresh
     * token at every renewal has retired the old one, and a write left for later would be lost with
     * a process killed right after.
     */
    @Test fun aRenewedSignInIsWrittenBeforeTheCallReturnsNotInTheBackground() {
        val prefs = NotingPrefs()
        TokenStore.write(prefs, "spotify", "{\"refreshToken\":\"new\"}")
        assertEquals(listOf("commit"), prefs.how)
        assertEquals("{\"refreshToken\":\"new\"}", prefs.saved["spotify"])
        TokenStore.write(prefs, "spotify", null)
        assertEquals(listOf("commit", "commit"), prefs.how)
        assertNull(prefs.saved["spotify"])
    }
}
