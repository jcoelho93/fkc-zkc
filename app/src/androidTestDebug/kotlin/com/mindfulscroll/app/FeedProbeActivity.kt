package com.mindfulscroll.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * A stand-in feed for ScrollCountingInstrumentedTest: a long, flingable list of plain rows. A fling
 * emits both TYPE_VIEW_SCROLLED and TYPE_WINDOW_CONTENT_CHANGED for its whole deceleration tail as
 * rows recycle, which is the event mix #25 was about.
 *
 * With [EXTRA_ANIMATE] it also rewrites a label every [ANIMATION_STEP_MILLIS], with no input at
 * all. That's the "video playing, nobody touching the screen" case: content keeps changing, and
 * the scroll count must not keep climbing because of it.
 *
 * **Framework widgets only, deliberately.** This runs in the test apk's own process, since that's
 * what makes it a foreign, monitorable package (see the androidTestDebug manifest). AGP leaves out
 * of the test apk every library the app apk already carries, because the test apk normally runs
 * inside the app's process. Out here there is no androidx at all. A RecyclerView crashed on
 * launch with ClassNotFoundException: androidx.core.view.ScrollingView.
 */
class FeedProbeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var frame = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ticker = TextView(this).apply {
            textSize = 20f
            setPadding(32, 32, 32, 32)
            text = "idle"
        }
        val feed = ListView(this).apply {
            adapter = ArrayAdapter(
                this@FeedProbeActivity,
                android.R.layout.simple_list_item_1,
                List(5_000) { "Post #$it" },
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(ticker)
                addView(feed)
            },
        )

        if (intent.getBooleanExtra(EXTRA_ANIMATE, false)) {
            handler.post(object : Runnable {
                override fun run() {
                    ticker.text = "frame ${frame++}"
                    handler.postDelayed(this, ANIMATION_STEP_MILLIS)
                }
            })
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ANIMATE = "animate"

        /** Well inside the idle gap, so the churn is continuous from the service's point of view. */
        const val ANIMATION_STEP_MILLIS = 250L
    }
}
