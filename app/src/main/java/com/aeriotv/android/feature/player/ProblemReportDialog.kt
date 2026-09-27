package com.aeriotv.android.feature.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.network.DispatchMoreReports
import com.aeriotv.android.core.network.DispatcharrClient
import kotlinx.coroutines.launch

/**
 * A report as it was when the user asked for it: the moment, the stream and
 * the player's state are read at the press, not after they typed their line,
 * so the report describes what went wrong rather than the menu.
 */
internal data class ProblemReportRequest(
    val atMs: Long,
    val streamUrl: String,
    val channelName: String,
    val player: Map<String, Any?>,
    val extra: Map<String, Any?>,
)

/**
 * "Send a report to the server" (Dispatch More): one optional line, then the
 * server's answer -- the report's id, or why it was not taken. Nothing is
 * retried; the user can send again.
 */
@Composable
internal fun ProblemReportDialog(
    request: ProblemReportRequest,
    reports: DispatchMoreReports,
    onDismiss: () -> Unit,
) {
    var what by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<DispatcharrClient.ReportAnswer?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Send a report to the server") },
        text = {
            Column {
                when (val a = answer) {
                    is DispatcharrClient.ReportAnswer.Sent ->
                        Text("Sent. The server keeps it as report ${a.id}, with what it knew about ${request.channelName} at that moment.")
                    is DispatcharrClient.ReportAnswer.Refused ->
                        Text("Not sent: ${a.message}")
                    null -> {
                        Text("About ${request.channelName}. The player's state and its log go with it.")
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = what,
                            onValueChange = { what = it.take(400) },
                            label = { Text("What went wrong? (optional)") },
                            singleLine = true,
                            enabled = !sending,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (answer == null) {
                TextButton(
                    enabled = !sending,
                    onClick = {
                        sending = true
                        scope.launch {
                            answer = reports.send(
                                what = what,
                                happenedAtMs = request.atMs,
                                streamUrl = request.streamUrl,
                                player = request.player,
                                extra = request.extra,
                            )
                            sending = false
                        }
                    },
                ) { Text(if (sending) "Sending…" else "Send") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (answer == null) {
                TextButton(enabled = !sending, onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
