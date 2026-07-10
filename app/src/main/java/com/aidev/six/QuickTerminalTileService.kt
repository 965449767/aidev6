package com.aidev.six

import android.content.Intent
import android.service.quicksettings.TileService

class QuickTerminalTileService : TileService() {

    override fun onClick() {
        startActivityAndCollapse(
            Intent(this, ShellActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
        )
    }
}
