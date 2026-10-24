package org.aralozkaya.msngr.notifications

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aralozkaya.msngr.ChatActivity

internal class AppVisibilityTracker(
    private val onForegroundChanged: (Boolean) -> Unit = {},
) : Application.ActivityLifecycleCallbacks {
    private var startedActivities = 0
    private var changingConfigurations = 0
    private val mutableForeground = MutableStateFlow(false)
    val foreground = mutableForeground.asStateFlow()
    @Volatile var activeConversationId: String? = null
        private set

    val isForeground: Boolean
        @Synchronized get() = startedActivities > 0

    override fun onActivityStarted(activity: Activity) {
        val becameForeground = synchronized(this) {
            val wasForeground = startedActivities > 0
            if (changingConfigurations > 0) changingConfigurations-- else startedActivities++
            !wasForeground && startedActivities > 0
        }
        if (becameForeground) {
            mutableForeground.value = true
            onForegroundChanged(true)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        val becameBackground = synchronized(this) {
            val wasForeground = startedActivities > 0
            if (activity.isChangingConfigurations) changingConfigurations++ else startedActivities--
            if (startedActivities < 0) startedActivities = 0
            wasForeground && startedActivities == 0
        }
        if (becameBackground) {
            mutableForeground.value = false
            onForegroundChanged(false)
        }
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) {
        activeConversationId = (activity as? ChatActivity)?.notificationConversationId
    }
    override fun onActivityPaused(activity: Activity) { activeConversationId = null }
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
