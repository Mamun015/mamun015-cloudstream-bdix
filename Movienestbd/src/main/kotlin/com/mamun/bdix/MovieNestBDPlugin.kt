package com.mamun.bdix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovieNestBDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MovieNestBDProvider())
    }
}
