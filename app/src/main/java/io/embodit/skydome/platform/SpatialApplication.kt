package io.embodit.skydome.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.skydome.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
