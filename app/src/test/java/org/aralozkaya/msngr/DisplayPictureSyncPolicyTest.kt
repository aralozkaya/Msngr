package org.aralozkaya.msngr

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayPictureSyncPolicyTest {
    private val old = byteArrayOf(1, 2, 3)
    private val new = byteArrayOf(4, 5, 6)

    @Test fun firstKnownSignInAlwaysUsesIncomingRegardlessOfPolicy() {
        for (behavior in DisplayPictureSyncBehavior.entries) for (bytes in listOf(null, new)) {
            assertEquals(DisplayPictureSyncDecision.Incoming,
                displayPictureSyncDecision(false, null, IncomingDisplayPicture(bytes, true), behavior))
        }
    }

    @Test fun identicalBytesAndIdenticalRemovalsNeverAskOrUploadLocal() {
        for (behavior in DisplayPictureSyncBehavior.entries) for (bytes in listOf(null, old)) {
            assertEquals(DisplayPictureSyncDecision.Incoming,
                displayPictureSyncDecision(true, bytes, IncomingDisplayPicture(bytes?.copyOf(), true), behavior))
        }
    }

    @Test fun differingBytesFollowAllThreePoliciesIncludingAdditionsAndRemovals() {
        for ((local, incoming) in listOf(old to new, old to null, null to new)) {
            for ((behavior, decision) in listOf(
                DisplayPictureSyncBehavior.AlwaysIncoming to DisplayPictureSyncDecision.Incoming,
                DisplayPictureSyncBehavior.AlwaysLocal to DisplayPictureSyncDecision.Local,
                DisplayPictureSyncBehavior.AskEveryTime to DisplayPictureSyncDecision.Ask,
            )) assertEquals(decision, displayPictureSyncDecision(true, local, IncomingDisplayPicture(incoming, true), behavior))
        }
    }

    @Test fun failedFetchIsNeverTreatedAsRemovalOrAFirstSignInSnapshot() {
        for (behavior in DisplayPictureSyncBehavior.entries) for (initialized in listOf(true, false)) {
            assertEquals(DisplayPictureSyncDecision.Unavailable,
                displayPictureSyncDecision(initialized, old, IncomingDisplayPicture(), behavior))
        }
    }
}
