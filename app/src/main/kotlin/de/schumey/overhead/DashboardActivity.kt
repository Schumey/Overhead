package de.schumey.overhead

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
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

    /** Renders the board from what is stored (no network) and hands it to the view. */
    private fun draw() {
        val (w, h) = board.size()
        if (w <= 0 || h <= 0) return
        val density = resources.displayMetrics.density
        val cx = zoomed(w / density)
        val d = cx.resources.displayMetrics.density
        val margin = (12 * density).toInt()
        val g = (12 * density).toInt()
        val inner = w - 2 * margin - g
        val ph = h - 2 * margin
        val now = System.currentTimeMillis()
        val snap = Snapshot.fromJson(prefs.snapshotJson)
        val weather = Forecasts.load(prefs)
        val agenda = Calendars.load(this, now)
        val style = prefs.dashStyle
        val top = margin.toFloat()
        val wCal = (inner * 0.30f).toInt()
        val wMain = inner - wCal
        val xCal = margin + wMain + g.toFloat()
        val cal = CalendarView.render(cx, agenda, wCal, ph, now, clock = true)
        val parts = ArrayList<Part>()
        val zones = ArrayList<Zone>()
        val openCalendar = Zone(RectF(xCal, top, xCal + wCal, top + ph)) { openCalendarApp() }
        parts += Part(cal.bitmap, xCal, top)

        if (style == Style.POSTER) {
            // One sky for both: the planes fly in the weather's sky, the weather and the flights sit below it.
            val bodyH = (ph * 0.5f).toInt()
            val wW = (wMain * 0.42f).toInt()
            val wF = wMain - wW
            val flights = Poster.render(cx, snap, wF, bodyH, now, loading = snap == null, sky = false)
            val wx = WeatherView.render(cx, weather, wW, bodyH, now, sky = false)
            val skyH = ph - bodyH
            val panel = Bitmap.createBitmap(wMain, ph, Bitmap.Config.ARGB_8888)
            val c = Canvas(panel)
            val r = 26f * d
            c.clipPath(Path().apply { addRoundRect(0f, 0f, wMain.toFloat(), ph.toFloat(), r, r, Path.Direction.CW) })
            c.drawColor(Poster.BG)
            val headerH = 30f * d
            val cond = WeatherView.conditions(weather) ?: SkyArt.Conditions(cloud = (snap?.cloudCover ?: 40) / 100f, precipMm = snap?.precipMm ?: 0.0)
            SkyArt.draw(c, weather?.lat ?: snap?.lat, weather?.lon ?: snap?.lon, now, cond, wMain.toFloat(), skyH.toFloat(), d, headerH, bodyScale = 1.25f)
            val shown = (if (snap?.error == Snapshot.ERR_LOCATION) emptyList() else snap?.flights.orEmpty()).take(flights.rowHex.size)
            Poster.drawPlanes(c, shown, wMain.toFloat(), skyH.toFloat(), d, headerH)
            Poster.drawHeader(c, cx, snap, snap == null, now, wMain.toFloat(), 16f * d, headerH, d)
            c.drawBitmap(wx.bitmap, 0f, skyH.toFloat(), null)
            c.drawBitmap(flights.bitmap, wW.toFloat(), skyH.toFloat(), null)
            c.drawRect(wW.toFloat() - d, skyH + 14f * d, wW.toFloat() + d, ph - 14f * d, android.graphics.Paint().apply { color = 0x33F1EEE3 })
            parts += Part(panel, margin.toFloat(), top)
            zones += Zone(RectF(margin.toFloat(), top + skyH, margin + wW.toFloat(), top + ph)) { refreshWeather() }
            zones += Zone(RectF(margin.toFloat(), top, margin + wMain.toFloat(), top + skyH)) { nextLook() }
            zones += Zone(RectF(margin + wW.toFloat(), top + skyH, margin + wMain.toFloat(), top + ph)) { if (snap?.error == Snapshot.ERR_LOCATION) openSettings() else nextLook() }
        } else {
            // weather and one of the other flight looks as separate panels
            val wW = (wMain * 0.42f).toInt()
            val wF = wMain - wW - g
            val wx = WeatherView.render(cx, weather, wW, ph, now)
            val fl = Views.render(cx, style, snap, wF, ph, now, loading = snap == null)
            parts += Part(wx.bitmap, margin.toFloat(), top)
            parts += Part(fl.bitmap, margin + wW + g.toFloat(), top)
            zones += Zone(RectF(margin.toFloat(), top, margin + wW.toFloat(), top + ph)) { refreshWeather() }
            zones += Zone(RectF(margin + wW + g.toFloat(), top, margin + wMain.toFloat(), top + ph)) { if (snap?.error == Snapshot.ERR_LOCATION) openSettings() else nextLook() }
        }
        zones += openCalendar
        ui.post { board.show(parts, zones) }
    }

    private fun refreshWeather() { lastSlow = 0L; work() }

    private fun openSettings() = startActivity(Intent(this, MainActivity::class.java))

    /** Sky (shared with the weather) -> radar -> board -> runway -> sky ... */
    private fun nextLook() {
        prefs.dashStyle = prefs.dashStyle.next()
        needsFlightsNow = prefs.dashStyle == Style.RUNWAY // the runway look needs airport data
        thread { draw() }
        if (needsFlightsNow) work()
    }

    private fun openCalendarApp() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}")))
        } catch (ex: Exception) {
            // no calendar app
        }
    }

    private class Part(val bitmap: Bitmap, val x: Float, val y: Float)

    private class Zone(val rect: RectF, val tap: () -> Unit)

    private inner class Board : View(this@DashboardActivity) {
        private var parts: List<Part> = emptyList()
        private var zones: List<Zone> = emptyList()

        init { setBackgroundColor(0xFF0E0D0B.toInt()) }

        fun size() = width to height

        fun show(p: List<Part>, z: List<Zone>) {
            parts = p
            zones = z
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (w > 0 && h > 0) thread { draw() }
        }

        override fun onDraw(canvas: Canvas) {
            for (p in parts) canvas.drawBitmap(p.bitmap, p.x, p.y, null)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_UP) zones.firstOrNull { it.rect.contains(e.x, e.y) }?.tap?.invoke()
            return true
        }
    }
}
