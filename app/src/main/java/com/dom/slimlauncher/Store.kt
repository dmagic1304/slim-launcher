package com.dom.slimlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AppEntry(
    val pkg: String,
    val component: ComponentName,
    val defaultLabel: String,
    val leanback: Boolean
)

/** All launchable apps, one entry per package. TV (leanback) activity wins over the phone one. */
fun queryApps(pm: PackageManager, selfPkg: String): List<AppEntry> {
    val found = LinkedHashMap<String, AppEntry>()
    val categories = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
    for (category in categories) {
        val query = Intent(Intent.ACTION_MAIN).addCategory(category)
        for (ri in pm.queryIntentActivities(query, 0)) {
            val ai = ri.activityInfo
            if (ai.packageName == selfPkg || found.containsKey(ai.packageName)) continue
            found[ai.packageName] = AppEntry(
                pkg = ai.packageName,
                component = ComponentName(ai.packageName, ai.name),
                defaultLabel = ri.loadLabel(pm).toString(),
                leanback = category == Intent.CATEGORY_LEANBACK_LAUNCHER
            )
        }
    }
    return found.values.toList()
}

/** Persists favorites, custom names and custom icons. Everything is kept in memory after first read. */
class Store(context: Context) {
    private val sp = context.getSharedPreferences("slim_launcher", Context.MODE_PRIVATE)
    private val iconDir = File(context.filesDir, "icons").apply { mkdirs() }

    private val labels = JSONObject(sp.getString("labels", "{}") ?: "{}")

    // pkg -> "file" (own PNG in iconDir) or "app:<other.package>" (borrow another app's icon)
    private val iconSrc = JSONObject(sp.getString("icons", "{}") ?: "{}")

    /**
     * Background video choice: null = never chosen (use the first video found), "" = off,
     * otherwise the file name inside the themes folder.
     */
    fun themeChoice(): String? = sp.getString("theme", null)

    fun setThemeChoice(name: String?) {
        sp.edit().putString("theme", name).apply()
    }

    fun hasFavorites() = sp.contains("favs")

    fun favorites(): List<String> {
        val arr = JSONArray(sp.getString("favs", "[]") ?: "[]")
        return List(arr.length()) { arr.getString(it) }
    }

    fun saveFavorites(pkgs: List<String>) {
        sp.edit().putString("favs", JSONArray(pkgs).toString()).apply()
    }

    fun customLabel(pkg: String): String? = labels.optString(pkg, "").ifEmpty { null }

    fun setCustomLabel(pkg: String, label: String?) {
        if (label.isNullOrBlank()) labels.remove(pkg) else labels.put(pkg, label.trim())
        sp.edit().putString("labels", labels.toString()).apply()
    }

    fun iconSource(pkg: String): String? = iconSrc.optString(pkg, "").ifEmpty { null }

    fun setIconSource(pkg: String, src: String?) {
        if (src == null) iconSrc.remove(pkg) else iconSrc.put(pkg, src)
        sp.edit().putString("icons", iconSrc.toString()).apply()
    }

    fun iconFile(pkg: String) = File(iconDir, "$pkg.png")

    fun saveIconBitmap(pkg: String, bmp: Bitmap) {
        iconFile(pkg).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        setIconSource(pkg, "file")
    }

    fun hasCustomization(pkg: String) = customLabel(pkg) != null || iconSource(pkg) != null

    fun resetCustom(pkg: String) {
        setCustomLabel(pkg, null)
        setIconSource(pkg, null)
        iconFile(pkg).delete()
    }
}

/** Resolves and caches the icon to show for an app (custom first, then the app's own). */
class IconLoader(private val context: Context, private val store: Store) {
    private val pm = context.packageManager
    private val cache = HashMap<String, Drawable>()

    fun clear() = cache.clear()

    fun invalidate(pkg: String) {
        cache.remove(pkg)
    }

    fun get(entry: AppEntry): Drawable {
        cache[entry.pkg]?.let { return it }
        val d = resolve(entry)
        cache[entry.pkg] = d
        return d
    }

    private fun resolve(entry: AppEntry): Drawable {
        val src = store.iconSource(entry.pkg)
        if (src == "file") {
            val f = store.iconFile(entry.pkg)
            if (f.exists()) {
                BitmapFactory.decodeFile(f.path)?.let { return BitmapDrawable(context.resources, it) }
            }
        } else if (src != null && src.startsWith("app:")) {
            try {
                return pm.getApplicationIcon(src.removePrefix("app:"))
            } catch (_: Exception) {
                // other app was uninstalled, fall through to the original icon
            }
        }
        return try {
            pm.getActivityIcon(entry.component)
        } catch (_: Exception) {
            pm.defaultActivityIcon
        }
    }
}
