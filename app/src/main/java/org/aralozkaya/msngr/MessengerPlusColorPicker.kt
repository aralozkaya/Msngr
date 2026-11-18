@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun MessengerPlusCustomColorDialog(
    initialColor: MessengerPlusColorValue,
    applyLabel: String,
    onColorSelected: (MessengerPlusColorValue) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val initialHsv = remember { initialColor.argb.toHsv() }
    var hue by rememberSaveable { mutableFloatStateOf(initialHsv[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(initialHsv[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(initialHsv[2]) }
    var colorInput by rememberSaveable { mutableStateOf(initialColor.wireToken) }
    val color = customColorValue(colorInput)

    fun updateWheel(nextHue: Float, nextSaturation: Float) {
        hue = nextHue
        saturation = nextSaturation
        colorInput = hsvColorToken(hue, saturation, brightness)
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.testTag(MESSENGER_PLUS_CUSTOM_COLOR_DIALOG_TAG),
        title = { Text(stringResource(R.string.color_picker_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ColorWheel(
                    hue = hue,
                    saturation = saturation,
                    brightness = brightness,
                    onColorChanged = ::updateWheel,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Text(stringResource(R.string.appearance_brightness), style = MaterialTheme.typography.labelMedium)
                val brightnessDescription = stringResource(R.string.appearance_brightness)
                Slider(
                    state = rememberSliderState(brightness).apply { value = brightness },
                    onValueChange = {
                        brightness = it
                        colorInput = hsvColorToken(hue, saturation, it)
                    },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = brightnessDescription },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(32.dp)
                            .background(Color.hsv(hue, saturation, brightness), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                    EmoticonOutlinedTextField(
                        value = colorInput,
                        onValueChange = {
                            colorInput = it.replace("\r", "").replace("\n", "").take(24)
                            customColorValue(colorInput)?.argb?.toHsv()?.let { hsv ->
                                hue = hsv[0]
                                saturation = hsv[1]
                                brightness = hsv[2]
                            }
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.color_picker_code_label)) },
                        supportingText = {
                            Text(
                                if (color == null) {
                                    twoNumbersSentenceResource(
                                        R.string.color_picker_code_error_before_first_number,
                                        "00",
                                        R.string.color_picker_code_error_between_numbers,
                                        67,
                                        R.string.color_picker_code_error_after_second_number,
                                    )
                                } else {
                                    twoNumbersSentenceResource(
                                        R.string.color_picker_code_hint_before_first_number,
                                        "00",
                                        R.string.color_picker_code_hint_between_numbers,
                                        67,
                                        R.string.color_picker_code_hint_after_second_number,
                                    )
                                },
                            )
                        },
                        singleLine = true,
                        isError = color == null,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { color?.let(onColorSelected) },
                enabled = color != null,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(applyLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** Hue runs clockwise from red at the right edge; saturation increases toward the rim. */
@Composable
internal fun ColorWheel(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onColorChanged: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = MESSENGER_PLUS_COLOR_WHEEL_TAG,
) {
    val currentOnColorChanged by rememberUpdatedState(onColorChanged)
    val hueColors = (0..6).map { Color.hsv(it * 60f, 1f, brightness) }
    Canvas(
        modifier = modifier
            .size(200.dp)
            .testTag(testTag)
            .let { baseModifier ->
                val wheelDesc = stringResource(R.string.color_wheel_description)
                val wheelState = twoNumbersSentenceResource(
                    R.string.color_wheel_state_before_first_number,
                    hue.toInt(),
                    R.string.color_wheel_state_between_numbers,
                    (saturation * 100).toInt(),
                    R.string.color_wheel_state_after_second_number,
                )
                val nextHue = stringResource(R.string.color_wheel_next_hue)
                val prevHue = stringResource(R.string.color_wheel_prev_hue)
                val incSat = stringResource(R.string.color_wheel_inc_sat)
                val decSat = stringResource(R.string.color_wheel_dec_sat)
                baseModifier.semantics {
                    contentDescription = wheelDesc
                    stateDescription = wheelState
                    customActions = listOf(
                        CustomAccessibilityAction(nextHue) {
                            onColorChanged((hue + 15f) % 360f, saturation)
                            true
                        },
                        CustomAccessibilityAction(prevHue) {
                            onColorChanged((hue + 345f) % 360f, saturation)
                            true
                        },
                        CustomAccessibilityAction(incSat) {
                            onColorChanged(hue, (saturation + 0.1f).coerceAtMost(1f))
                            true
                        },
                        CustomAccessibilityAction(decSat) {
                            onColorChanged(hue, (saturation - 0.1f).coerceAtLeast(0f))
                            true
                        },
                    )
                }
            }
            .pointerInput(Unit) {
                val radius = minOf(size.width, size.height) / 2f - 8.dp.toPx()
                val center = Offset(size.width / 2f, size.height / 2f)
                fun pick(position: Offset) {
                    val delta = position - center
                    val nextHue = ((atan2(delta.y, delta.x) * 180f / PI.toFloat()) + 360f) % 360f
                    currentOnColorChanged(nextHue, (delta.getDistance() / radius).coerceIn(0f, 1f))
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if ((down.position - center).getDistance() <= radius) {
                        down.consume()
                        pick(down.position)
                        drag(down.id) { change ->
                            change.consume()
                            pick(change.position)
                        }
                    }
                }
            },
    ) {
        val radius = size.minDimension / 2f - 8.dp.toPx()
        drawCircle(Brush.sweepGradient(hueColors, center), radius)
        drawCircle(
            Brush.radialGradient(
                listOf(Color.hsv(0f, 0f, brightness), Color.Transparent),
                center = center,
                radius = radius,
            ),
            radius,
        )
        val angle = hue * PI.toFloat() / 180f
        val marker = center + Offset(cos(angle), sin(angle)) * (saturation * radius)
        drawCircle(Color.hsv(hue, saturation, brightness), 7.dp.toPx(), marker)
        drawCircle(Color.Black, 8.dp.toPx(), marker, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 8.dp.toPx(), marker, style = Stroke(1.5.dp.toPx()))
    }
}

private fun Int.toHsv(): FloatArray =
    FloatArray(3).also { android.graphics.Color.colorToHSV(this, it) }

private fun hsvColorToken(hue: Float, saturation: Float, brightness: Float): String =
    String.format(
        Locale.ROOT,
        "#%06X",
        android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)) and 0xFFFFFF,
    )

private fun customColorValue(input: String): MessengerPlusColorValue? {
    val token = input.trim()
    return when {
        token.startsWith('#') -> MessengerPlusColorValue.fromHex(token)
        token.isNotEmpty() && token.all { it in '0'..'9' } ->
            token.toIntOrNull()?.takeIf { it in 0..67 }?.let(MessengerPlusTextCodec::paletteColor)
        else -> MessengerPlusTextCodec.colorValue(token)
    }
}

internal const val MESSENGER_PLUS_COLOR_WHEEL_TAG = "messenger_plus_color_wheel"
internal const val MESSENGER_PLUS_CUSTOM_COLOR_DIALOG_TAG = "messenger_plus_custom_color_dialog"
