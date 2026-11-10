package com.iastudio.vm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Service au premier plan : tant qu'un téléchargement, une génération ou une discussion est en cours,
 * Android garde Studo (et le moteur d'IA) en vie même si tu quittes l'écran de l'appli.
 * Une notification permanente l'indique, comme n'importe quel téléchargement.
 */
class KeepAliveService : Service() {
    private var cpu: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text") ?: "Studo travaille en arrière-plan"
        val type = intent?.getStringExtra("type") ?: "chat"
        Notifier.channels(this)
        val n = Notifier.build(this, Notifier.CH_WORK, type, "Studo", text).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, n)
        }
        if (cpu == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Studo:travail").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Studo:wifi").apply { setReferenceCounted(false); acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { cpu?.takeIf { it.isHeld }?.release() } catch (e: Exception) { }
        try { wifi?.takeIf { it.isHeld }?.release() } catch (e: Exception) { }
        cpu = null; wifi = null
        super.onDestroy()
    }

    companion object {
        private const val ID = 4242

        fun update(ctx: Context, on: Boolean, text: String, type: String = "chat") {
            try {
                val i = Intent(ctx, KeepAliveService::class.java).putExtra("text", text).putExtra("type", type)
                if (on) {
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                } else {
                    ctx.stopService(i)
                }
            } catch (e: Exception) {
                // démarrage refusé par Android (appli déjà en arrière-plan) : on continue sans service
            }
        }
    }
}
