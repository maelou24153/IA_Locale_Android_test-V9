package com.iastudio.vm

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lien avec Termux (terminal Linux complet sur Android) via son interface officielle RUN_COMMAND.
 * Il faut : Termux installé (F-Droid ou GitHub), « allow-external-apps=true » dans ~/.termux/termux.properties,
 * et l'autorisation « Exécuter des commandes Termux » accordée à Studo.
 */
object TermuxBridge {
    const val PKG = "com.termux"
    const val RESULT_ACTION = "com.iastudio.vm.TERMUX_RESULT"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val SETUP_COMMAND = "mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties"

    private val waiting = ConcurrentHashMap<Int, CompletableFuture<String>>()
    private val counter = AtomicInteger(100)

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun permissionGranted(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Lance une commande bash dans Termux et attend son résultat (sortie, erreurs, code). */
    fun run(ctx: Context, command: String, timeoutSec: Long = 90): String {
        if (!installed(ctx)) throw RuntimeException("Termux n'est pas installé (prends la version F-Droid ou GitHub, pas celle du Play Store)")
        if (!permissionGranted(ctx)) throw RuntimeException("Studo n'a pas l'autorisation Termux : Réglages › Termux › Autoriser")
        val id = counter.incrementAndGet()
        val fut = CompletableFuture<String>()
        waiting[id] = fut
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(ctx, id, Intent(RESULT_ACTION).setPackage(ctx.packageName).putExtra("id", id), flags)
        val i = Intent("com.termux.RUN_COMMAND")
            .setClassName(PKG, "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) {
            waiting.remove(id)
            throw RuntimeException(
                "Termux a refusé la commande. Vérifie « allow-external-apps=true » dans ~/.termux/termux.properties " +
                    "puis ferme et rouvre Termux. (${e.message})",
            )
        }
        return try {
            fut.get(timeoutSec, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            waiting.remove(id)
            "Pas de réponse après ${timeoutSec} s (la commande tourne peut-être encore dans Termux)"
        }
    }

    fun deliver(intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val fut = waiting.remove(id) ?: return
        val b = intent.getBundleExtra("result")
        if (b == null) { fut.complete("Termux n'a renvoyé aucun résultat"); return }
        val out = b.getString("stdout").orEmpty().trim()
        val err = b.getString("stderr").orEmpty().trim()
        val code = b.getInt("exitCode", -1)
        val msg = b.getString("errmsg").orEmpty()
        val sb = StringBuilder("Code $code")
        if (msg.isNotBlank()) sb.append(" · ").append(msg)
        if (out.isNotEmpty()) sb.append("\n").append(out.take(3000))
        if (err.isNotEmpty()) sb.append("\n[erreurs] ").append(err.take(1500))
        fut.complete(sb.toString())
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TermuxBridge.deliver(intent)
    }
}
