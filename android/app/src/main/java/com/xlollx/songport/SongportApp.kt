package com.xlollx.songport

import com.xlollx.songport.providers.*
import android.app.Application
import com.xlollx.songport.sync.Scheduler

class SongportApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The engine's platform: build-time credentials, resource names, and the connectors this build offers.
        com.xlollx.songport.platform.BuildDefaults.values = com.xlollx.songport.platform.AndroidEnv.buildDefaults()
        com.xlollx.songport.platform.Res.init(this)
        // MusicBrainz asks every client to name itself, with its version and a contact.
        com.xlollx.songport.ai.ArtistMixSource.userAgent = "Trackhop/${BuildConfig.VERSION_NAME} ( https://trackhop.org )"
        com.xlollx.songport.providers.installConnectors()
        // TikTok's pages: read here by the full build; on Google Play only through a connector plugin (BuiltIn.onAppStart).
        // The connections log (hosts only) starts before anything talks to the network.
        com.xlollx.songport.net.HostLog.init(this)
        com.xlollx.songport.providers.BuiltIn.onAppStart(this, com.xlollx.songport.net.HostLog.interceptor)
        // The sync cards' last "up to date" checks, so a card opens with them (FreshnessChecks).
        com.xlollx.songport.ui.FreshnessChecks.init(this)
        Notifications.ensureChannel(this)
        // Riallinea i lavori periodici alla configurazione salvata (idempotente).
        Scheduler.applyAll(this)
    }
}
