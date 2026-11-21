package com.example.wifip2pchat

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat

/**
 * Hardware-accelerated Glassmorphism effects helper.
 *
 * - Applies RenderEffect blur (API 31+) to ambient light orbs and frosted layers.
 * - Applies colored luminous outline spot/ambient shadows (API 28+) for soft glow borders.
 */
object GlassEffects {

    fun applyAmbientBlur(view: View?, radiusPx: Float = 56f) {
        if (view == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(
                RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
            )
        }
    }

    fun applyGlowElevation(
        view: View?,
        @ColorRes glowColorRes: Int,
        elevationDp: Float = 10f
    ) {
        if (view == null) return
        val density = view.resources.displayMetrics.density
        view.elevation = elevationDp * density
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val glowColor = ContextCompat.getColor(view.context, glowColorRes)
            view.outlineSpotShadowColor = glowColor
            view.outlineAmbientShadowColor = glowColor
        }
    }
}
