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
 * strips them). Its hash is the SHA-256 of the text, exactly as written: not a character may change.
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
}
