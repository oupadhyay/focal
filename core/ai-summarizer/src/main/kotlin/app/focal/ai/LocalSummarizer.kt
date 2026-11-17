package app.focal.ai

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ln

enum class LocalSummarizerBackend(val badgeLabel: String) {
    AICORE_GEMINI_NANO("Gemini Nano · On-Device"),
    LITERT_GEMMA_3_1B("Gemma 3 1B · On-Device GPU"),
    LOCAL_SALIENT_EXTRACTOR("Focal Local · Offline RAM"),
}

data class GroundedPageSummary(
    val headline: String,
    val bullets: ImmutableList<GroundedBullet>,
    val backend: LocalSummarizerBackend,
    val latencyMs: Long,
)

/**
 * Runtime bridge seam for plugging Android's system NPU (`ML Kit GenAI Summarization` / `AICore`)
 * or downloadable `LiteRT-LM` (`Gemma 3 1B-IT int4`) weights without introducing any cloud client.
 */
fun interface LocalGenerativeEngineDelegate {
    suspend fun generateRawHeadlineAndBullets(
        pageTitle: String,
        concatenatedBody: String,
        targetBullets: Int,
    ): Pair<String, List<String>>?
}

interface LocalSummarizer {
    fun detectActiveBackend(): LocalSummarizerBackend

    suspend fun summarizeAndGround(
        pageTitle: String,
        paragraphs: List<IndexedParagraph>,
        targetBullets: Int = 4,
    ): GroundedPageSummary
}

/**
 * 100% On-Device Grounded Summarizer.
 *
 * 1. Probes Android `AICore` (`com.google.android.aicore` NPU service) first (`0 MB` APK overhead).
 * 2. Falls back to opt-in downloaded `LiteRT-LM` `gemma-3-1b-it-int4.litertlm` in `noBackupFilesDir`.
 * 3. Guarantees instant 100% offline operation out-of-the-box via a deterministic TF-IDF + lead-bias
 *    salience sentence condenser when no neural model runtime delegate is attached yet.
 * 4. Every output bullet is grounded via [ParagraphGrounder] (`BM25 + char 4-gram Jaccard`) back to
 *    its exact `[data-focal-para-id="N"]` DOM element.
 */
class OnDeviceSummarizer(
    private val appContext: Context? = null,
    private val grounder: ParagraphGrounder = ParagraphGrounder(),
    private val aiCoreDelegate: LocalGenerativeEngineDelegate? = null,
    private val liteRtDelegate: LocalGenerativeEngineDelegate? = null,
) : LocalSummarizer {

    companion object {
        const val AICORE_PACKAGE_NAME = "com.google.android.aicore"
        const val LITERT_MODEL_REL_PATH = "focal_models/gemma-3-1b-it-int4.litertlm"
        private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?])\\s+(?=[A-Z0-9\"'“])")
    }

    override fun detectActiveBackend(): LocalSummarizerBackend {
        if (aiCoreDelegate != null && isAiCoreInstalled()) {
            return LocalSummarizerBackend.AICORE_GEMINI_NANO
        }
        if (liteRtDelegate != null && isLiteRtModelPresent()) {
            return LocalSummarizerBackend.LITERT_GEMMA_3_1B
        }
        return LocalSummarizerBackend.LOCAL_SALIENT_EXTRACTOR
    }

    override suspend fun summarizeAndGround(
        pageTitle: String,
        paragraphs: List<IndexedParagraph>,
        targetBullets: Int,
    ): GroundedPageSummary = withContext(Dispatchers.Default) {
        val startNs = System.nanoTime()
        val substantive = paragraphs.filter { it.text.trim().length >= 32 }
        val effectiveParas = substantive.ifEmpty { paragraphs }

        if (effectiveParas.isEmpty()) {
            return@withContext GroundedPageSummary(
                headline = pageTitle.ifBlank { "No Readable Article Content" },
                bullets = emptyList<GroundedBullet>().toImmutableList(),
                backend = detectActiveBackend(),
                latencyMs = 0L,
            )
        }

        val combinedText = effectiveParas.take(36).joinToString("\n\n") { it.text }
        val clampedCount = targetBullets.coerceIn(3, 5)
        val preferredBackend = detectActiveBackend()

        val neuralOutput: Pair<String, List<String>>? = when (preferredBackend) {
            LocalSummarizerBackend.AICORE_GEMINI_NANO ->
                aiCoreDelegate?.generateRawHeadlineAndBullets(pageTitle, combinedText, clampedCount)
            LocalSummarizerBackend.LITERT_GEMMA_3_1B ->
                liteRtDelegate?.generateRawHeadlineAndBullets(pageTitle, combinedText, clampedCount)
            LocalSummarizerBackend.LOCAL_SALIENT_EXTRACTOR -> null
        }

        val actualBackend = if (neuralOutput != null) {
            preferredBackend
        } else {
            LocalSummarizerBackend.LOCAL_SALIENT_EXTRACTOR
        }

        val (headline, rawBullets) = neuralOutput
            ?: buildSalientExtractiveSummary(pageTitle, effectiveParas, clampedCount)

        val grounded = grounder.groundBullets(
            bullets = rawBullets,
            paragraphs = effectiveParas,
        ).toImmutableList()

        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        GroundedPageSummary(
            headline = headline,
            bullets = grounded,
            backend = actualBackend,
            latencyMs = elapsedMs,
        )
    }

    private fun isAiCoreInstalled(): Boolean {
        val ctx = appContext ?: return false
        return try {
            ctx.packageManager.getPackageInfo(AICORE_PACKAGE_NAME, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun isLiteRtModelPresent(): Boolean {
        val ctx = appContext ?: return false
        val modelFile = File(ctx.noBackupFilesDir, LITERT_MODEL_REL_PATH)
        return modelFile.exists() && modelFile.length() > 1_048_576L
    }

    /**
     * Pure-Kotlin deterministic TF-IDF + positional salience condenser used when neural NPU/GPU
     * weights are not yet provisioned, ensuring 100% offline local summarization on every device.
     */
    internal fun buildSalientExtractiveSummary(
        pageTitle: String,
        paragraphs: List<IndexedParagraph>,
        maxBullets: Int,
    ): Pair<String, List<String>> {
        data class CandidateSentence(
            val paraOrder: Int,
            val paraId: Int,
            val text: String,
            val tokens: List<String>,
        )

        val candidates = mutableListOf<CandidateSentence>()
        paragraphs.forEachIndexed { order, para ->
            val sentences = para.text.trim().split(SENTENCE_SPLIT_REGEX)
            for (rawSentence in sentences) {
                val cleaned = rawSentence.trim()
                if (cleaned.length in 45..260) {
                    val tokens = ParagraphGrounder.tokenize(cleaned)
                    if (tokens.size >= 5) {
                        candidates.add(
                            CandidateSentence(
                                paraOrder = order,
                                paraId = para.paraId,
                                text = cleaned,
                                tokens = tokens,
                            ),
                        )
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            val fallbackBullets = paragraphs.take(maxBullets).map { para ->
                para.text.trim().let { if (it.length <= 180) it else it.take(177).trimEnd() + "…" }
            }
            return Pair(pageTitle.ifBlank { "Key Takeaways" }, fallbackBullets)
        }

        val docFreq = HashMap<String, Int>()
        val termFreq = HashMap<String, Int>()
        for (c in candidates) {
            for (t in c.tokens) {
                termFreq[t] = (termFreq[t] ?: 0) + 1
            }
            for (t in c.tokens.toSet()) {
                docFreq[t] = (docFreq[t] ?: 0) + 1
            }
        }

        val titleTokens = ParagraphGrounder.tokenize(pageTitle).toSet()
        val n = candidates.size.toDouble()

        val scored = candidates.mapIndexed { idx, c ->
            var lexical = 0.0
            for (t in c.tokens.toSet()) {
                val tf = termFreq[t] ?: 1
                val df = docFreq[t] ?: 1
                val idf = ln(1.0 + n / df)
                val titleBoost = if (t in titleTokens) 1.45 else 1.0
                lexical += tf * idf * titleBoost
            }
            val lengthNorm = c.tokens.size.coerceAtLeast(6).toDouble()
            // Mild lead-paragraph structural bias without starving conclusion insights
            val positionBias = when {
                c.paraOrder == 0 -> 1.22
                c.paraOrder <= 2 -> 1.10
                else -> 1.0
            }
            Pair(idx, (lexical / lengthNorm) * positionBias)
        }.sortedByDescending { it.second }

        // Maximal Marginal Relevance (MMR) deduplication via 4-gram Jaccard
        val selectedIndices = mutableListOf<Int>()
        val selectedGrams = mutableListOf<Set<String>>()
        for ((idx, _) in scored) {
            if (selectedIndices.size >= maxBullets) break
            val grams = ParagraphGrounder.extractCharFourGrams(candidates[idx].text)
            val redundant = selectedGrams.any { existing ->
                ParagraphGrounder.charFourGramJaccard(grams, existing) > 0.42f
            }
            if (!redundant) {
                selectedIndices.add(idx)
                selectedGrams.add(grams)
            }
        }

        // Preserve original reading flow order in bullet cards
        selectedIndices.sort()
        val bullets = selectedIndices.map { candidates[it].text }
        val thesis = pageTitle.trim().takeIf { it.length >= 8 }
            ?: bullets.firstOrNull()?.take(84)
            ?: "On-Device Summary"

        return Pair(thesis, bullets)
    }
}
