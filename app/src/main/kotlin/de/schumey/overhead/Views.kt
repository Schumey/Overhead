package de.schumey.overhead

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap

/** The looks the widget and the app can show. */
enum class Style(val key: String, val titleRes: Int, /** Height of the app preview relative to its width. */ val aspect: Float) {
    POSTER("poster", R.string.style_poster, 0.85f),
    RADAR("radar", R.string.style_radar, 0.80f),
    BOARD("board", R.string.style_board, 0.85f),
    RUNWAY("runway", R.string.style_runway, 0.80f);

    fun next(): Style = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(key: String?): Style = entries.firstOrNull { it.key == key } ?: POSTER
    }
}

/** A drawn view plus where the tappable rows are. */
class Rendered(
    val bitmap: Bitmap,
    /** Where the tap zone for the rows starts (everything above it opens the app). */
    val skyBottomPx: Int,
    /** Top and bottom pixel of every tappable row. */
    val rows: List<IntArray>,
    /** Hex code of the aircraft each row stands for (same order as [rows]); tapping a row opens it on a map. */
    val rowHex: List<String>,
    val description: String,
)

object Views {
    fun render(ctx: Context, style: Style, snap: Snapshot?, wPx: Int, hPx: Int, now: Long, loading: Boolean = false): Rendered =
        when (style) {
            Style.POSTER -> Poster.render(ctx, snap, wPx, hPx, now, loading)
            Style.RADAR -> RadarView.render(ctx, snap, wPx, hPx, now, loading)
            Style.BOARD -> BoardView.render(ctx, snap, wPx, hPx, now, loading)
            Style.RUNWAY -> RunwayView.render(ctx, snap, wPx, hPx, now, loading)
        }

    /** Airport data costs two extra requests, so it is only fetched while some view actually shows it. */
    fun needsAirport(ctx: Context): Boolean {
        val prefs = Prefs(ctx)
        if (prefs.appStyle == Style.RUNWAY) return true
        val mgr = AppWidgetManager.getInstance(ctx)
        return mgr.getAppWidgetIds(ComponentName(ctx, FlightWidget::class.java)).any { prefs.styleOf(it) == Style.RUNWAY }
    }
}
