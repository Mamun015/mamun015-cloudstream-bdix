package com.mamun.bdix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BdixICCFtpPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BdixICCFtpProvider())
    }
}
