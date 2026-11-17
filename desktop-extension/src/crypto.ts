/**
 * Focal Bridge — Shared Zero-Account E2EE Wire & Crypto Module.
 *
 * Matches Android `:core:sync-e2ee` byte-for-byte using pure WebCrypto (`crypto.subtle`):
 * - Pairing URI: `focal-pair://v1?secret=<base64url_nopad_32bytes>`
 * - KDF: `HKDF-SHA256` with UTF-8 salt `"focal-sync-v1-salt"`
 *   - `roomId` (64-char lowercase hex) = HKDF-Expand(PRK, "focal-room-id-v1", 32)
 *   - `symKey` (32-byte AES-256-GCM)   = HKDF-Expand(PRK, "focal-aead-key-v1", 32)
 *   - `sasBytes` (4 bytes)             = HKDF-Expand(PRK, "focal-sas-v1", 4)
 * - Wire frame: base64url_nopad(12-byte IV || ciphertext || 16-byte GCM tag)
 *   with AAD = UTF-8(roomId)
 */

export const PAIRING_SCHEME_PREFIX = "focal-pair://v1";
export const SECRET_BYTE_LENGTH = 32;
export const ROOM_ID_BYTE_LENGTH = 32;
export const SYM_KEY_BYTE_LENGTH = 32;
export const SAS_BYTE_LENGTH = 4;
export const AES_GCM_IV_BYTE_LENGTH = 12;
export const AES_GCM_TAG_BYTE_LENGTH = 16;
export const AES_GCM_TAG_BIT_LENGTH = 128;

export const HKDF_SALT = "focal-sync-v1-salt";
export const HKDF_INFO_ROOM_ID = "focal-room-id-v1";
export const HKDF_INFO_AEAD_KEY = "focal-aead-key-v1";
export const HKDF_INFO_SAS = "focal-sas-v1";

export const SAS_WORDS: readonly string[] = [
  "amber",
  "aperture",
  "argon",
  "atlas",
  "aurora",
  "basalt",
  "beacon",
  "beryl",
  "bloom",
  "breeze",
  "cairn",
  "calcite",
  "canopy",
  "carbon",
  "cedar",
  "chroma",
  "cipher",
  "cirrus",
  "cobalt",
  "comet",
  "coral",
  "corona",
  "cortex",
  "cosmos",
  "crest",
  "crystal",
  "cypress",
  "delta",
  "dew",
  "dune",
  "eclipse",
  "ember",
  "epoch",
  "ether",
  "facet",
  "fern",
  "fjord",
  "flare",
  "flint",
  "focal",
  "frost",
  "galaxy",
  "garnet",
  "glacier",
  "granite",
  "grove",
  "halo",
  "harbor",
  "helix",
  "horizon",
  "iris",
  "ivory",
  "jade",
  "jasper",
  "kelp",
  "kernel",
  "lagoon",
  "lantern",
  "lumen",
  "lunar",
  "meadow",
  "mirage",
  "nebula",
  "obsidian",
] as const;

export type SpaceId = "personal" | "work" | "reading";

export interface TabHandoffEvent {
  type: "tab_handoff";
  url: string;
  title: string;
  strippedParams?: string[];
  spaceId?: SpaceId;
  sourceDeviceName?: string;
}

export interface PresenceTabItem {
  id: string;
  url: string;
  title: string;
  spaceId?: SpaceId;
  active?: boolean;
  lastActiveMs?: number;
}

export interface PresenceTabsEvent {
  type: "presence_tabs";
  deviceName: string;
  platform: "desktop" | "android";
  tabs: PresenceTabItem[];
}

export interface SpaceBookmarkItem {
  id: string;
  title: string;
  url: string;
  pinned?: boolean;
  addedAtMs: number;
}

export interface SpaceModel {
  id: SpaceId;
  label: string;
  bookmarks: SpaceBookmarkItem[];
}

export interface SpacesSnapshotEvent {
  type: "spaces_snapshot";
  spaces: SpaceModel[];
  updatedAtMs: number;
}

export type SyncEvent =
  | TabHandoffEvent
  | PresenceTabsEvent
  | SpacesSnapshotEvent;

export interface EncryptedEnvelope<T = SyncEvent> {
  seq: number;
  senderId: string;
  timestampMs: number;
  event: T;
}

export interface DerivedFocalKeys {
  roomId: string;
  roomIdBytes: Uint8Array<ArrayBuffer>;
  symKey: CryptoKey;
  symKeyBytes: Uint8Array<ArrayBuffer>;
  sas: string;
  sasBytes: Uint8Array<ArrayBuffer>;
}

export interface ParsedPairingUri {
  secret: Uint8Array<ArrayBuffer>;
  secretBase64Url: string;
  relayUrl?: string;
}

export interface ParsePairingUriOptions {
  allowInsecureLocalhost?: boolean;
}

export class FocalCryptoError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "FocalCryptoError";
  }
}

export class FocalReplayError extends FocalCryptoError {
  readonly senderId: string;
  readonly receivedSeq: number;
  readonly highestSeq: number;

  constructor(senderId: string, receivedSeq: number, highestSeq: number) {
    super(
      `Replay or stale envelope rejected for sender "${senderId}": seq=${receivedSeq} <= highestSeq=${highestSeq}`
    );
    this.name = "FocalReplayError";
    this.senderId = senderId;
    this.receivedSeq = receivedSeq;
    this.highestSeq = highestSeq;
  }
}

const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder("utf-8", { fatal: true });

const BASE64URL_ALPHABET =
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
const BASE64URL_LOOKUP = new Int8Array(128).fill(-1);
for (let i = 0; i < BASE64URL_ALPHABET.length; i++) {
  BASE64URL_LOOKUP[BASE64URL_ALPHABET.charCodeAt(i)] = i;
}

function toOwnedUint8Array(bytes: Uint8Array): Uint8Array<ArrayBuffer> {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy;
}

function encodeUtf8(value: string): Uint8Array<ArrayBuffer> {
  return toOwnedUint8Array(textEncoder.encode(value));
}

/**
 * Encodes raw bytes to unpadded RFC 4648 §5 base64url.
 */
export function bytesToBase64Url(bytes: Uint8Array): string {
  let out = "";
  const len = bytes.byteLength;
  let i = 0;

  while (i + 2 < len) {
    const b0 = bytes[i++]!;
    const b1 = bytes[i++]!;
    const b2 = bytes[i++]!;
    const triplet = (b0 << 16) | (b1 << 8) | b2;
    out +=
      BASE64URL_ALPHABET[(triplet >>> 18) & 63]! +
      BASE64URL_ALPHABET[(triplet >>> 12) & 63]! +
      BASE64URL_ALPHABET[(triplet >>> 6) & 63]! +
      BASE64URL_ALPHABET[triplet & 63]!;
  }

  const rem = len - i;
  if (rem === 1) {
    const b0 = bytes[i]!;
    out +=
      BASE64URL_ALPHABET[(b0 >>> 2) & 63]! +
      BASE64URL_ALPHABET[(b0 & 3) << 4]!;
  } else if (rem === 2) {
    const b0 = bytes[i]!;
    const b1 = bytes[i + 1]!;
    const pair = (b0 << 8) | b1;
    out +=
      BASE64URL_ALPHABET[(pair >>> 10) & 63]! +
      BASE64URL_ALPHABET[(pair >>> 4) & 63]! +
      BASE64URL_ALPHABET[(pair & 15) << 2]!;
  }

  return out;
}

/**
 * Decodes unpadded RFC 4648 §5 base64url into raw bytes, rejecting padding,
 * non-base64url characters, impossible lengths, and non-canonical tail bits.
 */
export function base64UrlToBytes(encoded: string): Uint8Array<ArrayBuffer> {
  if (typeof encoded !== "string") {
    throw new FocalCryptoError("Expected base64url string");
  }
  const len = encoded.length;
  if (len === 0) {
    return new Uint8Array(0);
  }
  if (len % 4 === 1) {
    throw new FocalCryptoError("Invalid base64url length");
  }

  const sextets = new Uint8Array(len);
  for (let i = 0; i < len; i++) {
    const code = encoded.charCodeAt(i);
    const val = code < 128 ? BASE64URL_LOOKUP[code]! : -1;
    if (val < 0) {
      throw new FocalCryptoError(
        `Invalid base64url character at index ${i}`
      );
    }
    sextets[i] = val;
  }

  const rem = len % 4;
  if (rem === 2 && (sextets[len - 1]! & 0x0f) !== 0) {
    throw new FocalCryptoError("Non-canonical base64url trailing bits");
  }
  if (rem === 3 && (sextets[len - 1]! & 0x03) !== 0) {
    throw new FocalCryptoError("Non-canonical base64url trailing bits");
  }

  const outLen = Math.floor((len * 3) / 4);
  const out = new Uint8Array(outLen);
  let inIdx = 0;
  let outIdx = 0;

  while (inIdx + 3 < len) {
    const s0 = sextets[inIdx++]!;
    const s1 = sextets[inIdx++]!;
    const s2 = sextets[inIdx++]!;
    const s3 = sextets[inIdx++]!;
    const quad = (s0 << 18) | (s1 << 12) | (s2 << 6) | s3;
    out[outIdx++] = (quad >>> 16) & 0xff;
    out[outIdx++] = (quad >>> 8) & 0xff;
    out[outIdx++] = quad & 0xff;
  }

  if (rem === 2) {
    const s0 = sextets[inIdx]!;
    const s1 = sextets[inIdx + 1]!;
    out[outIdx] = ((s0 << 2) | (s1 >>> 4)) & 0xff;
  } else if (rem === 3) {
    const s0 = sextets[inIdx]!;
    const s1 = sextets[inIdx + 1]!;
    const s2 = sextets[inIdx + 2]!;
    const triple = (s0 << 12) | (s1 << 6) | s2;
    out[outIdx++] = (triple >>> 10) & 0xff;
    out[outIdx] = (triple >>> 2) & 0xff;
  }

  return out;
}

/**
 * Encodes bytes as lowercase hexadecimal string.
 */
export function bytesToHex(bytes: Uint8Array): string {
  let hex = "";
  for (let i = 0; i < bytes.byteLength; i++) {
    hex += bytes[i]!.toString(16).padStart(2, "0");
  }
  return hex;
}

/**
 * Decodes an even-length hexadecimal string into bytes.
 */
export function hexToBytes(hex: string): Uint8Array<ArrayBuffer> {
  if (hex.length % 2 !== 0 || !/^[0-9a-fA-F]*$/.test(hex)) {
    throw new FocalCryptoError("Invalid hexadecimal string");
  }
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.byteLength; i++) {
    out[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  }
  return out;
}

/**
 * Validates that a roomId is an exact 64-character lowercase hex string.
 */
export function isValidRoomId(roomId: string): boolean {
  return typeof roomId === "string" && /^[0-9a-f]{64}$/.test(roomId);
}

/**
 * Generates a fresh 32-byte pairing secret via WebCrypto CSPRNG.
 */
export function generatePairingSecret(): Uint8Array<ArrayBuffer> {
  const secret = new Uint8Array(SECRET_BYTE_LENGTH);
  globalThis.crypto.getRandomValues(secret);
  return secret;
}

/**
 * Formats a 32-byte secret as `focal-pair://v1?secret=<base64url_nopad_32bytes>`.
 */
export function formatPairingUri(
  secret: Uint8Array,
  options?: { relayUrl?: string }
): string {
  if (secret.byteLength !== SECRET_BYTE_LENGTH) {
    throw new FocalCryptoError(
      `Pairing secret must be exactly ${SECRET_BYTE_LENGTH} bytes (got ${secret.byteLength})`
    );
  }
  const secretParam = bytesToBase64Url(secret);
  let uri = `${PAIRING_SCHEME_PREFIX}?secret=${secretParam}`;
  if (options?.relayUrl) {
    uri += `&relay=${encodeURIComponent(options.relayUrl)}`;
  }
  return uri;
}

function isLoopbackHost(hostname: string): boolean {
  const normalized = hostname.toLowerCase();
  return (
    normalized === "localhost" ||
    normalized === "127.0.0.1" ||
    normalized === "[::1]"
  );
}

/**
 * Validates a sync relay URL, rejecting cleartext `ws://` and `http://` origins
 * by default to prevent metadata exposure and LAN/localhost WebSocket SSRF.
 * Loopback `ws://` / `http://` (`localhost`, `127.0.0.1`, `[::1]`) is permitted
 * only when `opts?.allowInsecureLocalhost === true`.
 */
export function validateRelayUrl(
  relayUrl: string,
  opts?: ParsePairingUriOptions
): string {
  if (typeof relayUrl !== "string" || relayUrl.trim().length === 0) {
    throw new FocalCryptoError("Invalid relay URL in pairing URI");
  }
  const trimmed = relayUrl.trim();
  let parsedRelay: URL;
  try {
    parsedRelay = new URL(trimmed);
  } catch {
    throw new FocalCryptoError("Invalid relay URL in pairing URI");
  }

  if (parsedRelay.username.length > 0 || parsedRelay.password.length > 0) {
    throw new FocalCryptoError("Relay URL must not embed credentials");
  }

  if (parsedRelay.protocol === "wss:" || parsedRelay.protocol === "https:") {
    return trimmed;
  }

  if (parsedRelay.protocol === "ws:" || parsedRelay.protocol === "http:") {
    if (
      opts?.allowInsecureLocalhost === true &&
      isLoopbackHost(parsedRelay.hostname)
    ) {
      return trimmed;
    }
    throw new FocalCryptoError(
      "Insecure cleartext relay URL rejected; use wss:// or https://"
    );
  }

  throw new FocalCryptoError("Unsupported relay protocol in pairing URI");
}

/**
 * Parses and validates `focal-pair://v1?secret=<base64url_nopad_32bytes>`.
 */
export function parsePairingUri(
  uri: string,
  opts?: ParsePairingUriOptions
): ParsedPairingUri {
  if (typeof uri !== "string") {
    throw new FocalCryptoError("Pairing URI must be a string");
  }
  const trimmed = uri.trim();
  const expectedPrefix = `${PAIRING_SCHEME_PREFIX}?`;
  if (!trimmed.startsWith(expectedPrefix)) {
    throw new FocalCryptoError(
      `Invalid pairing URI: must start with "${expectedPrefix}"`
    );
  }

  const rawQuery = trimmed.slice(expectedPrefix.length);
  const hashIndex = rawQuery.indexOf("#");
  const queryString = hashIndex >= 0 ? rawQuery.slice(0, hashIndex) : rawQuery;
  const params = new URLSearchParams(queryString);

  const allSecrets = params.getAll("secret");
  if (allSecrets.length !== 1) {
    throw new FocalCryptoError(
      "Pairing URI must include exactly one 'secret' parameter"
    );
  }
  const secretBase64Url = allSecrets[0]!;
  const secret = base64UrlToBytes(secretBase64Url);
  if (secret.byteLength !== SECRET_BYTE_LENGTH) {
    throw new FocalCryptoError(
      `Pairing secret must decode to exactly ${SECRET_BYTE_LENGTH} bytes (got ${secret.byteLength})`
    );
  }

  const allRelays = params.getAll("relay");
  if (allRelays.length > 1) {
    throw new FocalCryptoError(
      "Pairing URI must include at most one 'relay' parameter"
    );
  }

  const relayParam = allRelays[0]?.trim();
  if (relayParam !== undefined && relayParam.length > 0) {
    const validatedRelay = validateRelayUrl(relayParam, opts);
    return { secret, secretBase64Url, relayUrl: validatedRelay };
  }

  return { secret, secretBase64Url };
}

/**
 * Formats 4 SAS bytes into `"word1 · word2 · word3 · NN"`.
 */
export function formatSasBytes(sasBytes: Uint8Array): string {
  if (sasBytes.byteLength !== SAS_BYTE_LENGTH) {
    throw new FocalCryptoError(
      `SAS bytes must be exactly ${SAS_BYTE_LENGTH} bytes (got ${sasBytes.byteLength})`
    );
  }
  const w1 = SAS_WORDS[sasBytes[0]! & 63]!;
  const w2 = SAS_WORDS[sasBytes[1]! & 63]!;
  const w3 = SAS_WORDS[sasBytes[2]! & 63]!;
  const nn = String((sasBytes[3]! & 0xff) % 100).padStart(2, "0");
  return `${w1} · ${w2} · ${w3} · ${nn}`;
}

async function hkdfExpandBytes(
  hkdfBaseKey: CryptoKey,
  saltBytes: Uint8Array<ArrayBuffer>,
  info: string,
  byteLength: number
): Promise<Uint8Array<ArrayBuffer>> {
  const bits = await globalThis.crypto.subtle.deriveBits(
    {
      name: "HKDF",
      hash: "SHA-256",
      salt: saltBytes,
      info: encodeUtf8(info),
    },
    hkdfBaseKey,
    byteLength * 8
  );
  return new Uint8Array(bits);
}

/**
 * Derives `roomId`, `symKey` (AES-256-GCM), and `sas` from a 32-byte secret
 * via RFC 5869 `HKDF-SHA256` with salt `"focal-sync-v1-salt"`.
 */
export async function deriveFocalKeys(
  secret: Uint8Array
): Promise<DerivedFocalKeys> {
  if (secret.byteLength !== SECRET_BYTE_LENGTH) {
    throw new FocalCryptoError(
      `Secret must be ${SECRET_BYTE_LENGTH} bytes (got ${secret.byteLength})`
    );
  }

  const ownedSecret = toOwnedUint8Array(secret);
  const saltBytes = encodeUtf8(HKDF_SALT);

  const hkdfBaseKey = await globalThis.crypto.subtle.importKey(
    "raw",
    ownedSecret,
    "HKDF",
    false,
    ["deriveBits"]
  );

  const [roomIdBytes, symKeyBytes, sasBytes] = await Promise.all([
    hkdfExpandBytes(hkdfBaseKey, saltBytes, HKDF_INFO_ROOM_ID, ROOM_ID_BYTE_LENGTH),
    hkdfExpandBytes(hkdfBaseKey, saltBytes, HKDF_INFO_AEAD_KEY, SYM_KEY_BYTE_LENGTH),
    hkdfExpandBytes(hkdfBaseKey, saltBytes, HKDF_INFO_SAS, SAS_BYTE_LENGTH),
  ]);

  const symKey = await globalThis.crypto.subtle.importKey(
    "raw",
    symKeyBytes,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"]
  );

  return {
    roomId: bytesToHex(roomIdBytes),
    roomIdBytes,
    symKey,
    symKeyBytes,
    sas: formatSasBytes(sasBytes),
    sasBytes,
  };
}

/**
 * Per-sender monotonic sequence anti-replay tracker.
 * Rejects duplicate (`seq === highest`) and stale (`seq < highest`) envelopes.
 */
export class ReplayTracker {
  private readonly highestSeqBySender = new Map<string, number>();
  private readonly maxSenders: number;

  constructor(maxSenders = 64) {
    this.maxSenders = Math.max(4, maxSenders);
  }

  /**
   * Validates that `(senderId, seq)` is strictly newer than any previously
   * accepted envelope for `senderId`, then advances the watermark.
   */
  accept(senderId: string, seq: number): void {
    if (typeof senderId !== "string" || senderId.trim().length === 0) {
      throw new FocalCryptoError("Invalid senderId in envelope");
    }
    if (!Number.isSafeInteger(seq) || seq < 0) {
      throw new FocalCryptoError(`Invalid sequence number: ${String(seq)}`);
    }

    const prev = this.highestSeqBySender.get(senderId);
    if (prev !== undefined && seq <= prev) {
      throw new FocalReplayError(senderId, seq, prev);
    }

    // Refresh insertion order for simple LRU bounding across many ephemeral peers
    if (prev !== undefined) {
      this.highestSeqBySender.delete(senderId);
    } else if (this.highestSeqBySender.size >= this.maxSenders) {
      const oldestKey = this.highestSeqBySender.keys().next().value;
      if (oldestKey !== undefined) {
        this.highestSeqBySender.delete(oldestKey);
      }
    }

    this.highestSeqBySender.set(senderId, seq);
  }

  getHighestSeq(senderId: string): number | undefined {
    return this.highestSeqBySender.get(senderId);
  }

  clear(): void {
    this.highestSeqBySender.clear();
  }

  toSnapshot(): Record<string, number> {
    const out: Record<string, number> = {};
    for (const [senderId, seq] of this.highestSeqBySender.entries()) {
      out[senderId] = seq;
    }
    return out;
  }

  static fromSnapshot(
    snapshot: Record<string, number> | undefined | null,
    maxSenders = 64
  ): ReplayTracker {
    const tracker = new ReplayTracker(maxSenders);
    if (snapshot && typeof snapshot === "object") {
      for (const [senderId, seq] of Object.entries(snapshot)) {
        if (
          senderId.trim().length > 0 &&
          Number.isSafeInteger(seq) &&
          seq >= 0
        ) {
          tracker.highestSeqBySender.set(senderId, seq);
        }
      }
    }
    return tracker;
  }
}

function assertValidEnvelopeShape<T>(value: unknown): asserts value is EncryptedEnvelope<T> {
  if (typeof value !== "object" || value === null) {
    throw new FocalCryptoError("Decrypted payload is not a JSON object");
  }
  const candidate = value as Record<string, unknown>;
  if (
    typeof candidate["seq"] !== "number" ||
    !Number.isSafeInteger(candidate["seq"]) ||
    candidate["seq"] < 0
  ) {
    throw new FocalCryptoError("Invalid envelope 'seq'");
  }
  if (
    typeof candidate["senderId"] !== "string" ||
    candidate["senderId"].trim().length === 0
  ) {
    throw new FocalCryptoError("Invalid envelope 'senderId'");
  }
  if (
    typeof candidate["timestampMs"] !== "number" ||
    !Number.isFinite(candidate["timestampMs"]) ||
    candidate["timestampMs"] < 0
  ) {
    throw new FocalCryptoError("Invalid envelope 'timestampMs'");
  }
  if (typeof candidate["event"] !== "object" || candidate["event"] === null) {
    throw new FocalCryptoError("Invalid envelope 'event'");
  }
  const eventObj = candidate["event"] as Record<string, unknown>;
  if (typeof eventObj["type"] !== "string" || eventObj["type"].length === 0) {
    throw new FocalCryptoError("Invalid envelope 'event.type'");
  }
}

/**
 * Encrypts a JSON envelope using `AES-256-GCM` with a random 12-byte IV and
 * `additionalData = UTF-8(roomId)`, returning `base64url(IV || ciphertext || tag)`.
 */
export async function encryptEnvelope<T = SyncEvent>(
  envelope: EncryptedEnvelope<T>,
  symKey: CryptoKey,
  roomId: string
): Promise<string> {
  if (!isValidRoomId(roomId)) {
    throw new FocalCryptoError("roomId must be a 64-character lowercase hex string");
  }
  assertValidEnvelopeShape<T>(envelope);

  const iv = new Uint8Array(AES_GCM_IV_BYTE_LENGTH);
  globalThis.crypto.getRandomValues(iv);

  const additionalData = encodeUtf8(roomId);
  const plaintextBytes = encodeUtf8(JSON.stringify(envelope));

  const ciphertextWithTagBuffer = await globalThis.crypto.subtle.encrypt(
    {
      name: "AES-GCM",
      iv,
      additionalData,
      tagLength: AES_GCM_TAG_BIT_LENGTH,
    },
    symKey,
    plaintextBytes
  );

  const ciphertextWithTag = new Uint8Array(ciphertextWithTagBuffer);
  const wireBytes = new Uint8Array(
    AES_GCM_IV_BYTE_LENGTH + ciphertextWithTag.byteLength
  );
  wireBytes.set(iv, 0);
  wireBytes.set(ciphertextWithTag, AES_GCM_IV_BYTE_LENGTH);

  return bytesToBase64Url(wireBytes);
}

/**
 * Decrypts and authenticates `base64url(12B IV || ciphertext || 16B tag)` using
 * `AES-256-GCM` with `additionalData = UTF-8(roomId)`.
 *
 * Replay state is mutated ONLY after GCM tag authentication and schema validation pass.
 */
export async function decryptEnvelope<T = SyncEvent>(
  wireFrame: string,
  symKey: CryptoKey,
  roomId: string,
  replayTracker?: ReplayTracker
): Promise<EncryptedEnvelope<T>> {
  if (!isValidRoomId(roomId)) {
    throw new FocalCryptoError("roomId must be a 64-character lowercase hex string");
  }

  const wireBytes = base64UrlToBytes(wireFrame);
  const minWireBytes = AES_GCM_IV_BYTE_LENGTH + AES_GCM_TAG_BYTE_LENGTH;
  if (wireBytes.byteLength < minWireBytes) {
    throw new FocalCryptoError(
      `Wire frame too short (${wireBytes.byteLength} bytes < ${minWireBytes} minimum)`
    );
  }

  const iv = wireBytes.slice(0, AES_GCM_IV_BYTE_LENGTH);
  const ciphertextWithTag = wireBytes.slice(AES_GCM_IV_BYTE_LENGTH);
  const additionalData = encodeUtf8(roomId);

  let plaintextBuffer: ArrayBuffer;
  try {
    plaintextBuffer = await globalThis.crypto.subtle.decrypt(
      {
        name: "AES-GCM",
        iv,
        additionalData,
        tagLength: AES_GCM_TAG_BIT_LENGTH,
      },
      symKey,
      ciphertextWithTag
    );
  } catch {
    throw new FocalCryptoError(
      "AES-256-GCM authentication failed (invalid key, tampered ciphertext, or mismatched roomId AAD)"
    );
  }

  let jsonText: string;
  try {
    jsonText = textDecoder.decode(plaintextBuffer);
  } catch {
    throw new FocalCryptoError("Decrypted payload is not valid UTF-8");
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(jsonText);
  } catch {
    throw new FocalCryptoError("Decrypted payload is not valid JSON");
  }

  assertValidEnvelopeShape<T>(parsed);

  if (replayTracker) {
    replayTracker.accept(parsed.senderId, parsed.seq);
  }

  return parsed;
}
