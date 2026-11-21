package com.mamun.bdix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnirexToPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnirexToProvider())
    }
}