package org.aralozkaya.msngr.updates

import androidx.core.content.FileProvider

/** APKs need a distinct component so Android does not reuse the display-picture provider. */
class UpdateFileProvider : FileProvider()
