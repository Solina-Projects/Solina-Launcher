/*
 * Copyright 2026, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.search.algorithms

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import app.lawnchair.ui.preferences.components.HiddenAppsInSearch
import com.android.launcher3.model.data.AppInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SearchUtilsTest {

    @Test
    fun latinAccentsAreFoldedForSearch() {
        assertThat(normalizeSearchText("CAFÉ")).isEqualTo("cafe")
        assertThat(normalizeSearchText("Cafe\u0301")).isEqualTo("cafe")
        assertThat(normalizeSearchText("İSTANBUL")).isEqualTo("istanbul")
    }

    @Test
    fun nonLatinCombiningMarksArePreserved() {
        val arabic = "مُحَمَّد"
        val devanagari = "कि"

        assertThat(normalizeSearchText(arabic)).isEqualTo(arabic)
        assertThat(normalizeSearchText(devanagari)).isEqualTo(devanagari)
    }

    @Test
    fun normalizationUsesRootLocaleAndTrimsWhitespace() {
        assertThat(normalizeSearchText("  I  ")).isEqualTo("i")
        assertThat(normalizeSearchText("  SOLINA  ")).isEqualTo("solina")
    }

    @Test
    fun emptyAndWhitespaceQueriesNormalizeToEmpty() {
        assertThat(normalizeSearchText("")).isEmpty()
        assertThat(normalizeSearchText("   ")).isEmpty()
    }

    @Test
    fun hiddenAppsAreExcludedWhenSearchPreferenceIsOff() {
        val hiddenApp = appInfo("com.example.hidden", "Hidden App")
        val visibleApp = appInfo("com.example.visible", "Visible App")

        val results = sequenceOf(hiddenApp, visibleApp)
            .filterHiddenApps("hidden", setOf(hiddenApp.toComponentKey().toString()), HiddenAppsInSearch.NEVER)
            .toList()

        assertThat(results).containsExactly(visibleApp)
    }

    @Test
    fun hiddenAppsCanBeShownWhenTheirNameIsTyped() {
        val hiddenApp = appInfo("com.example.hidden", "Café Notes")
        val visibleApp = appInfo("com.example.visible", "Visible App")
        val hiddenKeys = setOf(hiddenApp.toComponentKey().toString())

        val exactNameResults = sequenceOf(hiddenApp, visibleApp)
            .filterHiddenApps("cafe notes", hiddenKeys, HiddenAppsInSearch.IF_NAME_TYPED)
            .toList()
        val partialNameResults = sequenceOf(hiddenApp, visibleApp)
            .filterHiddenApps("cafe", hiddenKeys, HiddenAppsInSearch.IF_NAME_TYPED)
            .toList()

        assertThat(exactNameResults).containsExactly(hiddenApp, visibleApp)
        assertThat(partialNameResults).containsExactly(visibleApp)
    }

    @Test
    fun hiddenAppsAreAlwaysIncludedWhenPreferenceIsOn() {
        val hiddenApp = appInfo("com.example.hidden", "Hidden App")
        val hiddenKeys = setOf(hiddenApp.toComponentKey().toString())

        val results = sequenceOf(hiddenApp)
            .filterHiddenApps("unrelated query", hiddenKeys, HiddenAppsInSearch.ALWAYS)
            .toList()

        assertThat(results).containsExactly(hiddenApp)
    }

    private fun appInfo(packageName: String, title: String) = AppInfo(
        ComponentName(packageName, "$packageName.MainActivity"),
        title,
        Process.myUserHandle(),
        Intent(),
    )
}
