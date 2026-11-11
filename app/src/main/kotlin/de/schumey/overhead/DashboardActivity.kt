package de.schumey.overhead

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import kotlin.concurrent.thread

/**
 * Full-screen board for a tablet in landscape: weather, flights and appointments side by side, each drawn by the same
 * code as the widgets. Keeps the screen on and refreshes by itself while it is open.
 */
class DashboardActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var board: Board
    private val ui = Handler(Looper.getMainLooper())
    private var busy = false
    private var lastFlights = 0L
    private var lastSlow = 0L
    private var needsFlightsNow = false

    private val tick = object : Runnable {
        override fun run() {
            work()
            ui.postDelayed(this, 20_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        actionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        board = Board()
        setContentView(board)
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        lastFlights = 0L; lastSlow = 0L
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun hideBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** A context whose density is scaled up on large screens, so the text stays readable from across the room. */
    private fun zoomed(wDp: Float): Context {
        val zoom = (wDp / 1000f).coerceIn(1f, 1.4f)
        if (zoom <= 1.01f) return this
        val cfg = Configuration(resources.configuration).apply { densityDpi = (resources.configuration.densityDpi * zoom).toInt() }
        return createConfigurationContext(cfg)
    }

    private var slowBusy = false

    /** Fetches what is due, then redraws. Flights (slow when airport data is needed) and weather/calendars run apart. */
    private fun work() {
        val now = System.currentTimeMillis()
        if (!busy && (needsFlightsNow || now - lastFlights >= 60_000)) {
            busy = true
            needsFlightsNow = false
            lastFlights = now
            thread {
                try {
                    try { Refresher.refresh(this, force = true, onPartial = { draw() }) } catch (e: Exception) { /* shown as stale */ }
                } finally {
                    busy = false
                }
                draw()
            }
        }
        if (!slowBusy && now - lastSlow >= 10 * 60_000) {
            slowBusy = true
            lastSlow = now
            thread {
                try {
                    try { Forecasts.refresh(this, false) } catch (e: Exception) { /* keep the old data */ }
                    try { Calendars.refreshLinks(this, false) } catch (e: Exception) { /* keep the old data */ }
                } finally {
                    slowBusy = false
                }
                draw()
            }
        }
        thread { draw() }
    }

    /** Renders the three panels from what is stored (no network) and hands them to the view. */
    private fun draw() {
        val (w, h) = board.size()
        if (w <= 0 || h <= 0) return
        val density = resources.displayMetrics.density
        val cx = zoomed(w / density)
        val d = cx.resources.displayMetrics.density
        val margin = (12 * density).toInt()
        val g = (12 * density).toInt()
        val inner = w - 2 * margin - 2 * g
        val wWeather = (inner * 0.30f).toInt()
        val wCal = (inner * 0.30f).toInt()
        val wFlights = inner - wWeather - wCal
        val ph = h - 2 * margin
        val now = System.currentTimeMillis()
        val snap = Snapshot.fromJson(prefs.snapshotJson)
        val weather = WeatherView.render(cx, Forecasts.load(prefs), wWeather, ph, now)
        val flights = Views.render(cx, prefs.appStyle, snap, wFlights, ph, now, loading = snap == null)
        val cal = CalendarView.render(cx, Calendars.load(this, now), wCal, ph, now)
        val x1 = margin.toFloat()
        val x2 = x1 + wWeather + g
        val x3 = x2 + wFlights + g
        val top = margin.toFloat()
        val panels = listOf(
            Panel(weather.bitmap, RectF(x1, top, x1 + wWeather, top + ph)),
            Panel(flights.bitmap, RectF(x2, top, x2 + wFlights, top + ph)),
            Panel(cal.bitmap, RectF(x3, top, x3 + wCal, top + ph)),
        )
        ui.post { board.show(panels, snap?.error == Snapshot.ERR_LOCATION) }
    }

    private class Panel(val bitmap: Bitmap, val rect: RectF)

    private inner class Board : View(this@DashboardActivity) {
        private var panels: List<Panel> = emptyList()
        private var locationMissing = false

        init { setBackgroundColor(0xFF0E0D0B.toInt()) }

        fun size() = width to height

        fun show(p: List<Panel>, noLocation: Boolean) {
            panels = p
            locationMissing = noLocation
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (w > 0 && h > 0) thread { draw() }
        }

        override fun onDraw(canvas: Canvas) {
            for (p in panels) canvas.drawBitmap(p.bitmap, p.rect.left, p.rect.top, null)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_DOWN) return true
            if (e.action != MotionEvent.ACTION_UP) return true
            val hit = panels.indexOfFirst { it.rect.contains(e.x, e.y) }
            when (hit) {
                0 -> { lastSlow = 0L; work() } // weather: refresh now
                1 -> if (locationMissing) {
                    startActivity(Intent(this@DashboardActivity, MainActivity::class.java))
                } else {
                    prefs.appStyle = prefs.appStyle.next() // flights: next look
                    needsFlightsNow = prefs.appStyle == Style.RUNWAY // the runway look needs airport data
                    thread { draw() }
                    if (needsFlightsNow) work()
                }
                2 -> try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}")))
                } catch (ex: Exception) {
                    // no calendar app
                }
            }
            return true
        }
    }
}
