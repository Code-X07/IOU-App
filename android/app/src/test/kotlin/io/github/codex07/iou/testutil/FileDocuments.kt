package io.github.codex07.iou.testutil

import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.store.DocumentStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Documents as plain files in [dir]; a [DocumentRef]'s URI is the file name. */
class FileDocuments(val dir: File) : DocumentStore {

    fun file(ref: DocumentRef) = File(dir, ref.uri)

    override fun openRead(ref: DocumentRef): InputStream = file(ref).inputStream()

    override fun openWrite(ref: DocumentRef): OutputStream {
        dir.mkdirs()
        return file(ref).outputStream()
    }
}
