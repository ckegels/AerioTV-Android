package com.aeriotv.android.feature.player

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aeriotv.android.core.network.DispatchMoreGuides
import com.aeriotv.android.core.network.DispatcharrClient
import java.text.DateFormat
import java.util.Date

/** What the list is about: the stream being watched, and its channel's name. */
internal data class GuideChoiceRequest(val streamUrl: String, val channelName: String)

private sealed interface GuideListState {
    data object Loading : GuideListState
    data class Shown(val choices: DispatcharrClient.GuideChoices) : GuideListState
    data class Failed(val message: String) : GuideListState
}

/**
 * "Wrong guide? Choose another" (Dispatch More, contract 7a): what the
 * channel's guide says now, then the guides it could be on, each with the
 * programme on it now in large type -- that is what gets compared with the
 * picture. One press on a row chooses it and closes the list; the change is
 * made in the background and a short notice says how it went. Back closes
 * without changing anything.
 *
 * When the server says it is still reading guides nobody had read, the list
 * is asked for again every [READING_AGAIN_MS] while it says so, at most
 * [READING_ASKS] times; rows that arrive are added without moving the focus.
 */
@Composable
internal fun GuideChoiceDialog(
    request: GuideChoiceRequest,
    guides: DispatchMoreGuides,
    onDismiss: () -> Unit,
) {
    var state by remember { mutableStateOf<GuideListState>(GuideListState.Loading) }
    var askedAgain by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val firstRow = remember { FocusRequester() }
    val closeButton = remember { FocusRequester() }

    LaunchedEffect(request) {
        state = guides.list(request.streamUrl).toState()
        var asks = 0
        while ((state as? GuideListState.Shown)?.choices?.reading == true && asks < READING_ASKS) {
            kotlinx.coroutines.delay(READING_AGAIN_MS)
            asks++
            val again = guides.list(request.streamUrl).toState()
            // Only a list that arrived replaces the one on screen
            if (again is GuideListState.Shown) state = again
        }
        askedAgain = true
        (state as? GuideListState.Shown)?.let { state = it.copy(choices = it.choices.copy(reading = false)) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(min = 420.dp, max = 640.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Wrong guide? Choose another", style = MaterialTheme.typography.titleLarge)
                when (val s = state) {
                    GuideListState.Loading -> {
                        Text("Looking for the guides ${request.channelName} could be on…")
                        CircularProgressIndicator(Modifier.padding(8.dp))
                    }
                    is GuideListState.Failed -> Text(s.message)
                    is GuideListState.Shown -> GuideList(s.choices, askedAgain, firstRow) { option ->
                        guides.chooseInBackground(request.streamUrl, option)
                        onDismiss()
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(closeButton)) {
                    Text("Close")
                }
            }
        }
    }

    // D-pad focus: the first guide when there is one, otherwise Close. Once a
    // row has it, a list that grows while guides are read leaves it where it is.
    LaunchedEffect(state) {
        if (state is GuideListState.Loading || focused) return@LaunchedEffect
        // The dialog's window composes a moment after this effect starts
        kotlinx.coroutines.delay(100)
        val hasRows = (state as? GuideListState.Shown)?.choices?.guides?.isNotEmpty() == true
        runCatching {
            if (hasRows) firstRow.requestFocus().also { focused = true } else closeButton.requestFocus()
        }
    }
}

@Composable
private fun GuideList(
    choices: DispatcharrClient.GuideChoices,
    askedAgain: Boolean,
    firstRow: FocusRequester,
    onChoose: (DispatcharrClient.GuideOption) -> Unit,
) {
    val current = choices.current
    Text(
        when {
            current == null -> "Guide now: none"
            current.now == null -> "Guide now: ${current.name} — no information"
            else -> "Guide now: ${current.name} — ${current.now.title} (${span(current.now)})"
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val stillReading = choices.reading && !askedAgain
    if (choices.guides.isEmpty()) {
        if (!stillReading) Text("No other guide has anything on for this channel right now.")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(choices.guides, key = { it.epgId }) { option ->
                GuideRow(
                    option = option,
                    modifier = if (option == choices.guides.first()) Modifier.focusRequester(firstRow) else Modifier,
                    onClick = { onChoose(option) },
                )
            }
        }
    }
    if (stillReading) {
        Text("Looking for more guides…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GuideRow(option: DispatcharrClient.GuideOption, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            // What is on now, large: this is what is compared with the picture
            Text(
                option.now?.title.orEmpty(),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    option.name.ifBlank { null },
                    option.source.ifBlank { null },
                    option.now?.endMs?.let { "until ${time(it)}" },
                ).joinToString(" · "),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            option.next?.let { next ->
                Text(
                    "Next: ${next.title}" + (next.startMs?.let { " (${time(it)})" } ?: ""),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun DispatcharrClient.GuideAnswer.toState(): GuideListState = when (this) {
    is DispatcharrClient.GuideAnswer.Listed -> GuideListState.Shown(choices)
    is DispatcharrClient.GuideAnswer.Refused -> GuideListState.Failed(message)
    is DispatcharrClient.GuideAnswer.Chosen -> GuideListState.Failed("Unexpected answer")
}

private fun span(programme: DispatcharrClient.GuideProgramme): String =
    listOfNotNull(programme.startMs?.let(::time), programme.endMs?.let(::time)).joinToString("–")

private fun time(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/** The server was still reading guides: ask again after this long, at most this often. */
private const val READING_AGAIN_MS = 5_000L
private const val READING_ASKS = 12
