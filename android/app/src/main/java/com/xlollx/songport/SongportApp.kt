package com.xlollx.songport

import com.xlollx.songport.providers.*
import android.app.Application
import com.xlollx.songport.sync.Scheduler

class SongportApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Why the app last closed, in the diagnostics log (Settings › Help): the first thing a report needs.
        com.xlollx.songport.data.CrashLog.install(this)
        // The engine's platform: build-time credentials, resource names, and the connectors this build offers.
        com.xlollx.songport.platform.BuildDefaults.values = com.xlollx.songport.platform.AndroidEnv.buildDefaults()
        com.xlollx.songport.platform.Res.init(this)
        // MusicBrainz asks every client to name itself, with its version and a contact.
        com.xlollx.songport.ai.ArtistMixSource.userAgent = "Trackhop/${BuildConfig.VERSION_NAME} ( https://trackhop.org )"
        com.xlollx.songport.providers.installConnectors()
        // Shared playlists reach their group's folder through the system's folder picker.
        com.xlollx.songport.sync.SafFolder.install(this)
        // Notification channels: their secrets with the sign-ins, encrypted with the Keystore (TokenStore).
        com.xlollx.songport.notify.Notify.secretsInVault = true
        // Gemini Nano, where the phone has it: an AI without a key for the Roast and the generator.
        com.xlollx.songport.ai.NanoAi.init()
        // TikTok's pages: read here by the full build; on Google Play only through a connector plugin (BuiltIn.onAppStart).
        // The connections log (hosts only) starts before anything talks to the network.
        com.xlollx.songport.net.HostLog.init(this)
        // The web connectors' extra accounts ask the connectors whether they can be added: from anywhere, with the app.
        com.xlollx.songport.providers.WebAccounts.app = this
        com.xlollx.songport.providers.BuiltIn.onAppStart(this, com.xlollx.songport.net.HostLog.interceptor)
        // Concerts: the tour dates on Spotify's artist pages, where the Spotify (web) connector is connected (unofficial);
        // a date is placed in a country by the UTC offsets that country uses (Android's time zone data).
        com.xlollx.songport.sync.SpotifyConcerts.reader = com.xlollx.songport.providers.SpotifyBridgeProvider.concertsReader(this)
        // Merch and Concerts: the artists' own sites, found beyond MusicBrainz (Wikidata; their Spotify page where
        // the Spotify (web) connector is connected) and read for their shop and their dates.
        com.xlollx.songport.sync.ArtistSites.enabled = true
        com.xlollx.songport.sync.ArtistSites.spotify = com.xlollx.songport.providers.SpotifySites.reader(this)
        com.xlollx.songport.sync.SpotifyConcerts.zones = { country ->
            android.icu.util.TimeZone.getAvailableIDs(country).mapNotNull { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
        }
        // The sync cards' last "up to date" checks, so a card opens with them (FreshnessChecks).
        com.xlollx.songport.ui.FreshnessChecks.init(this)
        Notifications.ensureChannel(this)
        // Riallinea i lavori periodici alla configurazione salvata (idempotente).
        Scheduler.applyAll(this)
    }
}
