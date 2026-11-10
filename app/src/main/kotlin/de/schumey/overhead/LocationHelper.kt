package de.schumey.overhead

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

object LocationHelper {
    fun hasLocation(ctx: Context) =
        granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBackground(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /**
     * Current position: the freshest last-known fix if it is recent, otherwise a single fresh request.
     * Blocks for up to [timeoutMs], so call it from a background thread only.
     */
    @SuppressLint("MissingPermission")
    fun current(ctx: Context, maxAgeMs: Long = 90_000, timeoutMs: Long = 6_000): Location? {
        if (!hasLocation(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return try {
            val enabled = lm.getProviders(true)
            val now = System.currentTimeMillis()
            val last = enabled.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            if (last != null && now - last.time <= maxAgeMs) return last

            // "fused" is the platform's own fused provider (API 31+ constant, but the name works from 30).
            val provider = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .firstOrNull { it in enabled }
            var fresh: Location? = null
            if (provider != null) {
                val latch = CountDownLatch(1)
                val cancel = CancellationSignal()
                lm.getCurrentLocation(provider, cancel, Executor { Thread(it).start() }) {
                    fresh = it
                    latch.countDown()
                }
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                cancel.cancel()
            }
            // A fix up to an hour old is still better than none for "what is above me".
            fresh ?: last?.takeIf { now - it.time <= 3_600_000 }
        } catch (e: SecurityException) {
            null
        }
    }
}
