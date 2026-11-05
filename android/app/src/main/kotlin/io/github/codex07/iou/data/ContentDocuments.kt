package io.github.codex07.iou.data

import android.content.ContentResolver
import androidx.core.net.toUri
import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.store.DocumentStore
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/** Documents picked through the Storage Access Framework; [DocumentRef.uri] is the picker's `content://` URI. */
class ContentDocuments(private val resolver: ContentResolver) : DocumentStore {

    override fun openRead(ref: DocumentRef): InputStream =
        resolver.openInputStream(ref.uri.toUri()) ?: throw FileNotFoundException(ref.uri)

    /** Mode `wt` truncates, so a shorter backup never leaves the end of an older one behind. */
    override fun openWrite(ref: DocumentRef): OutputStream =
        resolver.openOutputStream(ref.uri.toUri(), "wt") ?: throw FileNotFoundException(ref.uri)
}
