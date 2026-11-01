/*
 * Copyright (C) 2026 The Solina Launcher Project
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

package com.android.launcher3.model

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.launcher3.LauncherSettings.Favorites._ID
import com.android.launcher3.celllayout.CellPosMapper
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.Executors
import com.android.launcher3.util.ModelTestExtensions.bgDataModel
import com.android.launcher3.util.ModelTestExtensions.loadModelSync
import com.android.launcher3.util.TestUtil.runOnExecutorSync
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tests atomic persistence behavior in [ModelWriter]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class ModelWriterTest : AbstractWorkspaceModelTest() {

    @Before
    override fun setup() {
        super.setup()
        model.loadModelSync()
    }

    @Test
    fun addItemsToDatabase_whenSecondInsertFails_rollsBackBatchAndResetsIds() {
        val initialDatabaseCount = countDatabaseItems()
        val items = listOf(getNewItem(), getNewItem())
        val attemptedIds = mutableListOf<Int>()
        var insertCount = 0

        val writer =
            object :
                ModelWriter(
                    mTargetContext,
                    model,
                    model.bgDataModel,
                    false,
                    CellPosMapper.DEFAULT,
                    null,
                ) {
                override fun insertItem(values: ContentValues): Int {
                    attemptedIds.add(values.getAsInteger(_ID)!!)
                    insertCount++
                    return if (insertCount == 2) {
                        -1
                    } else {
                        model.modelDbController.insert(values)
                    }
                }
            }

        runOnExecutorSync(Executors.MODEL_EXECUTOR) { writer.addItemsToDatabase(items) }

        assertThat(attemptedIds).hasSize(2)
        assertThat(items.map { it.id })
            .containsExactly(ItemInfo.NO_ID, ItemInfo.NO_ID)
        assertThat(countDatabaseItems()).isEqualTo(initialDatabaseCount)

        val dataModel = model.bgDataModel
        attemptedIds.forEach { id -> assertThat(dataModel.itemsIdMap.get(id)).isNull() }
    }

    @Test
    fun addItemsToDatabase_whenAllInsertsSucceed_persistsBatchAndUpdatesModel() {
        val initialDatabaseCount = countDatabaseItems()
        val items = listOf(getNewItem(), getNewItem())

        val writer =
            ModelWriter(
                mTargetContext,
                model,
                model.bgDataModel,
                false,
                CellPosMapper.DEFAULT,
                null,
            )

        runOnExecutorSync(Executors.MODEL_EXECUTOR) { writer.addItemsToDatabase(items) }

        assertThat(items.all { it.id != ItemInfo.NO_ID }).isTrue()
        assertThat(countDatabaseItems()).isEqualTo(initialDatabaseCount + items.size)

        val dataModel = model.bgDataModel
        items.forEach { item ->
            assertThat(dataModel.itemsIdMap.get(item.id)).isSameInstanceAs(item)
        }
    }

    @Test
    fun deleteItemsFromDatabase_whenSecondDeleteFails_rollsBackBatchAndKeepsModelItems() {
        val items = listOf(getNewItem(), getNewItem())
        val addWriter =
            ModelWriter(
                mTargetContext,
                model,
                model.bgDataModel,
                false,
                CellPosMapper.DEFAULT,
                null,
            )
        runOnExecutorSync(Executors.MODEL_EXECUTOR) { addWriter.addItemsToDatabase(items) }

        assertThat(items.all { it.id != ItemInfo.NO_ID }).isTrue()
        val initialDatabaseCount = countDatabaseItems()
        var deleteCount = 0
        val writer =
            object :
                ModelWriter(
                    mTargetContext,
                    model,
                    model.bgDataModel,
                    false,
                    CellPosMapper.DEFAULT,
                    null,
                ) {
                override fun deleteItem(itemId: Int): Int {
                    deleteCount++
                    if (deleteCount == 2) {
                        throw IllegalStateException("Simulated second delete failure")
                    }
                    return model.modelDbController.delete(
                        com.android.launcher3.provider.LauncherDbUtils.itemIdMatch(itemId),
                        null,
                    )
                }
            }

        writer.deleteItemsFromDatabase(items, "rollback test")
        runOnExecutorSync(Executors.MODEL_EXECUTOR) {}

        assertThat(deleteCount).isEqualTo(2)
        assertThat(countDatabaseItems()).isEqualTo(initialDatabaseCount)
        val dataModel = model.bgDataModel
        items.forEach { item ->
            assertThat(dataModel.itemsIdMap.get(item.id)).isSameInstanceAs(item)
        }
    }

    private fun countDatabaseItems(): Int {
        var count = 0
        runOnExecutorSync(Executors.MODEL_EXECUTOR) {
            model.modelDbController.query(arrayOf(_ID), null, null, null).use { cursor ->
                count = cursor.count
            }
        }
        return count
    }
}
