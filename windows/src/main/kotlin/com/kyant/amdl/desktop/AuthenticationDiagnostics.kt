package com.kyant.amdl.desktop

import com.kyant.amdl.api.AmApi
import kotlinx.coroutines.runBlocking

/** Uses the production API, printing validation outcomes without credential values. */
internal object AuthenticationDiagnostics {
    fun run(songUrl: String? = null) = runBlocking {
        val saved = SettingsStore().load()
        check(saved.tokens.devToken.isNotBlank() && saved.tokens.mediaUserToken.isNotBlank()) {
            "No saved Apple Music session is available."
        }
        val api = AmApi(saved.tokens, saved.config.language)
        api.validateSession()
        println("Saved session: authenticated")
        if (songUrl != null) {
            val tracks = api.getTracks(songUrl)
            check(tracks.isNotEmpty()) { "The link contains no tracks." }
            println("Catalog lookup: OK (${tracks.size} tracks)")
            api.getTrackMetadata(tracks.first())
            println("Track metadata: OK")
        }
        println("Authentication diagnostics: OK")
    }
}
