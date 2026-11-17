package app.focal.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphGrounderTest {

    private val sampleParagraphs = listOf(
        IndexedParagraph(
            paraId = 0,
            text = "At 120Hz on modern OLED smartphones, the entire CPU and GPU RenderThread frame budget shrinks to 8.33 milliseconds per frame.",
        ),
        IndexedParagraph(
            paraId = 1,
            text = "Chrome for Android resizes the WebView viewport whenever the top omnibox scrolls away, triggering expensive layout passes.",
        ),
        IndexedParagraph(
            paraId = 2,
            text = "Focal runs Brave's Rust adblock engine over a zero-copy JNI bridge on Chromium's background worker thread in 0.18ms median.",
        ),
        IndexedParagraph(
            paraId = 3,
            text = "Android 13 introduced AGSL RuntimeShader pipelines, enabling concave optical waist warps and blue-noise dithering on RenderThread.",
        ),
    )

    @Test
    fun `BM25 plus 4-gram Jaccard grounds paraphrased bullets to exact data-focal-para-id`() {
        val grounder = ParagraphGrounder()
        val bullets = listOf(
            "Native Rust adblock over JNI blocks network trackers in 0.18ms on background threads.",
            "AGSL RuntimeShaders execute optical hourglass warps and OLED dithering on Android 13+.",
            "Maintaining 120Hz requires staying inside an 8.33ms RenderThread frame budget.",
        )

        val grounded = grounder.groundBullets(bullets, sampleParagraphs)
        assertEquals(3, grounded.size)
        assertEquals(2, grounded[0].groundedParaId)
        assertEquals(3, grounded[1].groundedParaId)
        assertEquals(0, grounded[2].groundedParaId)
        assertTrue(grounded.all { it.combinedScore > 0.3f })
    }

    @Test
    fun `OnDeviceSummarizer produces grounded bullets offline without network`() = runTest {
        val summarizer = OnDeviceSummarizer(appContext = null)
        assertEquals(LocalSummarizerBackend.LOCAL_SALIENT_EXTRACTOR, summarizer.detectActiveBackend())

        val result = summarizer.summarizeAndGround(
            pageTitle = "Engineering 120Hz Mobile Browser Frame Pacing",
            paragraphs = sampleParagraphs,
            targetBullets = 3,
        )

        assertEquals(LocalSummarizerBackend.LOCAL_SALIENT_EXTRACTOR, result.backend)
        assertEquals("Engineering 120Hz Mobile Browser Frame Pacing", result.headline)
        assertEquals(3, result.bullets.size)
        val validIds = sampleParagraphs.map { it.paraId }.toSet()
        assertTrue(result.bullets.all { it.groundedParaId in validIds })
    }
}
