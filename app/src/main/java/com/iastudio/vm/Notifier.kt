package com.iastudio.vm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import java.util.concurrent.atomic.AtomicInteger

/**
 * Notifications de Studo : chaque type a son émoticône (modifiable dans Réglages), affichée en grande icône
 * et au début du titre. Android impose une petite icône monochrome dans la barre d'état ; l'émoticône
 * personnalisée apparaît donc dans le volet des notifications.
 */
object Notifier {
    const val CH_WORK = "studo_work"
    const val CH_DONE = "studo_done"

    /** Types : download, chat, image, project, phone, done, error. */
    val defaults = linkedMapOf(
        "download" to "📥", "chat" to "💬", "image" to "🎨", "project" to "💻",
        "phone" to "📱", "done" to "✅", "error" to "⚠️",
    )
    val labels = mapOf(
        "download" to "Téléchargement", "chat" to "Discussion", "image" to "Image", "project" to "Logiciel",
        "phone" to "Téléphone", "done" to "Terminé", "error" to "Erreur",
    )

    /** Vrai quand l'écran de Studo est affiché (pas de notification « terminé » dans ce cas). */
    @Volatile var foreground = false
    private val nextId = AtomicInteger(1000)

    fun emoji(ctx: Context, type: String): String {
        val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val v = p.getString("emo_$type", null)?.trim().orEmpty()
        return v.ifEmpty { defaults[type] ?: "🤖" }
    }

    fun setEmoji(ctx: Context, type: String, value: String) {
        ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString("emo_$type", value.trim().take(8)).apply()
    }

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_WORK, "Travail en arrière-plan", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Travaux terminés", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Dessine l'émoticône dans une pastille ronde pour l'utiliser comme grande icône. */
    fun emojiBitmap(emoji: String): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8E4F5.toInt() }
        c.drawCircle(size / 2f, size / 2f, size / 2f, bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size * 0.55f; textAlign = Paint.Align.CENTER }
        val y = size / 2f - (p.descent() + p.ascent()) / 2f
        c.drawText(emoji, size / 2f, y, p)
        return bmp
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun build(ctx: Context, channel: String, type: String, title: String, text: String): Notification.Builder {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel) else Notification.Builder(ctx)
        val e = emoji(ctx, type)
        return b.setSmallIcon(if (channel == CH_WORK) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setLargeIcon(emojiBitmap(e))
            .setContentTitle("$e $title")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
    }

    /** Notification « terminé » : seulement si Studo n'est pas à l'écran. */
    fun done(ctx: Context, type: String, title: String, text: String, force: Boolean = false) {
        if (foreground && !force) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.areNotificationsEnabled()) return
            channels(ctx)
            nm.notify(nextId.incrementAndGet(), build(ctx, CH_DONE, type, title, text).setAutoCancel(true).build())
        } catch (e: Exception) {
            // notification impossible : sans importance
        }
    }
}
