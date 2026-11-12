package com.xlollx.songport.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonNameTest {
    @Test
    fun idsAndAddressesAreNotNames() {
        assertNull(PersonName.of("1234567890"))
        assertNull(PersonName.of("31abcdefghijklmnopqrstuvwx"))
        assertNull(PersonName.of("someone@example.com"))
        assertNull(PersonName.of("  "))
        assertNull(PersonName.of(null))
    }

    @Test
    fun realNamesStay() {
        assertEquals("Alex", PersonName.of(" Alex "))
        assertEquals("alex.m", PersonName.of("alex.m"))
        assertEquals("DJ 2000", PersonName.of("DJ 2000"))
    }
}
