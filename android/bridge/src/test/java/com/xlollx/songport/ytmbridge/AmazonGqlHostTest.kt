package com.xlollx.songport.ytmbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player's API host: gql.music.amazon.<tld>, or an EU-side one for "Rest of Europe" accounts. */
class AmazonGqlHostTest {
    @Test fun theUsualAndTheEuSideHostsAreApiHosts() {
        assertTrue(AmazonClient.isGqlHost("gql.music.amazon.com"))
        assertTrue(AmazonClient.isGqlHost("gql.music.amazon.co.uk"))
        assertTrue(AmazonClient.isGqlHost("gql-eu.music.amazon.com"))
        assertTrue(AmazonClient.isGqlHost("eu.gql.music.amazon.com"))
        assertTrue(AmazonClient.isGqlHost("GQL.music.amazon.de"))
    }

    @Test fun otherHostsAreNot() {
        assertFalse(AmazonClient.isGqlHost("music.amazon.com"))
        assertFalse(AmazonClient.isGqlHost("gql.music.amazon.com.evil.net"))
        assertFalse(AmazonClient.isGqlHost("m.media-amazon.com"))
        assertFalse(AmazonClient.isGqlHost("gql.example.com"))
        assertFalse(AmazonClient.isGqlHost(""))
    }
}
