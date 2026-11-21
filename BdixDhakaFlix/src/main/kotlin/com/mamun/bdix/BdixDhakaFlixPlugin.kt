package com.mamun.bdix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BdixDhakaFlixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DhakaFlixMoviesProvider())
        registerMainAPI(DhakaFlixSeriesProvider())
        registerMainAPI(DhakaFlixAnimeProvider())
    }
}