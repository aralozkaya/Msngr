package org.aralozkaya.msngr.pages

import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import org.aralozkaya.msngr.DisplayPictureSelection

@Composable
internal fun DisplayPictureResizeDialog(picture: DisplayPictureSelection, onResize: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Resize display picture?") },
        text = {
            Text("This picture is ${picture.width}×${picture.height} and ${(picture.bytes.size + 1023) / 1024} KiB. " +
                "Messenger pictures must be smaller than 200 KB (200,000 bytes). The app also limits pictures to 1 megapixel for playback. " +
                "Resize it to the largest size that fits, keeping its proportions" +
                (if (picture.animation != null) ", animation, and frame timing?" else "?"))
        },
        confirmButton = { Button(onClick = onResize) { Text("Resize and continue") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
