package com.aeriotv.android.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.network.DispatchMoreReports
import com.aeriotv.android.core.network.DispatcharrClient
import kotlinx.coroutines.launch

/**
 * A report as it was when the user asked for it: the moment, the stream and
 * the player's state are read at the press, not after they chose what went
 * wrong, so the report describes the problem rather than the menu.
 */
internal data class ProblemReportRequest(
    val atMs: Long,
    val streamUrl: String,
    val channelName: String,
    val player: Map<String, Any?>,
    val extra: Map<String, Any?>,
)

/** What went wrong, as a choice: a remote has no good way to type. The code
 *  goes to the server with the words ("problem" in the report's extra). */
internal enum class ProblemKind(val code: String, val label: String) {
    NO_LOAD("no_load", "Stream doesn't load"),
    STUTTER("stutter", "Picture stutters or freezes"),
    SOUND("sound", "Sound problem"),
    GUIDE("guide", "Wrong or missing guide"),
    WRONG_CHANNEL("wrong_channel", "Wrong channel or picture"),
    OTHER("other", "Something else"),
}

/**
 * "Send a report to the server" (Dispatch More): one press on what went
 * wrong sends it, then the server's answer -- the report's id, or why it was
 * not taken. Nothing is retried; the user can send again.
 */
@Composable
internal fun ProblemReportDialog(
    request: ProblemReportRequest,
    reports: DispatchMoreReports,
    onDismiss: () -> Unit,
) {
    var sending by remember { mutableStateOf<ProblemKind?>(null) }
    var answer by remember { mutableStateOf<DispatcharrClient.ReportAnswer?>(null) }
    val scope = rememberCoroutineScope()
    val firstChoice = remember { FocusRequester() }
    val closeButton = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = { if (sending == null) onDismiss() },
        title = { Text("What went wrong?") },
        text = {
            when (val a = answer) {
                is DispatcharrClient.ReportAnswer.Sent ->
                    Text("Sent. The server keeps it as report ${a.id}, with what it knew about ${request.channelName} at that moment.")
                is DispatcharrClient.ReportAnswer.Refused ->
                    Text("Not sent: ${a.message}")
                null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("About ${request.channelName}. The player's state and its log go with it.")
                    ProblemKind.entries.forEachIndexed { index, kind ->
                        OutlinedButton(
                            enabled = sending == null,
                            onClick = {
                                sending = kind
                                scope.launch {
                                    answer = reports.send(
                                        what = kind.label,
                                        happenedAtMs = request.atMs,
                                        streamUrl = request.streamUrl,
                                        player = request.player,
                                        extra = request.extra + ("problem" to kind.code),
                                    )
                                    sending = null
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(firstChoice) else Modifier),
                        ) {
                            Text(if (sending == kind) "Sending…" else kind.label)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (answer != null) {
                TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(closeButton)) { Text("Close") }
            }
        },
        dismissButton = {
            if (answer == null) {
                TextButton(enabled = sending == null, onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
    // D-pad focus: on the first choice when the dialog opens, on Close once answered.
    LaunchedEffect(answer) {
        // The dialog's window composes a moment after this effect starts.
        kotlinx.coroutines.delay(100)
        runCatching { if (answer == null) firstChoice.requestFocus() else closeButton.requestFocus() }
    }
}
