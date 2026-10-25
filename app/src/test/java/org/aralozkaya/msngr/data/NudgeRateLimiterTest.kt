package org.aralozkaya.msngr.data

import org.junit.Assert.*
import org.junit.Test

class NudgeRateLimiterTest {
    @Test fun oneImmediateNudgeThenExactlyElevenSecondsWithoutALifetimeCount() {
        var time = 0L
        val limiter = NudgeRateLimiter { time }
        repeat(20) {
            val send = limiter.reserveSend("PEER@example.com")
            // Even a slow connection must not permit a concurrent send.
            time += 30_000
            assertTrue(runCatching { limiter.reserveSend("1:peer@example.com;other-endpoint") }
                .exceptionOrNull() is NudgeCooldownException)
            limiter.finishSend(send, succeeded = true)
            time += 10_999
            val rejection = runCatching { limiter.reserveSend("peer@example.com") }.exceptionOrNull()
            assertEquals(1L, (rejection as NudgeCooldownException).remainingMillis)
            time++
        }
        limiter.reserveSend("peer@example.com")
        limiter.reserveSend("another@example.com")
    }

    @Test fun failedSendReleasesReservationAndSiblingEchoStillCounts() {
        var time = 100L
        val limiter = NudgeRateLimiter { time }
        val failed = limiter.reserveSend("peer")
        limiter.finishSend(failed, succeeded = false)
        val next = limiter.reserveSend("peer")
        limiter.observeOwnNudge("peer")
        limiter.finishSend(next, succeeded = false)
        assertTrue(runCatching { limiter.reserveSend("peer") }.isFailure)
        time += 11_000
        limiter.reserveSend("peer")
    }

    @Test fun sustainedFloodStaysSilentUntilAFullQuietGap() {
        var time = 0L
        val limiter = NudgeRateLimiter { time }
        assertTrue(limiter.allowReceivedAlert("chat", "PEER@example.com;endpoint-one"))
        repeat(100) {
            time += 10_999
            assertFalse(limiter.allowReceivedAlert("chat", "1:peer@example.com;endpoint-two"))
        }
        time += 11_000
        assertTrue(limiter.allowReceivedAlert("chat", "peer@example.com"))
    }

    @Test fun eachGroupSenderAndConversationHasAnIndependentIncomingAllowance() {
        val limiter = NudgeRateLimiter { 0L }
        assertTrue(limiter.allowReceivedAlert("group-one", "alice"))
        assertFalse(limiter.allowReceivedAlert("group-one", "alice"))
        assertTrue(limiter.allowReceivedAlert("group-one", "bob"))
        assertTrue(limiter.allowReceivedAlert("group-two", "alice"))
        assertTrue(limiter.allowReceivedAlert("alice", "alice"))
        // Sending and receiving have separate allowances.
        val outgoing = limiter.reserveSend("alice")
        limiter.finishSend(outgoing, succeeded = true)
        assertFalse(limiter.allowReceivedAlert("alice", "alice"))
    }

    @Test fun reconnectPreservesLimitsButAnAccountChangeInvalidatesOldReservations() {
        val limiter = NudgeRateLimiter { 0L }
        limiter.selectAccount("account-one")
        val old = limiter.reserveSend("peer")
        assertTrue(limiter.allowReceivedAlert("peer", "peer"))
        limiter.selectAccount("account-one")
        assertTrue(runCatching { limiter.reserveSend("peer") }.isFailure)
        assertFalse(limiter.allowReceivedAlert("peer", "peer"))
        limiter.selectAccount("account-two")
        val current = limiter.reserveSend("peer")
        limiter.finishSend(old, succeeded = true)
        assertEquals(Long.MAX_VALUE, limiter.cooldowns.value["peer"])
        limiter.finishSend(current, succeeded = true)
        assertEquals(11_000L, limiter.cooldowns.value["peer"])
        assertTrue(limiter.allowReceivedAlert("peer", "peer"))
    }
}
