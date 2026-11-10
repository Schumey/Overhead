package de.schumey.overhead

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import kotlin.concurrent.thread

/**
 * Home-screen widget with the aircraft closest to the observer.
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
            else -> super.onReceive(ctx, intent)
        }
    }

    override fun onEnabled(ctx: Context) = scheduleNext(ctx)

    override fun onDisabled(ctx: Context) = cancelAlarm(ctx)

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        renderAll(ctx)
    }

    companion object {
        const val ACTION_REFRESH = "de.schumey.overhead.REFRESH"
        const val ACTION_TICK = "de.schumey.overhead.TICK"
        private const val ROW_HEIGHT_DP = 68
        private const val CHROME_HEIGHT_DP = 48

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
            for (id in ids) mgr.updateAppWidget(id, build(ctx, mgr, id, snapshot, loading))
        }

        private fun rowsFor(ctx: Context, mgr: AppWidgetManager, id: Int): Int {
            val o = mgr.getAppWidgetOptions(id)
            val portrait = ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            val h = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            return if (h <= 0) 2 else ((h - CHROME_HEIGHT_DP) / ROW_HEIGHT_DP).coerceIn(1, 4)
        }

        private fun build(ctx: Context, mgr: AppWidgetManager, id: Int, snap: Snapshot?, loading: Boolean): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            val prefs = Prefs(ctx)
            val now = System.currentTimeMillis()
            val openApp = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(R.id.tv_title, openApp)
            rv.setOnClickPendingIntent(R.id.btn_refresh, PendingIntent.getBroadcast(
                ctx, 2, Intent(ctx, FlightWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))

            rv.setTextViewText(R.id.tv_status, when {
                loading -> ctx.getString(R.string.loading_short)
                snap != null && snap.error != Snapshot.ERR_LOCATION -> ctx.getString(R.string.updated, Fmt.time(snap.time))
                else -> ""
            })
            rv.removeAllViews(R.id.rows)

            val message: String? = when {
                snap == null -> ctx.getString(R.string.loading)
                snap.error == Snapshot.ERR_LOCATION -> ctx.getString(R.string.err_location)
                snap.flights.isEmpty() && snap.error == Snapshot.ERR_NETWORK -> ctx.getString(R.string.err_network)
                snap.flights.isEmpty() -> ctx.getString(R.string.empty, prefs.radiusKm)
                else -> null
            }
            if (message != null) {
                rv.setViewVisibility(R.id.tv_message, View.VISIBLE)
                rv.setTextViewText(R.id.tv_message, message)
                rv.setOnClickPendingIntent(R.id.tv_message, openApp)
                return rv
            }
            rv.setViewVisibility(R.id.tv_message, View.GONE)

            snap!!.flights.take(rowsFor(ctx, mgr, id)).forEachIndexed { i, f ->
                val t = Fmt.text(ctx, f, now)
                val row = RemoteViews(ctx.packageName, R.layout.widget_row)
                row.setTextViewText(R.id.row_title, t.title)
                row.setTextViewText(R.id.row_distance, t.distance)
                setOrHide(row, R.id.row_route, t.route)
                setOrHide(row, R.id.row_eta, t.eta)
                setOrHide(row, R.id.row_details, t.details.ifEmpty { null })
                // Tap a row: open that aircraft on a live map.
                row.setOnClickPendingIntent(R.id.row_root, PendingIntent.getActivity(
                    ctx, 100 + i,
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://globe.adsbexchange.com/?icao=${f.hex.lowercase()}")),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ))
                rv.addView(R.id.rows, row)
            }
            return rv
        }

        private fun setOrHide(rv: RemoteViews, viewId: Int, text: String?) {
            if (text == null) {
                rv.setViewVisibility(viewId, View.GONE)
            } else {
                rv.setViewVisibility(viewId, View.VISIBLE)
                rv.setTextViewText(viewId, text)
            }
        }
    }
}
