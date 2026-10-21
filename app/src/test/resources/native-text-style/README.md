# WLM 2009 native-format frames

Captured 2026-10-21 between two local Windows Live Messenger sessions. Files preserve
CRLF line endings and the complete declared MIME payload. The incoming fixture's sender
address/display name are replaced with `peer@example.test` / `Peer`; the outgoing fixtures
contain only synthetic probe text. `wlm-bold.txt` and `wlm-bisu-orange.txt` reproduce the
font dialog's bold-only and combined bold/italic/strikeout/underline/orange-color requests.

The `wlm-font-*.txt` fixtures capture additional Font Style choices. Light and Semibold
use their corresponding Segoe UI face names with an empty `EF`; Black Italic uses the
Segoe UI Black face with `EF=BI`.

The size in the probe body is a label, not a protocol field. A second combined-style
message at size 10 emitted the identical format header as size 18. See
`docs/NATIVE_TEXT_STYLING.md` for capture interpretation and the local size behavior.
