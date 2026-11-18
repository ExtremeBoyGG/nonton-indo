package com.indo

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CA3DPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CA3D())
    }
}
