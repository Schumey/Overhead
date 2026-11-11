package de.schumey.overhead

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Shows the same data as the widget in full, and holds the settings. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private val chips = LinkedHashMap<Style, TextView>()
    private lateinit var poster: ImageView
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var gpsSwitch: Switch
    private lateinit var permButton: Button
    private lateinit var backgroundButton: Button
    private lateinit var fixedText: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var busy = false
    private val autoRefresh = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 30_000)
        }
    }

    private val radii = listOf(10, 25, 50, 100)
    private val intervals = listOf(1, 2, 5, 15, 30)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        updateSettingsUi()
        showSnapshot(Snapshot.fromJson(prefs.snapshotJson))
        ui.post(autoRefresh)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(autoRefresh)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateSettingsUi()
        refresh()
        FlightWidget.refreshNow(this)
    }

    // --- data ---

    private fun refresh() {
        if (busy) return
        busy = true
        status.text = getString(R.string.loading)
        thread {
            val snap = try {
                Refresher.refresh(this, force = true, onPartial = {
                    FlightWidget.renderAll(this)
                    runOnUiThread { if (!isDestroyed) showSnapshot(Snapshot.fromJson(prefs.snapshotJson)) }
                })
            } catch (e: Exception) {
                null
            }
            FlightWidget.renderAll(this)
            runOnUiThread {
                busy = false
                if (!isDestroyed) showSnapshot(snap ?: Snapshot.fromJson(prefs.snapshotJson))
            }
        }
    }

    private fun showSnapshot(s: Snapshot?) {
        list.removeAllViews()
        val wPx = resources.displayMetrics.widthPixels - dp(32)
        val style = prefs.appStyle
        val rendered = Views.render(this, style, s, wPx, (wPx * style.aspect).toInt(), System.currentTimeMillis(), loading = s == null)
        poster.setImageBitmap(rendered.bitmap)
        poster.contentDescription = rendered.description.ifEmpty { getString(R.string.widget_title) }
        if (s == null) {
            status.text = getString(R.string.loading)
            return
        }
        status.text = when {
            s.error == Snapshot.ERR_LOCATION -> getString(R.string.err_location_app)
            s.error == Snapshot.ERR_NETWORK -> getString(R.string.err_network) + " · " + getString(R.string.updated, Fmt.time(s.time))
            s.flights.isEmpty() -> getString(R.string.empty, prefs.radiusKm)
            else -> getString(R.string.updated, Fmt.time(s.time)) + " · " + resources.getQuantityString(R.plurals.flights_count, s.flights.size, s.flights.size)
        }
        val now = System.currentTimeMillis()
        for (f in s.flights) list.addView(card(f, Fmt.text(this, f, now)))
    }

    // --- UI ---

    private fun card(f: Flight, t: Fmt.FlightText): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(getColor(R.color.card_bg))
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://globe.adsbexchange.com/?icao=${f.hex.lowercase()}")))
            }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(text(t.title, 16f, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        head.addView(text(t.distance, 14f, color = R.color.accent, bold = true))
        box.addView(head)
        t.route?.let { box.addView(text(it, 15f)) }
        t.eta?.let { box.addView(text(it, 14f, color = R.color.accent)) }
        if (t.details.isNotEmpty()) box.addView(text(t.details, 13f, color = R.color.text_secondary))
        return box
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                Style.entries.forEach { st ->
                    val chip = TextView(this@MainActivity).apply {
                        setText(st.titleRes)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setPadding(dp(14), dp(7), dp(14), dp(7))
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
                        setOnClickListener {
                            prefs.appStyle = st
                            updateChips()
                            val snap = Snapshot.fromJson(prefs.snapshotJson)
                            showSnapshot(snap)
                            // The runway view needs airport data that the other looks do not fetch.
                            if (st == Style.RUNWAY && snap?.airport == null) refresh()
                        }
                    }
                    chips[st] = chip
                    addView(chip)
                }
            })
        })
        updateChips()
        poster = ImageView(this).apply {
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(8) }
        }
        root.addView(poster)
        status = text("", 14f, color = R.color.text_secondary)
        root.addView(status)
        root.addView(Button(this).apply {
            setText(R.string.refresh)
            setOnClickListener { refresh() }
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        root.addView(text(getString(R.string.settings), 18f, bold = true).apply { setPadding(0, dp(28), 0, dp(4)) })

        gpsSwitch = Switch(this).apply {
            setText(R.string.use_gps)
            setOnCheckedChangeListener { _, checked ->
                if (checked != prefs.useGps) {
                    prefs.useGps = checked
                    changed()
                }
            }
        }
        root.addView(gpsSwitch)
        permButton = Button(this).apply {
            setText(R.string.grant_location)
            setOnClickListener {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            }
        }
        root.addView(permButton)
        backgroundButton = Button(this).apply {
            setText(R.string.grant_background)
            // On Android 11+ this opens the system settings page where "Allow all the time" is chosen.
            setOnClickListener { requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2) }
        }
        root.addView(backgroundButton)
        root.addView(text(getString(R.string.background_hint), 12f, color = R.color.text_secondary))

        fixedText = text("", 14f).apply { setPadding(0, dp(16), 0, 0) }
        root.addView(fixedText)
        root.addView(Button(this).apply {
            setText(R.string.save_fixed)
            setOnClickListener { saveFixedPlace() }
        })

        root.addView(text(getString(R.string.radius), 14f).apply { setPadding(0, dp(16), 0, 0) })
        root.addView(spinner(radii.map { "$it km" }, radii.indexOf(prefs.radiusKm).coerceAtLeast(0)) { i ->
            if (radii[i] != prefs.radiusKm) {
                prefs.radiusKm = radii[i]
                changed()
            }
        })

        root.addView(text(getString(R.string.interval), 14f).apply { setPadding(0, dp(16), 0, 0) })
        root.addView(spinner(intervals.map { "$it min" }, intervals.indexOf(prefs.intervalMin).coerceAtLeast(0)) { i ->
            if (intervals[i] != prefs.intervalMin) {
                prefs.intervalMin = intervals[i]
                FlightWidget.scheduleNext(this)
            }
        })

        root.addView(text(getString(R.string.sources), 12f, color = R.color.text_secondary).apply { setPadding(0, dp(28), 0, 0) })
        return ScrollView(this).apply { addView(root) }
    }

    private fun updateChips() {
        for ((st, chip) in chips) {
            val selected = st == prefs.appStyle
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                if (selected) setColor(getColor(R.color.text_primary)) else setStroke(dp(1), getColor(R.color.text_secondary))
            }
            chip.setTextColor(getColor(if (selected) R.color.card_bg else R.color.text_secondary))
        }
    }

    private fun updateSettingsUi() {
        gpsSwitch.isChecked = prefs.useGps
        val hasLoc = LocationHelper.hasLocation(this)
        permButton.visibility = if (hasLoc) View.GONE else View.VISIBLE
        // Only relevant once the widget exists: it refreshes from a background alarm.
        backgroundButton.visibility =
            if (hasLoc && !LocationHelper.hasBackground(this) && FlightWidget.hasWidgets(this)) View.VISIBLE else View.GONE
        val lat = prefs.fixedLat
        val lon = prefs.fixedLon
        fixedText.text = if (lat != null && lon != null) getString(R.string.fixed_at, lat, lon) else getString(R.string.fixed_none)
    }

    private fun saveFixedPlace() {
        if (!LocationHelper.hasLocation(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            return
        }
        thread {
            val loc = LocationHelper.current(this)
            runOnUiThread {
                if (loc == null) {
                    Toast.makeText(this, R.string.err_location_app, Toast.LENGTH_LONG).show()
                } else {
                    prefs.saveFixed(loc.latitude, loc.longitude)
                    updateSettingsUi()
                    changed()
                }
            }
        }
    }

    private fun changed() {
        refresh()
        FlightWidget.refreshNow(this)
    }

    private fun spinner(labels: List<String>, selected: Int, onPick: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
        setSelection(selected)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun text(s: String, sp: Float, bold: Boolean = false, color: Int = R.color.text_primary) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(getColor(color))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
