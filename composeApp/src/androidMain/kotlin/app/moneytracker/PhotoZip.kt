package app.moneytracker

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Entry photos to and from one flat .zip, so they can move to another phone alongside a backup. */
internal object PhotoZip {
    fun write(photos: List<File>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            photos.forEach { f ->
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Unpacks the photos from [input] into [dir]; returns how many were added. Photos already there are kept. */
    fun read(input: InputStream, dir: File): Int {
        var added = 0
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (unpack(zip, entry, dir)) added++
                zip.closeEntry()
            }
        }
        return added
    }

    private fun unpack(zip: ZipInputStream, entry: ZipEntry, dir: File): Boolean {
        // Flat names only: never follow paths out of the photos folder.
        val name = File(entry.name).name
        if (entry.isDirectory || name.isBlank() || name.startsWith(".")) return false
        val target = File(dir, name)
        if (target.exists()) return false
        target.outputStream().use { zip.copyTo(it) }
        return true
    }
}
