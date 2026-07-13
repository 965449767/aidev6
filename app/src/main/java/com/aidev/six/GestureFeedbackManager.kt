package com.aidev.six

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.lang.ref.WeakReference

class GestureFeedbackManager(
    activity: Activity,
    private val prefs: SharedPreferences
) {
    private val activityRef = WeakReference(activity)
    private val enabled: Boolean
        get() = prefs.getBoolean(Constants.PrefKeys.HAPTIC_TAP, true)

    private fun act(): Activity? = activityRef.get()

    fun tick() {
        if (!enabled) return
        act()?.window?.decorView?.performHapticFeedback(
            HapticFeedbackConstants.CLOCK_TICK,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
    }

    fun pickup() {
        if (!enabled) return
        act()?.window?.decorView?.performHapticFeedback(
            HapticFeedbackConstants.LONG_PRESS,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
    }

    fun confirm() {
        if (!enabled) return
        act()?.window?.decorView?.performHapticFeedback(
            HapticFeedbackConstants.CONFIRM,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
    }

    fun reject() {
        if (!enabled) return
        act()?.window?.decorView?.performHapticFeedback(
            HapticFeedbackConstants.REJECT,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
    }

    fun drop() {
        if (!enabled) return
        runCatching {
            val vibrator = vibrator() ?: return@runCatching
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 50, 30, 50),
                        intArrayOf(0, 255, 0, 255),
                        -1
                    )
                )
            } else {
                vibrator.vibrate(130)
            }
        }.onFailure { Log.e("GestureFeedback", "drop failed", it) }
    }

    fun error() {
        if (!enabled) return
        val vibrator = vibrator() ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 80, 60, 100),
                    intArrayOf(0, 200, 0, 150),
                    -1
                )
            )
        } else {
            vibrator.vibrate(240)
        }
    }

    fun glowHighlight(view: View, color: Int) {
        val original = view.background
        val highlight = GradientDrawable().apply {
            setColor(color and 0x00FFFFFF or 0x28000000.toInt())
            cornerRadius = 8f
        }
        view.background = highlight
        view.postDelayed({ view.background = original }, 250)
    }

    fun pulseGlow(view: View, color: Int) {
        view.animate()
            .scaleX(1.08f)
            .scaleY(1.08f)
            .setDuration(80)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150)
                    .start()
            }
            .start()
    }

    fun targetGlow(view: View, active: Boolean, accentColor: Int) {
        if (active) {
            val glow = GradientDrawable().apply {
                setColor(accentColor and 0x00FFFFFF or 0x20000000.toInt())
                cornerRadius = 10f
                setStroke(dp(2f), accentColor)
            }
            view.background = glow
        } else {
            view.background = null
        }
    }

    fun snapSpring(view: View, fromX: Float, toX: Float, onEnd: (() -> Unit)? = null) {
        view.translationX = fromX
        view.animate()
            .translationX(toX)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    private fun vibrator(): Vibrator? {
        val a = act() ?: return null
        return if (Build.VERSION.SDK_INT >= 31) {
            val mgr = a.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager ?: return null
            mgr.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            a.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun dp(value: Float): Int {
        val a = act() ?: return 0
        return (value * a.resources.displayMetrics.density).toInt()
    }
}
