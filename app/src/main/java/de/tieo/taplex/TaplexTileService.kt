package de.tieo.taplex

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Starts a lookup from the quick settings shade, or arms the screen capture fallback where
 * word lookup is turned off.
 */
class TaplexTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        if (TaplexAccessibilityService.running != null) {
            collapseShadeAndLookUp()
            return
        }
        if (CaptureService.running) {
            startService(
                Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)
            )
            refresh()
        } else {
            val intent = ProjectionRequestActivity.intent(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        }
    }

    /**
     * A tile click leaves the shade standing, and the shade is what a lookup would read.
     * Collapsing it is only offered as part of starting an activity, so the lookup goes
     * through [LookupActivity], which closes itself again immediately.
     */
    private fun collapseShadeAndLookUp() {
        val intent = LookupActivity.intent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    1,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        qsTile?.apply {
            state = when {
                TaplexAccessibilityService.running != null -> Tile.STATE_INACTIVE
                CaptureService.running -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }

}
