package com.iskezis.myst.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.MaterialSharedAxis
import android.animation.ObjectAnimator

/** Animações curtas e baratas, sempre respeitando a escala global do sistema. */
object UiAnimations {

    fun enabled(context: Context): Boolean {
        val scale = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        } catch (t: Throwable) {
            1f
        }
        return scale > 0f
    }

    fun View.pressScale() {
        if (!UiAnimations.enabled(context)) return
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate()
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(90)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .start()

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(OvershootInterpolator(1.4f))
                    .start()
            }
            false
        }
    }

    fun View.shake() {
        if (!UiAnimations.enabled(context)) return
        translationX = 0f
        ObjectAnimator.ofFloat(
            this,
            View.TRANSLATION_X,
            0f,
            dp(-8f),
            dp(8f),
            dp(-8f),
            dp(8f),
            0f,
        ).apply {
            duration = 250
            interpolator = FastOutSlowInInterpolator()
        }.start()
    }

    fun View.staggerIn(index: Int) {
        if (!UiAnimations.enabled(context)) return
        alpha = 0f
        translationY = dp(24f)
        animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((index.coerceAtMost(7) * 60).toLong())
            .setDuration(280)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    fun RecyclerView.staggerVisibleChildren() {
        if (!isAttachedToWindow || !UiAnimations.enabled(context)) return
        val count = childCount
        for (index in 0 until count) {
            // RecyclerView pode devolver null para um índice durante o layout
            // (principalmente se a tela foi trocada no meio da animação).
            val child: View? = getChildAt(index)
            child?.staggerIn(index)
        }
    }

    fun animatePlayIcon(button: ImageButton, playing: Boolean, playRes: Int, pauseRes: Int) {
        val target = if (playing) pauseRes else playRes
        if (button.tag == target) return
        button.tag = target
        if (!UiAnimations.enabled(button.context)) {
            button.setImageResource(target)
            return
        }
        button.animate().alpha(0f).setDuration(90).withEndAction {
            button.setImageResource(target)
            button.animate().alpha(1f).setDuration(140).start()
        }.start()
    }

    fun animateProgress(bar: ProgressBar, value: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            bar.setProgress(value, true)
        } else {
            bar.progress = value
        }
    }

    fun Fragment.applyFadeThrough() {
        val enter = MaterialFadeThrough().apply {
            duration = 300
            interpolator = FastOutSlowInInterpolator()
        }
        val exit = MaterialFadeThrough().apply {
            duration = 300
            interpolator = FastOutSlowInInterpolator()
        }
        enterTransition = enter
        exitTransition = exit
        reenterTransition = exit
        returnTransition = enter
    }

    fun Fragment.applySlideUp() {
        val enter = MaterialSharedAxis(MaterialSharedAxis.Y, true).apply {
            duration = 350
            interpolator = FastOutSlowInInterpolator()
        }
        val exit = MaterialFadeThrough().apply {
            duration = 250
            interpolator = FastOutSlowInInterpolator()
        }
        enterTransition = enter
        exitTransition = exit
        reenterTransition = exit
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Y, false).apply {
            duration = 300
            interpolator = FastOutSlowInInterpolator()
        }
    }

    private fun View.dp(value: Float): Float = value * resources.displayMetrics.density
}
