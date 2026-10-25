package org.aralozkaya.msngr.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

internal fun nudgeMonotonicMillis(): Long = System.nanoTime() / 1_000_000L

internal class NudgeCooldownException(val remainingMillis: Long) : IllegalStateException(
    "You may not send a nudge that often. Please wait ${(remainingMillis + 999) / 1_000} seconds.",
)

/** WLM 2009 allows one nudge per conversation every 11 seconds. See docs/NUDGES.md. */
internal class NudgeRateLimiter(private val now: () -> Long = ::nudgeMonotonicMillis) {
    private val mutableCooldowns = MutableStateFlow<Map<String, Long>>(emptyMap())
    val cooldowns = mutableCooldowns.asStateFlow()
    private val pending = mutableMapOf<String, Reservation>()
    private val received = mutableMapOf<Pair<String, String>, Long>()
    private var accountId: String? = null

    class Reservation internal constructor(internal val conversationId: String) {
        internal var siblingDeadline: Long? = null
    }

    @Synchronized fun selectAccount(id: String) {
        if (accountId == id) return
        accountId = id
        pending.clear()
        received.clear()
        mutableCooldowns.value = emptyMap()
    }

    /** Reserve before suspending so concurrent composer/notification actions cannot race. */
    @Synchronized fun reserveSend(conversationId: String): Reservation {
        val id = normalize(conversationId)
        val timestamp = now()
        val deadline = mutableCooldowns.value[id] ?: Long.MIN_VALUE
        if (id in pending || deadline > timestamp) {
            throw NudgeCooldownException(if (id in pending) COOLDOWN_MILLIS else deadline - timestamp)
        }
        val reservation = Reservation(id)
        pending[id] = reservation
        mutableCooldowns.value = mutableCooldowns.value.filterValues { it > timestamp } +
            (id to Long.MAX_VALUE)
        return reservation
    }

    @Synchronized fun finishSend(reservation: Reservation, succeeded: Boolean) {
        val id = reservation.conversationId
        if (pending[id] !== reservation) return // An account change invalidates in-flight sends.
        pending.remove(id)
        val deadline = if (succeeded) now() + COOLDOWN_MILLIS else reservation.siblingDeadline
        mutableCooldowns.value = if (deadline != null && deadline > now())
            mutableCooldowns.value + (id to deadline) else mutableCooldowns.value - id
    }

    /** Sibling endpoints also sent a nudge, but their echoes must never produce incoming alerts. */
    @Synchronized fun observeOwnNudge(conversationId: String) {
        val id = normalize(conversationId)
        val deadline = now() + COOLDOWN_MILLIS
        val reservation = pending[id]
        if (reservation != null) reservation.siblingDeadline = deadline
        else mutableCooldowns.value = mutableCooldowns.value + (id to deadline)
    }

    /** Use local arrival time and remember rejected packets too: a flood stays silent. */
    @Synchronized fun allowReceivedAlert(conversationId: String, sender: String): Boolean {
        val timestamp = now()
        received.entries.removeAll { timestamp - it.value >= COOLDOWN_MILLIS }
        val previous = received.put(normalize(conversationId) to normalize(sender), timestamp)
        return previous == null
    }

    private fun normalize(value: String): String = value.trim().substringBefore(';')
        .removePrefix("1:").removePrefix("9:").lowercase(Locale.ROOT)

    companion object {
        const val COOLDOWN_MILLIS = 11_000L
    }
}
