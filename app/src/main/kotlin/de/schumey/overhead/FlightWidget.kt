package de.schumey.overhead

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
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
 * Home-screen widget: the poster (see [Poster]) with the aircraft closest to the observer.
 *
 * Refresh: a self-rescheduling non-wakeup alarm (interval from the settings). It never wakes the phone, and it
 * skips the network while the screen is off; when the screen comes on, an overdue alarm fires right away.
 */
class FlightWidget : AppWidgetProvider() {

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE, ACTION_REFRESH, ACTION_TICK -> {
                val userTap = intent.action == ACTION_REFRESH
                scheduleNext(ctx)
                val pending = goAsync()
                thread {
                    try {
                        val interactive = ctx.getSystemService(PowerManager::class.java)?.isInteractive != false
                        if (userTap) renderAll(ctx, loading = true)
                        if (interactive || userTap) Refresher.refresh(ctx, force = userTap)
                        renderAll(ctx)
                    } catch (e: Exception) {
                        // A failed refresh must not crash the receiver; the next alarm tries again.
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_DUMP -> dump(ctx, intent)
            else -> super.onReceive(ctx, intent)
        }
    }

    override fun onEnabled(ctx: Context) = scheduleNext(ctx)

    override fun onDisabled(ctx: Context) = cancelAlarm(ctx)

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        renderAll(ctx)
    }

    /**
     * Development helper (debuggable builds only): writes the poster for the stored snapshot to files/<name>.png, so the
     * design can be checked without a visible screen. Extras: w, h (dp), t (epoch millis, to try other times of day), name.
     */
    private fun dump(ctx: Context, intent: Intent) {
        if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val d = ctx.resources.displayMetrics.density
        val w = (intent.getIntExtra("w", 320) * d).toInt()
        val h = (intent.getIntExtra("h", 200) * d).toInt()
        val t = intent.getLongExtra("t", System.currentTimeMillis())
        val name = intent.getStringExtra("name") ?: "poster"
        val snap = Snapshot.fromJson(Prefs(ctx).snapshotJson)
        fun save(bmp: Bitmap, file: String) =
            FileOutputStream(File(ctx.filesDir, file)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        save(Poster.render(ctx, snap, w, h, t).bitmap, "$name.png")

        // Also assemble the real RemoteViews (poster + tap zones) the way the launcher does, to prove it inflates and applies.
        val view = build(ctx, snap, false, w, h).apply(ctx, android.widget.FrameLayout(ctx))
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val composed = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(composed))
        save(composed, "${name}_widget.png")
    }

    companion object {
        const val ACTION_REFRESH = "de.schumey.overhead.REFRESH"
        const val ACTION_TICK = "de.schumey.overhead.TICK"
        const val ACTION_DUMP = "de.schumey.overhead.DUMP"

        /** Asks the widget to refresh now (used by the app after a settings change). */
        fun refreshNow(ctx: Context) {
            ctx.sendBroadcast(Intent(ctx, FlightWidget::class.java).setAction(ACTION_REFRESH))
        }

        fun hasWidgets(ctx: Context): Boolean {
            val mgr = AppWidgetManager.getInstance(ctx)
            return mgr.getAppWidgetIds(ComponentName(ctx, FlightWidget::class.java)).isNotEmpty()
        }

        fun scheduleNext(ctx: Context) {
            if (!hasWidgets(ctx)) return
            val alarms = ctx.getSystemService(AlarmManager::class.java) ?: return
            val delay = Prefs(ctx).intervalMin * 60_000L
            // Non-wakeup + allow-while-idle: no extra permission, and no wake-ups while the phone sleeps.
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delay, tickIntent(ctx))
        }

        fun cancelAlarm(ctx: Context) {
            ctx.getSystemService(AlarmManager::class.java)?.cancel(tickIntent(ctx))
        }

        private fun tickIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, FlightWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun renderAll(ctx: Context, loading: Boolean = false) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, FlightWidget::class.java))
            if (ids.isEmpty()) return
            val snapshot = Snapshot.fromJson(Prefs(ctx).snapshotJson)
            for (id in ids) {
                val (w, h) = sizePx(ctx, mgr, id)
                mgr.updateAppWidget(id, build(ctx, snapshot, loading, w, h))
            }
        }

        /** Size of this widget instance in pixels, from what the launcher reports (it differs by orientation). */
        private fun sizePx(ctx: Context, mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
            val o = mgr.getAppWidgetOptions(id)
            val portrait = ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            var wDp = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            var hDp = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            if (wDp <= 0 || hDp <= 0) { wDp = 320; hDp = 200 }
            val d = ctx.resources.displayMetrics.density
            return (wDp * d).toInt() to (hDp * d).toInt()
        }

        private fun build(ctx: Context, snap: Snapshot?, loading: Boolean, w: Int, h: Int): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            val poster = Poster.render(ctx, snap, w, h, System.currentTimeMillis(), loading)
            rv.setImageViewBitmap(R.id.poster, poster.bitmap)
            rv.setContentDescription(R.id.poster, poster.description.ifEmpty { ctx.getString(R.string.widget_title) })
            rv.setViewVisibility(R.id.tv_message, View.GONE)

            val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val openApp = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), immutable)
            rv.setOnClickPendingIntent(R.id.zone_refresh, PendingIntent.getBroadcast(
                ctx, 2, Intent(ctx, FlightWidget::class.java).setAction(ACTION_REFRESH), immutable,
            ))

            // Tap zones: the sky opens the app, each text row opens that aircraft on a live map.
            val px = TypedValue.COMPLEX_UNIT_PX
            rv.setViewLayoutHeight(R.id.zone_sky, (poster.rows.firstOrNull()?.get(0) ?: h).toFloat(), px)
            rv.setOnClickPendingIntent(R.id.zone_sky, openApp)
            val zones = intArrayOf(R.id.zone_1, R.id.zone_2, R.id.zone_3)
            zones.forEachIndexed { i, zone ->
                val row = poster.rows.getOrNull(i)
                val flight = snap?.flights?.getOrNull(i)
                if (row == null || flight == null) {
                    rv.setViewVisibility(zone, View.GONE)
                } else {
                    rv.setViewVisibility(zone, View.VISIBLE)
                    rv.setViewLayoutHeight(zone, (row[1] - row[0]).toFloat(), px)
                    rv.setOnClickPendingIntent(zone, PendingIntent.getActivity(
                        ctx, 100 + i,
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://globe.adsbexchange.com/?icao=${flight.hex.lowercase()}")),
                        immutable,
                    ))
                }
            }
            return rv
        }
    }
}
