package dev.dawngasm.app.data

import dev.dawngasm.app.model.Track

/**
 * Streams a resolved track to permanent storage and returns a `file://` URI.
 * Implementations should write to a temporary file and move it into place so a
 * cancelled download never leaves a broken library entry.
 */
interface TrackDownloader {
    fun folderLabel(treeUri: String?): String
    fun exists(localUri: String?): Boolean
    suspend fun download(track: Track, audio: ResolvedAudio, treeUri: String?): String
}
