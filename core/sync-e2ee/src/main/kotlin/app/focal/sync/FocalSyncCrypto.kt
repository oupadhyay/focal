package app.focal.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

open class FocalCryptoException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class FocalReplayException(
    val senderId: String,
    val receivedSeq: Long,
    val highestSeq: Long,
) : FocalCryptoException(
    "Replay or stale envelope rejected for sender \"$senderId\": seq=$receivedSeq <= highestSeq=$highestSeq",
)

data class DerivedFocalKeys(
    val roomId: String,
    val roomIdBytes: ByteArray,
    val symKey: ByteArray,
    val sasBytes: ByteArray,
    val sasWords: List<String>,
    val sasDigits: Int,
    val sas: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DerivedFocalKeys) return false
        return roomId == other.roomId &&
            roomIdBytes.contentEquals(other.roomIdBytes) &&
            symKey.contentEquals(other.symKey) &&
            sasBytes.contentEquals(other.sasBytes) &&
            sas == other.sas
    }

    override fun hashCode(): Int = roomId.hashCode()
}

data class ParsedPairingUri(
    val secret: ByteArray,
    val secretBase64Url: String,
    val relayUrl: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedPairingUri) return false
        return secret.contentEquals(other.secret) &&
            secretBase64Url == other.secretBase64Url &&
            relayUrl == other.relayUrl
    }

    override fun hashCode(): Int = secretBase64Url.hashCode()
}

data class PairingConfirmationPreview(
    val normalizedUri: String,
    val sas: String,
    val roomIdShort: String,
    val relayHost: String,
    val relayUrl: String,
    val isCustomRelay: Boolean,
)

/**
 * Per-sender monotonic sequence anti-replay tracker.
 * Rejects duplicate (`seq == highest`) and stale (`seq < highest`) envelopes.
 */
class ReplayTracker(
    maxSenders: Int = 64,
) {
    private val capacity = maxSenders.coerceAtLeast(4)
    private val highestSeqBySender = LinkedHashMap<String, Long>(capacity, 0.75f, true)

    @Synchronized
    fun accept(senderId: String, seq: Long) {
        if (senderId.isBlank()) {
            throw FocalCryptoException("Invalid senderId in envelope")
        }
        if (seq < 0L) {
            throw FocalCryptoException("Invalid sequence number: $seq")
        }
        val prev = highestSeqBySender[senderId]
        if (prev != null && seq <= prev) {
            throw FocalReplayException(senderId = senderId, receivedSeq = seq, highestSeq = prev)
        }
        if (prev == null && highestSeqBySender.size >= capacity) {
            val eldestKey = highestSeqBySender.keys.firstOrNull()
            if (eldestKey != null) {
                highestSeqBySender.remove(eldestKey)
            }
        }
        highestSeqBySender[senderId] = seq
    }

    @Synchronized
    fun getHighestSeq(senderId: String): Long? = highestSeqBySender[senderId]

    @Synchronized
    fun snapshot(): Map<String, Long> = LinkedHashMap(highestSeqBySender)

    @Synchronized
    fun restore(snapshot: Map<String, Long>) {
        highestSeqBySender.clear()
        for ((sender, seq) in snapshot) {
            if (sender.isBlank() || seq < 0L) continue
            if (highestSeqBySender.size >= capacity) {
                val eldestKey = highestSeqBySender.keys.firstOrNull()
                if (eldestKey != null) {
                    highestSeqBySender.remove(eldestKey)
                }
            }
            highestSeqBySender[sender] = seq
        }
    }

    @Synchronized
    fun clear() {
        highestSeqBySender.clear()
    }
}

/**
 * Zero-Account E2EE Wire & Crypto Engine matching `desktop-extension/src/crypto.ts` byte-for-byte:
 *
 * - Pairing URI: `focal-pair://v1?secret=<base64url_nopad_32bytes>`
 * - KDF: `HKDF-SHA256` with UTF-8 salt `"focal-sync-v1-salt"`:
 *   - `roomId` (64-char lowercase hex) = `HKDF-Expand(PRK, "focal-room-id-v1", 32)`
 *   - `symKey` (32 bytes)              = `HKDF-Expand(PRK, "focal-aead-key-v1", 32)`
 *   - `sasBytes` (4 bytes)             = `HKDF-Expand(PRK, "focal-sas-v1", 4)`
 * - AEAD: `AES-256-GCM` (`12-byte` random IV prepended to `ciphertext || 16-byte tag`,
 *   AAD = UTF-8 `roomId` string).
 */
object FocalSyncCrypto {
    const val PAIRING_SCHEME_PREFIX = "focal-pair://v1"
    const val SECRET_BYTE_LENGTH = 32
    const val ROOM_ID_BYTE_LENGTH = 32
    const val SYM_KEY_BYTE_LENGTH = 32
    const val SAS_BYTE_LENGTH = 4
    const val AES_GCM_IV_BYTE_LENGTH = 12
    const val AES_GCM_TAG_BYTE_LENGTH = 16
    const val AES_GCM_TAG_BIT_LENGTH = 128

    const val HKDF_SALT = "focal-sync-v1-salt"
    const val HKDF_INFO_ROOM_ID = "focal-room-id-v1"
    const val HKDF_INFO_AEAD_KEY = "focal-aead-key-v1"
    const val HKDF_INFO_SAS = "focal-sas-v1"

    /**
     * Exact 64-word optical/nature SAS wordlist (`sasBytes[i] & 63`).
     */
    val SAS_WORDS: List<String> = listOf(
        "amber", "aperture", "argon", "atlas", "aurora", "basalt", "beacon", "beryl",
        "bloom", "breeze", "cairn", "calcite", "canopy", "carbon", "cedar", "chroma",
        "cipher", "cirrus", "cobalt", "comet", "coral", "corona", "cortex", "cosmos",
        "crest", "crystal", "cypress", "delta", "dew", "dune", "eclipse", "ember",
        "epoch", "ether", "facet", "fern", "fjord", "flare", "flint", "focal",
        "frost", "galaxy", "garnet", "glacier", "granite", "grove", "halo", "harbor",
        "helix", "horizon", "iris", "ivory", "jade", "jasper", "kelp", "kernel",
        "lagoon", "lantern", "lumen", "lunar", "meadow", "mirage", "nebula", "obsidian",
    )

    private const val BASE64URL_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private val BASE64URL_LOOKUP = IntArray(128) { -1 }.apply {
        for (i in BASE64URL_ALPHABET.indices) {
            this[BASE64URL_ALPHABET[i].code] = i
        }
    }

    private val ROOM_ID_REGEX = Regex("^[0-9a-f]{64}$")
    private val secureRandom = SecureRandom()

    val wireJson: Json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    /**
     * Encodes raw bytes to unpadded RFC 4648 §5 base64url.
     */
    fun bytesToBase64Url(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i + 2 < bytes.size) {
            val b0 = bytes[i++].toInt() and 0xFF
            val b1 = bytes[i++].toInt() and 0xFF
            val b2 = bytes[i++].toInt() and 0xFF
            val triplet = (b0 shl 16) or (b1 shl 8) or b2
            out.append(BASE64URL_ALPHABET[(triplet ushr 18) and 63])
            out.append(BASE64URL_ALPHABET[(triplet ushr 12) and 63])
            out.append(BASE64URL_ALPHABET[(triplet ushr 6) and 63])
            out.append(BASE64URL_ALPHABET[triplet and 63])
        }
        when (bytes.size - i) {
            1 -> {
                val b0 = bytes[i].toInt() and 0xFF
                out.append(BASE64URL_ALPHABET[(b0 ushr 2) and 63])
                out.append(BASE64URL_ALPHABET[(b0 and 3) shl 4])
            }
            2 -> {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = bytes[i + 1].toInt() and 0xFF
                val pair = (b0 shl 8) or b1
                out.append(BASE64URL_ALPHABET[(pair ushr 10) and 63])
                out.append(BASE64URL_ALPHABET[(pair ushr 4) and 63])
                out.append(BASE64URL_ALPHABET[(pair and 15) shl 2])
            }
        }
        return out.toString()
    }

    /**
     * Decodes unpadded RFC 4648 §5 base64url into raw bytes, rejecting `=` padding,
     * non-base64url characters, impossible lengths, and non-canonical trailing bits.
     */
    fun base64UrlToBytes(encoded: String): ByteArray {
        val len = encoded.length
        if (len == 0) return ByteArray(0)
        if (len % 4 == 1) {
            throw FocalCryptoException("Invalid base64url length: $len")
        }

        val sextets = IntArray(len)
        for (i in 0 until len) {
            val code = encoded[i].code
            val value = if (code < 128) BASE64URL_LOOKUP[code] else -1
            if (value < 0) {
                throw FocalCryptoException("Invalid base64url character at index $i")
            }
            sextets[i] = value
        }

        val rem = len % 4
        if (rem == 2 && (sextets[len - 1] and 0x0F) != 0) {
            throw FocalCryptoException("Non-canonical base64url trailing bits")
        }
        if (rem == 3 && (sextets[len - 1] and 0x03) != 0) {
            throw FocalCryptoException("Non-canonical base64url trailing bits")
        }

        val outLen = (len * 3) / 4
        val out = ByteArray(outLen)
        var inIdx = 0
        var outIdx = 0
        while (inIdx + 3 < len) {
            val quad = (sextets[inIdx++] shl 18) or
                (sextets[inIdx++] shl 12) or
                (sextets[inIdx++] shl 6) or
                sextets[inIdx++]
            out[outIdx++] = ((quad ushr 16) and 0xFF).toByte()
            out[outIdx++] = ((quad ushr 8) and 0xFF).toByte()
            out[outIdx++] = (quad and 0xFF).toByte()
        }
        if (rem == 2) {
            val s0 = sextets[inIdx]
            val s1 = sextets[inIdx + 1]
            out[outIdx] = (((s0 shl 2) or (s1 ushr 4)) and 0xFF).toByte()
        } else if (rem == 3) {
            val triple = (sextets[inIdx] shl 12) or (sextets[inIdx + 1] shl 6) or sextets[inIdx + 2]
            out[outIdx++] = ((triple ushr 10) and 0xFF).toByte()
            out[outIdx] = ((triple ushr 2) and 0xFF).toByte()
        }
        return out
    }

    fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(hexChars[v ushr 4])
            sb.append(hexChars[v and 0x0F])
        }
        return sb.toString()
    }

    fun isValidRoomId(roomId: String): Boolean = ROOM_ID_REGEX.matches(roomId)

    fun generatePairingSecret(): ByteArray {
        val secret = ByteArray(SECRET_BYTE_LENGTH)
        secureRandom.nextBytes(secret)
        return secret
    }

    fun formatPairingUri(secret: ByteArray, relayUrl: String? = null): String {
        if (secret.size != SECRET_BYTE_LENGTH) {
            throw FocalCryptoException(
                "Pairing secret must be exactly $SECRET_BYTE_LENGTH bytes (got ${secret.size})",
            )
        }
        val secretParam = bytesToBase64Url(secret)
        return if (!relayUrl.isNullOrBlank()) {
            val encodedRelay = URLEncoder.encode(relayUrl, StandardCharsets.UTF_8.name())
            "$PAIRING_SCHEME_PREFIX?secret=$secretParam&relay=$encodedRelay"
        } else {
            "$PAIRING_SCHEME_PREFIX?secret=$secretParam"
        }
    }

    fun parsePairingUri(
        uri: String,
        allowInsecureLocalhost: Boolean = false,
    ): ParsedPairingUri {
        val trimmed = uri.trim()
        val expectedPrefix = "$PAIRING_SCHEME_PREFIX?"
        if (!trimmed.startsWith(expectedPrefix)) {
            throw FocalCryptoException("Invalid pairing URI: must start with \"$expectedPrefix\"")
        }

        val rawQuery = trimmed.substring(expectedPrefix.length).substringBefore('#')
        val secrets = mutableListOf<String>()
        var relayParam: String? = null

        if (rawQuery.isNotEmpty()) {
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val eqIdx = pair.indexOf('=')
                val rawKey = if (eqIdx >= 0) pair.substring(0, eqIdx) else pair
                val rawVal = if (eqIdx >= 0) pair.substring(eqIdx + 1) else ""
                val key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name())
                val value = URLDecoder.decode(rawVal, StandardCharsets.UTF_8.name())
                when (key) {
                    "secret" -> secrets.add(value)
                    "relay" -> if (relayParam == null) relayParam = value.trim()
                }
            }
        }

        if (secrets.size != 1) {
            throw FocalCryptoException("Pairing URI must include exactly one 'secret' parameter")
        }

        val secretBase64Url = secrets[0]
        val secret = base64UrlToBytes(secretBase64Url)
        if (secret.size != SECRET_BYTE_LENGTH) {
            throw FocalCryptoException(
                "Pairing secret must decode to exactly $SECRET_BYTE_LENGTH bytes (got ${secret.size})",
            )
        }

        val validatedRelay = relayParam?.takeIf { it.isNotEmpty() }?.also { candidate ->
            val parsed = try {
                URI(candidate)
            } catch (e: Exception) {
                throw FocalCryptoException("Invalid relay URL in pairing URI", e)
            }
            val scheme = parsed.scheme?.lowercase()
            val host = parsed.host?.lowercase()
            if (host.isNullOrBlank()) {
                throw FocalCryptoException("Relay URL in pairing URI must include a host")
            }
            val isTlsScheme = scheme == "wss" || scheme == "https"
            val isLocalLoopback = host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
            val isInsecureLocalAllowed =
                allowInsecureLocalhost && isLocalLoopback && (scheme == "ws" || scheme == "http")
            if (!isTlsScheme && !isInsecureLocalAllowed) {
                throw FocalCryptoException(
                    "Relay URL in pairing URI must use wss:// or https://",
                )
            }
        }

        return ParsedPairingUri(
            secret = secret,
            secretBase64Url = secretBase64Url,
            relayUrl = validatedRelay,
        )
    }

    /**
     * Parses a `focal-pair://` URI without joining or connecting, returning the derived 4-part SAS
     * code and relay host for explicit user confirmation (`P0-6`).
     */
    fun previewPairingUri(
        uri: String,
        defaultRelayUrl: String = "wss://relay.focal.app",
        allowInsecureLocalhost: Boolean = false,
    ): PairingConfirmationPreview {
        val parsed = parsePairingUri(uri = uri, allowInsecureLocalhost = allowInsecureLocalhost)
        val keys = deriveFocalKeys(parsed.secret)
        val effectiveRelay = parsed.relayUrl ?: defaultRelayUrl
        val relayHost = try {
            val u = URI(effectiveRelay)
            val host = u.host.orEmpty()
            if (u.port > 0) "$host:${u.port}" else host
        } catch (_: Exception) {
            effectiveRelay
        }.ifBlank { effectiveRelay }

        val isCustomRelay = parsed.relayUrl != null &&
            !parsed.relayUrl.trimEnd('/').equals(defaultRelayUrl.trimEnd('/'), ignoreCase = true)

        return PairingConfirmationPreview(
            normalizedUri = formatPairingUri(parsed.secret, parsed.relayUrl),
            sas = keys.sas,
            roomIdShort = keys.roomId.take(12),
            relayHost = relayHost,
            relayUrl = effectiveRelay,
            isCustomRelay = isCustomRelay,
        )
    }

    /**
     * Formats 4 SAS bytes into `"word1 · word2 · word3 · NN"` matching Desktop Extension.
     */
    fun formatSasBytes(sasBytes: ByteArray): String {
        if (sasBytes.size != SAS_BYTE_LENGTH) {
            throw FocalCryptoException(
                "SAS bytes must be exactly $SAS_BYTE_LENGTH bytes (got ${sasBytes.size})",
            )
        }
        val w1 = SAS_WORDS[sasBytes[0].toInt() and 63]
        val w2 = SAS_WORDS[sasBytes[1].toInt() and 63]
        val w3 = SAS_WORDS[sasBytes[2].toInt() and 63]
        val nn = ((sasBytes[3].toInt() and 0xFF) % 100).toString().padStart(2, '0')
        return "$w1 · $w2 · $w3 · $nn"
    }

    /**
     * RFC 5869 `HKDF-Extract(salt, IKM)` using `HMAC-SHA256`.
     */
    fun hkdfExtractSha256(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    /**
     * RFC 5869 `HKDF-Expand(PRK, info, length)` using `HMAC-SHA256`.
     */
    fun hkdfExpandSha256(prk: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        require(outputLength in 1..(255 * 32)) { "Invalid HKDF outputLength: $outputLength" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val result = ByteArray(outputLength)
        var prevBlock = ByteArray(0)
        var generated = 0
        var counter = 1

        while (generated < outputLength) {
            mac.reset()
            mac.update(prevBlock)
            mac.update(info)
            mac.update(counter.toByte())
            prevBlock = mac.doFinal()

            val toCopy = minOf(prevBlock.size, outputLength - generated)
            System.arraycopy(prevBlock, 0, result, generated, toCopy)
            generated += toCopy
            counter++
        }
        return result
    }

    /**
     * Derives `roomId` (64-char lowercase hex), `symKey` (32-byte AES-256-GCM), and `sas`
     * from a 32-byte secret using `HKDF-SHA256` with UTF-8 salt `"focal-sync-v1-salt"`.
     */
    fun deriveFocalKeys(secret: ByteArray): DerivedFocalKeys {
        if (secret.size != SECRET_BYTE_LENGTH) {
            throw FocalCryptoException(
                "Secret must be $SECRET_BYTE_LENGTH bytes (got ${secret.size})",
            )
        }

        val saltBytes = HKDF_SALT.toByteArray(StandardCharsets.UTF_8)
        val prk = hkdfExtractSha256(salt = saltBytes, ikm = secret)

        val roomIdBytes = hkdfExpandSha256(
            prk = prk,
            info = HKDF_INFO_ROOM_ID.toByteArray(StandardCharsets.UTF_8),
            outputLength = ROOM_ID_BYTE_LENGTH,
        )
        val symKeyBytes = hkdfExpandSha256(
            prk = prk,
            info = HKDF_INFO_AEAD_KEY.toByteArray(StandardCharsets.UTF_8),
            outputLength = SYM_KEY_BYTE_LENGTH,
        )
        val sasBytes = hkdfExpandSha256(
            prk = prk,
            info = HKDF_INFO_SAS.toByteArray(StandardCharsets.UTF_8),
            outputLength = SAS_BYTE_LENGTH,
        )

        val words = listOf(
            SAS_WORDS[sasBytes[0].toInt() and 63],
            SAS_WORDS[sasBytes[1].toInt() and 63],
            SAS_WORDS[sasBytes[2].toInt() and 63],
        )
        val digits = (sasBytes[3].toInt() and 0xFF) % 100

        return DerivedFocalKeys(
            roomId = bytesToHex(roomIdBytes),
            roomIdBytes = roomIdBytes,
            symKey = symKeyBytes,
            sasBytes = sasBytes,
            sasWords = words,
            sasDigits = digits,
            sas = formatSasBytes(sasBytes),
        )
    }

    /**
     * Encrypts plaintext bytes with `AES-256-GCM` (`12-byte IV || ciphertext || 16-byte tag`)
     * using `AAD = UTF-8(roomId)`.
     */
    fun encryptRawBytes(
        plaintext: ByteArray,
        symKey: ByteArray,
        roomId: String,
        ivOverride: ByteArray? = null,
    ): ByteArray {
        if (!isValidRoomId(roomId)) {
            throw FocalCryptoException("roomId must be a 64-character lowercase hex string")
        }
        if (symKey.size != SYM_KEY_BYTE_LENGTH) {
            throw FocalCryptoException("symKey must be 32 bytes")
        }
        val iv = ivOverride?.also {
            if (it.size != AES_GCM_IV_BYTE_LENGTH) {
                throw FocalCryptoException("IV must be 12 bytes")
            }
        } ?: ByteArray(AES_GCM_IV_BYTE_LENGTH).also(secureRandom::nextBytes)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(symKey, "AES"),
            GCMParameterSpec(AES_GCM_TAG_BIT_LENGTH, iv),
        )
        cipher.updateAAD(roomId.toByteArray(StandardCharsets.UTF_8))
        val ciphertextWithTag = cipher.doFinal(plaintext)

        val wireBytes = ByteArray(AES_GCM_IV_BYTE_LENGTH + ciphertextWithTag.size)
        System.arraycopy(iv, 0, wireBytes, 0, AES_GCM_IV_BYTE_LENGTH)
        System.arraycopy(ciphertextWithTag, 0, wireBytes, AES_GCM_IV_BYTE_LENGTH, ciphertextWithTag.size)
        return wireBytes
    }

    /**
     * Decrypts `12-byte IV || ciphertext || 16-byte tag` with `AES-256-GCM` and `AAD = UTF-8(roomId)`.
     */
    fun decryptRawBytes(
        wireBytes: ByteArray,
        symKey: ByteArray,
        roomId: String,
    ): ByteArray {
        if (!isValidRoomId(roomId)) {
            throw FocalCryptoException("roomId must be a 64-character lowercase hex string")
        }
        if (symKey.size != SYM_KEY_BYTE_LENGTH) {
            throw FocalCryptoException("symKey must be 32 bytes")
        }
        val minBytes = AES_GCM_IV_BYTE_LENGTH + AES_GCM_TAG_BYTE_LENGTH
        if (wireBytes.size < minBytes) {
            throw FocalCryptoException("Wire frame too short (${wireBytes.size} bytes < $minBytes minimum)")
        }

        val iv = wireBytes.copyOfRange(0, AES_GCM_IV_BYTE_LENGTH)
        val ciphertextWithTag = wireBytes.copyOfRange(AES_GCM_IV_BYTE_LENGTH, wireBytes.size)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(symKey, "AES"),
                GCMParameterSpec(AES_GCM_TAG_BIT_LENGTH, iv),
            )
            cipher.updateAAD(roomId.toByteArray(StandardCharsets.UTF_8))
            cipher.doFinal(ciphertextWithTag)
        } catch (e: GeneralSecurityException) {
            throw FocalCryptoException(
                "AES-256-GCM authentication failed (invalid key, tampered ciphertext, or mismatched roomId AAD)",
                e,
            )
        }
    }

    /**
     * Encrypts an [EncryptedEnvelope] into `base64url_nopad(12B IV || ciphertext || 16B tag)`.
     */
    fun encryptEnvelope(
        envelope: EncryptedEnvelope,
        symKey: ByteArray,
        roomId: String,
    ): String {
        validateEnvelopeMetadata(envelope)
        val jsonBytes = wireJson.encodeToString(envelope).toByteArray(StandardCharsets.UTF_8)
        val wireBytes = encryptRawBytes(plaintext = jsonBytes, symKey = symKey, roomId = roomId)
        return bytesToBase64Url(wireBytes)
    }

    /**
     * Decrypts and authenticates `base64url_nopad(12B IV || ciphertext || 16B tag)`.
     * Mutates [replayTracker] ONLY after GCM tag authentication and schema validation pass.
     */
    fun decryptEnvelope(
        wireFrame: String,
        symKey: ByteArray,
        roomId: String,
        replayTracker: ReplayTracker? = null,
    ): EncryptedEnvelope {
        val wireBytes = base64UrlToBytes(wireFrame)
        val plaintextBytes = decryptRawBytes(wireBytes = wireBytes, symKey = symKey, roomId = roomId)

        val jsonText = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(plaintextBytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw FocalCryptoException("Decrypted payload is not valid UTF-8", e)
        }

        val parsed = try {
            wireJson.decodeFromString<EncryptedEnvelope>(jsonText)
        } catch (e: Exception) {
            throw FocalCryptoException("Decrypted payload is not a valid Focal envelope", e)
        }

        validateEnvelopeMetadata(parsed)
        replayTracker?.accept(senderId = parsed.senderId, seq = parsed.seq)
        return parsed
    }

    private fun validateEnvelopeMetadata(envelope: EncryptedEnvelope) {
        if (envelope.seq < 0L) {
            throw FocalCryptoException("Invalid envelope 'seq': ${envelope.seq}")
        }
        if (envelope.senderId.isBlank()) {
            throw FocalCryptoException("Invalid envelope 'senderId'")
        }
        if (envelope.timestampMs < 0L) {
            throw FocalCryptoException("Invalid envelope 'timestampMs': ${envelope.timestampMs}")
        }
    }
}
