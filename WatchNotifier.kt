package com.example.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.util.ArabicTextNormalizer

/** The words the student wants to be alerted about (course names, doctors...). Stored on the phone. */
object WatchList {
    private const val PREFS = "bolite_watch"
    private const val KEY = "keywords"
    private const val MAX_WORDS = 30

    fun get(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun save(context: Context, words: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, words.joinToString("\n")).apply()
    }

    fun add(context: Context, word: String): List<String> {
        val clean = word.trim().replace('\n', ' ')
        val current = get(context)
        if (clean.isEmpty()) return current
        val key = ArabicTextNormalizer.normalize(clean)
        if (current.any { ArabicTextNormalizer.normalize(it) == key }) return current
        val updated = (current + clean).takeLast(MAX_WORDS)
        save(context, updated)
        return updated
    }

    fun remove(context: Context, word: String): List<String> {
        val updated = get(context).filter { it != word }
        save(context, updated)
        return updated
    }
}

/** Sends ONE notification when newly archived posts contain a watched word. */
object WatchNotifier {
    private const val CHANNEL_ID = "bolite_watch"

    fun notifyMatches(context: Context, newPostTexts: List<String>, groupName: String) {
        val words = WatchList.get(context)
        if (words.isEmpty() || newPostTexts.isEmpty()) return
        val wordTokens = words.map { it to ArabicTextNormalizer.tokens(it) }
        var matched = 0
        var firstWord: String? = null
        var sample = ""
        for (text in newPostTexts) {
            val haystack = ArabicTextNormalizer.normalize(text)
            val hit = wordTokens.firstOrNull { (_, tokens) ->
                tokens.isNotEmpty() && ArabicTextNormalizer.matchesAll(haystack, tokens)
            } ?: continue
            matched++
            if (firstWord == null) {
                firstWord = hit.first
                sample = text.trim().replace(Regex("\\s+"), " ").take(90)
            }
        }
        if (matched == 0) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "منشورات تهمني", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = if (launch != null) {
            PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else null
        val title = if (matched == 1) "منشور جديد عن «$firstWord»" else "$matched منشورات جديدة عن كلماتك"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(if (groupName.isBlank()) sample else "$groupName: $sample")
            .setStyle(NotificationCompat.BigTextStyle().bigText(sample))
            .setAutoCancel(true)
        if (pending != null) builder.setContentIntent(pending)
        manager.notify((System.currentTimeMillis() % 100000).toInt(), builder.build())
    }
}
