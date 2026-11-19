package app.focal.engine.webview

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DomTextSegment(
    val id: Int,
    val text: String,
)

/**
 * UTF-16 surrogate-safe DOM text segment chunker & batcher for on-device ML Kit translation.
 *
 * - Never splits UTF-16 surrogate pairs (emoji, astral CJK symbols).
 * - Preserves exact leading and trailing whitespace around inline DOM nodes (`Hello <b>world</b>`).
 * - Groups pieces into bounded ~2,500-character batches so visible viewport text translates
 *   progressively without blocking main-thread DOM responsiveness.
 */
object DomTranslationBatcher {
    const val DEFAULT_MAX_CHARS_PER_PIECE = 2_500
    const val DEFAULT_MAX_BATCH_CHARS = 2_500
    const val DEFAULT_MAX_PIECES_PER_BATCH = 24

    data class TextPiece(
        val segmentId: Int,
        val coreText: String,
        val leadingWhitespace: String = "",
        val trailingWhitespace: String = "",
    )

    // The 59 BCP-47 language codes supported by com.google.mlkit:translate:17.0.3
    private val SUPPORTED_TRANSLATE_CODES: Set<String> = setOf(
        "af", "ar", "be", "bg", "bn", "ca", "cs", "cy", "da", "de", "el", "en", "eo", "es", "et",
        "fa", "fi", "fr", "ga", "gl", "gu", "he", "hi", "hr", "ht", "hu", "id", "is", "it", "ja",
        "ka", "kn", "ko", "lt", "lv", "mk", "mr", "ms", "mt", "nl", "no", "pl", "pt", "ro", "ru",
        "sk", "sl", "sq", "sv", "sw", "ta", "te", "th", "tl", "tr", "uk", "ur", "vi", "zh",
    )

    private val LANGUAGE_CODE_ALIASES: Map<String, String> = mapOf(
        "iw" to "he",
        "in" to "id",
        "nb" to "no",
        "nn" to "no",
        "fil" to "tl",
        "cmn" to "zh",
        "yue" to "zh",
    )

    /**
     * Normalizes an arbitrary BCP-47 tag (`"zh-Hans"`, `"fr-FR"`, `"nb-NO"`, `"und"`) into a
     * canonical ML Kit `TranslateLanguage` code, or `null` if unsupported/undetermined.
     */
    fun normalizeLanguageCode(rawTag: String?): String? {
        if (rawTag.isNullOrBlank()) return null
        val primary = rawTag.trim()
            .replace('_', '-')
            .substringBefore('-')
            .lowercase(Locale.US)
        if (primary.isEmpty() || primary == "und") return null
        val mapped = LANGUAGE_CODE_ALIASES[primary] ?: primary
        return mapped.takeIf { it in SUPPORTED_TRANSLATE_CODES }
    }

    /**
     * Splits [segments] into UTF-16 surrogate-safe [TextPiece]s no longer than [maxCharsPerPiece],
     * preserving exact leading and trailing whitespace around each piece.
     */
    fun splitSurrogateSafe(
        segments: List<DomTextSegment>,
        maxCharsPerPiece: Int = DEFAULT_MAX_CHARS_PER_PIECE,
    ): List<TextPiece> {
        require(maxCharsPerPiece >= 2) { "maxCharsPerPiece must allow at least one surrogate pair (2 UTF-16 code units)" }
        val seenIds = HashSet<Int>(segments.size)
        val result = ArrayList<TextPiece>(segments.size)

        for (segment in segments) {
            require(seenIds.add(segment.id)) { "Duplicate DOM segment id: ${segment.id}" }
            val text = segment.text
            if (text.isEmpty()) {
                result += TextPiece(segmentId = segment.id, coreText = "")
                continue
            }

            var offset = 0
            while (offset < text.length) {
                var end = minOf(offset + maxCharsPerPiece, text.length)

                // Never slice between a UTF-16 high surrogate and low surrogate
                if (end < text.length && end > offset &&
                    text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()
                ) {
                    end--
                }
                if (end == offset) {
                    // Single surrogate pair at boundary when maxCharsPerPiece == 2
                    end = minOf(offset + 2, text.length)
                }

                // Prefer splitting at a natural word boundary in the back half of the window
                if (end < text.length) {
                    val spaceIdx = text.lastIndexOf(' ', end - 1)
                    if (spaceIdx > offset + (maxCharsPerPiece / 2)) {
                        end = spaceIdx + 1
                    }
                }

                val slice = text.substring(offset, end)
                val leading = slice.takeWhile(Char::isWhitespace)
                val remainder = slice.substring(leading.length)
                val trailing = remainder.takeLastWhile(Char::isWhitespace)
                val core = remainder.substring(0, remainder.length - trailing.length)

                result += TextPiece(
                    segmentId = segment.id,
                    coreText = core,
                    leadingWhitespace = leading,
                    trailingWhitespace = trailing,
                )
                offset = end
            }
        }
        return result
    }

    /**
     * Groups [pieces] into bounded batches capped by [maxBatchChars] UTF-16 code units and
     * [maxPiecesPerBatch] items per batch.
     */
    fun groupIntoBatches(
        pieces: List<TextPiece>,
        maxBatchChars: Int = DEFAULT_MAX_BATCH_CHARS,
        maxPiecesPerBatch: Int = DEFAULT_MAX_PIECES_PER_BATCH,
    ): List<List<TextPiece>> {
        require(maxBatchChars > 0)
        require(maxPiecesPerBatch > 0)
        if (pieces.isEmpty()) return emptyList()

        val batches = ArrayList<List<TextPiece>>()
        var current = ArrayList<TextPiece>()
        var currentChars = 0

        for (piece in pieces) {
            val pieceLen = piece.coreText.length
            val wouldExceedChars = current.isNotEmpty() && (currentChars + pieceLen > maxBatchChars)
            val wouldExceedCount = current.size >= maxPiecesPerBatch
            if (wouldExceedChars || wouldExceedCount) {
                batches += current
                current = ArrayList()
                currentChars = 0
            }
            current += piece
            currentChars += pieceLen
        }
        if (current.isNotEmpty()) {
            batches += current
        }
        return batches
    }

    /**
     * Reassembles translated [TextPiece] cores back into complete [DomTextSegment]s with exact
     * preserved whitespace and original segment order.
     */
    fun reassembleSegments(
        pieces: List<TextPiece>,
        translatedCores: List<String>,
    ): List<DomTextSegment> {
        require(pieces.size == translatedCores.size) {
            "Translated piece count (${translatedCores.size}) != source piece count (${pieces.size})"
        }
        val grouped = LinkedHashMap<Int, StringBuilder>()
        for (i in pieces.indices) {
            val piece = pieces[i]
            val sb = grouped.getOrPut(piece.segmentId) { StringBuilder() }
            sb.append(piece.leadingWhitespace)
            sb.append(translatedCores[i])
            sb.append(piece.trailingWhitespace)
        }
        return grouped.entries.map { (id, sb) -> DomTextSegment(id = id, text = sb.toString()) }
    }

    /**
     * Stateful progressive reassembler that flushes each [DomTextSegment] to the DOM as soon as
     * all of its [TextPiece]s have finished translating across one or more batches.
     */
    class ProgressiveAssembler(allPieces: List<TextPiece>) {
        private val remainingCounts = HashMap<Int, Int>()
        private val builders = LinkedHashMap<Int, StringBuilder>()

        init {
            for (piece in allPieces) {
                remainingCounts[piece.segmentId] = (remainingCounts[piece.segmentId] ?: 0) + 1
            }
        }

        fun acceptBatch(
            batchPieces: List<TextPiece>,
            translatedCores: List<String>,
        ): List<DomTextSegment> {
            require(batchPieces.size == translatedCores.size) {
                "Batch translated count (${translatedCores.size}) != batch piece count (${batchPieces.size})"
            }
            val completed = ArrayList<DomTextSegment>()
            for (i in batchPieces.indices) {
                val piece = batchPieces[i]
                val sb = builders.getOrPut(piece.segmentId) { StringBuilder() }
                sb.append(piece.leadingWhitespace)
                sb.append(translatedCores[i])
                sb.append(piece.trailingWhitespace)

                val remaining = (remainingCounts[piece.segmentId] ?: 1) - 1
                if (remaining <= 0) {
                    remainingCounts.remove(piece.segmentId)
                    builders.remove(piece.segmentId)
                    completed += DomTextSegment(id = piece.segmentId, text = sb.toString())
                } else {
                    remainingCounts[piece.segmentId] = remaining
                }
            }
            return completed
        }
    }
}

/**
 * Abstraction over ML Kit on-device language identification and neural machine translation
 * so page translation flows can be verified deterministically in JVM unit tests.
 */
interface OnDeviceTranslationEngine {
    suspend fun identifyLanguage(sampleText: String): String?

    suspend fun translateBatches(
        sourceLanguage: String,
        targetLanguage: String,
        batches: List<List<String>>,
        onDownloadingModel: () -> Unit,
        onTranslating: () -> Unit = {},
        onBatchTranslated: suspend (batchIndex: Int, translatedTexts: List<String>) -> Unit,
    )
}

/**
 * 100% on-device Google ML Kit translation engine (`com.google.mlkit:language-id` + `translate`).
 *
 * Page content never leaves the device; language models (~30MB each) are downloaded once on demand
 * by ML Kit and executed locally on-device with zero external translation proxy tabs.
 */
class MlKitTranslationEngine : OnDeviceTranslationEngine {

    override suspend fun identifyLanguage(sampleText: String): String? {
        val sample = sampleText.trim().take(4_000)
        if (sample.isEmpty()) return null
        val identifier = LanguageIdentification.getClient()
        return try {
            val raw = identifier.identifyLanguage(sample).awaitTask()
            DomTranslationBatcher.normalizeLanguageCode(raw)
        } finally {
            identifier.close()
        }
    }

    override suspend fun translateBatches(
        sourceLanguage: String,
        targetLanguage: String,
        batches: List<List<String>>,
        onDownloadingModel: () -> Unit,
        onTranslating: () -> Unit,
        onBatchTranslated: suspend (batchIndex: Int, translatedTexts: List<String>) -> Unit,
    ) {
        val srcCode = TranslateLanguage.fromLanguageTag(sourceLanguage)
            ?: throw IllegalArgumentException("Unsupported source language: $sourceLanguage")
        val dstCode = TranslateLanguage.fromLanguageTag(targetLanguage)
            ?: throw IllegalArgumentException("Unsupported target language: $targetLanguage")

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcCode)
            .setTargetLanguage(dstCode)
            .build()
        val translator = Translation.getClient(options)

        try {
            val modelManager = RemoteModelManager.getInstance()
            val requiredModels = setOf(srcCode, dstCode)
            var needsDownload = false
            for (lang in requiredModels) {
                val downloaded = runCatching {
                    modelManager
                        .isModelDownloaded(TranslateRemoteModel.Builder(lang).build())
                        .awaitTask()
                }.getOrDefault(false)
                if (!downloaded) {
                    needsDownload = true
                    break
                }
            }
            if (needsDownload) {
                onDownloadingModel()
            }
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitTask()
            onTranslating()

            withContext(Dispatchers.Default) {
                for ((batchIndex, batchTexts) in batches.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val translated = ArrayList<String>(batchTexts.size)
                    for (item in batchTexts) {
                        currentCoroutineContext().ensureActive()
                        if (item.isBlank()) {
                            translated += item
                        } else {
                            translated += translator.translate(item).awaitTask()
                        }
                    }
                    onBatchTranslated(batchIndex, translated)
                }
            }
        } finally {
            translator.close()
        }
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
    }
    addOnFailureListener { error ->
        if (cont.isActive) cont.resumeWithException(error)
    }
    addOnCanceledListener {
        cont.cancel()
    }
}

/**
 * DOM walker, in-place text-node patcher, and instant original-text restorer scripts.
 *
 * All scripts verify a monotonic generation [token] stored on `window.__focalTranslationState`
 * so late translation batches can never mutate a newly navigated document.
 */
object InPlaceDomTranslationScripts {
    const val TITLE_SEGMENT_ID = -1

    fun buildCollectScript(token: Long, maxNodes: Int = 800): String = """
        (function() {
          try {
            const prev = window.__focalTranslationState;
            if (prev && Array.isArray(prev.nodes) && Array.isArray(prev.originals)) {
              if (typeof prev.origTitle === 'string') document.title = prev.origTitle;
              for (let i = 0; i < prev.nodes.length; i++) {
                const n = prev.nodes[i];
                if (n && n.isConnected) n.nodeValue = prev.originals[i];
              }
            }
          } catch (_) {}

          const body = document.body;
          if (!body) {
            return JSON.stringify({ htmlLang: document.documentElement ? (document.documentElement.lang || '') : '', segments: [] });
          }

          const SKIP_TAGS = {
            SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1, CODE: 1, PRE: 1,
            KBD: 1, SAMP: 1, SVG: 1, MATH: 1, TEXTAREA: 1, INPUT: 1,
            SELECT: 1, OPTION: 1, IFRAME: 1, CANVAS: 1, VIDEO: 1, AUDIO: 1
          };

          function isSkippedElement(el) {
            let cur = el;
            while (cur && cur.nodeType === 1) {
              if (SKIP_TAGS[cur.tagName]) return true;
              if (cur.isContentEditable) return true;
              const tr = cur.getAttribute && cur.getAttribute('translate');
              if (tr && tr.toLowerCase() === 'no') return true;
              if (cur.classList && cur.classList.contains('notranslate')) return true;
              cur = cur.parentElement;
            }
            return false;
          }

          const letterRegex = /[A-Za-z\u00C0-\u024F\u0370-\u03FF\u0400-\u04FF\u0590-\u05FF\u0600-\u06FF\u0900-\u097F\u3040-\u30FF\u3400-\u9FFF\uAC00-\uD7AF]/;
          const walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
          const viewportH = window.innerHeight || 900;
          const inView = [];
          const outView = [];

          let node;
          while ((node = walker.nextNode())) {
            const raw = node.nodeValue;
            if (!raw || raw.trim().length < 2) continue;
            if (!letterRegex.test(raw)) continue;
            const parent = node.parentElement;
            if (!parent || isSkippedElement(parent)) continue;
            const rect = parent.getBoundingClientRect();
            if (rect.width === 0 && rect.height === 0) continue;
            const visibleNow = rect.bottom >= -120 && rect.top <= viewportH + 240;
            if (visibleNow) {
              inView.push(node);
            } else {
              outView.push(node);
            }
          }

          const orderedNodes = inView.concat(outView).slice(0, $maxNodes);
          const originals = [];
          const segments = [];
          const origTitle = document.title || '';

          if (origTitle.trim().length >= 2 && letterRegex.test(origTitle)) {
            segments.push({ id: $TITLE_SEGMENT_ID, text: origTitle });
          }

          for (let i = 0; i < orderedNodes.length; i++) {
            const raw = orderedNodes[i].nodeValue;
            originals.push(raw);
            segments.push({ id: i, text: raw });
          }

          window.__focalTranslationState = {
            token: $token,
            origTitle: origTitle,
            nodes: orderedNodes,
            originals: originals
          };

          return JSON.stringify({
            htmlLang: document.documentElement ? (document.documentElement.lang || '') : '',
            segments: segments
          });
        })();
    """.trimIndent()

    fun buildApplyScript(token: Long, updates: List<DomTextSegment>): String {
        val payloadLiteral = buildString {
            append('[')
            updates.forEachIndexed { index, segment ->
                if (index > 0) append(',')
                append("{\"id\":")
                append(segment.id)
                append(",\"text\":")
                appendJsonString(segment.text)
                append('}')
            }
            append(']')
        }
        return """
            (function() {
              const st = window.__focalTranslationState;
              if (!st || st.token !== $token) return false;
              const updates = $payloadLiteral;
              for (let i = 0; i < updates.length; i++) {
                const item = updates[i];
                if (item.id === $TITLE_SEGMENT_ID) {
                  document.title = item.text;
                } else if (item.id >= 0 && item.id < st.nodes.length) {
                  const n = st.nodes[item.id];
                  if (n && n.isConnected) {
                    n.nodeValue = item.text;
                  }
                }
              }
              return true;
            })();
        """.trimIndent()
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> {
                    if (ch.code < 0x20) {
                        append(String.format(Locale.US, "\\u%04x", ch.code))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }

    const val RESTORE_ORIGINAL_SCRIPT: String = """
        (function() {
          const st = window.__focalTranslationState;
          if (!st) return false;
          try {
            if (typeof st.origTitle === 'string') {
              document.title = st.origTitle;
            }
            if (Array.isArray(st.nodes) && Array.isArray(st.originals)) {
              for (let i = 0; i < st.nodes.length; i++) {
                const n = st.nodes[i];
                if (n && n.isConnected) {
                  n.nodeValue = st.originals[i];
                }
              }
            }
          } finally {
            window.__focalTranslationState = null;
          }
          return true;
        })();
    """
}
