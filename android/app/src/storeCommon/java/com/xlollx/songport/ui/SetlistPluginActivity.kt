package com.xlollx.songport.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.xlollx.songport.R
import com.xlollx.songport.data.Diagnostics
import com.xlollx.songport.providers.BridgePlugin
import com.xlollx.songport.providers.PluginRelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Store builds: setlist.fm's own site, browsed without a setlist.fm key in the connector plugin's
 * screen ([BridgePlugin.SETLIST_ACTION]), the way the GitHub and F-Droid build browses it in its own.
 * Nothing on screen here: it checks the plugin is new enough ([PluginRelay.browsesSetlists]; if not,
 * it says to update it, as the other features a plugin brings do), opens the plugin's screen for a
 * result, and hands the concert the person chose to the Setlists page ([SetlistsKept.browsed]), where
 * the usual picker asks where it goes.
 */
class SetlistPluginActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }

    private val browse = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            runCatching { PluginRelay.setlistFromPlugin(data.getStringExtra(RESULT_HTML), data.getStringExtra(RESULT_URL)) }
                .onSuccess { SetlistsKept.browsed(it, data.getBooleanExtra(RESULT_EXISTING, false)) }
                .onFailure { e ->
                    Diagnostics.log(applicationContext, "setlists", "the plugin's page could not be read: ${e.message}")
                    Toast.makeText(this, e.message ?: getString(R.string.error_generic), Toast.LENGTH_LONG).show()
                }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Remade while the plugin's screen is open: its answer still comes to [browse].
        if (savedInstanceState != null) return
        val app = applicationContext
        lifecycleScope.launch {
            val ready = withContext(Dispatchers.IO) { PluginRelay.browsesSetlists(app) }
            val pkg = BridgePlugin.packageName(app)
            val opened = ready && pkg != null && runCatching {
                browse.launch(
                    Intent(BridgePlugin.SETLIST_ACTION).setPackage(pkg)
                        .putExtra(EXTRA_ARTIST, intent.getStringExtra(EXTRA_ARTIST)).putExtra(EXTRA_LINK, intent.getStringExtra(EXTRA_LINK)),
                )
            }.isSuccess
            if (!opened) {
                Toast.makeText(this@SetlistPluginActivity, R.string.setlists_browse_need_plugin, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    companion object {
        // The plugin's extras, as Trackhop Bridge's SetlistBrowseActivity reads and answers them.
        private const val EXTRA_ARTIST = "artist"
        private const val EXTRA_LINK = "link"
        private const val RESULT_HTML = "html"
        private const val RESULT_URL = "url"
        private const val RESULT_EXISTING = "existing"

        fun intent(ctx: Context, artist: String?, link: String?): Intent =
            Intent(ctx, SetlistPluginActivity::class.java).putExtra(EXTRA_ARTIST, artist).putExtra(EXTRA_LINK, link)
    }
}
