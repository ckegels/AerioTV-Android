package com.aeriotv.android.feature.livetv

import com.aeriotv.android.feature.playlist.PlaylistViewModel

/**
 * GH #80: the "All Channels" pill can be hidden like any group. The hidden
 * set carries the [PlaylistViewModel.ALL_GROUPS] sentinel for it. It is kept
 * whenever nothing else would be left to pick, so the guide never empties.
 */
fun groupTokens(visibleGroups: List<String>, hiddenGroups: Set<String>): List<String> {
    // orderGroups now places the All token inside the list (any group can
    // sit above it in Manual order); keep that position when it is there.
    // The pinned Favorites token rides along wherever orderGroups placed it.
    val others = visibleGroups.filterNot { it == PlaylistViewModel.ALL_GROUPS }
    val allShown = PlaylistViewModel.ALL_GROUPS !in hiddenGroups || others.isEmpty()
    return if (PlaylistViewModel.ALL_GROUPS in visibleGroups) {
        if (allShown) visibleGroups else others
    } else {
        if (allShown) listOf(PlaylistViewModel.ALL_GROUPS) + others else others
    }
}

/** Where a reset lands: All when it is shown, else the first visible group. */
fun fallbackGroupToken(tokens: List<String>): String =
    tokens.firstOrNull() ?: PlaylistViewModel.ALL_GROUPS

/**
 * GH #81: is [token] a synthetic group rather than a provider group name?
 * Synthetic groups (All, Favorites, and the collection tokens) exist without a
 * matching `groupTitle` on any channel, so anything that validates a selection
 * against the channel list has to exempt them.
 */
fun isSyntheticGroupToken(token: String): Boolean =
    token == PlaylistViewModel.ALL_GROUPS ||
        token == PlaylistViewModel.FAVORITES_GROUP ||
        token.startsWith(com.aeriotv.android.core.data.ChannelCollection.TOKEN_PREFIX)

/**
 * GH #81: resolve a persisted Live TV group token against a playlist's live
 * group names.
 *
 * Returns the token to select, or null when nothing should be restored (so the
 * caller keeps its default of All). Synthetic tokens survive unconditionally:
 * Favorites is not a provider group, so matching it by name against the channel
 * list is exactly the bug that dropped it. Provider group names are matched
 * case-insensitively and returned in the live list's spelling, so a provider
 * that re-cases a group keeps the selection. When [knownGroupNames] is empty
 * the channels have not loaded yet and the token is accepted as-is; the
 * screens' own stranded-selection effect prunes it later if it never appears.
 */
fun restoredGroupToken(saved: String?, knownGroupNames: Collection<String>): String? {
    val token = saved?.trim().orEmpty()
    if (token.isEmpty()) return null
    if (isSyntheticGroupToken(token)) return token
    if (knownGroupNames.isEmpty()) return token
    return knownGroupNames.firstOrNull { it.equals(token, ignoreCase = true) }
}
