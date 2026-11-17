/**
 * Focal Sync Relay — Zero-Knowledge Stateless E2EE Cloudflare Worker + Durable Object.
 *
 * Security & Privacy Invariants:
 * - Routes `/v1/rooms/:roomId` strictly for 64-character lowercase hex `roomId`s
 *   (derived client-side via HKDF; never linked to identity, email, or account).
 * - Never inspects, parses, or logs decrypted plaintext. Every payload is treated
 *   as an opaque ciphertext wire frame (`<= 64 KB`).
 * - Uses Cloudflare Durable Objects WebSocket Hibernation API (`acceptWebSocket`,
 *   `webSocketMessage`, `webSocketClose`, `alarm`) for zero idle memory billing.
 * - Buffers at most the last 100 opaque ciphertext frames with strict 7-day TTL
 *   alarm eviction (`storage.deleteAll()`) for offline device catch-up.
 * - Optionally forwards opaque ciphertext wakeups to Firebase Cloud Messaging (FCM)
 *   without ever inspecting plaintext.
 */

export const MAX_FRAME_BYTES = 64 * 1024; // 64 KB
export const MAX_FCM_INLINE_WIRE_FRAME_BYTES = 3072; // <= 3 KB inline in FCM data; > 3 KB sends wakeOnly
export const MAX_ROOM_CONNECTIONS = 16;
export const RING_BUFFER_CAPACITY = 100;
export const RING_BUFFER_TTL_MS = 7 * 24 * 60 * 60 * 1000; // 7 days

const STORAGE_KEY_RING_BUFFER = "ringBuffer";
const STORAGE_KEY_PUSH_TARGETS = "pushTargets";
const STORAGE_KEY_ROOM_ID = "roomId";

export interface Env {
  FOCAL_ROOMS: DurableObjectNamespace;
  FCM_ENDPOINT?: string;
  FCM_BEARER_TOKEN?: string;
}

export interface BufferedCiphertextFrame {
  frame: string;
  storedAtMs: number;
}

export interface StoredPushTarget {
  fcmToken: string;
  deviceTag: string;
  updatedAtMs: number;
}

interface SocketAttachment {
  roomId: string;
  deviceTag: string | null;
}

interface WakeupRequestBody {
  wireFrame?: unknown;
  fcmToken?: unknown;
  deviceTag?: unknown;
}

const ROOM_ROUTE_REGEX = /^\/v1\/rooms\/([^/]+)(\/wakeup)?$/;
const ROOM_ID_HEX_REGEX = /^[0-9a-f]{64}$/;

const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder("utf-8", {
  fatal: true,
  ignoreBOM: false,
});

/**
 * Validates that `roomId` is an exact 64-character lowercase hex string.
 */
export function isValidRoomId(roomId: string): boolean {
  return typeof roomId === "string" && ROOM_ID_HEX_REGEX.test(roomId);
}

function jsonResponse(body: Record<string, unknown>, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
    },
  });
}

function createSwitchingProtocolsResponse(clientSocket: WebSocket): Response {
  try {
    return new Response(null, {
      status: 101,
      webSocket: clientSocket,
    });
  } catch {
    // Non-workerd runtimes (e.g. Node.js undici in unit tests) reject status 101 in Response constructor
    const fallback = new Response(null, { status: 200 });
    Object.defineProperty(fallback, "status", { value: 101 });
    Object.defineProperty(fallback, "webSocket", { value: clientSocket });
    return fallback;
  }
}

/**
 * Hibernatable Durable Object backing a single opaque `roomId`.
 */
export class FocalRoomDO implements DurableObject {
  private readonly state: DurableObjectState;
  private readonly env: Env;
  private readonly nowFn: () => number;
  private readonly fetchFn: typeof fetch;

  constructor(
    state: DurableObjectState,
    env: Env,
    options?: { nowFn?: () => number; fetchFn?: typeof fetch }
  ) {
    this.state = state;
    this.env = env;
    this.nowFn = options?.nowFn ?? (() => Date.now());
    this.fetchFn = options?.fetchFn ?? globalThis.fetch.bind(globalThis);
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const match = ROOM_ROUTE_REGEX.exec(url.pathname);
    if (!match) {
      return jsonResponse({ error: "Not found" }, 404);
    }

    const roomId = match[1]!;
    const subRoute = match[2];

    if (!isValidRoomId(roomId)) {
      return jsonResponse(
        { error: "Invalid roomId: expected 64-character lowercase hex" },
        400
      );
    }

    if (subRoute === "/wakeup") {
      if (request.method !== "POST") {
        return jsonResponse({ error: "Method not allowed" }, 405);
      }
      return this.handleHttpWakeup(request, roomId);
    }

    if (request.method !== "GET") {
      return jsonResponse({ error: "Method not allowed" }, 405);
    }

    const upgradeHeader = request.headers.get("Upgrade");
    if (!upgradeHeader || upgradeHeader.toLowerCase() !== "websocket") {
      return jsonResponse({ error: "Expected Upgrade: websocket" }, 426);
    }

    const activeSockets = this.state.getWebSockets();
    if (activeSockets.length >= MAX_ROOM_CONNECTIONS) {
      return jsonResponse(
        {
          error: `Room connection cap (${MAX_ROOM_CONNECTIONS} devices) exceeded`,
        },
        429
      );
    }

    const deviceTagHeader =
      request.headers.get("X-Focal-Device-Tag")?.trim() || null;
    const fcmTokenHeader =
      request.headers.get("X-Focal-Fcm-Token")?.trim() || null;

    await this.state.storage.put(STORAGE_KEY_ROOM_ID, roomId);
    if (fcmTokenHeader && deviceTagHeader) {
      await this.upsertPushTarget(fcmTokenHeader, deviceTagHeader);
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];

    const tags = deviceTagHeader ? [deviceTagHeader] : undefined;
    this.state.acceptWebSocket(server, tags);

    const attachment: SocketAttachment = {
      roomId,
      deviceTag: deviceTagHeader,
    };
    if (typeof server.serializeAttachment === "function") {
      server.serializeAttachment(attachment);
    }

    // Replay unexpired opaque ciphertext frames from the 100-item ring buffer
    const catchUpFrames = await this.loadActiveRingBuffer();
    for (const entry of catchUpFrames) {
      try {
        server.send(entry.frame);
      } catch {
        break;
      }
    }

    return createSwitchingProtocolsResponse(client);
  }

  async webSocketMessage(
    ws: WebSocket,
    message: string | ArrayBuffer
  ): Promise<void> {
    let wireFrame: string;
    let byteLength: number;

    if (typeof message === "string") {
      byteLength = textEncoder.encode(message).byteLength;
      if (byteLength === 0 || byteLength > MAX_FRAME_BYTES) {
        this.rejectOversizedOrEmptyFrame(ws, byteLength);
        return;
      }
      wireFrame = message;
    } else if (message instanceof ArrayBuffer) {
      byteLength = message.byteLength;
      if (byteLength === 0 || byteLength > MAX_FRAME_BYTES) {
        this.rejectOversizedOrEmptyFrame(ws, byteLength);
        return;
      }
      try {
        wireFrame = textDecoder.decode(message);
      } catch {
        this.safeClose(ws, 1007, "Invalid UTF-8 wire frame");
        return;
      }
    } else {
      this.safeClose(ws, 1003, "Unsupported WebSocket frame type");
      return;
    }

    const attachment = this.readAttachment(ws);
    const roomId =
      attachment?.roomId ??
      (await this.state.storage.get<string>(STORAGE_KEY_ROOM_ID)) ??
      "";

    await this.appendFrameToRingBuffer(wireFrame);
    this.broadcastExcludingSender(wireFrame, ws);

    if (roomId) {
      await this.forwardOpaqueFcmWakeups(
        roomId,
        wireFrame,
        attachment?.deviceTag ?? null
      );
    }
  }

  async webSocketClose(
    ws: WebSocket,
    code: number,
    reason: string,
    _wasClean: boolean
  ): Promise<void> {
    this.safeClose(ws, code || 1000, reason || "Closed");
  }

  async webSocketError(ws: WebSocket, _error: unknown): Promise<void> {
    this.safeClose(ws, 1011, "WebSocket transport error");
  }

  /**
   * 7-day TTL eviction handler invoked by Cloudflare Durable Object alarms.
   */
  async alarm(): Promise<void> {
    const now = this.nowFn();
    const cutoff = now - RING_BUFFER_TTL_MS;

    const [rawBuffer, rawTargets] = await Promise.all([
      this.state.storage.get<BufferedCiphertextFrame[]>(STORAGE_KEY_RING_BUFFER),
      this.state.storage.get<StoredPushTarget[]>(STORAGE_KEY_PUSH_TARGETS),
    ]);

    const activeBuffer = Array.isArray(rawBuffer)
      ? rawBuffer.filter((item) => item.storedAtMs > cutoff)
      : [];
    const activeTargets = Array.isArray(rawTargets)
      ? rawTargets.filter((target) => target.updatedAtMs > cutoff)
      : [];

    if (activeBuffer.length === 0 && activeTargets.length === 0) {
      await this.state.storage.deleteAll();
      return;
    }

    await Promise.all([
      this.state.storage.put(STORAGE_KEY_RING_BUFFER, activeBuffer),
      this.state.storage.put(STORAGE_KEY_PUSH_TARGETS, activeTargets),
    ]);

    const earliestRemaining = Math.min(
      ...activeBuffer.map((b) => b.storedAtMs),
      ...activeTargets.map((t) => t.updatedAtMs)
    );
    if (Number.isFinite(earliestRemaining)) {
      await this.state.storage.setAlarm(earliestRemaining + RING_BUFFER_TTL_MS);
    }
  }

  private async handleHttpWakeup(
    request: Request,
    roomId: string
  ): Promise<Response> {
    const rawBodyBytes = await request.arrayBuffer();
    if (rawBodyBytes.byteLength > MAX_FRAME_BYTES * 2) {
      return jsonResponse({ error: "Payload too large" }, 413);
    }

    let parsed: WakeupRequestBody;
    try {
      parsed = JSON.parse(textDecoder.decode(rawBodyBytes)) as WakeupRequestBody;
    } catch {
      return jsonResponse({ error: "Invalid JSON body" }, 400);
    }

    const senderTag =
      typeof parsed.deviceTag === "string" && parsed.deviceTag.trim().length > 0
        ? parsed.deviceTag.trim()
        : null;

    if (
      typeof parsed.fcmToken === "string" &&
      parsed.fcmToken.trim().length > 0 &&
      senderTag
    ) {
      await this.upsertPushTarget(parsed.fcmToken.trim(), senderTag);
    }

    if (parsed.wireFrame !== undefined) {
      if (typeof parsed.wireFrame !== "string") {
        return jsonResponse({ error: "wireFrame must be a string" }, 400);
      }
      const frameBytes = textEncoder.encode(parsed.wireFrame).byteLength;
      if (frameBytes === 0 || frameBytes > MAX_FRAME_BYTES) {
        return jsonResponse(
          { error: `wireFrame must be 1..${MAX_FRAME_BYTES} bytes` },
          413
        );
      }

      await this.state.storage.put(STORAGE_KEY_ROOM_ID, roomId);
      await this.appendFrameToRingBuffer(parsed.wireFrame);
      this.broadcastExcludingSender(parsed.wireFrame, null);
      const pushedCount = await this.forwardOpaqueFcmWakeups(
        roomId,
        parsed.wireFrame,
        senderTag
      );
      return jsonResponse({ ok: true, pushedCount }, 202);
    }

    return jsonResponse({ ok: true, registered: Boolean(parsed.fcmToken) }, 200);
  }

  private async loadActiveRingBuffer(): Promise<BufferedCiphertextFrame[]> {
    const now = this.nowFn();
    const cutoff = now - RING_BUFFER_TTL_MS;
    const stored = await this.state.storage.get<BufferedCiphertextFrame[]>(
      STORAGE_KEY_RING_BUFFER
    );
    if (!Array.isArray(stored) || stored.length === 0) {
      return [];
    }
    const fresh = stored.filter((entry) => entry.storedAtMs > cutoff);
    if (fresh.length !== stored.length) {
      await this.state.storage.put(STORAGE_KEY_RING_BUFFER, fresh);
    }
    return fresh;
  }

  private async appendFrameToRingBuffer(wireFrame: string): Promise<void> {
    const now = this.nowFn();
    const current = await this.loadActiveRingBuffer();
    current.push({ frame: wireFrame, storedAtMs: now });
    const bounded =
      current.length > RING_BUFFER_CAPACITY
        ? current.slice(current.length - RING_BUFFER_CAPACITY)
        : current;

    await this.state.storage.put(STORAGE_KEY_RING_BUFFER, bounded);
    await this.state.storage.setAlarm(now + RING_BUFFER_TTL_MS);
  }

  private broadcastExcludingSender(
    wireFrame: string,
    senderSocket: WebSocket | null
  ): void {
    for (const peer of this.state.getWebSockets()) {
      if (peer === senderSocket) {
        continue;
      }
      try {
        peer.send(wireFrame);
      } catch {
        this.safeClose(peer, 1011, "Peer send failed");
      }
    }
  }

  private async upsertPushTarget(
    fcmToken: string,
    deviceTag: string
  ): Promise<void> {
    const now = this.nowFn();
    const cutoff = now - RING_BUFFER_TTL_MS;
    const existing =
      (await this.state.storage.get<StoredPushTarget[]>(
        STORAGE_KEY_PUSH_TARGETS
      )) ?? [];

    const filtered = existing.filter(
      (item) => item.updatedAtMs > cutoff && item.deviceTag !== deviceTag
    );
    filtered.push({
      fcmToken: fcmToken.slice(0, 4096),
      deviceTag: deviceTag.slice(0, 128),
      updatedAtMs: now,
    });

    const bounded = filtered.slice(-MAX_ROOM_CONNECTIONS);
    await this.state.storage.put(STORAGE_KEY_PUSH_TARGETS, bounded);
    await this.state.storage.setAlarm(now + RING_BUFFER_TTL_MS);
  }

  /**
   * Forwards strictly opaque wakeup payloads to FCM when configured, without
   * ever inspecting or decrypting `wireFrame`.
   *
   * FCM HTTP v1 enforces a 4 KB limit on `message.data`. When `wireFrame` fits
   * within 3072 bytes (`wireFrame.length <= 3072`), it is included inline;
   * larger frames (`> 3072` up to `64 KB`) send `{ roomId, wakeOnly: "1" }`
   * without `wireFrame` so FCM never rejects `> 4 KB` messages with HTTP 400.
   */
  private async forwardOpaqueFcmWakeups(
    roomId: string,
    wireFrame: string,
    senderDeviceTag: string | null
  ): Promise<number> {
    if (!this.env.FCM_ENDPOINT || !this.env.FCM_BEARER_TOKEN) {
      return 0;
    }

    const now = this.nowFn();
    const cutoff = now - RING_BUFFER_TTL_MS;
    const targets =
      (await this.state.storage.get<StoredPushTarget[]>(
        STORAGE_KEY_PUSH_TARGETS
      )) ?? [];

    const eligible = targets.filter(
      (t) =>
        t.updatedAtMs > cutoff &&
        (senderDeviceTag === null || t.deviceTag !== senderDeviceTag)
    );
    if (eligible.length === 0) {
      return 0;
    }

    const canInlineWireFrame =
      wireFrame.length <= MAX_FCM_INLINE_WIRE_FRAME_BYTES &&
      textEncoder.encode(wireFrame).byteLength <=
        MAX_FCM_INLINE_WIRE_FRAME_BYTES;

    const dataPayload: Record<string, string> = canInlineWireFrame
      ? { roomId, wireFrame }
      : { roomId, wakeOnly: "1" };

    let delivered = 0;
    await Promise.all(
      eligible.map(async (target) => {
        try {
          const res = await this.fetchFn(this.env.FCM_ENDPOINT!, {
            method: "POST",
            headers: {
              Authorization: `Bearer ${this.env.FCM_BEARER_TOKEN!}`,
              "Content-Type": "application/json; charset=utf-8",
            },
            body: JSON.stringify({
              message: {
                token: target.fcmToken,
                android: { priority: "high" },
                data: dataPayload,
              },
            }),
          });
          if (res.ok) {
            delivered += 1;
          }
        } catch {
          // Best-effort wakeup; encrypted frame remains safely in the 7-day DO ring buffer
        }
      })
    );

    return delivered;
  }

  private readAttachment(ws: WebSocket): SocketAttachment | null {
    if (typeof ws.deserializeAttachment === "function") {
      const raw = ws.deserializeAttachment() as SocketAttachment | null;
      if (raw && typeof raw === "object" && typeof raw.roomId === "string") {
        return raw;
      }
    }
    return null;
  }

  private rejectOversizedOrEmptyFrame(ws: WebSocket, byteLength: number): void {
    const code = byteLength > MAX_FRAME_BYTES ? 1009 : 1007;
    const reason =
      byteLength > MAX_FRAME_BYTES
        ? "Wire frame exceeds 64 KB cap"
        : "Empty wire frame";
    this.safeClose(ws, code, reason);
  }

  private safeClose(ws: WebSocket, code: number, reason: string): void {
    try {
      ws.close(code, reason);
    } catch {
      // Ignore secondary close errors
    }
  }
}

/**
 * Stateless Cloudflare Worker entry point routing `/v1/rooms/:roomId` to `FocalRoomDO`.
 */
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/health" && request.method === "GET") {
      return jsonResponse({ ok: true, service: "focal-sync-relay" }, 200);
    }

    const match = ROOM_ROUTE_REGEX.exec(url.pathname);
    if (!match) {
      return jsonResponse({ error: "Not found" }, 404);
    }

    const roomId = match[1]!;
    if (!isValidRoomId(roomId)) {
      return jsonResponse(
        { error: "Invalid roomId: expected 64-character lowercase hex" },
        400
      );
    }

    const durableObjectId = env.FOCAL_ROOMS.idFromName(roomId);
    const roomStub = env.FOCAL_ROOMS.get(durableObjectId);
    return roomStub.fetch(request);
  },
} satisfies ExportedHandler<Env>;
