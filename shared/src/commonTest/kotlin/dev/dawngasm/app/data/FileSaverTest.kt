package dev.dawngasm.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FakeFileSaver : FileSaver {
    private val files = mutableMapOf<String, ByteArray>()

    override fun folderLabel(treeUri: String?): String =
        if (treeUri == null) "Default" else "Custom: $treeUri"

    override fun exists(localUri: String?): Boolean =
        localUri != null && files.containsKey(localUri)

    override suspend fun save(fileName: String, bytes: ByteArray, treeUri: String?): String {
        val uri = "file://$fileName"
        files[uri] = bytes
        return uri
    }
}

class FileSaverTest {
    @Test
    fun testFileSaverContract() {
        val saver = FakeFileSaver()
        assertEquals("Default", saver.folderLabel(null))
        assertEquals("Custom: test", saver.folderLabel("test"))
        assertFalse(saver.exists("file://missing.mp3"))
    }
}
