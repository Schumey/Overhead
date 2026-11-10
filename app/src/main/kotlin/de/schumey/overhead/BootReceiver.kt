package de.schumey.overhead

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarms are lost on reboot and on app updates; bring the widget refresh back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        FlightWidget.scheduleNext(ctx)
        FlightWidget.renderAll(ctx)
    }
}
