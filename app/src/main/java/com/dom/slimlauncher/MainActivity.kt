package com.dom.slimlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private class Action(val label: String, val run: () -> Unit)

    private lateinit var store: Store
    private lateinit var icons: IconLoader
    private lateinit var favAdapter: AppAdapter
    private lateinit var allAdapter: AppAdapter
    private lateinit var favRv: RecyclerView
    private lateinit var allRv: RecyclerView
    private lateinit var home: View
    private lateinit var allApps: View

    private var apps: List<AppEntry> = emptyList()
    private var byPkg: Map<String, AppEntry> = emptyMap()
    private val favEntries = mutableListOf<AppEntry>()
    private var pendingIconPkg: String? = null
    private var focusedOnce = false

    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadApps()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingIconPkg = savedInstanceState?.getString("pendingIconPkg")
        store = Store(this)
        icons = IconLoader(this, store)

        val dm = resources.displayMetrics
        val spans = (((dm.widthPixels / dm.density) - 96f) / 150f).toInt().coerceIn(3, 10)

        favAdapter = AppAdapter(
            icons,
            { e -> displayLabel(e) },
            onClick = { e -> if (e == null) showAllApps() else launch(e) },
            onLongClick = { e, pos -> showMenu(e, pos, false) }
        )
        allAdapter = AppAdapter(
            icons,
            { e -> displayLabel(e) },
            onClick = { e -> if (e != null) launch(e) },
            onLongClick = { e, _ -> showMenu(e, -1, true) }
        )
        favRv = makeGrid(favAdapter, spans)
        allRv = makeGrid(allAdapter, spans)

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val clock = TextClock(this).apply {
            textSize = 28f
            setTextColor(Color.WHITE)
        }
        val date = TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            textSize = 16f
            setTextColor(Color.argb(179, 255, 255, 255))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(48), dp(24), dp(48), 0)
            addView(clock)
            addView(date)
        }
        home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(match, wrap))
            addView(favRv)
        }

        val title = TextView(this).apply {
            text = "All apps"
            textSize = 28f
            setTextColor(Color.WHITE)
            setPadding(dp(48), dp(24), dp(48), 0)
        }
        allApps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, LinearLayout.LayoutParams(match, wrap))
            addView(allRv)
            visibility = View.GONE
        }

        val root = FrameLayout(this)
        root.addView(home, FrameLayout.LayoutParams(match, match))
        root.addView(allApps, FrameLayout.LayoutParams(match, match))
        setContentView(root)

        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        loadApps()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pendingIconPkg", pendingIconPkg)
    }

    override fun onDestroy() {
        unregisterReceiver(pkgReceiver)
        executor.shutdown()
        super.onDestroy()
    }

    // Home button pressed while we are already running.
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (allApps.visibility == View.VISIBLE) showHome()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // A launcher never finishes; Back just leaves "All apps".
        if (allApps.visibility == View.VISIBLE) showHome()
    }

    // ---------------------------------------------------------------- data

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun displayLabel(e: AppEntry) = store.customLabel(e.pkg) ?: e.defaultLabel

    private fun makeGrid(a: AppAdapter, spans: Int) = RecyclerView(this).apply {
        layoutManager = GridLayoutManager(this@MainActivity, spans)
        adapter = a
        itemAnimator = null
        clipToPadding = false
        clipChildren = false
        setHasFixedSize(true)
        setPadding(dp(40), dp(16), dp(40), dp(40))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
    }

    private fun loadApps() {
        executor.execute {
            val list = queryApps(packageManager, packageName)
            ui.post { if (!isDestroyed) applyApps(list) }
        }
    }

    private fun applyApps(list: List<AppEntry>) {
        apps = list.sortedBy { displayLabel(it).lowercase() }
        byPkg = list.associateBy { it.pkg }

        // First run: seed favorites with the first TV apps found so the screen isn't empty.
        if (!store.hasFavorites()) {
            store.saveFavorites(apps.filter { it.leanback }.take(10).map { it.pkg })
        }
        favEntries.clear()
        favEntries.addAll(store.favorites().mapNotNull { byPkg[it] })

        icons.clear()
        favAdapter.submit(ArrayList<AppEntry?>(favEntries).apply { add(null) })
        allAdapter.submit(apps)

        if (!focusedOnce) {
            focusedOnce = true
            focusAfterLayout(favRv, 0)
        }
    }

    private fun saveFavorites() = store.saveFavorites(favEntries.map { it.pkg })

    // ---------------------------------------------------------------- navigation

    private fun showAllApps() {
        home.visibility = View.GONE
        allApps.visibility = View.VISIBLE
        focusAfterLayout(allRv, 0)
    }

    private fun showHome() {
        allApps.visibility = View.GONE
        home.visibility = View.VISIBLE
        focusAfterLayout(favRv, (favAdapter.itemCount - 1).coerceAtLeast(0))
    }

    /** Gives focus to the item at [pos] once the next layout pass has happened. */
    private fun focusAfterLayout(rv: RecyclerView, pos: Int) {
        rv.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                rv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                rv.layoutManager?.findViewByPosition(pos)?.requestFocus()
            }
        })
        rv.requestLayout()
    }

    private fun launch(e: AppEntry) {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .setComponent(e.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            )
        } catch (ex: Exception) {
            toast("Can't open ${displayLabel(e)}")
        }
    }

    private fun openAppSettings(e: AppEntry) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (ex: Exception) {
            toast("App settings not available for ${displayLabel(e)}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- long-press menu

    private fun showMenu(entry: AppEntry, favPosFromGrid: Int, inAllApps: Boolean) {
        val favIdx = favEntries.indexOfFirst { it.pkg == entry.pkg }
        val actions = ArrayList<Action>()
        actions.add(Action("App settings") { openAppSettings(entry) })
        actions.add(Action("Rename") { askRename(entry) })
        actions.add(Action("Change icon") { chooseIcon(entry) })
        if (favIdx >= 0) {
            if (!inAllApps && favIdx > 0) actions.add(Action("Move left") { moveFavorite(favIdx, -1) })
            if (!inAllApps && favIdx < favEntries.size - 1) {
                actions.add(Action("Move right") { moveFavorite(favIdx, 1) })
            }
            actions.add(Action("Remove from favorites") { removeFavorite(favIdx, inAllApps) })
        } else {
            actions.add(Action("Add to favorites") { addFavorite(entry) })
        }
        if (store.hasCustomization(entry.pkg)) {
            actions.add(Action("Reset name and icon") { resetCustom(entry) })
        }
        showActions(displayLabel(entry), actions)
    }

    private fun showActions(title: String, actions: List<Action>) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(actions.map { it.label }.toTypedArray()) { _, which -> actions[which].run() }
            .show()
    }

    // ---------------------------------------------------------------- favorites

    private fun addFavorite(e: AppEntry) {
        favEntries.add(e)
        favAdapter.insertAt(favEntries.size - 1, e)
        saveFavorites()
        toast("${displayLabel(e)} added to favorites")
    }

    private fun removeFavorite(idx: Int, inAllApps: Boolean) {
        favEntries.removeAt(idx)
        favAdapter.removeAt(idx)
        saveFavorites()
        if (!inAllApps) focusAfterLayout(favRv, idx)
    }

    private fun moveFavorite(idx: Int, delta: Int) {
        val to = idx + delta
        if (to < 0 || to >= favEntries.size) return
        favEntries.add(to, favEntries.removeAt(idx))
        favAdapter.move(idx, to)
        saveFavorites()
    }

    // ---------------------------------------------------------------- rename

    private fun askRename(entry: AppEntry) {
        val input = EditText(this).apply {
            setText(displayLabel(entry))
            setSelectAllOnFocus(true)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        lateinit var dialog: AlertDialog
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else false
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                store.setCustomLabel(entry.pkg, if (text.isEmpty() || text == entry.defaultLabel) null else text)
                favAdapter.refresh(entry.pkg)
                // Re-sort "All apps" so a renamed app (TizenTube -> YouTube) lands in the right place.
                apps = apps.sortedBy { displayLabel(it).lowercase() }
                allAdapter.submit(apps)
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    // ---------------------------------------------------------------- icons

    private fun chooseIcon(entry: AppEntry) {
        showActions(
            "Icon for ${displayLabel(entry)}",
            listOf(
                Action("Use another app's icon…") { pickAppIcon(entry) },
                Action("Pick an image file…") { pickImageFile(entry) },
                Action("Reset to original icon") {
                    store.setIconSource(entry.pkg, null)
                    store.iconFile(entry.pkg).delete()
                    onIconChanged(entry.pkg)
                }
            )
        )
    }

    private fun pickAppIcon(entry: AppEntry) {
        val candidates = apps.sortedBy { it.defaultLabel.lowercase() }
        AlertDialog.Builder(this)
            .setTitle("Choose an app's icon")
            .setItems(candidates.map { it.defaultLabel }.toTypedArray()) { _, i ->
                store.setIconSource(entry.pkg, "app:" + candidates[i].pkg)
                onIconChanged(entry.pkg)
            }
            .show()
    }

    private fun pickImageFile(entry: AppEntry) {
        pendingIconPkg = entry.pkg
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_ICON)
        } catch (e: ActivityNotFoundException) {
            toast("No file picker found on this device")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_ICON || resultCode != RESULT_OK) return
        val pkg = pendingIconPkg ?: return
        val uri = data?.data ?: return
        try {
            val bmp = decodeScaled(uri) ?: throw IllegalStateException("decode failed")
            store.saveIconBitmap(pkg, bmp)
            onIconChanged(pkg)
        } catch (e: Exception) {
            toast("Couldn't load that image")
        }
    }

    private fun decodeScaled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= 256) return bmp
        val scale = 256f / longest
        return Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun onIconChanged(pkg: String) {
        icons.invalidate(pkg)
        favAdapter.refresh(pkg)
        allAdapter.refresh(pkg)
    }

    private fun resetCustom(entry: AppEntry) {
        store.resetCustom(entry.pkg)
        onIconChanged(entry.pkg)
    }

    companion object {
        private const val REQ_ICON = 1
    }
}
