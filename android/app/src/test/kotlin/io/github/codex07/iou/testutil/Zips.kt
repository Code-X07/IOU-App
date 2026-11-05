package io.github.codex07.iou.testutil

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Zip files as ordered maps of entry name to text, for building and editing import files in tests. */
object Zips {
    fun read(bytes: ByteArray): LinkedHashMap<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return entries
    }

    fun write(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Builds an archive with two identical names by renaming equal-length ZIP header names. */
    fun duplicateName(bytes: ByteArray, first: String, second: String): ByteArray {
        require(first.length == second.length)
        val entries = read(bytes).also { it[first] = "a"; it[second] = "b" }
        val archive = write(entries)
        val from = second.toByteArray(Charsets.UTF_8)
        val to = first.toByteArray(Charsets.UTF_8)
        for (i in 0..archive.size - from.size) {
            if (from.indices.all { archive[i + it] == from[it] }) {
                to.copyInto(archive, i)
            }
        }
        return archive
    }

    /** [bytes] with [name]'s text transformed. */
    fun edit(bytes: ByteArray, name: String, transform: (String) -> String): ByteArray =
        write(read(bytes).also { it[name] = transform(it.getValue(name)) })
}
