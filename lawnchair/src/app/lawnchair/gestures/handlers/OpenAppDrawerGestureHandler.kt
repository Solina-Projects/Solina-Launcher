package app.lawnchair.gestures.handlers

import android.content.Context
import app.lawnchair.SolinaLauncher
import app.lawnchair.animateToAllApps

class OpenAppDrawerGestureHandler(context: Context) : GestureHandler(context) {

    override suspend fun onTrigger(launcher: SolinaLauncher) {
        launcher.animateToAllApps()
    }
}
