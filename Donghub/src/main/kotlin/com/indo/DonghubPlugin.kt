package com.indo

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DonghubPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Donghub())
    }
}
