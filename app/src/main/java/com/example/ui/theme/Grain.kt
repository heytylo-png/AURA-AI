package com.example.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.MaterialTheme
import kotlin.random.Random

/**
 * A faint film grain, generated once in memory (no image file) and tiled over the
 * background. Kept at a few percent so it never reduces text contrast.
 */
object Grain {
    const val TILE = 128

    /** Alpha (0–255) of the light and dark specks. */
    const val LIGHT_ALPHA = 12
    const val DARK_ALPHA = 40

    /** ARGB pixels of the tile; deterministic so the texture never "swims" between launches. */
    fun pixels(size: Int = TILE, seed: Int = 24): IntArray {
        val random = Random(seed)
        return IntArray(size * size) {
            when (random.nextInt(100)) {
                in 0..13 -> (LIGHT_ALPHA shl 24) or 0xFFFFFF // light speck
                in 14..27 -> (DARK_ALPHA shl 24)             // dark speck
                else -> 0
            }
        }
    }

    val tile: ImageBitmap by lazy {
        Bitmap.createBitmap(pixels(), TILE, TILE, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    val brush: ShaderBrush by lazy {
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader = ImageShader(tile, TileMode.Repeated, TileMode.Repeated)
        }
    }
}

/** Aura's screen background: graphite plus grain. Use instead of `.background(colorScheme.background)`. */
fun Modifier.auraBackground(): Modifier = composed {
    val color = MaterialTheme.colorScheme.background
    this
        .background(color)
        .drawBehind { drawRect(Grain.brush) }
}
