package com.melomaniac.app

import android.app.Application
import com.melomaniac.app.data.AppContainer

class MeloManiacApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
    }

    companion object {
        lateinit var instance: MeloManiacApp
            private set
    }
}
