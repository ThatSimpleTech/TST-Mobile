package com.thatsimpletech.assist.a11y

import android.app.KeyguardManager
import android.content.Context
import com.thatsimpletech.assist.core.loop.Observer
import com.thatsimpletech.assist.core.observe.Rect
import com.thatsimpletech.assist.core.observe.Screen

/**
 * No-accessibility observer (D8 / TM-024). Intent / device / partner verbs parse
 * without hints and execute. Hint verbs parse-fail (empty hint set) and stop
 * after one repair. QS still needs the screen driver.
 */
class EmptyObserver(private val context: Context) : Observer {
    override fun observe(): Screen {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return Screen(
            app = "",
            activity = "",
            display = Rect(0, 0, 1, 1),
            nodes = emptyList(),
            keyguard = km?.isKeyguardLocked == true,
            secure = false,
            onQs = false,
        )
    }

    override fun fingerprint(): String = FINGERPRINT

    companion object {
        const val FINGERPRINT = "empty"
    }
}
