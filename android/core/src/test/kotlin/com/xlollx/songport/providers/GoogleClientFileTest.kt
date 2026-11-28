package com.xlollx.songport.providers

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class GoogleClientFileTest {
    @Test
    fun readsADesktopAppClient() {
        val c = GoogleClientFile.parse("""{"installed":{"client_id":"123-abc.apps.googleusercontent.com","project_id":"trackhop","auth_uri":"https://accounts.google.com/o/oauth2/auth","client_secret":"GOCSPX-test","redirect_uris":["http://localhost"]}}""")
        assertEquals(GoogleClientFile.Client("123-abc.apps.googleusercontent.com", "GOCSPX-test"), c)
    }

    @Test
    fun readsAWebApplicationClient() {
        val c = GoogleClientFile.parse("﻿" + """ {"web":{"client_id":" 9-x.apps.googleusercontent.com ","client_secret":"s"}} """)
        assertEquals(GoogleClientFile.Client("9-x.apps.googleusercontent.com", "s"), c)
    }

    @Test
    fun refusesAnythingElse() {
        assertNull(GoogleClientFile.parse("not json"))
        assertNull(GoogleClientFile.parse("""{"installed":{"client_secret":"s"}}"""))
        assertNull(GoogleClientFile.parse("""{"type":"service_account","client_id":"1234567890","private_key":"x"}"""))
        assertNull(GoogleClientFile.parse("[1,2]"))
    }
}
