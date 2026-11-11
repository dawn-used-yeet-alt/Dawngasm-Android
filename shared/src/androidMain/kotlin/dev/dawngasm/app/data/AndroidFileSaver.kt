package dev.dawngasm.app.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream

class AndroidFileSaver(
    private val context: Context,
) : FileSaver {
    override fun folderLabel(treeUri: String?): String {
        if (treeUri == null) return "App storage (Music/Dawngasm)"
        val uri = runCatching { treeUri.toUri() }.getOrNull() ?: return "Custom folder"
        return runCatching { DocumentFile.fromTreeUri(context, uri)?.name }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Custom folder"
    }

    override fun exists(localUri: String?): Boolean {
        if (localUri.isNullOrBlank()) return false
        val uri = runCatching { localUri.toUri() }.getOrNull() ?: return false
        return when (uri.scheme) {
            "file" -> uri.path?.let { File(it).exists() } == true
            "content" -> runCatching {
                DocumentFile.fromSingleUri(context, uri)?.exists() == true
            }.getOrDefault(false)
            else -> false
        }
    }

    override suspend fun save(fileName: String, bytes: ByteArray, treeUri: String?): String {
        val extension = fileName.substringAfterLast('.', "m4a")
        return if (treeUri != null) {
            saveToTree(treeUri, fileName, extension, bytes).toString()
        } else {
            saveToAppStorage(fileName, bytes).toString()
        }
    }

    private fun saveToTree(
        treeUri: String,
        fileName: String,
        extension: String,
        bytes: ByteArray,
    ): Uri {
        val tree = DocumentFile.fromTreeUri(context, treeUri.toUri())
            ?: throw IllegalStateException("The selected folder is no longer available")
        if (!tree.canWrite()) {
            throw IllegalStateException("The selected folder is read-only")
        }
        val uniqueName = uniqueName(tree, fileName)
        val mime = mimeType(extension)
        val document = tree.createFile(mime, uniqueName)
            ?: throw IllegalStateException("Could not create the file in the selected folder")
        try {
            context.contentResolver.openOutputStream(document.uri, "w")?.use { output ->
                output.write(bytes)
            } ?: throw IllegalStateException("Could not open the selected file for writing")
        } catch (error: Exception) {
            runCatching { document.delete() }
            throw error
        }
        return document.uri
    }

    private fun saveToAppStorage(fileName: String, bytes: ByteArray): Uri {
        val mediaRoot = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir
        val folder = File(mediaRoot, "Dawngasm").apply { mkdirs() }
        val file = uniqueFile(folder, fileName)
        try {
            FileOutputStream(file).use { output ->
                output.write(bytes)
            }
        } catch (error: Exception) {
            runCatching { file.delete() }
            throw error
        }
        return Uri.fromFile(file)
    }

    private fun uniqueName(tree: DocumentFile, desired: String): String {
        if (tree.findFile(desired) == null) return desired
        val base = desired.substringBeforeLast('.', desired)
        val extension = desired.substringAfterLast('.', "")
        val suffix = if (extension.isBlank()) "" else ".$extension"
        var index = 2
        while (true) {
            val candidate = "$base ($index)$suffix"
            if (tree.findFile(candidate) == null) return candidate
            index += 1
        }
    }

    private fun uniqueFile(folder: File, desired: String): File {
        var candidate = File(folder, desired)
        if (!candidate.exists()) return candidate
        val base = desired.substringBeforeLast('.', desired)
        val extension = desired.substringAfterLast('.', "")
        val suffix = if (extension.isBlank()) "" else ".$extension"
        var index = 2
        while (true) {
            candidate = File(folder, "$base ($index)$suffix")
            if (!candidate.exists()) return candidate
            index += 1
        }
    }

    private fun mimeType(extension: String): String = when (extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        else -> "audio/mp4"
    }
}
