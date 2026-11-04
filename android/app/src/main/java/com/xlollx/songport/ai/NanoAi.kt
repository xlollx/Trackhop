package com.xlollx.songport.ai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.xlollx.songport.ai.AiClient.LocalState
import com.xlollx.songport.model.ProviderException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gemini Nano on the phone, through AICore (ML Kit's Prompt API): the AI for people without a key.
 * Most phones and every emulator here have no AICore: then [state] stays NONE and nothing changes.
 * Every call into the library is guarded, so a missing or broken AICore never takes the app down.
 */
object NanoAi : AiClient.LocalModel {
    const val NAME = "Gemini Nano"

    var state by mutableStateOf(LocalState.NONE)
        private set
    /** Download progress, in bytes (total 0 = not known yet). */
    var downloaded by mutableLongStateOf(0L)
        private set
    var total by mutableLongStateOf(0L)
        private set

    private var client: GenerativeModel? = null
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun model(): GenerativeModel = client ?: Generation.getClient().also { client = it }

    /** At app start: plugs into [AiClient] and asks AICore what it has, off the main thread. */
    fun init() {
        AiClient.localModel = this
        scope.launch { refresh() }
    }

    /** Which AI to use for the Roast and the generator: the saved key first, else Nano when offered. */
    fun pick(ctx: Context): AiClient.Config? = AiClient.pick(AiClient.config(ctx), state, NAME)

    private suspend fun refresh() {
        val status = guard { model().checkStatus() }
        state = when (status) {
            FeatureStatus.AVAILABLE -> LocalState.READY
            FeatureStatus.DOWNLOADABLE -> LocalState.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> LocalState.DOWNLOADING
            else -> LocalState.NONE
        }
    }

    /** Starts fetching the model when it is not on the phone yet; [state] and the byte counts follow. */
    fun prepare() {
        if (state == LocalState.DOWNLOADABLE || state == LocalState.DOWNLOADING) scope.launch { runCatching { ready() } }
    }

    private suspend fun ready() = lock.withLock {
        if (state == LocalState.READY) return@withLock
        refresh()
        if (state == LocalState.READY) return@withLock
        if (state == LocalState.NONE) throw ProviderException("$NAME is not available on this phone")
        state = LocalState.DOWNLOADING
        try {
            model().download().collect { s ->
                when (s) {
                    is DownloadStatus.DownloadStarted -> total = s.bytesToDownload
                    is DownloadStatus.DownloadProgress -> downloaded = s.totalBytesDownloaded
                    is DownloadStatus.DownloadFailed -> throw s.e
                    else -> if (s == DownloadStatus.DownloadCompleted) state = LocalState.READY
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            refresh()
            throw ProviderException("$NAME: " + (e.message ?: e.javaClass.simpleName))
        }
        if (state != LocalState.READY) refresh()
        if (state != LocalState.READY) throw ProviderException("$NAME is not ready yet")
    }

    override suspend fun complete(system: String, user: String): String {
        ready()
        // This version of the API has no separate system instruction: both go in one prompt.
        val request = generateContentRequest(TextPart(system + "\n\n" + user)) { temperature = 0.7f }
        val answer = try { model().generateContent(request) } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            throw ProviderException("$NAME: " + (e.message ?: e.javaClass.simpleName))
        }
        return answer.candidates.firstOrNull()?.text.orEmpty()
    }

    private inline fun <T> guard(block: () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (_: Throwable) { null }
}
