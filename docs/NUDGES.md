# Nudge cooldown and incoming flood protection

Msngr follows the installed Windows Live Messenger 2009 client's sending rule:
one immediate nudge per conversation, followed by an **11-second cooldown**.
There is no separate lifetime or unanswered-nudge count: another nudge is allowed
after each cooldown. Text messages and incoming nudges do not reset the outgoing
cooldown. Other conversations have independent cooldowns.

## Reference investigation

Inspected the local `msnmsgr.exe`, file/product version **14.0.8117.0416**,
SHA-256 `59867c3cd9b1a8a4d241b2584a0f4b532be44100b6ac55ae28b7310c4695d0f9`.
This is the installed Escargot-patched client; these findings describe this build.
No live nudges were sent to contacts during this investigation.

The executable's nudge command handler at virtual address `0x56472e` permits
sending only in conversation state 1. Its blocked path loads resource 696,
"You may not send a nudge that often." The nudge callback at `0x568931` starts
timer `0x190` with interval `0x2af8` (**11,000 ms**) for an outgoing nudge and
marks the outgoing cooldown active. The timer handler at `0x568b68` restores
state 1 and clears the cooldown flag. The separate animation timer finishes
after approximately one second, but does not restore sending while that flag
is set. The constructor initializes sending as available. There is no
consecutive-send counter in these paths: the maximum immediate burst is one.

## App behavior

- The live and mock clients enforce the cooldown before sending. A reservation
  prevents concurrent composer and notification actions from both sending.
- The cooldown starts after a successful transport write; a failed or cancelled
  attempt releases its reservation. Cooldown rejection is a local result and
  does not discard the switchboard or report a connection failure.
- The composer disables its nudge button and shows the remaining wait.
  Notification actions use the same check and do not retry cooldown rejections.
- Nudges from the account's other endpoints update the outgoing cooldown
  without creating an incoming alert.
- Incoming private and group nudges always append their usual chat text and
  update unread state. An independent receiving check decides whether to emit
  an alert event, which feeds both Android notifications and in-app banners
  and vibrations.
- Receiving limits apply to each sender in each conversation, combining the
  sender's endpoints and case variants. A group member cannot consume another
  member's allowance.
- Receiving uses a local monotonic clock, never sender-provided timestamps.
  Every arrival advances the receiving timer, including suppressed arrivals.
  Continuous flooding therefore stays silent after its first nudge; an alert
  becomes eligible again after **11 seconds without a nudge from that sender**.
  Ordinary text and typing cannot reset this check.
- Limits survive same-account reconnects and history deletion in the running
  client. Switching account/service resets them. They are in-memory arrival
  limits; a new app process starts with a fresh allowance.

The receive-side quiet-gap rule is an intentional additional protection against
patched senders. It does not claim to identify which remote client was modified.
