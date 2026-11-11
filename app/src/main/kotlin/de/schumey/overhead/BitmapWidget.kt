package de.schumey.overhead

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * A widget that is one picture drawn by [render], refreshed by a self-rescheduling non-wakeup alarm (no network while
 * the screen is off, an immediate refresh when the label at the top right is tapped). The weather widget does the
 * same by itself; the calendar and the combined widget share this.
 */
abstract class BitmapWidget : AppWidgetProvider() {
    /** Used in action names and as the request code offset, e.g. "CALENDAR". */
    protected abstract val key: String
    protected abstract val requestBase: Int
    protected abstract val intervalMs: Long

    /** Network and slow work; called on a background thread. */
    protected abstract fun refreshData(ctx: Context, force: Boolean)

    protected abstract fun render(ctx: Context, w: Int, h: Int, now: Long, loading: Boolean): Rendered

    /** Tap zones; the default is "the whole picture opens the app". */
    protected open fun bindZones(ctx: Context, rv: RemoteViews, h: Int, d: Float) {
        rv.setViewLayoutHeight(R.id.zone_sky, h.toFloat(), TypedValue.COMPLEX_UNIT_PX)
        rv.setOnClickPendingIntent(R.id.zone_sky, openApp(ctx))
    }

    /** Debug dump of a picture with made-up data; the default is the real one. */
    protected open fun renderDump(ctx: Context, w: Int, h: Int, now: Long, intent: Intent): Rendered = render(ctx, w, h, now, false)

    private val actionRefresh get() = "de.schumey.overhead.${key}_REFRESH"
    private val actionTick get() = "de.schumey.overhead.${key}_TICK"
    private val actionDump get() = "de.schumey.overhead.${key}_DUMP"

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE, actionRefresh, actionTick -> {
                val userTap = intent.action == actionRefresh
                scheduleNext(ctx)
                val pending = goAsync()
                thread {
                    try {
                        val interactive = ctx.getSystemService(PowerManager::class.java)?.isInteractive != false
                        if (userTap) renderAll(ctx, loading = true)
                        if (interactive || userTap) refreshData(ctx, userTap)
                        renderAll(ctx)
                    } catch (e: Exception) {
                        // the next alarm tries again
                    } finally {
                        pending.finish()
                    }
                }
            }
            actionDump -> dump(ctx, intent)
            else -> super.onReceive(ctx, intent)
        }
    }

    override fun onEnabled(ctx: Context) = scheduleNext(ctx)

    override fun onDisabled(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(tickIntent(ctx))
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        renderAll(ctx)
    }

    /** Debug builds only: writes files/<name>.png. Extras: w, h (dp), t (epoch millis), name. */
    private fun dump(ctx: Context, intent: Intent) {
        if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val d = ctx.resources.displayMetrics.density
        val w = (intent.getIntExtra("w", 320) * d).toInt()
        val h = (intent.getIntExtra("h", 300) * d).toInt()
        val t = intent.getLongExtra("t", System.currentTimeMillis())
        val bmp = renderDump(ctx, w, h, t, intent).bitmap
        FileOutputStream(File(ctx.filesDir, "${intent.getStringExtra("name") ?: key.lowercase()}.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun component(ctx: Context) = ComponentName(ctx, this::class.java)

    fun hasWidgets(ctx: Context): Boolean =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(component(ctx)).isNotEmpty()

    fun scheduleNext(ctx: Context) {
        if (!hasWidgets(ctx)) return
        ctx.getSystemService(AlarmManager::class.java)
            ?.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + intervalMs, tickIntent(ctx))
    }

    private fun tickIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, requestBase, Intent(ctx, this::class.java).setAction(actionTick),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun renderAll(ctx: Context, loading: Boolean = false) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(component(ctx))
        if (ids.isEmpty()) return
        for (id in ids) {
            val (w, h) = FlightWidget.sizePx(ctx, mgr, id)
            mgr.updateAppWidget(id, build(ctx, w, h, loading))
        }
    }

    private fun build(ctx: Context, w: Int, h: Int, loading: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget)
        val view = render(ctx, w, h, System.currentTimeMillis(), loading)
        rv.setImageViewBitmap(R.id.poster, view.bitmap)
        rv.setContentDescription(R.id.poster, view.description)
        rv.setViewVisibility(R.id.tv_message, View.GONE)
        rv.setViewVisibility(R.id.zone_cycle, View.GONE)
        FlightWidget.ZONES.forEach { rv.setViewVisibility(it, View.GONE) }
        bindZones(ctx, rv, h, ctx.resources.displayMetrics.density)
        rv.setOnClickPendingIntent(R.id.zone_refresh, PendingIntent.getBroadcast(
            ctx, requestBase + 1, Intent(ctx, this::class.java).setAction(actionRefresh), IMMUTABLE,
        ))
        return rv
    }

    protected fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, requestBase + 2, Intent(ctx, MainActivity::class.java), IMMUTABLE)

    /** The phone's calendar app on today's date. */
    protected fun openCalendar(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, requestBase + 3,
        Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}")),
        IMMUTABLE,
    )

    private companion object {
        const val IMMUTABLE = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}

/** Calendar widget: date and the coming appointments from the phone's calendars and ICS links. */
class CalendarWidget : BitmapWidget() {
    override val key = "CALENDAR"
    override val requestBase = 10
    override val intervalMs = 15 * 60_000L

    override fun refreshData(ctx: Context, force: Boolean) = Calendars.refreshLinks(ctx, force)

    override fun render(ctx: Context, w: Int, h: Int, now: Long, loading: Boolean): Rendered =
        CalendarView.render(ctx, Calendars.load(ctx, now), w, h, now, loading)

    override fun bindZones(ctx: Context, rv: RemoteViews, h: Int, d: Float) {
        rv.setViewLayoutHeight(R.id.zone_sky, h.toFloat(), TypedValue.COMPLEX_UNIT_PX)
        // without access or sources the tap opens the app, where it is set up
        val data = Calendars.load(ctx, System.currentTimeMillis())
        rv.setOnClickPendingIntent(R.id.zone_sky, if (data.hasSources) openCalendar(ctx) else openApp(ctx))
    }

    override fun renderDump(ctx: Context, w: Int, h: Int, now: Long, intent: Intent): Rendered {
        val data = if (intent.getBooleanExtra("demo", false)) DemoCalendar.data(now) else Calendars.load(ctx, now)
        return CalendarView.render(ctx, data, w, h, now)
    }

    companion object {
        fun scheduleNext(ctx: Context) = CalendarWidget().scheduleNext(ctx)
        fun renderAll(ctx: Context, loading: Boolean = false) = CalendarWidget().renderAll(ctx, loading)
        fun hasWidgets(ctx: Context) = CalendarWidget().hasWidgets(ctx)
    }
}

/** The large widget: the weather on top, the appointments below. */
class DayWidget : BitmapWidget() {
    override val key = "DAY"
    override val requestBase = 20
    override val intervalMs = 15 * 60_000L

    override fun refreshData(ctx: Context, force: Boolean) {
        Forecasts.refresh(ctx, force)
        Calendars.refreshLinks(ctx, force)
    }

    override fun render(ctx: Context, w: Int, h: Int, now: Long, loading: Boolean): Rendered =
        DayView.render(ctx, Forecasts.load(Prefs(ctx)), Calendars.load(ctx, now), w, h, now, loading)

    override fun bindZones(ctx: Context, rv: RemoteViews, h: Int, d: Float) {
        // the weather part opens the app, the appointments open the calendar
        val top = DayView.topHeight(h, d)
        rv.setViewLayoutHeight(R.id.zone_sky, top, TypedValue.COMPLEX_UNIT_PX)
        rv.setOnClickPendingIntent(R.id.zone_sky, openApp(ctx))
        rv.setViewVisibility(R.id.zone_1, View.VISIBLE)
        rv.setViewLayoutHeight(R.id.zone_1, h - top, TypedValue.COMPLEX_UNIT_PX)
        val hasSources = Calendars.load(ctx, System.currentTimeMillis()).hasSources
        rv.setOnClickPendingIntent(R.id.zone_1, if (hasSources) openCalendar(ctx) else openApp(ctx))
    }

    override fun renderDump(ctx: Context, w: Int, h: Int, now: Long, intent: Intent): Rendered {
        val data = if (intent.getBooleanExtra("demo", false)) DemoCalendar.data(now) else Calendars.load(ctx, now)
        return DayView.render(ctx, Forecasts.load(Prefs(ctx)), data, w, h, now, false)
    }

    companion object {
        fun scheduleNext(ctx: Context) = DayWidget().scheduleNext(ctx)
        fun renderAll(ctx: Context, loading: Boolean = false) = DayWidget().renderAll(ctx, loading)
        fun hasWidgets(ctx: Context) = DayWidget().hasWidgets(ctx)
    }
}
