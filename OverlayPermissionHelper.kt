package com.example.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.example.FloatingBubbleService

object OverlayPermissionHelper {

    fun hasPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun requestPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            }
        }
    }

    /**
     * Checks Settings.canDrawOverlays(context).
     * If false, launches ACTION_MANAGE_OVERLAY_PERMISSION.
     * If true, starts the overlay service immediately.
     */
    fun startServiceIfPermitted(context: Context): Boolean {
        return if (hasPermission(context)) {
            FloatingBubbleService.start(context)
            true
        } else {
            requestPermission(context)
            false
        }
    }
}
