package com.example.jarvis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Flowing coloured waves, a bit like a voice-assistant glow. Reacts when you speak. */
class WaveView(ctx: Context) : View(ctx) {
    private val paints = List(3) { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    private val path = Path()
    private var phase = 0f
    private var level = 0.3f
    private var target = 0.3f
    private var animator: ValueAnimator? = null

    fun pulse() {
        target = 1f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val c0 = intArrayOf(Color.argb(150, 91, 200, 255), Color.argb(150, 59, 130, 246), Color.argb(150, 139, 92, 246))
        val c1 = intArrayOf(Color.argb(130, 139, 92, 246), Color.argb(130, 236, 72, 153), Color.argb(130, 251, 146, 60))
        val c2 = intArrayOf(Color.argb(120, 34, 211, 238), Color.argb(120, 99, 102, 241), Color.argb(120, 236, 72, 153))
        val sets = listOf(c0, c1, c2)
        for (i in 0 until 3) {
            paints[i].shader = LinearGradient(0f, 0f, w.toFloat(), 0f, sets[i], null, Shader.TileMode.CLAMP)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        level += (target - level) * 0.08f
        target = max(0.28f, target - 0.012f)
        val w = width.toFloat()
        val h = height.toFloat()
        val base = h * 0.80f
        for (idx in 0 until 3) {
            path.reset()
            path.moveTo(0f, h)
            var x = 0f
            while (x <= w) {
                val nx = x / w
                val s = sin(PI * nx).toFloat()
                val env = s * s
                val amp = h * 0.42f * level * env * (0.55f + 0.25f * idx)
                val angle = 2.0 * PI * (nx * (1.1 + idx * 0.45)) + phase * (1.0 + idx * 0.5) + idx * 1.7
                val y = base - amp * sin(angle).toFloat() - amp * 0.15f
                path.lineTo(x, y)
                x += 8f
            }
            path.lineTo(w, h)
            path.close()
            canvas.drawPath(path, paints[idx])
        }
    }
}

/** The bottom panel that appears after you say "Jarvis". */
class JarvisOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var wave: WaveView? = null
    private var title: TextView? = null
    private var sub: TextView? = null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    val isShowing: Boolean get() = root != null

    fun show(titleText: String) {
        if (root != null) {
            setText(titleText, null)
            return
        }
        if (!Settings.canDrawOverlays(ctx)) return

        val titleView = TextView(ctx).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 3
            text = titleText
        }
        val subView = TextView(ctx).apply {
            textSize = 14f
            setTextColor(Color.argb(255, 159, 180, 214))
            gravity = Gravity.CENTER
            maxLines = 3
            visibility = View.GONE
        }
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(12), dp(22), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 11, 16, 32))
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.argb(90, 91, 200, 255))
            }
            addView(titleView)
            addView(subView)
        }
        val waveView = WaveView(ctx)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                pill,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(24), 0, dp(24), dp(4)) }
            )
            addView(
                waveView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(120))
            )
            alpha = 0f
            translationY = dp(60).toFloat()
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM
        try {
            wm.addView(container, lp)
        } catch (e: Exception) {
            return
        }
        container.animate().alpha(1f).translationY(0f).setDuration(220).start()
        root = container
        wave = waveView
        title = titleView
        sub = subView
    }

    fun setText(main: String, secondary: String?) {
        title?.text = main
        val s = sub
        if (s != null) {
            if (secondary.isNullOrBlank()) {
                s.visibility = View.GONE
            } else {
                s.text = secondary
                s.visibility = View.VISIBLE
            }
        }
    }

    fun pulse() {
        wave?.pulse()
    }

    fun hide() {
        val r = root ?: return
        root = null
        wave = null
        title = null
        sub = null
        r.animate().alpha(0f).translationY(dp(60).toFloat()).setDuration(200).withEndAction {
            try { wm.removeView(r) } catch (_: Exception) { }
        }.start()
        // safety: make sure it is gone even if the animation is cancelled
        handler.postDelayed({ try { wm.removeView(r) } catch (_: Exception) { } }, 600)
    }
}
