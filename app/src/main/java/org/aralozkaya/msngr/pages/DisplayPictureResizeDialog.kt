@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr.pages

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.aralozkaya.msngr.DisplayPictureSelection
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.formatLocalizedInt
import org.aralozkaya.msngr.numberSentenceResource
import org.aralozkaya.msngr.twoNumbersSentenceResource

@Composable
internal fun DisplayPictureResizeDialog(picture: DisplayPictureSelection, onResize: () -> Unit, onCancel: () -> Unit) {
    val dimensionsText = twoNumbersSentenceResource(
        R.string.display_picture_resize_dimensions_before_first_number,
        picture.width,
        R.string.display_picture_resize_dimensions_between_numbers,
        picture.height,
        R.string.display_picture_resize_dimensions_after_second_number,
    )
    val sizeKibText = numberSentenceResource(
        R.string.display_picture_resize_size_before_number,
        (picture.bytes.size + 1023) / 1024,
        R.string.display_picture_resize_size_after_number,
    )
    val kbLimitText = twoNumbersSentenceResource(
        R.string.display_picture_resize_kb_limit_before_first_number,
        200,
        R.string.display_picture_resize_kb_limit_between_numbers,
        formatLocalizedInt(200_000),
        R.string.display_picture_resize_kb_limit_after_second_number,
    )
    val mpLimitText = numberSentenceResource(
        R.string.display_picture_resize_mp_limit_before_number,
        1,
        R.string.display_picture_resize_mp_limit_after_number,
    )
    val promptText = stringResource(
        if (picture.animation != null) {
            R.string.display_picture_resize_prompt_animated
        } else {
            R.string.display_picture_resize_prompt_static
        },
    )
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.display_picture_resize_title)) },
        text = {
            Text(
                stringResource(
                    R.string.display_picture_resize_body_format,
                    dimensionsText,
                    sizeKibText,
                    kbLimitText,
                    mpLimitText,
                    promptText,
                ),
            )
        },
        confirmButton = {
            Button(shapes = ButtonDefaults.shapes(), onClick = onResize) {
                Text(stringResource(R.string.display_picture_resize_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
