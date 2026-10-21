package com.dom.slimlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Brings Slim Launcher back whenever the stock launcher (or any other Home app) comes to the
 * front: after a reboot, after pressing Home inside an app, or after backing out of an app.
 *
 * It only looks at WHICH app's window just appeared. It reads no screen content.
 */
class HomeGuardService : AccessibilityService() {

    private var homePackages: Set<String> = emptySet()
    private var lastLaunch = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshHomePackages()
    }

    private fun refreshHomePackages() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        homePackages = packageManager.queryIntentActivities(home, 0)
            .map { it.activityInfo.packageName }
            .filter { it != packageName }
            .toSet()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        if (homePackages.isEmpty()) refreshHomePackages()
        if (pkg !in homePackages) return

        // Avoid launching twice for the burst of events one transition produces.
        val now = SystemClock.uptimeMillis()
        if (now - lastLaunch < 800) return
        lastLaunch = now

        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            )
        } catch (_: Exception) {
            // Nothing sensible to do; the next Home press will try again.
        }
    }

    override fun onInterrupt() {}
}
