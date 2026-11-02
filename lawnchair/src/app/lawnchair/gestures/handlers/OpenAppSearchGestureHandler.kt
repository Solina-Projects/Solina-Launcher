package app.lawnchair.gestures.handlers

import android.content.Context
import app.lawnchair.SolinaLauncher
import app.lawnchair.animateToAllApps

class OpenAppSearchGestureHandler(context: Context) : GestureHandler(context) {

    override suspend fun onTrigger(launcher: SolinaLauncher) {
        val searchUiManager = launcher.appsView.searchUiManager
        searchUiManager.setDirectFocus(true)
        searchUiManager.editText?.showKeyboard()
        launcher.animateToAllApps()
    }
}
