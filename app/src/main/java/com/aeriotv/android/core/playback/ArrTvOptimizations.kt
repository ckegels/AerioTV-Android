package com.aeriotv.android.core.playback

/**
 * The switches for arrTV's own playback and server optimizations (Settings >
 * General > arrTV optimizations). Each one on by default; switched off, the
 * part it guards does nothing and the app behaves as it did before it existed,
 * so any of them can be ruled out when something goes wrong.
 *
 * Plain volatile flags: read on hot paths (every request, every watchdog
 * tick), written by the collectors in AerioTVApplication from AppPreferences.
 */
object ArrTvOptimizations {
    /** Tell a Dispatch More server which device this is: the device headers,
     *  the channel being left, the Multiview session. Off: no header at all,
     *  and nothing below that needs one (decode limit, stutter report). */
    @Volatile var identifyDevice = true

    /** Send the tallest picture this device decodes (X-Dispatch-Max-Video),
     *  so the server never starts it on a stream it cannot play. */
    @Volatile var sendMaxVideo = true

    /** Reopen the stream when the picture froze after its first frame while
     *  data kept arriving (a stream swap inside the connection). */
    @Volatile var freezeRescue = true

    /** Short waits before trying a channel's next stream (IPTV answers in
     *  about a second); off: the long waits sized for antenna tuners. */
    @Volatile var fastFailover = true

    /** Walk to the next stream when data arrives and nothing becomes
     *  playable (a stream this device cannot decode). */
    @Volatile var skipUnplayable = true

    /** Tell the server the moment the picture stalls, so it can move the
     *  channel to its next stream (server switch "Change stream when arrTV
     *  stutters"). */
    @Volatile var reportStalls = true
}

/**
 * The rows of Settings > General > arrTV optimizations, in order: the stored
 * key (default on) and what switching it sets. Adding an optimization is one
 * entry here plus the flag above.
 */
enum class ArrTvOptimization(
    val prefKey: String,
    val title: String,
    val subtitle: String,
    val apply: (Boolean) -> Unit,
) {
    IDENTIFY_DEVICE(
        "arrtv_opt_identify_device",
        "Tell Dispatch More which TV this is",
        "Device id and name, the channel being left, the Multiview session. Off: nothing below that needs it works.",
        { ArrTvOptimizations.identifyDevice = it },
    ),
    SEND_MAX_VIDEO(
        "arrtv_opt_send_max_video",
        "Only start streams this TV can play",
        "Tells the server the tallest picture this TV decodes, so it never starts it on a 4K stream it cannot play.",
        { ArrTvOptimizations.sendMaxVideo = it },
    ),
    FAST_FAILOVER(
        "arrtv_opt_fast_failover",
        "Faster switch to the next stream",
        "Waits about 6 s instead of 28 s for a stream that sends nothing, and 6 s instead of 15 s for one it cannot play.",
        { ArrTvOptimizations.fastFailover = it },
    ),
    SKIP_UNPLAYABLE(
        "arrtv_opt_skip_unplayable",
        "Skip streams this TV cannot play",
        "When data arrives but no picture comes, asks the server for the channel's next stream.",
        { ArrTvOptimizations.skipUnplayable = it },
    ),
    FREEZE_RESCUE(
        "arrtv_opt_freeze_rescue",
        "Reopen a frozen stream",
        "When the picture stops after it started while data keeps arriving, opens the channel again on a new connection.",
        { ArrTvOptimizations.freezeRescue = it },
    ),
    REPORT_STALLS(
        "arrtv_opt_report_stalls",
        "Tell the server when the picture stutters",
        "So Dispatch More can move the channel to another stream at once (its own switch has to be on too).",
        { ArrTvOptimizations.reportStalls = it },
    ),
}
