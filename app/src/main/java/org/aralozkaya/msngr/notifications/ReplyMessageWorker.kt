package org.aralozkaya.msngr.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

internal class ReplyMessageWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val reply = inputData.getString(KEY_REPLY_TEXT)?.trim()?.takeIf(String::isNotEmpty)
            ?: return Result.failure()
        return deliverNudgeOrReply(reply)
    }

    companion object {
        const val KEY_REPLY_TEXT = "reply_text"
        const val KEY_IS_GROUP = "is_group"
    }
}
