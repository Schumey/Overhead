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
 * Home-screen widget. What it looks like (poster, radar, board, runway) is chosen per widget instance, see [Style].
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
                        // onPartial: the first save (flights) is shown while a slow extra (airport lookup) is still running.
                        if (interactive || userTap) Refresher.refresh(ctx, force = userTap, onPartial = { renderAll(ctx) })
                        renderAll(ctx)
                    } catch (e: Exception) {
                        // A failed refresh must not crash the receiver; the next alarm tries again.
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_CYCLE -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
                val prefs = Prefs(ctx)
                prefs.setStyle(id, prefs.styleOf(id).next())
                renderAll(ctx)
                // The runway view needs airport data the other views do not fetch: get it now rather than at the next alarm.
                val pending = goAsync()
                thread {
                    try {
                        if (prefs.styleOf(id) == Style.RUNWAY && Snapshot.fromJson(prefs.snapshotJson)?.airport == null) {
                            Refresher.refresh(ctx, force = true, onPartial = { renderAll(ctx) })
                            renderAll(ctx)
                        }
                    } catch (e: Exception) {
                        // shown on the next refresh
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

    override fun onDeleted(ctx: Context, appWidgetIds: IntArray) {
        val prefs = Prefs(ctx)
        appWidgetIds.forEach { prefs.forgetWidget(it) }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        renderAll(ctx)
    }

    /**
     * Development helper (debuggable builds only): writes the view for the stored snapshot to files/<name>.png, so the
     * design can be checked without a visible screen. Extras: w, h (dp), t (epoch millis, to try other times of day),
     * name, style (poster, radar, board, runway; also becomes the app's current look so a refresh fetches what it needs).
     */
    private fun dump(ctx: Context, intent: Intent) {
        if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val d = ctx.resources.displayMetrics.density
        val w = (intent.getIntExtra("w", 320) * d).toInt()
        val h = (intent.getIntExtra("h", 200) * d).toInt()
        val t = intent.getLongExtra("t", System.currentTimeMillis())
        val name = intent.getStringExtra("name") ?: "poster"
        val style = Style.of(intent.getStringExtra("style"))
        Prefs(ctx).appStyle = style
        val snap = Snapshot.fromJson(Prefs(ctx).snapshotJson)
        fun save(bmp: Bitmap, file: String) =
            FileOutputStream(File(ctx.filesDir, file)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        save(Views.render(ctx, style, snap, w, h, t).bitmap, "$name.png")

        // Also assemble the real RemoteViews (picture + tap zones) the way the launcher does, to prove it inflates and applies.
        val view = build(ctx, AppWidgetManager.INVALID_APPWIDGET_ID, style, snap, false, w, h).apply(ctx, android.widget.FrameLayout(ctx))
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val composed = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(composed))
        save(composed, "${name}_widget.png")
    }

    companion object {
        const val ACTION_REFRESH = "de.schumey.overhead.REFRESH"
        const val ACTION_TICK = "de.schumey.overhead.TICK"
        const val ACTION_CYCLE = "de.schumey.overhead.CYCLE"
        const val ACTION_DUMP = "de.schumey.overhead.DUMP"

        internal val ZONES = intArrayOf(
            R.id.zone_1, R.id.zone_2, R.id.zone_3, R.id.zone_4, R.id.zone_5,
            R.id.zone_6, R.id.zone_7, R.id.zone_8, R.id.zone_9, R.id.zone_10,
        )

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
            val prefs = Prefs(ctx)
            val snapshot = Snapshot.fromJson(prefs.snapshotJson)
            for (id in ids) {
                val (w, h) = sizePx(ctx, mgr, id)
                mgr.updateAppWidget(id, build(ctx, id, prefs.styleOf(id), snapshot, loading, w, h))
            }
        }

        /** Size of this widget instance in pixels, from what the launcher reports (it differs by orientation). */
        internal fun sizePx(ctx: Context, mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
            val o = mgr.getAppWidgetOptions(id)
            val portrait = ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            var wDp = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            var hDp = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            if (wDp <= 0 || hDp <= 0) { wDp = 320; hDp = 200 }
            val d = ctx.resources.displayMetrics.density
            return (wDp * d).toInt() to (hDp * d).toInt()
        }

        private fun build(ctx: Context, id: Int, style: Style, snap: Snapshot?, loading: Boolean, w: Int, h: Int): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            val view = Views.render(ctx, style, snap, w, h, System.currentTimeMillis(), loading)
            rv.setImageViewBitmap(R.id.poster, view.bitmap)
            rv.setContentDescription(R.id.poster, view.description.ifEmpty { ctx.getString(R.string.widget_title) })
            rv.setViewVisibility(R.id.tv_message, View.GONE)

            val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val openApp = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), immutable)
            rv.setOnClickPendingIntent(R.id.zone_refresh, PendingIntent.getBroadcast(
                ctx, 2, Intent(ctx, FlightWidget::class.java).setAction(ACTION_REFRESH), immutable,
            ))
            // Top left: switch to the next look. One PendingIntent per widget, so several widgets keep their own choice.
            rv.setOnClickPendingIntent(R.id.zone_cycle, PendingIntent.getBroadcast(
                ctx, 1000 + id,
                Intent(ctx, FlightWidget::class.java).setAction(ACTION_CYCLE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                immutable,
            ))

            // Tap zones: the picture opens the app, each row opens that aircraft on a live map.
            val px = TypedValue.COMPLEX_UNIT_PX
            rv.setViewLayoutHeight(R.id.zone_sky, (view.rows.firstOrNull()?.get(0) ?: h).toFloat(), px)
            rv.setOnClickPendingIntent(R.id.zone_sky, openApp)
            ZONES.forEachIndexed { i, zone ->
                val row = view.rows.getOrNull(i)
                val hex = view.rowHex.getOrNull(i)
                if (row == null || hex == null) {
                    rv.setViewVisibility(zone, View.GONE)
                } else {
                    rv.setViewVisibility(zone, View.VISIBLE)
                    // Rows are stacked back to back; the gap between two rows belongs to the lower one so nothing is dead.
                    val top = if (i == 0) row[0] else view.rows[i - 1][1]
                    rv.setViewLayoutHeight(zone, (row[1] - top).toFloat(), px)
                    rv.setOnClickPendingIntent(zone, PendingIntent.getActivity(
                        ctx, 100 + i,
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://globe.adsbexchange.com/?icao=${hex.lowercase()}")),
                        immutable,
                    ))
                }
            }
            return rv
        }
    }
}
