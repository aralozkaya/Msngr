# Emotion sound fixtures

`wlm-*.txt` are complete ordinary MSNP text frames captured between two locally installed
Windows Live Messenger 2009 sessions with Messenger Plus!. Incoming sender addresses and display
names have been replaced with test identifiers; no authentication frames are included.

- `hello`: native `/shello`, including binary orange Plus! text.
- `styled`: `/shello [b][c=#FF6600]Styled sound[/c][/b]` received by the peer.
- `custom`: a native custom-sound announcement with its original NBSP separators and padding.

`hello.mp3` is the original built-in Hello sound (code 35), from the installed Plus! download
cache (`BuiltIn35.dat`), also verified against the live Escargot builtin endpoint. A copy in
Android test assets permits actual offline playback verification. Original Messenger Plus!
content belongs to its respective owners and is retained here as a compatibility test fixture.

Protocol research and service details: [EMOTION_SOUNDS.md](../../../../../docs/EMOTION_SOUNDS.md).
