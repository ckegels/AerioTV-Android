package com.aeriotv.android.feature.livetv

import com.aeriotv.android.core.data.ChannelCollection
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GH #81: the saved Live TV group must restore for synthetic groups too. */
class GroupTokensTest {

    private val groups = listOf("News", "Sports", "Movies")

    @Test
    fun favoritesTokenRestoresEvenThoughNoChannelCarriesThatGroupName() {
        assertEquals(
            PlaylistViewModel.FAVORITES_GROUP,
            restoredGroupToken(PlaylistViewModel.FAVORITES_GROUP, groups),
        )
    }

    @Test
    fun favoritesTokenRestoresBeforeChannelsHaveLoaded() {
        assertEquals(
            PlaylistViewModel.FAVORITES_GROUP,
            restoredGroupToken(PlaylistViewModel.FAVORITES_GROUP, emptyList()),
        )
    }

    @Test
    fun allAndCollectionTokensRestore() {
        assertEquals(PlaylistViewModel.ALL_GROUPS, restoredGroupToken(PlaylistViewModel.ALL_GROUPS, groups))
        val collection = ChannelCollection.token("abc")
        assertEquals(collection, restoredGroupToken(collection, groups))
    }

    @Test
    fun providerGroupRestoresInTheLiveSpelling() {
        assertEquals("Sports", restoredGroupToken("sports", groups))
    }

    @Test
    fun unknownProviderGroupDoesNotRestore() {
        assertNull(restoredGroupToken("Gone", groups))
    }

    @Test
    fun blankOrMissingTokenDoesNotRestore() {
        assertNull(restoredGroupToken(null, groups))
        assertNull(restoredGroupToken("   ", groups))
    }

    @Test
    fun syntheticTokensAreRecognized() {
        assertTrue(isSyntheticGroupToken(PlaylistViewModel.FAVORITES_GROUP))
        assertTrue(isSyntheticGroupToken(PlaylistViewModel.ALL_GROUPS))
        assertTrue(isSyntheticGroupToken(ChannelCollection.token("1")))
    }

    @Test
    fun favoritesSurvivesGroupTokenAssemblyWhenItIsInTheOrderedList() {
        val ordered = orderGroups(groups, GroupSortMode.Manual, listOf(PlaylistViewModel.FAVORITES_GROUP), hasFavorites = true)
        assertEquals(PlaylistViewModel.FAVORITES_GROUP, ordered.first())
        val tokens = groupTokens(ordered, emptySet())
        assertEquals(PlaylistViewModel.FAVORITES_GROUP, tokens.first())
    }

    // ---- GH #81: display names -------------------------------------------

    @Test
    fun syntheticTokensGetHumanLabels() {
        assertEquals("Favorites", groupDisplayName(PlaylistViewModel.FAVORITES_GROUP))
        assertEquals("All Channels", groupDisplayName(PlaylistViewModel.ALL_GROUPS))
    }

    @Test
    fun providerGroupNameIsItsOwnLabel() {
        assertEquals("Sports", groupDisplayName("Sports"))
    }

    @Test
    fun collectionTokenUsesTheCollectionName() {
        val collection = ChannelCollection(id = "abc", name = "Late Night")
        assertEquals(
            "Late Night",
            groupDisplayName(ChannelCollection.token("abc"), listOf(collection)),
        )
    }

    @Test
    fun unknownCollectionTokenNeverPrintsTheRawToken() {
        assertEquals("Collection", groupDisplayName(ChannelCollection.token("gone")))
    }

    // ---- GH #81: default group resolution ---------------------------------

    @Test
    fun defaultGroupWinsOverTheLastUsedGroup() {
        assertEquals("News", launchGroupToken("News", "Sports", groups))
    }

    @Test
    fun recentlyWatchedAppliesWhenNoDefaultIsSet() {
        assertEquals("Sports", launchGroupToken("", "Sports", groups))
        assertEquals("Sports", launchGroupToken(null, "Sports", groups))
    }

    @Test
    fun staleDefaultFallsBackToTheRecentlyWatchedGroup() {
        assertEquals("Sports", launchGroupToken("Gone", "Sports", groups))
    }

    /**
     * Logan 2026-09-14: with the Manage Groups "Recently Watched" toggle off
     * the last used group is never consulted, so a stored Recently Watched
     * default (the empty token) lands on the caller's All Channels fallback.
     */
    @Test
    fun recentlyWatchedDefaultFallsBackToAllWhenTheToggleIsOff() {
        assertNull(launchGroupToken("", "Sports", groups, recentlyWatchedEnabled = false))
        assertNull(launchGroupToken(null, "Sports", groups, recentlyWatchedEnabled = false))
        assertNull(launchGroupToken("Gone", "Sports", groups, recentlyWatchedEnabled = false))
        // An explicit default still wins with the toggle off.
        assertEquals("News", launchGroupToken("News", "Sports", groups, recentlyWatchedEnabled = false))
    }

    @Test
    fun nothingResolvesWhenBothTokensAreStaleOrEmpty() {
        assertNull(launchGroupToken("Gone", "AlsoGone", groups))
        assertNull(launchGroupToken("", "", groups))
    }

    @Test
    fun favoritesIsAValidDefaultGroup() {
        assertEquals(
            PlaylistViewModel.FAVORITES_GROUP,
            launchGroupToken(PlaylistViewModel.FAVORITES_GROUP, "Sports", groups),
        )
    }
}
