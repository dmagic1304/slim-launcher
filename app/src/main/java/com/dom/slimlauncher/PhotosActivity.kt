package com.dom.slimlauncher

import android.Manifest
import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Simple full-quality photo viewer.
 *
 * Why it looks sharper than most TV photo apps: Android TV draws normal app screens at 1080p and
 * upscales them, even on a 4K TV. Photos here are drawn into a SurfaceView whose buffer is set to the
 * TV's real output resolution (e.g. 3840x2160), and each photo is resized once, with filtering, to
 * exactly fit that buffer.
 *
 * Remote: OK opens; Left/Right previous/next; OK (or Play/Pause) in the viewer toggles a slideshow;
 * Up/Down shows the file name; Back leaves the viewer / goes up a folder.
 * The screen stays awake (no screensaver) while a photo is shown.
 */
class PhotosActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var prefs: SharedPreferences
    private lateinit var browser: View
    private lateinit var pathView: TextView
    private lateinit var messageView: TextView
    private lateinit var list: RecyclerView
    private lateinit var viewer: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var info: TextView

    private val adapter = EntryAdapter { onPick(it) }

    private var dir: File? = null // null = list of storage devices
    private var photos: List<File> = emptyList()
    private var index = 0
    private var slideshow = false
    private var bitmap: Bitmap? = null
    private var surfaceReady = false
    private var targetW = 1920
    private var targetH = 1080
    private var generation = 0

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val ui = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor()
    private val nextSlide = Runnable { step(1) }
    private val hideInfo = Runnable { info.visibility = View.GONE }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("photos", MODE_PRIVATE)
        measureScreen()
        buildUi()
        if (hasPermission()) openStart() else requestPermissions(arrayOf(permission()), REQ_PERM)
    }

    override fun onResume() {
        super.onResume()
        if (slideshow) scheduleNext()
    }

    override fun onPause() {
        ui.removeCallbacks(nextSlide)
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        decoder.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        if (hasPermission()) {
            openStart()
        } else {
            show(null)
            message(
                "Photos needs permission to read your storage.\n" +
                    "Allow it in Settings > Apps > Slim Launcher > Permissions, or from your PC run:\n" +
                    "adb shell pm grant $packageName ${permission()}"
            )
        }
    }

    // ---------------------------------------------------------------- setup

    private fun permission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasPermission() = checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

    /** The TV's real output resolution, not the 1080p the UI is drawn at. */
    @Suppress("DEPRECATION")
    private fun measureScreen() {
        val mode = windowManager.defaultDisplay.mode
        val w = maxOf(mode.physicalWidth, mode.physicalHeight)
        val h = minOf(mode.physicalWidth, mode.physicalHeight)
        if (w > 0 && h > 0) {
            targetW = w
            targetH = h
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val title = TextView(this).apply {
            text = "Photos"
            textSize = 28f
            setTextColor(Color.WHITE)
        }
        pathView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.argb(179, 255, 255, 255))
            setPadding(0, dp(4), 0, dp(12))
        }
        messageView = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.argb(204, 255, 255, 255))
            setPadding(0, dp(12), 0, 0)
            visibility = View.GONE
        }
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PhotosActivity)
            adapter = this@PhotosActivity.adapter
            itemAnimator = null
            layoutParams = LinearLayout.LayoutParams(match, 0, 1f)
        }
        browser = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), dp(24))
            addView(title, LinearLayout.LayoutParams(match, wrap))
            addView(pathView, LinearLayout.LayoutParams(match, wrap))
            addView(messageView, LinearLayout.LayoutParams(match, wrap))
            addView(list)
        }

        surface = SurfaceView(this).apply {
            holder.addCallback(this@PhotosActivity)
            holder.setFixedSize(targetW, targetH)
        }
        info = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
        }
        viewer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(match, match))
            addView(
                info,
                FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.START).apply {
                    setMargins(dp(32), 0, 0, dp(32))
                }
            )
            visibility = View.GONE
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(browser, FrameLayout.LayoutParams(match, match))
        root.addView(viewer, FrameLayout.LayoutParams(match, match))
        setContentView(root)
    }

    // ---------------------------------------------------------------- browsing

    @Suppress("DEPRECATION")
    private fun internalRoot(): File = Environment.getExternalStorageDirectory()

    /**
     * The app's own folder. Android always lets an app read its own folder, with no permission and
     * no media index needed, so photos copied here always show up.
     */
    private fun inboxDir(): File? = getExternalFilesDir("photos")

    private fun openStart() {
        val saved = prefs.getString("dir", null)?.let { File(it) }
        show(if (saved != null && saved.isDirectory) saved else null)
    }

    private fun show(d: File?) {
        dir = d
        messageView.visibility = View.GONE
        val entries = ArrayList<Entry>()
        if (d == null) {
            pathView.text = "Storage"
            inboxDir()?.let { entries.add(Entry.Folder(it, "Slim Launcher photos (always visible)")) }
            entries.add(Entry.Folder(internalRoot(), "Internal storage"))
            File("/storage").listFiles()
                ?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead() }
                ?.sortedBy { it.name }
                ?.forEach { entries.add(Entry.Folder(it, "USB / SD card (${it.name})")) }
            photos = emptyList()
        } else {
            pathView.text = d.absolutePath
            entries.add(Entry.Up)
            val files = d.listFiles()
            if (files == null) {
                photos = emptyList()
                message("Can't read this folder.")
            } else {
                val folders = files.filter { it.isDirectory && !it.name.startsWith(".") }
                    .sortedBy { it.name.lowercase() }
                val images = files.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
                    .sortedBy { it.name.lowercase() }
                folders.forEach { entries.add(Entry.Folder(it, it.name)) }
                images.forEach { entries.add(Entry.Photo(it)) }
                photos = images
                if (folders.isEmpty() && images.isEmpty()) message("No photos or folders here.")
                prefs.edit().putString("dir", d.absolutePath).apply()
            }
        }
        adapter.submit(entries)
        list.scrollToPosition(0)
        focusRow(0)
    }

    private fun message(text: String) {
        messageView.text = text
        messageView.visibility = View.VISIBLE
    }

    private fun goUp() {
        val d = dir ?: return finish()
        val parent = d.parentFile
        val atVolumeRoot = d.absolutePath == internalRoot().absolutePath ||
            d.absolutePath == inboxDir()?.absolutePath ||
            parent == null || parent.absolutePath == "/storage" || parent.absolutePath == "/storage/emulated"
        show(if (atVolumeRoot) null else parent)
    }

    private fun onPick(e: Entry) {
        when (e) {
            is Entry.Up -> goUp()
            is Entry.Folder -> show(e.file)
            is Entry.Photo -> openViewer(photos.indexOf(e.file))
        }
    }

    private fun focusRow(pos: Int) {
        list.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                list.viewTreeObserver.removeOnGlobalLayoutListener(this)
                list.layoutManager?.findViewByPosition(pos)?.requestFocus()
            }
        })
        list.requestLayout()
    }

    // ---------------------------------------------------------------- viewer

    private fun openViewer(i: Int) {
        if (photos.isEmpty() || i < 0) return
        index = i.coerceIn(0, photos.size - 1)
        browser.visibility = View.GONE
        viewer.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        load()
    }

    private fun closeViewer() {
        stopSlideshow()
        generation++
        bitmap = null
        viewer.visibility = View.GONE
        browser.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pos = adapter.positionOf(photos.getOrNull(index))
        if (pos >= 0) {
            list.scrollToPosition(pos)
            focusRow(pos)
        }
    }

    private fun load() {
        val file = photos.getOrNull(index) ?: return
        val gen = ++generation
        decoder.execute {
            val bmp = try {
                decodeForScreen(file, targetW, targetH)
            } catch (t: Throwable) { // includes OutOfMemoryError on huge files
                null
            }
            ui.post {
                if (gen != generation || isDestroyed) return@post
                if (bmp == null) {
                    showInfo("Can't open ${file.name}")
                    if (slideshow) scheduleNext()
                    return@post
                }
                bitmap = bmp
                draw()
                showInfo("${index + 1} / ${photos.size}   ${file.name}" + if (slideshow) "   (slideshow)" else "")
                if (slideshow) scheduleNext()
            }
        }
    }

    private fun draw() {
        val bmp = bitmap ?: return
        if (!surfaceReady) return
        val holder = surface.holder
        val canvas: Canvas = try {
            holder.lockHardwareCanvas()
        } catch (e: Exception) {
            holder.lockCanvas()
        } ?: return
        try {
            canvas.drawColor(Color.BLACK)
            val cw = canvas.width.toFloat()
            val ch = canvas.height.toFloat()
            // The bitmap was already sized for this surface, so the scale here is normally 1.
            val s = min(cw / bmp.width, ch / bmp.height)
            val w = bmp.width * s
            val h = bmp.height * s
            canvas.drawBitmap(bmp, null, RectF((cw - w) / 2f, (ch - h) / 2f, (cw + w) / 2f, (ch + h) / 2f), paint)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun step(delta: Int) {
        if (photos.isEmpty()) return
        index = (index + delta + photos.size) % photos.size
        load()
    }

    private fun toggleSlideshow() {
        if (slideshow) {
            stopSlideshow()
            showInfo("Slideshow off")
        } else {
            slideshow = true
            showInfo("Slideshow on (every ${SLIDE_MS / 1000} s)")
            scheduleNext()
        }
    }

    private fun stopSlideshow() {
        slideshow = false
        ui.removeCallbacks(nextSlide)
    }

    private fun scheduleNext() {
        ui.removeCallbacks(nextSlide)
        if (slideshow && photos.size > 1) ui.postDelayed(nextSlide, SLIDE_MS)
    }

    private fun showInfo(text: String) {
        info.text = text
        info.visibility = View.VISIBLE
        ui.removeCallbacks(hideInfo)
        ui.postDelayed(hideInfo, 3000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (viewer.visibility != View.VISIBLE) return super.dispatchKeyEvent(event)
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> true
            else -> false
        }
        if (!handled) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> step(1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> step(-1)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> {
                val f = photos.getOrNull(index)
                if (f != null) showInfo("${index + 1} / ${photos.size}   ${f.name}")
            }
            else -> if (event.repeatCount == 0) toggleSlideshow()
        }
        return true
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            viewer.visibility == View.VISIBLE -> closeViewer()
            dir != null -> goUp()
            else -> finish()
        }
    }

    // ---------------------------------------------------------------- SurfaceHolder.Callback

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        draw()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = draw()

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    companion object {
        private const val REQ_PERM = 1
        private const val SLIDE_MS = 8000L
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}

// -------------------------------------------------------------------- list model + adapter

private sealed class Entry {
    object Up : Entry()
    data class Folder(val file: File, val title: String) : Entry()
    data class Photo(val file: File) : Entry()
}

private class EntryAdapter(private val onPick: (Entry) -> Unit) : RecyclerView.Adapter<EntryAdapter.VH>() {

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    private val items = ArrayList<Entry>()

    fun submit(newItems: List<Entry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun positionOf(file: File?): Int = items.indexOfFirst { it is Entry.Photo && it.file == file }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val d = parent.resources.displayMetrics.density
        val tv = TextView(parent.context).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            isFocusable = true
            isClickable = true
            setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), (12 * d).toInt())
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnFocusChangeListener { v, hasFocus ->
                v.setBackgroundColor(if (hasFocus) Color.argb(70, 255, 255, 255) else Color.TRANSPARENT)
            }
        }
        return VH(tv)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        h.text.text = when (val e = items[position]) {
            is Entry.Up -> "..   Up one level"
            is Entry.Folder -> "[ ]  ${e.title}"
            is Entry.Photo -> "      ${e.file.name}"
        }
        h.text.setOnClickListener { items.getOrNull(h.bindingAdapterPosition)?.let(onPick) }
    }
}

// -------------------------------------------------------------------- decoding

/**
 * Decodes [file] to exactly fit [maxW] x [maxH] (keeping the aspect ratio, upscaling small images),
 * applying the camera's EXIF rotation. Decodes no smaller than needed, then resizes once with filtering.
 */
private fun decodeForScreen(file: File, maxW: Int, maxH: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val rotation = exifRotation(file)
    val swap = rotation == 90 || rotation == 270
    val shownW = if (swap) bounds.outHeight else bounds.outWidth
    val shownH = if (swap) bounds.outWidth else bounds.outHeight
    val scale = min(maxW / shownW.toFloat(), maxH / shownH.toFloat())

    // Largest power-of-two subsample that still leaves at least the pixels we need.
    val needW = bounds.outWidth * scale
    val needH = bounds.outHeight * scale
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= needW && bounds.outHeight / (sample * 2) >= needH) sample *= 2

    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeFile(file.path, opts) ?: return null

    val finalW = (shownW * scale).roundToInt().coerceAtLeast(1)
    val finalH = (shownH * scale).roundToInt().coerceAtLeast(1)
    if (rotation == 0 && decoded.width == finalW && decoded.height == finalH) return decoded

    // Scale in the file's own orientation, then rotate to how the camera meant it.
    val preW = if (swap) finalH else finalW
    val preH = if (swap) finalW else finalH
    val m = Matrix().apply {
        postScale(preW / decoded.width.toFloat(), preH / decoded.height.toFloat())
        if (rotation != 0) postRotate(rotation.toFloat())
    }
    val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
    if (out !== decoded) decoded.recycle()
    return out
}

private fun exifRotation(file: File): Int = try {
    when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
} catch (e: Exception) {
    0
}
