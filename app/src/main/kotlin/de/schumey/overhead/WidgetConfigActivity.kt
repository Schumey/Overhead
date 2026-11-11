package de.schumey.overhead

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Shown when a widget is added (and when it is reconfigured): pick one of the looks, each with a live preview. */
class WidgetConfigActivity : Activity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Cancelled unless a look is picked: the launcher then drops the widget.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val prefs = Prefs(this)
        val snap = Snapshot.fromJson(prefs.snapshotJson)
        val current = prefs.styleOf(widgetId)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        root.addView(TextView(this).apply {
            setText(R.string.widget_config_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(getColor(R.color.text_secondary))
        })
        val wPx = resources.displayMetrics.widthPixels - dp(32)
        for (style in Style.entries) {
            val preview = Views.render(this, style, snap, wPx, (wPx * 0.62f).toInt(), System.currentTimeMillis())
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(getColor(R.color.card_bg))
                    if (style == current) setStroke(dp(2), getColor(R.color.accent))
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
                setOnClickListener { choose(style) }
            }
            card.addView(ImageView(this).apply {
                setImageBitmap(preview.bitmap)
                adjustViewBounds = true
                contentDescription = getString(style.titleRes)
            })
            card.addView(TextView(this).apply {
                setText(style.titleRes)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(getColor(R.color.text_primary))
                setPadding(dp(4), dp(8), 0, 0)
            })
            root.addView(card)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun choose(style: Style) {
        Prefs(this).setStyle(widgetId, style)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        FlightWidget.renderAll(this)
        FlightWidget.refreshNow(this) // also fetches what this look needs (e.g. the airport) and starts the update alarm
        finish()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
