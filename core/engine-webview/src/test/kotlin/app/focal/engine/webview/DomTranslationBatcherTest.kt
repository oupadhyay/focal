package app.focal.engine.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomTranslationBatcherTest {

    @Test
    fun `splitSurrogateSafe never splits UTF-16 surrogate pairs at chunk boundary`() {
        // 'a' (1 unit) + "🚀" (2 units, U+1F680) + "𠀀" (2 units, U+20000) + 'z' (1 unit)
        val text = "a\uD83D\uDE80\uD840\uDC00z"
        val pieces = DomTranslationBatcher.splitSurrogateSafe(
            segments = listOf(DomTextSegment(id = 0, text = text)),
            maxCharsPerPiece = 2,
        )

        assertTrue(pieces.size >= 3)
        for (piece in pieces) {
            val combined = piece.leadingWhitespace + piece.coreText + piece.trailingWhitespace
            assertNoUnpairedSurrogates(combined)
            assertTrue("Piece exceeds maxCharsPerPiece=2: '$combined'", combined.length <= 2)
        }

        val reassembled = DomTranslationBatcher.reassembleSegments(
            pieces = pieces,
            translatedCores = pieces.map { it.coreText },
        )
        assertEquals(listOf(DomTextSegment(id = 0, text = text)), reassembled)
    }

    @Test
    fun `splitSurrogateSafe and reassembleSegments preserve exact leading and trailing whitespace`() {
        val segments = listOf(
            DomTextSegment(id = 0, text = "Bonjour "),
            DomTextSegment(id = 1, text = " le monde  "),
            DomTextSegment(id = 2, text = "\n\tAu revoir.\n"),
        )
        val pieces = DomTranslationBatcher.splitSurrogateSafe(segments)
        assertEquals(3, pieces.size)
        assertEquals("", pieces[0].leadingWhitespace)
        assertEquals("Bonjour", pieces[0].coreText)
        assertEquals(" ", pieces[0].trailingWhitespace)
        assertEquals(" ", pieces[1].leadingWhitespace)
        assertEquals("le monde", pieces[1].coreText)
        assertEquals("  ", pieces[1].trailingWhitespace)

        val translated = DomTranslationBatcher.reassembleSegments(
            pieces = pieces,
            translatedCores = listOf("Hello", "the world", "Goodbye."),
        )
        assertEquals(
            listOf(
                DomTextSegment(id = 0, text = "Hello "),
                DomTextSegment(id = 1, text = " the world  "),
                DomTextSegment(id = 2, text = "\n\tGoodbye.\n"),
            ),
            translated,
        )
    }

    @Test
    fun `groupIntoBatches enforces both character and item caps`() {
        val segments = (0 until 10).map { i -> DomTextSegment(id = i, text = "word$i " + "x".repeat(40)) }
        val pieces = DomTranslationBatcher.splitSurrogateSafe(segments, maxCharsPerPiece = 100)

        val charCapped = DomTranslationBatcher.groupIntoBatches(
            pieces = pieces,
            maxBatchChars = 100,
            maxPiecesPerBatch = 50,
        )
        assertEquals(5, charCapped.size)
        charCapped.forEach { batch ->
            assertTrue(batch.sumOf { it.coreText.length } <= 100)
        }

        val countCapped = DomTranslationBatcher.groupIntoBatches(
            pieces = pieces,
            maxBatchChars = 10_000,
            maxPiecesPerBatch = 3,
        )
        assertEquals(listOf(3, 3, 3, 1), countCapped.map { it.size })
    }

    @Test
    fun `ProgressiveAssembler flushes completed segments immediately while holding split segments across batches`() {
        val longText = "Premier paragraphe tres long qui sera coupe en deux morceaux distincts."
        val segments = listOf(
            DomTextSegment(id = 0, text = "Titre "),
            DomTextSegment(id = 1, text = longText),
            DomTextSegment(id = 2, text = " Pied de page"),
        )
        val pieces = DomTranslationBatcher.splitSurrogateSafe(segments, maxCharsPerPiece = 40)
        val longPieceCount = pieces.count { it.segmentId == 1 }
        assertTrue("Expected segment 1 to split into >= 2 pieces", longPieceCount >= 2)

        // Force batch 0 to hold segment 0 + only the first piece of segment 1
        val batches = DomTranslationBatcher.groupIntoBatches(
            pieces = pieces,
            maxBatchChars = 500,
            maxPiecesPerBatch = 2,
        )
        assertTrue(batches.size >= 2)

        val assembler = DomTranslationBatcher.ProgressiveAssembler(pieces)
        val batch0Ready = assembler.acceptBatch(
            batchPieces = batches[0],
            translatedCores = batches[0].map { "[T:${it.coreText}]" },
        )
        // Segment 0 is complete in batch 0; segment 1 is still pending its second piece
        assertEquals(listOf(DomTextSegment(id = 0, text = "[T:Titre] ")), batch0Ready)

        val remainingReady = mutableListOf<DomTextSegment>()
        for (i in 1 until batches.size) {
            remainingReady += assembler.acceptBatch(
                batchPieces = batches[i],
                translatedCores = batches[i].map { "[T:${it.coreText}]" },
            )
        }
        assertEquals(listOf(1, 2), remainingReady.map { it.id })
        assertTrue(remainingReady[0].text.contains("[T:"))
        assertEquals(" [T:Pied de page]", remainingReady[1].text)
    }

    @Test
    fun `normalizeLanguageCode maps BCP-47 tags and aliases to ML Kit codes`() {
        assertEquals("fr", DomTranslationBatcher.normalizeLanguageCode("fr-FR"))
        assertEquals("zh", DomTranslationBatcher.normalizeLanguageCode("zh-Hans"))
        assertEquals("zh", DomTranslationBatcher.normalizeLanguageCode("cmn-Hant-TW"))
        assertEquals("no", DomTranslationBatcher.normalizeLanguageCode("nb_NO"))
        assertEquals("he", DomTranslationBatcher.normalizeLanguageCode("iw"))
        assertEquals("id", DomTranslationBatcher.normalizeLanguageCode("in-ID"))
        assertEquals("tl", DomTranslationBatcher.normalizeLanguageCode("fil"))
        assertEquals("en", DomTranslationBatcher.normalizeLanguageCode("EN-US"))

        assertNull(DomTranslationBatcher.normalizeLanguageCode("und"))
        assertNull(DomTranslationBatcher.normalizeLanguageCode(""))
        assertNull(DomTranslationBatcher.normalizeLanguageCode("   "))
        assertNull(DomTranslationBatcher.normalizeLanguageCode(null))
        assertNull(DomTranslationBatcher.normalizeLanguageCode("zz-ZZ"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `splitSurrogateSafe rejects duplicate segment IDs`() {
        DomTranslationBatcher.splitSurrogateSafe(
            listOf(
                DomTextSegment(id = 7, text = "Hello"),
                DomTextSegment(id = 7, text = "World"),
            ),
        )
    }

    @Test
    fun `InPlaceDomTranslationScripts embed generation token and safely escape JS strings`() {
        val collect = InPlaceDomTranslationScripts.buildCollectScript(token = 42L)
        assertTrue(collect.contains("token: 42"))
        assertTrue(collect.contains("notranslate"))
        assertTrue(collect.contains("isContentEditable"))

        val apply = InPlaceDomTranslationScripts.buildApplyScript(
            token = 42L,
            updates = listOf(
                DomTextSegment(id = InPlaceDomTranslationScripts.TITLE_SEGMENT_ID, text = "Focal \"Title\"\nLine2"),
                DomTextSegment(id = 0, text = "Path C:\\temp\u2028End"),
            ),
        )
        assertTrue(apply.contains("st.token !== 42"))
        assertTrue(apply.contains("{\"id\":-1,\"text\":\"Focal \\\"Title\\\"\\nLine2\"}"))
        assertTrue(apply.contains("{\"id\":0,\"text\":\"Path C:\\\\temp\\u2028End\"}"))
        assertFalse(apply.contains("\u2028"))

        assertTrue(InPlaceDomTranslationScripts.RESTORE_ORIGINAL_SCRIPT.contains("window.__focalTranslationState = null"))
    }

    private fun assertNoUnpairedSurrogates(s: String) {
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch.isHighSurrogate()) {
                assertTrue("Unpaired high surrogate at index $i", i + 1 < s.length && s[i + 1].isLowSurrogate())
                i += 2
            } else {
                assertFalse("Unpaired low surrogate at index $i", ch.isLowSurrogate())
                i++
            }
        }
    }
}
