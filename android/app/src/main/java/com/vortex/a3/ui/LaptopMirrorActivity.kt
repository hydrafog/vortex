package com.vortex.a3.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import com.vortex.a3.core.mirror.LaptopMirror
import com.vortex.a3.core.mirror.LaptopMirrorClient

class LaptopMirrorActivity : Activity() {
    private var client: LaptopMirrorClient? = null
    private var worker: Thread? = null

    private var scale = 1f
    private lateinit var surface: AspectRatioSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val key = intent.getByteArrayExtra(EXTRA_KEY)
        if (port == 0 || key == null || key.size != 32) {
            Log.w(TAG, "missing/invalid launch params — finishing")
            finish()
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        surface = AspectRatioSurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        root.addView(surface)
        setContentView(root)

        LaptopMirror.viewerCloser = { runOnUiThread { finish() } }

        attachZoomPan(root)

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val c = LaptopMirrorClient(port, key, holder.surface) { w, h ->
                    runOnUiThread { surface.setAspect(w, h) }
                }
                client = c
                worker = Thread({ c.start() }, "laptop-mirror-view").also { it.start() }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                stopClient()
            }
        })
    }

    private fun attachZoomPan(root: FrameLayout) {
        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    scale = (scale * d.scaleFactor).coerceIn(1f, 5f)
                    surface.scaleX = scale
                    surface.scaleY = scale
                    clampPan()
                    return true
                }
            },
        )
        val panDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    dx: Float,
                    dy: Float,
                ): Boolean {
                    if (scale > 1f) {
                        surface.translationX -= dx
                        surface.translationY -= dy
                        clampPan()
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    scale = 1f
                    surface.scaleX = 1f
                    surface.scaleY = 1f
                    surface.translationX = 0f
                    surface.translationY = 0f
                    return true
                }
            },
        )
        root.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            panDetector.onTouchEvent(ev)
            true
        }
    }

    private fun clampPan() {
        val maxX = (surface.width * (scale - 1f)) / 2f
        val maxY = (surface.height * (scale - 1f)) / 2f
        surface.translationX = surface.translationX.coerceIn(-maxX, maxX)
        surface.translationY = surface.translationY.coerceIn(-maxY, maxY)
    }

    override fun onDestroy() {
        super.onDestroy()
        LaptopMirror.viewerCloser = null
        stopClient()
        LaptopMirror.onViewerClosed(applicationContext)
    }

    private fun stopClient() {
        client?.stop()
        client = null
        worker?.let { try { it.join(500) } catch (_: Throwable) {} }
        worker = null
    }

    companion object {
        private const val TAG = "LaptopMirror"
        const val EXTRA_PORT = "port"
        const val EXTRA_KEY = "key"
    }
}

private class AspectRatioSurfaceView(context: Context) : SurfaceView(context) {
    private var aspectW = 16
    private var aspectH = 9

    fun setAspect(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == aspectW && h == aspectH)) return
        aspectW = w
        aspectH = h
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = MeasureSpec.getSize(widthMeasureSpec)
        val availH = MeasureSpec.getSize(heightMeasureSpec)
        if (availW == 0 || availH == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val target = aspectW.toFloat() / aspectH
        val avail = availW.toFloat() / availH
        val (w, h) = if (avail > target) {
            ((availH * target).toInt()) to availH
        } else {
            availW to ((availW / target).toInt())
        }
        setMeasuredDimension(w, h)
    }
}
