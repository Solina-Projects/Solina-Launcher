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

package app.lawnchair.backup

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.launcher3.util.SandboxApplication
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LawnchairBackupSelectiveRestoreTest {
    @get:Rule
    val context = SandboxApplication()

    @Test
    fun wallpaperOnlyRestoreDoesNotDeleteOtherDatabaseFiles() = runBlocking {
        val unrelatedDatabase = context.getDatabasePath("backup_restore_safety_marker.db")
        unrelatedDatabase.parentFile?.mkdirs()
        unrelatedDatabase.writeText("must survive wallpaper-only restore")

        val backupFile = File(context.cacheDir, "wallpaper_only.lawnchairbackup")
        val info = app.lawnchair.LawnchairProto.BackupInfo.newBuilder()
            .setBackupVersion(1)
            .setContents(LawnchairBackup.INCLUDE_WALLPAPER)
            .setPreviewWidth(1)
            .setPreviewHeight(1)
            .build()
        val wallpaper = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            ZipOutputStream(FileOutputStream(backupFile)).use { zip ->
                zip.putNextEntry(ZipEntry(LawnchairBackup.INFO_FILE_NAME))
                info.writeTo(zip)
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(LawnchairBackup.WALLPAPER_FILE_NAME))
                check(wallpaper.compress(Bitmap.CompressFormat.PNG, 100, zip))
                zip.closeEntry()
            }
        } finally {
            wallpaper.recycle()
        }

        val backup = LawnchairBackup(context, Uri.fromFile(backupFile))
        backup.readInfoAndPreview()
        // A sandbox may not grant wallpaper-write permission; the invariant under test is that
        // unrelated database files remain untouched even if applying the wallpaper fails.
        runCatching { backup.restore(LawnchairBackup.INCLUDE_WALLPAPER) }

        assertTrue(
            "Wallpaper-only restore must not remove unrelated databases",
            unrelatedDatabase.exists(),
        )
        unrelatedDatabase.delete()
        backupFile.delete()
    }
    @Test
    fun archiveWithPathTraversalEntryIsRejected() = runBlocking {
        val backupFile = File(context.cacheDir, "path_traversal.lawnchairbackup")
        val info = app.lawnchair.LawnchairProto.BackupInfo.newBuilder()
            .setBackupVersion(1)
            .setContents(LawnchairBackup.INCLUDE_WALLPAPER)
            .setPreviewWidth(1)
            .setPreviewHeight(1)
            .build()
        ZipOutputStream(FileOutputStream(backupFile)).use { zip ->
            zip.putNextEntry(ZipEntry(LawnchairBackup.INFO_FILE_NAME))
            info.writeTo(zip)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("../shared_prefs/attacker.xml"))
            zip.write("must not be extracted".toByteArray())
            zip.closeEntry()
        }

        val backup = LawnchairBackup(context, Uri.fromFile(backupFile))
        var rejected = false
        try {
            backup.readInfoAndPreview()
        } catch (_: IllegalArgumentException) {
            rejected = true
        } finally {
            backupFile.delete()
        }
        assertTrue("Archive path traversal entry must be rejected", rejected)
    }

    @Test
    fun unsupportedBackupVersionIsRejected() = runBlocking {
        val backupFile = File(context.cacheDir, "unsupported_version.lawnchairbackup")
        val info = app.lawnchair.LawnchairProto.BackupInfo.newBuilder()
            .setBackupVersion(999)
            .setContents(LawnchairBackup.INCLUDE_WALLPAPER)
            .setPreviewWidth(1)
            .setPreviewHeight(1)
            .build()
        ZipOutputStream(FileOutputStream(backupFile)).use { zip ->
            zip.putNextEntry(ZipEntry(LawnchairBackup.INFO_FILE_NAME))
            info.writeTo(zip)
            zip.closeEntry()
        }

        val backup = LawnchairBackup(context, Uri.fromFile(backupFile))
        var rejected = false
        try {
            backup.readInfoAndPreview()
        } catch (_: IllegalArgumentException) {
            rejected = true
        } finally {
            backupFile.delete()
        }
        assertTrue("Unknown backup versions must be rejected", rejected)
    }

    @Test
    fun restoreRejectsMissingSelectedWallpaperPayload() = runBlocking {
        val backupFile = File(context.cacheDir, "missing_wallpaper.lawnchairbackup")
        val info = app.lawnchair.LawnchairProto.BackupInfo.newBuilder()
            .setBackupVersion(1)
            .setContents(LawnchairBackup.INCLUDE_WALLPAPER)
            .setPreviewWidth(1)
            .setPreviewHeight(1)
            .build()

        try {
            ZipOutputStream(FileOutputStream(backupFile)).use { zip ->
                zip.putNextEntry(ZipEntry(LawnchairBackup.INFO_FILE_NAME))
                info.writeTo(zip)
                zip.closeEntry()
            }

            val backup = LawnchairBackup(context, Uri.fromFile(backupFile))
            backup.readInfoAndPreview()

            var rejected = false
            try {
                backup.restore(LawnchairBackup.INCLUDE_WALLPAPER)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("Restore must reject a missing selected wallpaper payload", rejected)
        } finally {
            backupFile.delete()
        }
    }


}
