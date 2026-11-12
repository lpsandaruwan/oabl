# box-trace

`oabltrace` is an LD_PRELOAD shim that logs Suding's Android Auto daemon on the AI box (`sdAutoReverse`,
32-bit ARM): its 14 calls into `libSdAutoReverse.so`, `startRecordScreen` in `libscreenrecord.so`, the
tinyalsa audio calls, and every callback the protocol library makes back into the daemon. Each wrapper
forwards 12 raw words, so unknown signatures pass through unchanged.

```
./build.sh                        # needs the NDK (27.1.12297006 by default)
./box-trace.sh start [serial]     # stops sd-reverse, runs the daemon with the shim (box must be in AA mode)
./box-trace.sh log   [serial]     # pulls oabl-trace.log
./box-trace.sh stop  [serial]     # back to the stock service (a reboot also restores it)
```

Log lines: `> name args` / `< name ret duration`; `sum` lines are one-second summaries of hot calls
(video and audio frames, pcm read/write); `SLOW` marks calls over 8 ms; `IDR #n nals=… gap=…ms` marks
H.264 keyframes in pushed video. `OABL_TRACE_NOCB=1` leaves the callback struct alone.

First idle-box run (12 November, no head unit): the daemon sets `SD_AUTO_BT_MAC` and
`SD_AUTO_BT_PAIRING_METHOD` through `sd_cfg_set_config`, registers 14 callbacks (struct words 1-11 and
13-15; daemon code offsets 0x7c5c-0x8414), calls `server_init` (4 ms) and `server_wait`. A session is
needed for the video and audio calls.

## At boot (12 November)

`/system/lib/liboabltrace.so` is loaded by `/system/bin/sd-reverse.sh` while `persist.oabl.shim` is 1; see
`docs/device-backups/2026-11-12-boot-tracer/`. After updating the shim, push it to `/system/lib/` (after
`adb remount`) and `setprop ctl.restart sd-reverse`. `box-trace.sh start` still works for hand runs, but it
stops the boot-launched daemon first.
