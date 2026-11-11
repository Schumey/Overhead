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
import android.os.PowerManager
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Weather widget in the same look as the flight widget (see [WeatherView]). Refreshes every 30 minutes with the same
 * rules as the flight widget: a non-wakeup alarm, no network while the screen is off, an immediate refresh on tap.
 */
class WeatherWidget : AppWidgetProvider() {

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
                        if (interactive || userTap) Forecasts.refresh(ctx, force = userTap)
                        renderAll(ctx)
                    } catch (e: Exception) {
                        // the next alarm tries again
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

    override fun onDisabled(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(tickIntent(ctx))
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        renderAll(ctx)
    }

    /**
     * Development helper (debuggable builds only), like the flight widget's: writes the view to files/<name>.png.
     * Extras: w, h (dp), t (epoch millis), name; and code, cloud, precip to pretend other weather (WMO code, percent, mm).
     */
    private fun dump(ctx: Context, intent: Intent) {
        if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val d = ctx.resources.displayMetrics.density
        val w = (intent.getIntExtra("w", 320) * d).toInt()
        val h = (intent.getIntExtra("h", 300) * d).toInt()
        val t = intent.getLongExtra("t", System.currentTimeMillis())
        var state = Forecasts.load(Prefs(ctx))
        if (state?.forecast != null && intent.hasExtra("code")) {
            state = state.copy(forecast = state.forecast!!.copy(
                code = intent.getIntExtra("code", 3),
                cloud = intent.getIntExtra("cloud", state.forecast!!.cloud),
                precipMm = intent.getFloatExtra("precip", state.forecast!!.precipMm.toFloat()).toDouble(),
            ))
        }
        val bmp = WeatherView.render(ctx, state, w, h, t).bitmap
        FileOutputStream(File(ctx.filesDir, "${intent.getStringExtra("name") ?: "weather"}.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        const val ACTION_REFRESH = "de.schumey.overhead.WEATHER_REFRESH"
        const val ACTION_TICK = "de.schumey.overhead.WEATHER_TICK"
        const val ACTION_DUMP = "de.schumey.overhead.WEATHER_DUMP"
        private const val INTERVAL_MS = 30 * 60_000L

        fun hasWidgets(ctx: Context): Boolean =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, WeatherWidget::class.java)).isNotEmpty()

        fun scheduleNext(ctx: Context) {
            if (!hasWidgets(ctx)) return
            ctx.getSystemService(AlarmManager::class.java)
                ?.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + INTERVAL_MS, tickIntent(ctx))
        }

        private fun tickIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 3, Intent(ctx, WeatherWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun renderAll(ctx: Context, loading: Boolean = false) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WeatherWidget::class.java))
            if (ids.isEmpty()) return
            val state = Forecasts.load(Prefs(ctx))
            for (id in ids) {
                val (w, h) = FlightWidget.sizePx(ctx, mgr, id)
                mgr.updateAppWidget(id, build(ctx, state, loading, w, h))
            }
        }

        private fun build(ctx: Context, state: WeatherState?, loading: Boolean, w: Int, h: Int): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            val view = WeatherView.render(ctx, state, w, h, System.currentTimeMillis(), loading)
            rv.setImageViewBitmap(R.id.poster, view.bitmap)
            rv.setContentDescription(R.id.poster, view.description)
            rv.setViewVisibility(R.id.tv_message, View.GONE)
            val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            // The whole picture opens the app, the label at the top right refreshes; there are no rows and no second look.
            rv.setViewLayoutHeight(R.id.zone_sky, h.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            rv.setOnClickPendingIntent(R.id.zone_sky, PendingIntent.getActivity(ctx, 4, Intent(ctx, MainActivity::class.java), immutable))
            rv.setOnClickPendingIntent(R.id.zone_refresh, PendingIntent.getBroadcast(
                ctx, 5, Intent(ctx, WeatherWidget::class.java).setAction(ACTION_REFRESH), immutable,
            ))
            rv.setViewVisibility(R.id.zone_cycle, View.GONE)
            FlightWidget.ZONES.forEach { rv.setViewVisibility(it, View.GONE) }
            return rv
        }
    }
}
