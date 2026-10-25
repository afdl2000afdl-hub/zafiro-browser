package com.niki914.zafiro.chat.agentic.iab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [IabDownloads] 文件名清洗 / 建议名 / 私有目录边界的 JVM 单测（防路径穿越是重点）。 */
class IabDownloadsTest {

    @Test
    fun sanitizeName_stripsPathTraversal() {
        assertEquals("passwd", IabDownloads.sanitizeName("../../etc/passwd"))
        assertEquals("download", IabDownloads.sanitizeName(".."))
        assertEquals("download", IabDownloads.sanitizeName("..."))
        assertEquals("report", IabDownloads.sanitizeName("report"))
    }

    @Test
    fun sanitizeName_keepsSafeCharacters() {
        assertEquals("a-b_c.d", IabDownloads.sanitizeName("a-b_c.d"))
        // 空格与非法字符 → 下划线
        assertEquals("my_file_x", IabDownloads.sanitizeName("my file x"))
    }

    @Test
    fun sanitizeName_truncatesOverlong() {
        val long = "x".repeat(500)
        assertEquals(128, IabDownloads.sanitizeName(long).length)
    }

    @Test
    fun suggestedName_prefersContentDispositionFilename() {
        val name = IabDownloads.suggestedName(
            url = "https://example.com/dl?id=3",
            contentDisposition = "attachment; filename=\"report final.pdf\"",
            mimeType = "application/pdf",
        )
        assertEquals("report_final.pdf", name)
    }

    @Test
    fun suggestedName_prefersContentDispositionStar() {
        val name = IabDownloads.suggestedName(
            url = "https://example.com/dl",
            contentDisposition = "attachment; filename*=UTF-8''na%C3%A4me.csv",
            mimeType = "text/csv",
        )
        // Expected uses a \u00E4 escape (pure-ASCII in the source) so the literal
        // compiles identically no matter which charset the CI Kotlin daemon uses
        // to read this .kt file (JDK 17 file.encoding is locale-derived and the
        // runner locale is not UTF-8). Runtime actual is charset-independent: it
        // comes from URLDecoder(..., "UTF-8") decoding the percent-encoded input.
        assertEquals("n\u00E4me.csv", name)
    }

    @Test
    fun suggestedName_fallsBackToUrlLastSegment() {
        assertEquals("data.json", IabDownloads.suggestedName("https://example.com/a/b/data.json", null, null))
    }

    @Test
    fun suggestedName_extFromMimeWhenMissing() {
        assertEquals("blob.png", IabDownloads.suggestedName("https://example.com/blob", null, "image/png"))
    }

    @Test
    fun extFor_knownMimes() {
        assertEquals("pdf", IabDownloads.extFor("application/pdf"))
        assertEquals("apk", IabDownloads.extFor("application/vnd.android.package-archive"))
        assertNull(IabDownloads.extFor("application/x-something-unknown"))
        assertNull(IabDownloads.extFor(null))
    }

    @Test
    fun isWithinAppDirs_acceptsOwnDirs() {
        assertTrue(IabDownloads.isWithinAppDirs("/data/app/x/files/downloads/a.txt", listOf("/data/app/x/files")))
        assertTrue(IabDownloads.isWithinAppDirs("/data/app/x/cache/iab/a.bin", listOf("/data/app/x/files", "/data/app/x/cache")))
    }

    @Test
    fun isWithinAppDirs_rejectsSiblingsAndPrefixTricks() {
        assertFalse(IabDownloads.isWithinAppDirs("/data/app/x/files_evil/a", listOf("/data/app/x/files")))
        assertFalse(IabDownloads.isWithinAppDirs("/sdcard/Download/a", listOf("/data/app/x/files")))
        assertFalse(IabDownloads.isWithinAppDirs("/data/app/x", listOf("/data/app/x/files")))
    }

    @Test
    fun normalizePath_relativeResolvesAgainstBase() {
        assertEquals("/app/files/rel.txt", IabDownloads.normalizePath("rel.txt", "/app/files"))
        assertEquals("/abs/x", IabDownloads.normalizePath("/abs/x", "/app/files"))
    }
}
