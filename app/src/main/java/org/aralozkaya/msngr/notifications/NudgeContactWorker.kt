package org.aralozkaya.msngr.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

internal class NudgeContactWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = deliverNudgeOrReply(reply = null)

    companion object {
        const val KEY_CHANNEL_ID = "channel_id"
    }
}
