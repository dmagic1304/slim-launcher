package com.dom.slimlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
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
import android.view.KeyEvent
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
import java.io.File
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
    private var spans = 4

    /** Index in favEntries of the favorite being moved, or -1 when not in move mode. */
    private var movingIndex = -1
    private var focusedOnce = false

    private lateinit var background: ThemeBackground
    private var themeKey: String? = null
    private val idleRunnable = Runnable { background.pauseIdle() }

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
        spans = (((dm.widthPixels / dm.density) - 96f) / 150f).toInt().coerceIn(3, 10)

        favAdapter = AppAdapter(
            icons,
            { e -> displayLabel(e) },
            onClick = { e -> if (e == null) showAllApps() else launch(e) },
            onLongClick = { e, pos -> showMenu(e, pos, false) },
            onLongClickTile = { showLauncherMenu() }
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

        background = ThemeBackground(this).apply {
            onError = {
                toast("Couldn't play the background video")
                setVideo(null)
            }
        }

        val root = FrameLayout(this)
        root.addView(background, FrameLayout.LayoutParams(match, match))
        root.addView(home, FrameLayout.LayoutParams(match, match))
        root.addView(allApps, FrameLayout.LayoutParams(match, match))
        setContentView(root)
        applyTheme()

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
        ui.removeCallbacks(idleRunnable)
        background.stop()
        unregisterReceiver(pkgReceiver)
        executor.shutdown()
        super.onDestroy()
    }

    // Home button pressed while we are already running.
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        endMove()
        if (allApps.visibility == View.VISIBLE) showHome()
    }

    override fun onResume() {
        super.onResume()
        applyTheme() // picks up videos copied to the Shield while the launcher was in the background
        background.start()
        userActivity()
    }

    override fun onPause() {
        endMove()
        ui.removeCallbacks(idleRunnable)
        background.stop() // frees the video decoder for whatever app opens next
        super.onPause()
    }

    /** Any remote key wakes the background; a few minutes without one freezes it. */
    private fun userActivity() {
        background.resumeIdle()
        ui.removeCallbacks(idleRunnable)
        ui.postDelayed(idleRunnable, IDLE_MS)
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
            if (!inAllApps && favEntries.size > 1) actions.add(Action("Move") { startMove(favIdx) })
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

    // ---------------------------------------------------------------- launcher menu (All apps tile)

    private fun guardEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val cn = ComponentName(this, HomeGuardService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
        }
    }

    private fun showLauncherMenu() {
        val bg = currentThemeFile()?.name ?: if (store.themeChoice() == "") "off" else "none found"
        val guard = if (guardEnabled()) {
            "Home button takeover is ON (open accessibility settings)"
        } else {
            "Turn on Home button takeover (opens accessibility settings)"
        }
        showActions(
            "Slim Launcher",
            listOf(
                Action("Background: $bg") { showThemePicker() },
                Action(guard) { openAccessibilitySettings() }
            )
        )
    }

    // ---------------------------------------------------------------- background videos

    /** App-specific storage: no permissions needed, and `adb push` can write here. */
    private fun themeDir(): File? = getExternalFilesDir("themes")

    private fun themeFiles(): List<File> =
        themeDir()?.listFiles { f -> f.isFile && f.extension.lowercase() in VIDEO_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    private fun currentThemeFile(): File? {
        val files = themeFiles()
        return when (val choice = store.themeChoice()) {
            null -> files.firstOrNull() // never chosen: use the first video found
            "" -> null // switched off
            else -> files.firstOrNull { it.name == choice }
        }
    }

    /** Applies the chosen video; does nothing if it hasn't changed. */
    private fun applyTheme() {
        val f = currentThemeFile()
        val key = f?.let { "${it.absolutePath}:${it.lastModified()}" }
        if (key == themeKey) return
        themeKey = key
        background.setVideo(f)
    }

    private fun showThemePicker() {
        val files = themeFiles()
        if (files.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No background videos found")
                .setMessage(
                    "Copy .mp4 videos into:\n${themeDir()?.absolutePath ?: "(storage unavailable)"}" +
                        "\n\nThen open this menu again."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val names = files.map { it.name } + "Off (plain background)"
        AlertDialog.Builder(this)
            .setTitle("Background")
            .setItems(names.toTypedArray()) { _, i ->
                store.setThemeChoice(if (i < files.size) files[i].name else "")
                applyTheme()
            }
            .show()
    }

    /**
     * Opens Accessibility settings directly where the device supports it (the Shield doesn't);
     * otherwise opens the main Settings screen and says where to go.
     */
    private fun openAccessibilitySettings() {
        val where = "Go to Device Preferences > Accessibility > Slim Launcher Home button"
        val direct = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val target = direct.resolveActivity(packageManager)
        // On some TVs this resolves to the system "no app can do this" chooser, which is useless.
        if (target != null && target.packageName != "android") {
            try {
                startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
                // fall through to main Settings
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Toast.makeText(this, where, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Open Settings. $where", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- move mode

    private fun startMove(idx: Int) {
        if (idx !in favEntries.indices) return
        movingIndex = idx
        favAdapter.movingPkg = favEntries[idx].pkg
        favAdapter.refresh(favEntries[idx].pkg)
        focusAfterLayout(favRv, idx)
        Toast.makeText(this, "Use the arrows to move, OK or Back to place it", Toast.LENGTH_LONG).show()
    }

    private fun endMove() {
        if (movingIndex < 0) return
        val pkg = favAdapter.movingPkg
        movingIndex = -1
        favAdapter.movingPkg = null
        if (pkg != null) favAdapter.refresh(pkg)
        saveFavorites()
    }

    /** While moving, the arrows move the favorite instead of the focus. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) userActivity()
        if (movingIndex < 0) return super.dispatchKeyEvent(event)
        val delta = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_UP -> -spans
            KeyEvent.KEYCODE_DPAD_DOWN -> spans
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK -> 0
            else -> return super.dispatchKeyEvent(event)
        }
        // Act on key-down only; swallow key-up too so OK doesn't also launch the app.
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (delta == 0) {
            if (event.repeatCount == 0) endMove()
            return true
        }
        val to = (movingIndex + delta).coerceIn(0, favEntries.size - 1)
        if (to != movingIndex) {
            moveFavorite(movingIndex, to - movingIndex)
            movingIndex = to
            favRv.scrollToPosition(to)
            focusAfterLayout(favRv, to)
        }
        return true
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
        private const val IDLE_MS = 5 * 60 * 1000L
        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv")
    }
}
