package com.indo

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class OtakudesuPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Otakudesu())
        registerExtractorAPI(KrakenFiles())
    }
}
