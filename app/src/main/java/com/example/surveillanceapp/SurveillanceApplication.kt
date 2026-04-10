package com.example.surveillanceapp

import android.app.Application
import android.content.Context
import com.example.surveillanceapp.dji.DjiMobileSdk

/**
 * Application entry: DJI MSDK v5 requires early native helper installation and SDK init
 * before any activity uses the SDK (registration, product link, video).
 */
class SurveillanceApplication : Application() {

    // region DJI native bootstrap (official sample pattern)
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        com.cySdkyc.clx.Helper.install(this)
    }
    // endregion

    // region Lifecycle
    override fun onCreate() {
        super.onCreate()
        DjiMobileSdk.start(applicationContext)
    }

    override fun onTerminate() {
        DjiMobileSdk.destroy()
        super.onTerminate()
    }
    // endregion
}
