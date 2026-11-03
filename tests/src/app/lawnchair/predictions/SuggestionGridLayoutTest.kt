/*
 * Copyright (C) 2026 Solina-Projects
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
