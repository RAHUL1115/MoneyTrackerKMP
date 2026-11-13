package app.moneytracker

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Photo archives and the chosen backup folder's naming: what goes in, what comes back, and what is never touched. */
class PhotoZipTest {
    private val root = Files.createTempDirectory("photozip").toFile()

    @AfterTest fun cleanUp() { root.deleteRecursively() }

    private fun folder(name: String) = File(root, name).apply { mkdirs() }

    @Test fun photosRoundTrip() {
        val from = folder("from")
        File(from, "a.jpg").writeText("A")
        File(from, "b.jpg").writeText("B")
        val zip = ByteArrayOutputStream().also { PhotoZip.write(from.listFiles()!!.sortedBy { it.name }, it) }.toByteArray()

        val to = folder("to")
        assertEquals(2, PhotoZip.read(ByteArrayInputStream(zip), to))
        assertEquals("A", File(to, "a.jpg").readText())
        assertEquals("B", File(to, "b.jpg").readText())
    }

    @Test fun keepsExistingPhotosAndOnlyFlatNames() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                fun put(name: String, text: String = "new") { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
                put("kept.jpg")
                put("../escape.jpg", "flat")
                put(".hidden")
                put("folder/")
            }
        }.toByteArray()
        val to = folder("photos")
        File(to, "kept.jpg").writeText("old")

        assertEquals(1, PhotoZip.read(ByteArrayInputStream(zip), to))
        assertEquals("old", File(to, "kept.jpg").readText())
        assertEquals("flat", File(to, "escape.jpg").readText())
        assertFalse(File(root, "escape.jpg").exists())
        assertFalse(File(to, ".hidden").exists())
        assertFalse(File(to, "folder").exists())
    }

    @Test fun backupFolderCopiesAreRecognisedByKind() {
        assertTrue(ChosenBackupFolder.isCopyOf("MoneyTracker-auto-20261107-093000.mtbak", "auto"))
        assertFalse(ChosenBackupFolder.isCopyOf("MoneyTracker-manual-20261107-093000.mtbak", "auto"))
        assertFalse(ChosenBackupFolder.isCopyOf("MoneyTracker-auto-20261107-093000.mmdb", "auto"))
        assertFalse(ChosenBackupFolder.isCopyOf("auto-20261107-093000.mtbak", "auto"))
    }
}
