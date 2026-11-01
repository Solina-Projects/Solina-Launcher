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
import java.util.zip.CRC32
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

    @Test
    fun restoreRejectsInvalidLauncherDatabaseWithoutReplacingRestoreTarget() = runBlocking {
        val backupFile = File(context.cacheDir, "invalid_launcher_database.lawnchairbackup")
        val restoreTarget = context.getDatabasePath(LawnchairBackup.RESTORED_DB_FILE_NAME)
        restoreTarget.parentFile?.mkdirs()
        restoreTarget.writeText("pre-existing restore target must survive")

        val info = app.lawnchair.LawnchairProto.BackupInfo.newBuilder()
            .setBackupVersion(1)
            .setContents(LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS)
            .setPreviewWidth(1)
            .setPreviewHeight(1)
            .build()

        try {
            ZipOutputStream(FileOutputStream(backupFile)).use { zip ->
                zip.putNextEntry(ZipEntry(LawnchairBackup.INFO_FILE_NAME))
                info.writeTo(zip)
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(LawnchairBackup.LAUNCHER_DB_FILE_NAME))
                zip.write("this is not a SQLite database".toByteArray())
                zip.closeEntry()
            }

            val backup = LawnchairBackup(context, Uri.fromFile(backupFile))
            backup.readInfoAndPreview()
            val result = runCatching {
                backup.restore(LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS)
            }

            assertTrue("Restore must reject an invalid launcher database", result.isFailure)
            assertTrue(
                "Invalid database must not replace the existing restore target",
                restoreTarget.readText() == "pre-existing restore target must survive",
            )
        } finally {
            backupFile.delete()
            restoreTarget.delete()
        }
    }

    @Test
    fun restoreUsesSameArchiveSnapshotThatWasValidatedForPreview() = runBlocking {
        val backupFile = File(context.cacheDir, "archive_snapshot_toctou.lawnchairbackup")
        val info = app.lawnchair.LawnchairProto.BackupInfo.newBuilder()
            .setBackupVersion(1)
            .setContents(LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS)
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

            // Replace the provider-backed source after preview validation. Restore must continue
            // using the private snapshot and reject its missing database entry.
            ZipOutputStream(FileOutputStream(backupFile)).use { zip ->
                zip.putNextEntry(ZipEntry(LawnchairBackup.INFO_FILE_NAME))
                info.writeTo(zip)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(LawnchairBackup.LAUNCHER_DB_FILE_NAME))
                zip.write("replacement archive database".toByteArray())
                zip.closeEntry()
            }

            val failure = runCatching {
                backup.restore(LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS)
            }.exceptionOrNull()
            assertTrue(
                "Restore must validate and extract from the same archive snapshot",
                failure?.message?.contains("Backup is missing required entries") == true,
            )
        } finally {
            backupFile.delete()
        }
    }

    @Test
    fun oversizedImageDimensionsAreRejectedBeforeBitmapDecode() = runBlocking {
        val backupFile = File(context.cacheDir, "oversized_image_dimensions.lawnchairbackup")
        val info = app.lawnchair.LawnchairProto.BackupInfo.newBuilder()
            .setBackupVersion(1)
            .setContents(LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS)
            .setPreviewWidth(1)
            .setPreviewHeight(1)
            .build()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            val png = java.io.ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            // PNG IHDR starts at byte 16. Keep a valid chunk CRC while advertising an
            // unreasonable image size; bounds decoding must reject it before allocation.
            png[16] = 0
            png[17] = 0
            png[18] = 0x9c.toByte()
            png[19] = 0x40
            png[20] = 0
            png[21] = 0
            png[22] = 0x9c.toByte()
            png[23] = 0x40
            val ihdrCrc = CRC32().apply { update(png, 12, 17) }.value
            for (index in 0..3) {
                png[29 + index] = (ihdrCrc ushr (24 - index * 8)).toByte()
            }

            ZipOutputStream(FileOutputStream(backupFile)).use { zip ->
                zip.putNextEntry(ZipEntry(LawnchairBackup.INFO_FILE_NAME))
                info.writeTo(zip)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(LawnchairBackup.SCREENSHOT_FILE_NAME))
                zip.write(png)
                zip.closeEntry()
            }

            val backup = LawnchairBackup(context, Uri.fromFile(backupFile))
            val failure = runCatching { backup.readInfoAndPreview() }.exceptionOrNull()
            assertTrue(
                "Oversized image dimensions must be rejected before bitmap allocation",
                failure?.message?.contains("dimensions exceed") == true,
            )
        } finally {
            bitmap.recycle()
            backupFile.delete()
        }
    }

    @Test
    fun excessiveCentralDirectoryMetadataIsRejected() = runBlocking {
        val backupFile = File(context.cacheDir, "excessive_central_directory.lawnchairbackup")
        val info = app.lawnchair.LawnchairProto.BackupInfo.newBuilder()
            .setBackupVersion(1)
            .setContents(LawnchairBackup.INCLUDE_WALLPAPER)
            .setPreviewWidth(1)
            .setPreviewHeight(1)
            .build()

        try {
            ZipOutputStream(FileOutputStream(backupFile)).use { zip ->
                val entry = ZipEntry(LawnchairBackup.INFO_FILE_NAME).apply {
                    comment = "x".repeat(65535)
                }
                zip.putNextEntry(entry)
                info.writeTo(zip)
                zip.closeEntry()
            }

            val backup = LawnchairBackup(context, Uri.fromFile(backupFile))
            val failure = runCatching { backup.readInfoAndPreview() }.exceptionOrNull()
            assertTrue(
                "Oversized central-directory metadata must be rejected before ZipFile parsing",
                failure?.message?.contains("central-directory metadata") == true,
            )
        } finally {
            backupFile.delete()
        }
    }

    @Test
    fun excessiveZipCompressionRatioIsRejected() = runBlocking {
        val backupFile = File(context.cacheDir, "excessive_compression_ratio.lawnchairbackup")
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

                // Highly repetitive data compresses beyond the supported 1000:1 ratio.
                zip.putNextEntry(ZipEntry(LawnchairBackup.SCREENSHOT_FILE_NAME))
                zip.write(ByteArray(16 * 1024 * 1024))
                zip.closeEntry()
            }

            val backup = LawnchairBackup(context, Uri.fromFile(backupFile))
            val failure = runCatching { backup.readInfoAndPreview() }.exceptionOrNull()
            assertTrue(
                "Archives with suspicious compression ratios must be rejected",
                failure?.message?.contains("compression ratio") == true,
            )
        } finally {
            backupFile.delete()
        }
    }

    @Test
    fun corruptedZipPayloadCrcIsRejected() = runBlocking {
        val backupFile = File(context.cacheDir, "corrupted_crc.lawnchairbackup")
        val infoBytes = app.lawnchair.LawnchairProto.BackupInfo.newBuilder()
            .setBackupVersion(1)
            .setContents(LawnchairBackup.INCLUDE_WALLPAPER)
            .setPreviewWidth(1)
            .setPreviewHeight(1)
            .build()
            .toByteArray()
        try {
            ZipOutputStream(FileOutputStream(backupFile)).use { zip ->
                val crc = CRC32().apply { update(infoBytes) }
                val entry = ZipEntry(LawnchairBackup.INFO_FILE_NAME).apply {
                    method = ZipEntry.STORED
                    size = infoBytes.size.toLong()
                    compressedSize = infoBytes.size.toLong()
                    this.crc = crc.value
                }
                zip.putNextEntry(entry)
                zip.write(infoBytes)
                zip.closeEntry()
            }

            val archive = backupFile.readBytes()
            val nameLength = (archive[26].toInt() and 0xff) or
                ((archive[27].toInt() and 0xff) shl 8)
            val extraLength = (archive[28].toInt() and 0xff) or
                ((archive[29].toInt() and 0xff) shl 8)
            val payloadOffset = 30 + nameLength + extraLength
            archive[payloadOffset] = (archive[payloadOffset].toInt() xor 0x01).toByte()
            backupFile.writeBytes(archive)

            val backup = LawnchairBackup(context, Uri.fromFile(backupFile))
            val result = runCatching { backup.readInfoAndPreview() }
            assertTrue("Archive with a mismatched ZIP CRC must be rejected", result.isFailure)
        } finally {
            backupFile.delete()
        }
    }

}
