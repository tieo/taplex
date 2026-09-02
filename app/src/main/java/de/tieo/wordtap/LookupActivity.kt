package de.tieo.wordtap

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Starts a lookup and gets out of the way.
 *
 * The quick settings tile can only close the shade as part of launching an activity, and a
 * lookup that ran with the shade still open would read the shade. This activity is that
 * launch: it finishes itself at once, waits for the app underneath to come back to the
 * front, and then asks the accessibility service to read the screen.
 */
class LookupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
        val service = WordTapAccessibilityService.running
        if (service == null) {
            startActivity(Intent(this, MainActivity::class.java))
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({ service.lookUp() }, SETTLE_MS)
    }

    companion object {
        /** Long enough for the shade to be gone and the app underneath to be drawn again. */
        private const val SETTLE_MS = 400L

        fun intent(context: Context): Intent =
            Intent(context, LookupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
