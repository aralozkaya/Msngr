package org.aralozkaya.msngr

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/** RMS of signed little-endian PCM16, scaled for speech rather than full-scale clipping. */
internal fun voiceClipLevel(pcm: ByteArray, count: Int = pcm.size): Float {
    val samples = count.coerceIn(0, pcm.size) / 2
    if (samples == 0) return 0f
    var energy = 0.0
    repeat(samples) { index ->
        val sample = ((pcm[index * 2].toInt() and 255) or
            (pcm[index * 2 + 1].toInt() shl 8)).toShort().toDouble()
        energy += sample * sample
    }
    return (sqrt(energy / samples) / 8_192).toFloat().coerceIn(0f, 1f)
}

@Composable
internal fun VoiceWaveform(
    levels: List<Float>,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    live: Boolean = false,
) {
    val color = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    Canvas(modifier) {
        val count = (size.width / 5.dp.toPx()).toInt().coerceIn(1, 40)
        val spacing = size.width / count
        repeat(count) { index ->
            val sourceIndex = if (live) levels.size - count + index else index * levels.size / count
            val level = levels.getOrNull(sourceIndex)?.coerceIn(0f, 1f) ?: 0f
            val height = maxOf(3.dp.toPx(), level * size.height)
            val x = spacing * (index + 0.5f)
            drawLine(
                color = if (progress == 0f || index.toFloat() / count <= progress) color else muted,
                start = Offset(x, (size.height - height) / 2),
                end = Offset(x, (size.height + height) / 2),
                strokeWidth = minOf(3.dp.toPx(), spacing * 0.6f),
                cap = StrokeCap.Round,
            )
        }
    }
}
