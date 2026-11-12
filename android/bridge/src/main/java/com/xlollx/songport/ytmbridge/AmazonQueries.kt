package com.xlollx.songport.ytmbridge

/**
 * Full texts of the Amazon Music player's GraphQL operations, for the ones whose persisted query
 * Amazon may forget. The player's Apollo client sends a query as its hash only; when Amazon answers
 * PERSISTED_QUERY_NOT_FOUND it sends the same request again with the text (Apollo's automatic
 * persisted queries), and [AmazonClient] does the same.
 *
 * Each text is the player's own document (dragonfly bundle on the player's CDN, November 2026)
 * printed as the player sends it: graphql-js print(), `__typename` added to every selection set
 * but the operation's (Apollo's cache does it), fields marked `@localOnly` left out (the player
 * strips them), fields marked `@client` left out (Apollo answers them locally). Its hash is the
 * SHA-256 of the text, exactly as written: not a character may change.
 */
internal object AmazonQueries {
    /** The search page's "Songs" results (`SearchTracksResultDocument`). Variables: query, nextPageToken. */
    const val SEARCH_TRACKS_RESULT_HASH = "497c73d052acd7e258f42a1a1cd868e8c143882fafb2c09492aa0fe583b03c6a"
    val SEARCH_TRACKS_RESULT: String = """query searchTracksResult(${'$'}query: String!, ${'$'}nextPageToken: String) {
  searchTracks(
    searchOptions: {searchFilters: [{query: ${'$'}query}], searchType: "personalized", token: ${'$'}nextPageToken}
  ) {
    edgeCount
    edges {
      node {
        uri
        ...bauhausItemTrack
        __typename
      }
      __typename
    }
    pageInfo {
      hasNextPage
      token
      __typename
    }
    __typename
  }
}

fragment itemConverterTrackImage on Track {
  images {
    url
    height
    width
    __typename
  }
  __typename
}

fragment playCapabilityTrack on Track {
  id
  eligibility {
    playbackSubscriptionTiers
    playback {
      canPlayOnDemand
      shuffleType
      __typename
    }
    __typename
  }
  __typename
}

fragment playbackIndicatorCapabilityTrack on Track {
  id
  ...playCapabilityTrack
  __typename
}

fragment itemConverterTrackEqualizer on Track {
  playParams {
    id
    containerId
    __typename
  }
  __typename
}

fragment itemConverterTrackImagery on Track {
  ...itemConverterTrackImage
  ...playbackIndicatorCapabilityTrack
  ...itemConverterTrackEqualizer
  __typename
}

fragment itemConverterTrackLabelReleaseDate on Track {
  releaseDate
  __typename
}

fragment itemConverterTrackLabelTrending on Track {
  isTrending
  __typename
}

fragment itemConverterTrackPrimaryText on Track {
  shortTitle
  __typename
}

fragment itemConverterTrackPrimaryTextInlineBadgeExplicit on Track {
  parentalSettings {
    hasExplicitLanguage
    __typename
  }
  __typename
}

fragment itemConverterTrackSecondaryText on Track {
  contributingArtists {
    concatenatedName
    __typename
  }
  __typename
}

fragment itemTrackDownload on Track {
  __typename
}

fragment itemConverterTrackDownloadStatusIndicator on Track {
  ...itemTrackDownload
  __typename
}

fragment badgesCapabilityTrack on Track {
  id
  hasLyrics
  audioQualities
  ...playCapabilityTrack
  __typename
}

fragment itemConverterTrackBadges on Track {
  ...badgesCapabilityTrack
  __typename
}

fragment itemTrackPlayback on Track {
  id
  playParams {
    id
    containerId
    selections
    __typename
  }
  title
  mediaType
  localAsin
  album {
    id
    title
    images {
      height
      width
      url
      __typename
    }
    contributingArtists {
      edges {
        role
        node {
          id
          name
          __typename
        }
        __typename
      }
      __typename
    }
    audioQualities
    __typename
  }
  contributingArtists {
    edges {
      role
      node {
        id
        name
        __typename
      }
      __typename
    }
    __typename
  }
  parentalSettings {
    hasExplicitLanguage
    __typename
  }
  eligibility {
    playbackSubscriptionTiers
    playback {
      canPlayOnDemand
      shuffleType
      __typename
    }
    __typename
  }
  audioQualities
  playbackInformation(drmType: "WIDEVINE") {
    videoId
    __typename
  }
  hasLyrics
  duration
  __typename
}

fragment useLikeActionOnTrack on Track {
  id
  playParams {
    id
    __typename
  }
  ...itemTrackPlayback
  __typename
}

fragment itemTrackUiClickEntityContext on Track {
  id
  title
  mediaType
  __typename
  playParams {
    id
    __typename
  }
}

fragment itemConverterTrackOnClickPlayback on Track {
  ...itemTrackPlayback
  ...playCapabilityTrack
  ...itemTrackUiClickEntityContext
  __typename
}

fragment trackAlbumMetadata on Track {
  album {
    id
    title
    images {
      height
      width
      url
      __typename
    }
    contributingArtists {
      edges {
        role
        node {
          id
          name
          __typename
        }
        __typename
      }
      __typename
    }
    __typename
  }
  __typename
}

fragment itemConverterTrackPrefetch on Track {
  eligibility {
    playbackSubscriptionTiers
    __typename
  }
  ...trackAlbumMetadata
  __typename
}

fragment bauhausItemTrack on Track {
  id
  ...itemConverterTrackImagery
  ...itemConverterTrackLabelReleaseDate
  ...itemConverterTrackLabelTrending
  ...itemConverterTrackPrimaryText
  ...itemConverterTrackPrimaryTextInlineBadgeExplicit
  ...itemConverterTrackSecondaryText
  ...itemConverterTrackDownloadStatusIndicator
  ...itemConverterTrackBadges
  ...useLikeActionOnTrack
  ...itemConverterTrackOnClickPlayback
  ...itemConverterTrackPrefetch
  title
  contributingArtists {
    edges {
      node {
        id
        name
        __typename
      }
      role
      __typename
    }
    __typename
  }
  __typename
}"""

    /**
     * The search page's "Albums" results (`SearchAlbumsResultDocument`). Variables: query, nextPageToken.
     * The player's document marks three fields @client (download status, library state, like state):
     * Apollo leaves them out before sending, and a fragment left with only `__typename`
     * (itemAlbumDownload, itemConverterAlbumLibraryState) goes with its spreads.
     */
    const val SEARCH_ALBUMS_RESULT_HASH = "a81290f212b38f48b599e9789d8d712e7dd1bfcb95cdcc3777d38af50628aa2a"
    val SEARCH_ALBUMS_RESULT: String = """query searchAlbumsResult(${'$'}query: String!, ${'$'}nextPageToken: String) {
  searchAlbums(
    searchOptions: {searchFilters: [{query: ${'$'}query}], token: ${'$'}nextPageToken}
  ) {
    edgeCount
    edges {
      node {
        uri
        ...bauhausItemAlbum
        __typename
      }
      __typename
    }
    pageInfo {
      hasNextPage
      token
      __typename
    }
    __typename
  }
}

fragment itemConverterAlbumImage on Album {
  images {
    url
    height
    width
    __typename
  }
  __typename
}

fragment playCapabilityAlbum on Album {
  id
  eligibility {
    playback {
      canPlayOnDemand
      shuffleType
      __typename
    }
    __typename
  }
  __typename
}

fragment playbackIndicatorCapabilityAlbum on Album {
  id
  ...playCapabilityAlbum
  __typename
}

fragment itemConverterAlbumEqualizer on Album {
  playParams {
    id
    containerId
    __typename
  }
  __typename
}

fragment itemConverterAlbumImagery on Album {
  ...itemConverterAlbumImage
  ...playbackIndicatorCapabilityAlbum
  ...itemConverterAlbumEqualizer
  __typename
}

fragment itemConverterAlbumLabelReleaseDate on Album {
  releaseDate
  __typename
}

fragment itemConverterAlbumPrimaryText on Album {
  title
  __typename
}

fragment itemConverterAlbumPrimaryTextInlineBadgeExplicit on Album {
  parentalSettings {
    hasExplicitLanguage
    __typename
  }
  __typename
}

fragment itemConverterAlbumSecondaryTextArtistName on Album {
  contributingArtists {
    concatenatedName
    __typename
  }
  __typename
}

fragment itemConverterAlbumSecondaryTextFormatAndReleaseDate on Album {
  format
  releaseDate
  __typename
}

fragment itemConverterAlbumSecondaryTextArtistAndReleaseDate on Album {
  ...itemConverterAlbumSecondaryTextArtistName
  releaseDate
  __typename
}

fragment itemConverterAlbumDownloadStatusIndicator on Album {
  __typename
}

fragment badgesCapabilityAlbum on Album {
  id
  ...playCapabilityAlbum
  eligibility {
    playback {
      canPlayOnDemand
      __typename
    }
    __typename
  }
  audioQualities
  __typename
}

fragment itemConverterAlbumBadges on Album {
  ...badgesCapabilityAlbum
  __typename
}

fragment itemConverterAlbumBadgeEligibility on Album {
  ...playCapabilityAlbum
  __typename
}

fragment itemAlbumUiClickEntityContext on Album {
  id
  title
  __typename
}

fragment itemConverterAlbumOnClickNavigation on Album {
  id
  ...itemAlbumUiClickEntityContext
  __typename
}

fragment itemAlbumPlayback on Album {
  playParams {
    id
    containerId
    selections
    __typename
  }
  __typename
}

fragment itemConverterAlbumOnClickPlayback on Album {
  id
  ...itemAlbumPlayback
  ...playCapabilityAlbum
  ...itemAlbumUiClickEntityContext
  __typename
}

fragment useLikeActionOnAlbum on Album {
  id
  title
  releaseDate
  contributingArtists {
    edges {
      role
      node {
        id
        name
        __typename
      }
      __typename
    }
    __typename
  }
  playParams {
    id
    __typename
  }
  __typename
}

fragment bauhausItemAlbum on Album {
  id
  ...itemConverterAlbumImagery
  ...itemConverterAlbumLabelReleaseDate
  ...itemConverterAlbumPrimaryText
  ...itemConverterAlbumPrimaryTextInlineBadgeExplicit
  ...itemConverterAlbumSecondaryTextArtistName
  ...itemConverterAlbumSecondaryTextFormatAndReleaseDate
  ...itemConverterAlbumSecondaryTextArtistAndReleaseDate
  ...itemConverterAlbumDownloadStatusIndicator
  ...itemConverterAlbumBadges
  ...itemConverterAlbumBadgeEligibility
  ...itemConverterAlbumOnClickNavigation
  ...itemConverterAlbumOnClickPlayback
  ...useLikeActionOnAlbum
  __typename
}"""

    /**
     * The search page's "Artists" results (`SearchArtistsResultDocument`). Variables: query, nextPageToken.
     * Printed by scripts/amazon_queries.py from the player of November 2026.
     */
    const val SEARCH_ARTISTS_RESULT_HASH = "fcc1f3af13c13e10829a800f547972716ecb533316982a3033d15b46f1b0beca"
    val SEARCH_ARTISTS_RESULT: String = """query searchArtistsResult(${'$'}query: String!, ${'$'}nextPageToken: String) {
  searchArtists(
    searchOptions: {searchFilters: [{query: ${'$'}query}], token: ${'$'}nextPageToken}
  ) {
    edgeCount
    edges {
      node {
        uri
        ...bauhausItemArtist
        __typename
      }
      __typename
    }
    pageInfo {
      hasNextPage
      token
      __typename
    }
    __typename
  }
}

fragment itemConverterArtistImage on Artist {
  images {
    url
    imageType
    width
    height
    __typename
  }
  __typename
}

fragment itemConverterArtistEqualizer on Artist {
  playParams {
    id
    __typename
  }
  __typename
}

fragment itemConverterArtistImagery on Artist {
  ...itemConverterArtistImage
  ...itemConverterArtistEqualizer
  __typename
}

fragment itemConverterArtistPrimaryText on Artist {
  name
  __typename
}

fragment itemConverterArtistSecondaryText on Artist {
  fanContext {
    fanRank
    __typename
  }
  __typename
}

fragment itemArtistUiClickEntityContext on Artist {
  id
  name
  __typename
}

fragment itemConverterArtistOnClickNavigation on Artist {
  id
  ...itemArtistUiClickEntityContext
  __typename
}

fragment itemArtistPlayback on Artist {
  id
  playParams {
    id
    selections
    __typename
  }
  __typename
}

fragment itemConverterArtistOnClickPlayback on Artist {
  id
  ...itemArtistPlayback
  ...itemArtistUiClickEntityContext
  __typename
}

fragment bauhausItemArtist on Artist {
  id
  ...itemConverterArtistImagery
  ...itemConverterArtistPrimaryText
  ...itemConverterArtistSecondaryText
  ...itemConverterArtistOnClickNavigation
  ...itemConverterArtistOnClickPlayback
  __typename
}"""
}
