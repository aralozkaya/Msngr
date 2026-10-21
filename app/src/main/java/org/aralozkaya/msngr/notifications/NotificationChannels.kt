package org.aralozkaya.msngr.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import org.aralozkaya.msngr.R

object NotificationChannels {
    const val MESSAGES = "messages"
    const val NUDGES = "nudges"
    const val CONTACT_SIGNED_IN = "contact_signed_in"
    const val OFFLINE_MESSAGES = "offline_messages"
    const val CONTACT_REQUESTS = "contact_requests"
    const val GROUP_INVITATIONS = "group_invitations"
    const val BACKGROUND_CONNECTION = "background_connection"
    const val APP_UPDATES = "app_updates"

    val userConfigurable = listOf(MESSAGES, NUDGES, CONTACT_SIGNED_IN, OFFLINE_MESSAGES, CONTACT_REQUESTS, GROUP_INVITATIONS, APP_UPDATES)

    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    APP_UPDATES,
                    context.getString(R.string.update_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.update_notification_channel_description)
                    setShowBadge(true)
                },
                NotificationChannel(
                    MESSAGES,
                    context.getString(R.string.notification_channel_messages),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(
                        R.string.notification_channel_messages_description,
                    )
                },
                NotificationChannel(
                    NUDGES,
                    context.getString(R.string.notification_channel_nudges),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(
                        R.string.notification_channel_nudges_description,
                    )
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 120, 80, 160, 80, 220)
                },
                NotificationChannel(
                    CONTACT_SIGNED_IN,
                    context.getString(R.string.notification_channel_contact_signed_in),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(
                        R.string.notification_channel_contact_signed_in_description,
                    )
                },
                NotificationChannel(
                    OFFLINE_MESSAGES,
                    context.getString(R.string.notification_channel_offline_messages),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(
                        R.string.notification_channel_offline_messages_description,
                    )
                },
                NotificationChannel(
                    CONTACT_REQUESTS,
                    context.getString(R.string.notification_channel_contact_requests),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notification_channel_contact_requests_description)
                    setShowBadge(true)
                },
                NotificationChannel(
                    GROUP_INVITATIONS,
                    context.getString(R.string.group_invitations_title),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notification_channel_group_invitations_description)
                    setShowBadge(true)
                },
                NotificationChannel(
                    BACKGROUND_CONNECTION,
                    context.getString(R.string.notification_channel_background_connection),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(
                        R.string.notification_channel_background_connection_description,
                    )
                    setShowBadge(false)
                },
            ),
        )
        // Remove the replaced channel from Android settings on existing installations.
        manager.deleteNotificationChannel("friends_online")
    }
}
