# Libsiren in Msngr

Upstream: https://github.com/kakaroto/libsiren
Revision: c7724c03d4a6daf4d6dff9e917c14ce5071dce66
Author: Youness Alaoui. License: LGPL 2.1 (see COPYING; source headers also permit later versions).

The complete codec sources are included and built into the separate shared library
`libmsngr_siren.so`. To rebuild or replace it, build this project's CMake target
with Android NDK 28.2.13676358. No proprietary Messenger DLL is distributed.

Local changes: allocation failure checks, frame bitstream bounds, rejecting
out-of-range envelope powers before table access, and clamping the noise table
index. The JNI bridge serializes codec access because upstream uses shared
scratch state. Input is restricted to 15 seconds of 16 kHz mono audio.
