/**
 * Focal Bridge — Reconnecting E2EE WebSocket Continuity Client.
 *
 * Features:
 * - Exponential backoff reconnection with configurable jitter
 * - Encrypted AES-256-GCM envelope dispatch (`tab_handoff`, `presence_tabs`, `spaces_snapshot`)
 * - Bounded FIFO offline outbound ciphertext queue with automatic flush on reconnect
 * - Self-echo filtering and per-sender monotonic sequence replay filtering
 */

import {
  type DerivedFocalKeys,
  type EncryptedEnvelope,
  FocalCryptoError,
  FocalReplayError,
  type PresenceTabItem,
  type PresenceTabsEvent,
  ReplayTracker,
  type SpaceId,
  type SpaceModel,
  type SpacesSnapshotEvent,
  type SyncEvent,
  type TabHandoffEvent,
  decryptEnvelope,
  encryptEnvelope,
  isValidRoomId,
} from "./crypto.js";
import { cleanUrl, cleanUrlDetailed } from "./url-cleaner.js";

export const DEFAULT_RELAY_BASE_URL = "wss://relay.focal.app";
export const WS_OPEN_READY_STATE = 1;
export const WS_CONNECTING_READY_STATE = 0;
export const MAX_WIRE_FRAME_BYTES = 64 * 1024;

export type SyncConnectionStatus =
  | "disconnected"
  | "connecting"
  | "connected"
  | "reconnecting";

export interface WebSocketLike {
  readonly readyState: number;
  onopen: ((event: unknown) => void) | null;
  onmessage: ((event: { data: unknown }) => void) | null;
  onclose: ((event: { code?: number; reason?: string }) => void) | null;
  onerror: ((event: unknown) => void) | null;
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

export interface PersistedSyncClientState {
  nextSeq: number;
  outboundQueue: string[];
  replaySnapshot: Record<string, number>;
}

export interface FocalSyncClientOptions {
  keys: DerivedFocalKeys;
  senderId: string;
  relayBaseUrl?: string;
  initialState?: Partial<PersistedSyncClientState>;
  maxQueueSize?: number;
  baseBackoffMs?: number;
  maxBackoffMs?: number;
  jitterRatio?: number;
  wsFactory?: (url: string) => WebSocketLike;
  onEnvelope?: (envelope: EncryptedEnvelope<SyncEvent>) => void | Promise<void>;
  onStatusChange?: (status: SyncConnectionStatus) => void;
  onPersistState?: (state: PersistedSyncClientState) => void | Promise<void>;
  onError?: (error: Error) => void;
}

export interface DispatchResult<T extends SyncEvent = SyncEvent> {
  envelope: EncryptedEnvelope<T>;
  wireFrame: string;
  sentImmediately: boolean;
  queuedCount: number;
}

const textEncoder = new TextEncoder();

/**
 * Normalizes a relay origin (`wss://`, `ws://`, `https://`, `http://`) and
 * builds the `/v1/rooms/:roomId` WebSocket endpoint URL.
 */
export function buildRoomWebSocketUrl(
  relayBaseUrl: string,
  roomId: string
): string {
  if (!isValidRoomId(roomId)) {
    throw new FocalCryptoError("Invalid 64-char hex roomId");
  }
  const parsed = new URL(relayBaseUrl.trim());
  if (parsed.protocol === "https:") {
    parsed.protocol = "wss:";
  } else if (parsed.protocol === "http:") {
    parsed.protocol = "ws:";
  } else if (parsed.protocol !== "wss:" && parsed.protocol !== "ws:") {
    throw new FocalCryptoError(
      `Unsupported relay protocol "${parsed.protocol}"`
    );
  }
  const basePath = parsed.pathname.replace(/\/+$/, "");
  parsed.pathname = `${basePath}/v1/rooms/${roomId}`;
  parsed.search = "";
  parsed.hash = "";
  return parsed.toString();
}

/**
 * Computes exponential backoff delay with optional bounded jitter.
 */
export function computeBackoffDelayMs(
  attempt: number,
  baseBackoffMs = 500,
  maxBackoffMs = 30_000,
  jitterRatio = 0.2,
  randomFn: () => number = Math.random
): number {
  const safeAttempt = Math.max(0, Math.min(attempt, 20));
  const exponential = Math.min(
    maxBackoffMs,
    baseBackoffMs * Math.pow(2, safeAttempt)
  );
  if (jitterRatio <= 0) {
    return Math.round(exponential);
  }
  const clampedRatio = Math.min(jitterRatio, 0.5);
  const jitterFactor = 1 + (randomFn() * 2 - 1) * clampedRatio;
  return Math.max(50, Math.round(Math.min(maxBackoffMs, exponential * jitterFactor)));
}

export class FocalSyncClient {
  private readonly keys: DerivedFocalKeys;
  private readonly senderId: string;
  private readonly relayBaseUrl: string;
  private readonly maxQueueSize: number;
  private readonly baseBackoffMs: number;
  private readonly maxBackoffMs: number;
  private readonly jitterRatio: number;
  private readonly wsFactory: (url: string) => WebSocketLike;
  private readonly onEnvelope?: (
    envelope: EncryptedEnvelope<SyncEvent>
  ) => void | Promise<void>;
  private readonly onStatusChange?: (status: SyncConnectionStatus) => void;
  private readonly onPersistState?: (
    state: PersistedSyncClientState
  ) => void | Promise<void>;
  private readonly onError?: (error: Error) => void;

  private readonly replayTracker: ReplayTracker;
  private outboundQueue: string[];
  private nextSeq: number;

  private socket: WebSocketLike | null = null;
  private status: SyncConnectionStatus = "disconnected";
  private shouldStayConnected = false;
  private reconnectAttempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private replaysIgnored = 0;

  constructor(options: FocalSyncClientOptions) {
    if (!options.senderId || options.senderId.trim().length === 0) {
      throw new FocalCryptoError("senderId must be a non-empty string");
    }
    this.keys = options.keys;
    this.senderId = options.senderId.trim();
    this.relayBaseUrl = options.relayBaseUrl ?? DEFAULT_RELAY_BASE_URL;
    this.maxQueueSize = Math.max(1, options.maxQueueSize ?? 100);
    this.baseBackoffMs = Math.max(50, options.baseBackoffMs ?? 500);
    this.maxBackoffMs = Math.max(
      this.baseBackoffMs,
      options.maxBackoffMs ?? 30_000
    );
    this.jitterRatio = options.jitterRatio ?? 0.2;
    this.wsFactory =
      options.wsFactory ?? ((url: string) => new WebSocket(url) as unknown as WebSocketLike);
    this.onEnvelope = options.onEnvelope;
    this.onStatusChange = options.onStatusChange;
    this.onPersistState = options.onPersistState;
    this.onError = options.onError;

    const initialSeq = options.initialState?.nextSeq;
    this.nextSeq =
      typeof initialSeq === "number" &&
      Number.isSafeInteger(initialSeq) &&
      initialSeq >= 1
        ? initialSeq
        : 1;
    this.outboundQueue = Array.isArray(options.initialState?.outboundQueue)
      ? options.initialState.outboundQueue.slice(-this.maxQueueSize)
      : [];
    this.replayTracker = ReplayTracker.fromSnapshot(
      options.initialState?.replaySnapshot
    );
  }

  getStatus(): SyncConnectionStatus {
    return this.status;
  }

  getQueuedCount(): number {
    return this.outboundQueue.length;
  }

  getReplaysIgnoredCount(): number {
    return this.replaysIgnored;
  }

  getRoomId(): string {
    return this.keys.roomId;
  }

  getSas(): string {
    return this.keys.sas;
  }

  exportState(): PersistedSyncClientState {
    return {
      nextSeq: this.nextSeq,
      outboundQueue: [...this.outboundQueue],
      replaySnapshot: this.replayTracker.toSnapshot(),
    };
  }

  /**
   * Opens the WebSocket connection (idempotent if already connecting/connected).
   */
  connect(): void {
    this.shouldStayConnected = true;
    this.clearReconnectTimer();

    if (
      this.socket &&
      (this.socket.readyState === WS_OPEN_READY_STATE ||
        this.socket.readyState === WS_CONNECTING_READY_STATE)
    ) {
      return;
    }

    this.setStatus(
      this.reconnectAttempt > 0 ? "reconnecting" : "connecting"
    );

    const endpointUrl = buildRoomWebSocketUrl(
      this.relayBaseUrl,
      this.keys.roomId
    );

    let ws: WebSocketLike;
    try {
      ws = this.wsFactory(endpointUrl);
    } catch (err) {
      this.reportError(
        err instanceof Error ? err : new Error("WebSocket creation failed")
      );
      this.scheduleReconnect();
      return;
    }

    this.socket = ws;

    ws.onopen = () => {
      if (this.socket !== ws) {
        return;
      }
      this.reconnectAttempt = 0;
      this.setStatus("connected");
      this.flushOutboundQueue();
    };

    ws.onmessage = (event) => {
      if (this.socket !== ws) {
        return;
      }
      void this.handleIncomingMessage(event.data);
    };

    ws.onerror = () => {
      if (this.socket !== ws) {
        return;
      }
      this.reportError(new Error("Relay WebSocket transport error"));
    };

    ws.onclose = () => {
      if (this.socket !== ws) {
        return;
      }
      this.socket = null;
      if (this.shouldStayConnected) {
        this.scheduleReconnect();
      } else {
        this.setStatus("disconnected");
      }
    };
  }

  /**
   * Cleanly closes the WebSocket connection and halts automatic reconnection.
   */
  disconnect(): void {
    this.shouldStayConnected = false;
    this.reconnectAttempt = 0;
    this.clearReconnectTimer();

    const active = this.socket;
    this.socket = null;
    if (active) {
      try {
        active.close(1000, "Client disconnected");
      } catch {
        // Ignore close errors on torn-down sockets
      }
    }
    this.setStatus("disconnected");
  }

  /**
   * Ensures the client is connected and flushes any pending offline frames.
   * Useful from MV3 `chrome.alarms` wakeups.
   */
  ensureAlive(): void {
    if (!this.shouldStayConnected) {
      return;
    }
    if (!this.socket || this.socket.readyState !== WS_OPEN_READY_STATE) {
      if (this.reconnectTimer === null) {
        this.connect();
      }
      return;
    }
    this.flushOutboundQueue();
  }

  /**
   * Encrypts and dispatches any `SyncEvent` (or queues offline if disconnected).
   */
  async dispatchEvent<T extends SyncEvent>(
    event: T
  ): Promise<DispatchResult<T>> {
    const seq = this.nextSeq++;
    const envelope: EncryptedEnvelope<T> = {
      seq,
      senderId: this.senderId,
      timestampMs: Date.now(),
      event,
    };

    const wireFrame = await encryptEnvelope(
      envelope,
      this.keys.symKey,
      this.keys.roomId
    );

    const frameByteLength = textEncoder.encode(wireFrame).byteLength;
    if (frameByteLength > MAX_WIRE_FRAME_BYTES) {
      throw new FocalCryptoError(
        `Encrypted wire frame exceeds 64 KB relay cap (${frameByteLength} bytes)`
      );
    }

    let sentImmediately = false;
    if (
      this.socket &&
      this.socket.readyState === WS_OPEN_READY_STATE &&
      this.outboundQueue.length === 0
    ) {
      try {
        this.socket.send(wireFrame);
        sentImmediately = true;
      } catch {
        this.enqueueWireFrame(wireFrame);
      }
    } else {
      this.enqueueWireFrame(wireFrame);
      if (this.socket && this.socket.readyState === WS_OPEN_READY_STATE) {
        this.flushOutboundQueue();
        sentImmediately = this.outboundQueue.length === 0;
      }
    }

    await this.persistState();

    return {
      envelope,
      wireFrame,
      sentImmediately,
      queuedCount: this.outboundQueue.length,
    };
  }

  /**
   * Strips tracking query parameters and sends a `tab_handoff` envelope to Android.
   */
  async sendTabHandoff(input: {
    url: string;
    title: string;
    spaceId?: SpaceId;
    sourceDeviceName?: string;
  }): Promise<DispatchResult<TabHandoffEvent>> {
    const cleaned = cleanUrlDetailed(input.url);
    const event: TabHandoffEvent = {
      type: "tab_handoff",
      url: cleaned.cleanUrl,
      title: input.title.trim() || cleaned.cleanUrl,
      ...(cleaned.strippedParams.length > 0
        ? { strippedParams: cleaned.strippedParams }
        : {}),
      ...(input.spaceId ? { spaceId: input.spaceId } : {}),
      ...(input.sourceDeviceName
        ? { sourceDeviceName: input.sourceDeviceName }
        : {}),
    };
    return this.dispatchEvent(event);
  }

  /**
   * Encrypts and broadcasts current desktop presence tabs.
   */
  async sendPresenceTabs(
    tabs: PresenceTabItem[],
    deviceName = "Focal Bridge Desktop"
  ): Promise<DispatchResult<PresenceTabsEvent>> {
    const sanitizedTabs: PresenceTabItem[] = tabs.map((tab) => ({
      ...tab,
      url: cleanUrl(tab.url),
    }));
    const event: PresenceTabsEvent = {
      type: "presence_tabs",
      deviceName,
      platform: "desktop",
      tabs: sanitizedTabs,
    };
    return this.dispatchEvent(event);
  }

  /**
   * Encrypts and broadcasts the `Personal / Work / Reading` Spaces bookmark snapshot.
   */
  async sendSpacesSnapshot(
    spaces: SpaceModel[]
  ): Promise<DispatchResult<SpacesSnapshotEvent>> {
    const sanitizedSpaces: SpaceModel[] = spaces.map((space) => ({
      ...space,
      bookmarks: space.bookmarks.map((bm) => ({
        ...bm,
        url: cleanUrl(bm.url),
      })),
    }));
    const event: SpacesSnapshotEvent = {
      type: "spaces_snapshot",
      spaces: sanitizedSpaces,
      updatedAtMs: Date.now(),
    };
    return this.dispatchEvent(event);
  }

  private enqueueWireFrame(wireFrame: string): void {
    if (this.outboundQueue.length >= this.maxQueueSize) {
      this.outboundQueue.shift();
    }
    this.outboundQueue.push(wireFrame);
  }

  private flushOutboundQueue(): void {
    if (!this.socket || this.socket.readyState !== WS_OPEN_READY_STATE) {
      return;
    }
    let flushedAny = false;
    while (this.outboundQueue.length > 0) {
      const nextFrame = this.outboundQueue[0]!;
      try {
        this.socket.send(nextFrame);
        this.outboundQueue.shift();
        flushedAny = true;
      } catch {
        break;
      }
    }
    if (flushedAny) {
      void this.persistState();
    }
  }

  private async handleIncomingMessage(rawData: unknown): Promise<void> {
    let wireFrame: string;
    if (typeof rawData === "string") {
      wireFrame = rawData;
    } else if (rawData instanceof ArrayBuffer) {
      wireFrame = new TextDecoder().decode(rawData);
    } else if (ArrayBuffer.isView(rawData)) {
      wireFrame = new TextDecoder().decode(rawData);
    } else {
      return;
    }

    try {
      // Authenticate & decrypt first before mutating anti-replay state
      const envelope = await decryptEnvelope<SyncEvent>(
        wireFrame,
        this.keys.symKey,
        this.keys.roomId
      );

      // Ignore self-originated frames (e.g. replayed from relay catch-up ring buffer)
      if (envelope.senderId === this.senderId) {
        return;
      }

      // Enforce per-sender monotonic sequence anti-replay
      this.replayTracker.accept(envelope.senderId, envelope.seq);
      await this.persistState();
      await this.onEnvelope?.(envelope);
    } catch (err) {
      if (err instanceof FocalReplayError) {
        this.replaysIgnored += 1;
        return;
      }
      this.reportError(
        err instanceof Error ? err : new Error("Failed to process wire frame")
      );
    }
  }

  private scheduleReconnect(): void {
    if (!this.shouldStayConnected || this.reconnectTimer !== null) {
      return;
    }
    this.setStatus("reconnecting");
    const delayMs = computeBackoffDelayMs(
      this.reconnectAttempt,
      this.baseBackoffMs,
      this.maxBackoffMs,
      this.jitterRatio
    );
    this.reconnectAttempt += 1;

    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (this.shouldStayConnected) {
        this.connect();
      }
    }, delayMs);
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private setStatus(next: SyncConnectionStatus): void {
    if (this.status !== next) {
      this.status = next;
      this.onStatusChange?.(next);
    }
  }

  private async persistState(): Promise<void> {
    try {
      await this.onPersistState?.(this.exportState());
    } catch (err) {
      this.reportError(
        err instanceof Error
          ? err
          : new Error("Failed to persist sync client state")
      );
    }
  }

  private reportError(error: Error): void {
    this.onError?.(error);
  }
}
