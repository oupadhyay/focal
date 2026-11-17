package app.focal.ai

import kotlin.math.ln

data class IndexedParagraph(
    val paraId: Int,
    val text: String,
)

data class GroundedBullet(
    val text: String,
    val groundedParaId: Int,
    val bm25Score: Float,
    val charFourGramJaccard: Float,
    val combinedScore: Float,
    val sourceSnippet: String,
)

/**
 * Deterministic post-hoc lexical + character n-gram source grounder (`< 2ms` CPU).
 *
 * Maps each generated summary bullet back to its best-matching `[data-focal-para-id="N"]`
 * DOM node by combining:
 * 1. **Okapi BM25** (`k1 = 1.5`, `b = 0.75`) over normalized content tokens (`weight = 0.65`).
 * 2. **Character 4-gram Jaccard overlap** (`|G_q ∩ G_d| / |G_q ∪ G_d|`, `weight = 0.35`)
 *    for resilience against morphological inflections, hyphenation, numbers, and proper nouns.
 */
class ParagraphGrounder(
    private val k1: Double = 1.5,
    private val b: Double = 0.75,
    private val bm25Weight: Float = 0.65f,
    private val jaccardWeight: Float = 0.35f,
) {

    companion object {
        private val TOKEN_SPLIT_REGEX = Regex("[^\\p{L}\\p{N}]+")
        private val WHITESPACE_REGEX = Regex("\\s+")

        private val STOPWORDS = setOf(
            "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can", "could",
            "did", "do", "does", "for", "from", "had", "has", "have", "he", "her", "his",
            "how", "if", "in", "into", "is", "it", "its", "may", "might", "more", "most",
            "no", "not", "of", "on", "one", "only", "or", "other", "our", "out", "over",
            "she", "should", "so", "some", "such", "than", "that", "the", "their", "them",
            "then", "there", "these", "they", "this", "those", "through", "to", "too",
            "under", "up", "very", "was", "we", "were", "what", "when", "where", "which",
            "while", "who", "will", "with", "would", "you", "your",
        )

        fun tokenize(raw: String): List<String> {
            return raw.lowercase()
                .split(TOKEN_SPLIT_REGEX)
                .filter { it.length >= 2 && it !in STOPWORDS }
        }

        fun extractCharFourGrams(raw: String): Set<String> {
            val normalized = raw.lowercase()
                .replace(TOKEN_SPLIT_REGEX, " ")
                .replace(WHITESPACE_REGEX, " ")
                .trim()
            if (normalized.isEmpty()) return emptySet()
            if (normalized.length < 4) return setOf(normalized)

            val grams = HashSet<String>((normalized.length - 2).coerceAtLeast(4))
            for (i in 0..(normalized.length - 4)) {
                grams.add(normalized.substring(i, i + 4))
            }
            return grams
        }

        fun charFourGramJaccard(gramsA: Set<String>, gramsB: Set<String>): Float {
            if (gramsA.isEmpty() || gramsB.isEmpty()) return 0f
            val smaller = if (gramsA.size <= gramsB.size) gramsA else gramsB
            val larger = if (gramsA.size <= gramsB.size) gramsB else gramsA
            var intersection = 0
            for (g in smaller) {
                if (g in larger) intersection++
            }
            if (intersection == 0) return 0f
            val union = gramsA.size + gramsB.size - intersection
            return if (union > 0) intersection.toFloat() / union.toFloat() else 0f
        }
    }

    private data class PreparedDoc(
        val paragraph: IndexedParagraph,
        val termFreqs: Map<String, Int>,
        val docLen: Int,
        val fourGrams: Set<String>,
    )

    fun groundBullets(
        bullets: List<String>,
        paragraphs: List<IndexedParagraph>,
    ): List<GroundedBullet> {
        if (bullets.isEmpty()) return emptyList()
        if (paragraphs.isEmpty()) {
            return bullets.map { bullet ->
                GroundedBullet(
                    text = bullet,
                    groundedParaId = 0,
                    bm25Score = 0f,
                    charFourGramJaccard = 0f,
                    combinedScore = 0f,
                    sourceSnippet = "",
                )
            }
        }

        val docs = paragraphs.map { para ->
            val tokens = tokenize(para.text)
            val freqs = HashMap<String, Int>(tokens.size)
            for (token in tokens) {
                freqs[token] = (freqs[token] ?: 0) + 1
            }
            PreparedDoc(
                paragraph = para,
                termFreqs = freqs,
                docLen = tokens.size.coerceAtLeast(1),
                fourGrams = extractCharFourGrams(para.text),
            )
        }

        val totalDocs = docs.size
        val avgDocLen = docs.sumOf { it.docLen }.toDouble() / totalDocs.coerceAtLeast(1)

        // Document frequency per term across the page's paragraphs
        val docFreq = HashMap<String, Int>()
        for (doc in docs) {
            for (term in doc.termFreqs.keys) {
                docFreq[term] = (docFreq[term] ?: 0) + 1
            }
        }

        return bullets.map { bulletText ->
            groundSingleBullet(
                bulletText = bulletText,
                docs = docs,
                totalDocs = totalDocs,
                avgDocLen = avgDocLen,
                docFreq = docFreq,
            )
        }
    }

    private fun groundSingleBullet(
        bulletText: String,
        docs: List<PreparedDoc>,
        totalDocs: Int,
        avgDocLen: Double,
        docFreq: Map<String, Int>,
    ): GroundedBullet {
        val queryTokens = tokenize(bulletText).distinct()
        val queryGrams = extractCharFourGrams(bulletText)

        val rawBm25 = FloatArray(docs.size)
        val rawJaccard = FloatArray(docs.size)
        var maxBm25 = 0f

        for (i in docs.indices) {
            val doc = docs[i]
            var score = 0.0
            for (term in queryTokens) {
                val tf = doc.termFreqs[term] ?: 0
                if (tf == 0) continue
                val df = docFreq[term] ?: 0
                // Standard Robertson-Sparck Jones smooth IDF
                val idf = ln(1.0 + (totalDocs - df + 0.5) / (df + 0.5))
                val denom = tf + k1 * (1.0 - b + b * (doc.docLen / avgDocLen))
                score += idf * ((tf * (k1 + 1.0)) / denom)
            }
            val bm25F = score.toFloat()
            rawBm25[i] = bm25F
            if (bm25F > maxBm25) maxBm25 = bm25F

            rawJaccard[i] = charFourGramJaccard(queryGrams, doc.fourGrams)
        }

        var bestIdx = 0
        var bestCombined = -1f
        for (i in docs.indices) {
            val normalizedBm25 = if (maxBm25 > 1e-6f) rawBm25[i] / maxBm25 else 0f
            val combined = bm25Weight * normalizedBm25 + jaccardWeight * rawJaccard[i]
            if (combined > bestCombined) {
                bestCombined = combined
                bestIdx = i
            }
        }

        val winner = docs[bestIdx].paragraph
        val snippet = winner.text.trim().let {
            if (it.length <= 140) it else it.take(137).trimEnd() + "…"
        }

        return GroundedBullet(
            text = bulletText.trim(),
            groundedParaId = winner.paraId,
            bm25Score = rawBm25[bestIdx],
            charFourGramJaccard = rawJaccard[bestIdx],
            combinedScore = bestCombined.coerceAtLeast(0f),
            sourceSnippet = snippet,
        )
    }
}
