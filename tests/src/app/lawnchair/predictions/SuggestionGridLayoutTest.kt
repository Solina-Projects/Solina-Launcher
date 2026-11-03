package app.lawnchair.predictions

import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestionGridLayoutTest {

    @Test
    fun mostUsedFillsRowsInRankOrderAndDeduplicates() {
        assertEquals(
            listOf(listOf("a", "b", "c"), listOf("d")),
            SuggestionGridLayout.arrange(
                source = SuggestionGridLayout.Source.MOST_USED,
                dockRanked = emptyList(),
                usageRanked = listOf("a", "b", "a", "c", "d"),
                suggestionRows = 2,
                columns = 3,
                dockRows = 1,
            ),
        )
    }

    @Test
    fun oneRowDockUsesUsageRankingForAdditionalRows() {
        assertEquals(
            listOf(listOf("dock-a", "dock-b"), listOf("used-a", "used-b"), listOf("used-c")),
            SuggestionGridLayout.arrange(
                source = SuggestionGridLayout.Source.DOCK,
                dockRanked = listOf("dock-a", "dock-b"),
                usageRanked = listOf("dock-a", "used-a", "used-b", "used-c"),
                suggestionRows = 3,
                columns = 2,
                dockRows = 1,
            ),
        )
    }

    @Test
    fun multiRowDockFillsFromBottomWithoutReorderingWithinRows() {
        assertEquals(
            listOf(listOf("dock-f", "dock-g", "dock-h"), listOf("dock-a", "dock-b", "dock-c")),
            SuggestionGridLayout.arrange(
                source = SuggestionGridLayout.Source.DOCK,
                dockRanked = listOf("dock-a", "dock-b", "dock-c", "dock-f", "dock-g", "dock-h"),
                usageRanked = emptyList(),
                suggestionRows = 2,
                columns = 3,
                dockRows = 3,
            ),
        )
    }

    @Test
    fun usageFallbackNeverDuplicatesDockApps() {
        assertEquals(
            listOf(listOf("dock-a", "dock-b"), listOf("used-a", "used-b")),
            SuggestionGridLayout.arrange(
                source = SuggestionGridLayout.Source.DOCK,
                dockRanked = listOf("dock-a", "dock-b"),
                usageRanked = listOf("dock-a", "dock-b", "used-a", "used-b"),
                suggestionRows = 2,
                columns = 2,
                dockRows = 1,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidColumnCount() {
        SuggestionGridLayout.arrange(
            source = SuggestionGridLayout.Source.MOST_USED,
            dockRanked = emptyList<String>(),
            usageRanked = emptyList(),
            suggestionRows = 1,
            columns = 0,
            dockRows = 1,
        )
    }
}
