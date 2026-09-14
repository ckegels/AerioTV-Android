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
}
