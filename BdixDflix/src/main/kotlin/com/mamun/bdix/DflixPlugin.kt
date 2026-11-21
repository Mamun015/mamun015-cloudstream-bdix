package com.mamun.bdix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Dflix is one site with two halves: movies under /m and series under /s.
 * Both are registered here so the app shows them as two separate sources.
 */
@CloudstreamPlugin
class DflixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DflixMoviesProvider())
        registerMainAPI(DflixSeriesProvider())
    }
}
