package com.dom.slimlauncher

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.File
import java.io.FileInputStream
import kotlin.math.max

/**
 * A muted, looping video behind the launcher, with a dark overlay so icons stay readable.
 *
 * Resource rules:
 *  - [start] / [stop] follow the activity lifecycle. [stop] releases the player completely, so the
 *    hardware video decoder is free for whatever app you open next.
 *  - [pauseIdle] freezes the picture (no decoding) after a period of no remote activity.
 */
class ThemeBackground(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {

    private val texture = TextureView(context)
    private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var videoW = 0
    private var videoH = 0
    private var active = false
    private var ready = false
    private var idle = false

    /** Called (on the main thread) when the video can't be played. */
    var onError: (() -> Unit)? = null

    init {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        addView(texture, FrameLayout.LayoutParams(match, match))
        addView(
            View(context).apply { setBackgroundColor(Color.argb(120, 0, 0, 0)) },
            FrameLayout.LayoutParams(match, match)
        )
        texture.surfaceTextureListener = this
        visibility = GONE
    }

    /** Choose the video to show, or null for none (the plain gradient shows instead). */
    fun setVideo(f: File?) {
        file = f
        release()
        visibility = if (f == null) GONE else VISIBLE
        if (f != null && active && surface != null) prepare()
    }

    fun start() {
        active = true
        if (file != null && surface != null && player == null) prepare()
    }

    fun stop() {
        active = false
        release()
    }

    fun pauseIdle() {
        idle = true
        val p = player ?: return
        if (ready && p.isPlaying) p.pause()
    }

    fun resumeIdle() {
        idle = false
        val p = player ?: return
        if (active && ready && !p.isPlaying) p.start()
    }

    // ---------------------------------------------------------------- internals

    private fun prepare() {
        val f = file ?: return
        val s = surface ?: return
        release()
        try {
            val mp = MediaPlayer()
            player = mp
            FileInputStream(f).use { mp.setDataSource(it.fd) }
            mp.setSurface(s)
            mp.setVolume(0f, 0f)
            mp.isLooping = true
            mp.setOnVideoSizeChangedListener { _, w, h ->
                videoW = w
                videoH = h
                applyCrop()
            }
            mp.setOnPreparedListener {
                ready = true
                if (active && !idle) it.start()
            }
            mp.setOnErrorListener { _, _, _ ->
                release()
                onError?.invoke()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            release()
            onError?.invoke()
        }
    }

    private fun release() {
        ready = false
        val p = player ?: return
        player = null
        try {
            p.setOnPreparedListener(null)
            p.setOnErrorListener(null)
            p.release()
        } catch (_: Exception) {
        }
    }

    /** TextureView stretches the video to its own size; undo that and fill the screen (center-crop). */
    private fun applyCrop() {
        val vw = texture.width.toFloat()
        val vh = texture.height.toFloat()
        if (videoW == 0 || videoH == 0 || vw == 0f || vh == 0f) return
        val scale = max(vw / videoW, vh / videoH)
        val m = Matrix()
        m.setScale(videoW * scale / vw, videoH * scale / vh, vw / 2f, vh / 2f)
        texture.setTransform(m)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(st)
        applyCrop()
        if (active && file != null && player == null) prepare()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = applyCrop()

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        release()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
