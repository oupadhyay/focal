import assert from "node:assert/strict";
import { describe, it, beforeEach } from "node:test";

import worker, {
  FocalRoomDO,
  MAX_FCM_INLINE_WIRE_FRAME_BYTES,
  MAX_FRAME_BYTES,
  MAX_ROOM_CONNECTIONS,
  RING_BUFFER_CAPACITY,
  RING_BUFFER_TTL_MS,
  isValidRoomId,
} from "../dist/index.js";

async function deriveTestRoomAndKey(secretBytes) {
  const enc = new TextEncoder();
  const baseKey = await globalThis.crypto.subtle.importKey(
    "raw",
    secretBytes,
    "HKDF",
    false,
    ["deriveBits"]
  );
  const salt = enc.encode("focal-sync-v1-salt");
  const roomBits = await globalThis.crypto.subtle.deriveBits(
    { name: "HKDF", hash: "SHA-256", salt, info: enc.encode("focal-room-id-v1") },
    baseKey,
    256
  );
  const keyBits = await globalThis.crypto.subtle.deriveBits(
    { name: "HKDF", hash: "SHA-256", salt, info: enc.encode("focal-aead-key-v1") },
    baseKey,
    256
  );
  const roomId = Buffer.from(roomBits).toString("hex");
  const symKey = await globalThis.crypto.subtle.importKey(
    "raw",
    keyBits,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"]
  );
  return { roomId, symKey };
}

/**
 * Faithful in-memory Cloudflare `WebSocketPair` + Hibernatable `DurableObjectState`
 * harness for deterministic Node.js testing of `FocalRoomDO` and the Worker router.
 */
class MockPairSocket {
  constructor() {
    this.peer = null;
    this.sentMessages = [];
    this.closed = false;
    this.closeCode = null;
    this.closeReason = "";
    this._attachment = null;
  }

  send(data) {
    if (this.closed) {
      throw new Error("Socket is closed");
    }
    if (this.peer) {
      this.peer.receivedFromPeer.push(data);
    }
    this.sentMessages.push(data);
  }

  close(code = 1000, reason = "") {
    this.closed = true;
    this.closeCode = code;
    this.closeReason = reason;
    if (this._onInternalClose) {
      const cb = this._onInternalClose;
      this._onInternalClose = null;
      cb();
    }
  }

  serializeAttachment(value) {
    this._attachment = structuredClone(value);
  }

  deserializeAttachment() {
    return this._attachment ? structuredClone(this._attachment) : null;
  }
}

class MockWebSocketPair {
  constructor() {
    const client = new MockPairSocket();
    const server = new MockPairSocket();
    client.receivedFromPeer = [];
    server.receivedFromPeer = [];
    client.peer = server;
    server.peer = client;
    this[0] = client;
    this[1] = server;
  }
}

globalThis.WebSocketPair = MockWebSocketPair;

class MockDurableObjectStorage {
  constructor() {
    this.map = new Map();
    this.alarmTimeMs = null;
    this.deleteAllCalls = 0;
  }

  async get(key) {
    const val = this.map.get(key);
    return val !== undefined ? structuredClone(val) : undefined;
  }

  async put(key, value) {
    this.map.set(key, structuredClone(value));
  }

  async deleteAll() {
    this.map.clear();
    this.alarmTimeMs = null;
    this.deleteAllCalls += 1;
  }

  async setAlarm(scheduledTime) {
    this.alarmTimeMs =
      typeof scheduledTime === "number"
        ? scheduledTime
        : scheduledTime.getTime();
  }

  async getAlarm() {
    return this.alarmTimeMs;
  }
}

class MockDurableObjectState {
  constructor() {
    this.storage = new MockDurableObjectStorage();
    this.sockets = new Map(); // serverSocket -> tags[]
  }

  acceptWebSocket(ws, tags = []) {
    this.sockets.set(ws, [...tags]);
    ws._onInternalClose = () => {
      this.sockets.delete(ws);
    };
  }

  getWebSockets(tag) {
    const active = [];
    for (const [ws, tags] of this.sockets.entries()) {
      if (ws.closed) {
        continue;
      }
      if (tag === undefined || tags.includes(tag)) {
        active.push(ws);
      }
    }
    return active;
  }
}

function createTestHarness(extraEnv = {}) {
  let currentNowMs = 1_763_400_000_000;
  const fcmRequests = [];
  const roomsByName = new Map();

  const fetchFn = async (url, init) => {
    fcmRequests.push({
      url: String(url),
      method: init?.method ?? "GET",
      headers: init?.headers ?? {},
      body: init?.body ? JSON.parse(String(init.body)) : null,
    });
    return new Response(JSON.stringify({ name: "projects/focal/messages/1" }), {
      status: 200,
    });
  };

  const env = {
    ...extraEnv,
    FOCAL_ROOMS: {
      idFromName(name) {
        return { name };
      },
      get(id) {
        let entry = roomsByName.get(id.name);
        if (!entry) {
          const state = new MockDurableObjectState();
          const instance = new FocalRoomDO(state, env, {
            nowFn: () => currentNowMs,
            fetchFn,
          });
          entry = { state, instance };
          roomsByName.set(id.name, entry);
        }
        return entry.instance;
      },
    },
  };

  return {
    env,
    fcmRequests,
    advanceTimeMs(deltaMs) {
      currentNowMs += deltaMs;
    },
    getRoomEntry(roomId) {
      const id = env.FOCAL_ROOMS.idFromName(roomId);
      env.FOCAL_ROOMS.get(id);
      return roomsByName.get(roomId);
    },
  };
}

const VALID_ROOM_ID = "a".repeat(64);

async function upgradePeer(env, roomId, headers = {}) {
  const response = await worker.fetch(
    new Request(`https://sync.focal.app/v1/rooms/${roomId}`, {
      method: "GET",
      headers: {
        Upgrade: "websocket",
        ...headers,
      },
    }),
    env
  );
  return {
    response,
    clientSocket: response.webSocket,
    serverSocket: response.webSocket?.peer,
  };
}

describe("Focal Sync Relay — Worker Routing & Validation", () => {
  let harness;
  beforeEach(() => {
    harness = createTestHarness();
  });

  it("validates 64-character lowercase hex roomId strictly", async () => {
    assert.equal(isValidRoomId("0123456789abcdef".repeat(4)), true);
    assert.equal(isValidRoomId("A".repeat(64)), false);
    assert.equal(isValidRoomId("a".repeat(63)), false);
    assert.equal(isValidRoomId("g".repeat(64)), false);

    // Health endpoint
    const healthRes = await worker.fetch(
      new Request("https://sync.focal.app/health"),
      harness.env
    );
    assert.equal(healthRes.status, 200);

    // Invalid short roomId -> 400
    const badShort = await worker.fetch(
      new Request("https://sync.focal.app/v1/rooms/abcd1234", {
        headers: { Upgrade: "websocket" },
      }),
      harness.env
    );
    assert.equal(badShort.status, 400);

    // Uppercase hex roomId -> 400
    const badUpper = await worker.fetch(
      new Request(`https://sync.focal.app/v1/rooms/${"F".repeat(64)}`, {
        headers: { Upgrade: "websocket" },
      }),
      harness.env
    );
    assert.equal(badUpper.status, 400);

    // Unknown route -> 404
    const notFound = await worker.fetch(
      new Request("https://sync.focal.app/v2/other"),
      harness.env
    );
    assert.equal(notFound.status, 404);

    // Missing Upgrade: websocket header -> 426
    const noUpgrade = await worker.fetch(
      new Request(`https://sync.focal.app/v1/rooms/${VALID_ROOM_ID}`),
      harness.env
    );
    assert.equal(noUpgrade.status, 426);
  });
});

describe("FocalRoomDO — Broadcast, Ring Buffer Catch-Up, Limits & TTL", () => {
  let harness;
  beforeEach(() => {
    harness = createTestHarness();
  });

  it("broadcasts opaque frames to all room peers while excluding the sender", async () => {
    const peerA = await upgradePeer(harness.env, VALID_ROOM_ID);
    const peerB = await upgradePeer(harness.env, VALID_ROOM_ID);
    const peerC = await upgradePeer(harness.env, VALID_ROOM_ID);

    assert.equal(peerA.response.status, 101);
    assert.equal(peerB.response.status, 101);
    assert.equal(peerC.response.status, 101);

    const { instance } = harness.getRoomEntry(VALID_ROOM_ID);
    await instance.webSocketMessage(peerA.serverSocket, "opaque_ciphertext_01");

    assert.deepEqual(peerA.clientSocket.receivedFromPeer, []);
    assert.deepEqual(peerB.clientSocket.receivedFromPeer, [
      "opaque_ciphertext_01",
    ]);
    assert.deepEqual(peerC.clientSocket.receivedFromPeer, [
      "opaque_ciphertext_01",
    ]);
  });

  it("replays last 100 frames on offline catch-up and purges storage on 7-day TTL alarm", async () => {
    const sender = await upgradePeer(harness.env, VALID_ROOM_ID);
    const { instance, state } = harness.getRoomEntry(VALID_ROOM_ID);

    for (let i = 0; i < 105; i++) {
      await instance.webSocketMessage(sender.serverSocket, `frame_${i}`);
    }

    // Late-joining offline peer receives exact last 100 frames (frame_5 .. frame_104)
    const catchingUpPeer = await upgradePeer(harness.env, VALID_ROOM_ID);
    assert.equal(
      catchingUpPeer.clientSocket.receivedFromPeer.length,
      RING_BUFFER_CAPACITY
    );
    assert.equal(catchingUpPeer.clientSocket.receivedFromPeer[0], "frame_5");
    assert.equal(catchingUpPeer.clientSocket.receivedFromPeer[99], "frame_104");

    // Advance past 7-day TTL and trigger DO alarm
    assert.ok((await state.storage.getAlarm()) !== null);
    harness.advanceTimeMs(RING_BUFFER_TTL_MS + 1_000);
    await instance.alarm();
    assert.equal(state.storage.deleteAllCalls, 1);

    // Subsequent peer sees zero stale frames
    const postExpiryPeer = await upgradePeer(harness.env, VALID_ROOM_ID);
    assert.deepEqual(postExpiryPeer.clientSocket.receivedFromPeer, []);
  });

  it("enforces the 64 KB max frame size cap and rejects oversized or empty frames", async () => {
    const sender = await upgradePeer(harness.env, VALID_ROOM_ID);
    const receiver = await upgradePeer(harness.env, VALID_ROOM_ID);
    const { instance } = harness.getRoomEntry(VALID_ROOM_ID);

    // Exact 64 KB (65,536 bytes) succeeds
    const exactMaxFrame = "x".repeat(MAX_FRAME_BYTES);
    await instance.webSocketMessage(sender.serverSocket, exactMaxFrame);
    assert.equal(receiver.clientSocket.receivedFromPeer.length, 1);
    assert.equal(sender.serverSocket.closed, false);

    // 64 KB + 1 byte (65,537 bytes) is rejected with code 1009 and NOT broadcast or buffered
    const oversizedFrame = "y".repeat(MAX_FRAME_BYTES + 1);
    await instance.webSocketMessage(sender.serverSocket, oversizedFrame);
    assert.equal(sender.serverSocket.closed, true);
    assert.equal(sender.serverSocket.closeCode, 1009);
    assert.equal(receiver.clientSocket.receivedFromPeer.length, 1);

    // Late peer only receives the valid frame, never the rejected oversized frame
    const latePeer = await upgradePeer(harness.env, VALID_ROOM_ID);
    assert.equal(latePeer.clientSocket.receivedFromPeer.length, 1);
  });

  it("enforces the 16-device per-room connection cap and frees slots on close", async () => {
    const connectedPeers = [];
    for (let i = 0; i < MAX_ROOM_CONNECTIONS; i++) {
      const peer = await upgradePeer(harness.env, VALID_ROOM_ID);
      assert.equal(peer.response.status, 101);
      connectedPeers.push(peer);
    }

    // 17th device must be rejected with 429 Too Many Requests
    const seventeenth = await upgradePeer(harness.env, VALID_ROOM_ID);
    assert.equal(seventeenth.response.status, 429);

    // Disconnect 1st device -> slot opens up immediately
    const { instance } = harness.getRoomEntry(VALID_ROOM_ID);
    await instance.webSocketClose(
      connectedPeers[0].serverSocket,
      1000,
      "Normal close",
      true
    );

    const replacement = await upgradePeer(harness.env, VALID_ROOM_ID);
    assert.equal(replacement.response.status, 101);
  });

  it("forwards opaque ciphertext FCM push wakeups without inspecting plaintext", async () => {
    const fcmHarness = createTestHarness({
      FCM_ENDPOINT: "https://fcm.googleapis.com/v1/projects/focal/messages:send",
      FCM_BEARER_TOKEN: "test-oauth-bearer",
    });

    const androidPeer = await upgradePeer(fcmHarness.env, VALID_ROOM_ID, {
      "X-Focal-Device-Tag": "android-pixel",
      "X-Focal-Fcm-Token": "fcm_reg_token_pixel9",
    });
    const desktopPeer = await upgradePeer(fcmHarness.env, VALID_ROOM_ID, {
      "X-Focal-Device-Tag": "desktop-helium",
    });

    const { instance } = fcmHarness.getRoomEntry(VALID_ROOM_ID);

    // Desktop sends opaque frame -> triggers FCM wakeup to Android peer
    await instance.webSocketMessage(
      desktopPeer.serverSocket,
      "opaque_handoff_wire_frame"
    );
    assert.equal(fcmHarness.fcmRequests.length, 1);
    assert.deepEqual(fcmHarness.fcmRequests[0].body, {
      message: {
        token: "fcm_reg_token_pixel9",
        android: { priority: "high" },
        data: {
          roomId: VALID_ROOM_ID,
          wireFrame: "opaque_handoff_wire_frame",
        },
      },
    });

    // Android sends opaque frame -> does NOT send self-wakeup back to Android
    await instance.webSocketMessage(
      androidPeer.serverSocket,
      "opaque_android_presence"
    );
    assert.equal(fcmHarness.fcmRequests.length, 1);
  });

  it("guards FCM HTTP v1 wakeup payload size: inlines <= 3072-byte wireFrames and sends wakeOnly for > 3072-byte frames up to 64 KB", async () => {
    assert.equal(MAX_FCM_INLINE_WIRE_FRAME_BYTES, 3072);

    const fcmHarness = createTestHarness({
      FCM_ENDPOINT: "https://fcm.googleapis.com/v1/projects/focal/messages:send",
      FCM_BEARER_TOKEN: "test-oauth-bearer",
    });

    await upgradePeer(fcmHarness.env, VALID_ROOM_ID, {
      "X-Focal-Device-Tag": "android-pixel",
      "X-Focal-Fcm-Token": "fcm_reg_token_pixel9",
    });
    const desktopPeer = await upgradePeer(fcmHarness.env, VALID_ROOM_ID, {
      "X-Focal-Device-Tag": "desktop-helium",
    });

    const { instance } = fcmHarness.getRoomEntry(VALID_ROOM_ID);

    // 1. Exact boundary <= 3072 bytes -> includes wireFrame inline in FCM message.data
    const inlineMaxFrame = "w".repeat(3072);
    await instance.webSocketMessage(desktopPeer.serverSocket, inlineMaxFrame);
    assert.equal(fcmHarness.fcmRequests.length, 1);
    assert.deepEqual(fcmHarness.fcmRequests[0].body, {
      message: {
        token: "fcm_reg_token_pixel9",
        android: { priority: "high" },
        data: {
          roomId: VALID_ROOM_ID,
          wireFrame: inlineMaxFrame,
        },
      },
    });

    // 2. Boundary > 3072 bytes (3073 bytes) -> sends { roomId, wakeOnly: "1" } WITHOUT wireFrame
    const overLimitFrame = "x".repeat(3073);
    await instance.webSocketMessage(desktopPeer.serverSocket, overLimitFrame);
    assert.equal(fcmHarness.fcmRequests.length, 2);
    assert.deepEqual(fcmHarness.fcmRequests[1].body, {
      message: {
        token: "fcm_reg_token_pixel9",
        android: { priority: "high" },
        data: {
          roomId: VALID_ROOM_ID,
          wakeOnly: "1",
        },
      },
    });
    assert.equal(
      "wireFrame" in fcmHarness.fcmRequests[1].body.message.data,
      false
    );

    // 3. Maximum 64 KB frame via HTTP /wakeup -> sends { roomId, wakeOnly: "1" } WITHOUT wireFrame and buffers frame for WS catch-up
    const maxRelayFrame = "z".repeat(MAX_FRAME_BYTES);
    const wakeupRes = await worker.fetch(
      new Request(`https://sync.focal.app/v1/rooms/${VALID_ROOM_ID}/wakeup`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          deviceTag: "desktop-helium",
          wireFrame: maxRelayFrame,
        }),
      }),
      fcmHarness.env
    );
    assert.equal(wakeupRes.status, 202);
    assert.equal(fcmHarness.fcmRequests.length, 3);
    assert.deepEqual(fcmHarness.fcmRequests[2].body, {
      message: {
        token: "fcm_reg_token_pixel9",
        android: { priority: "high" },
        data: {
          roomId: VALID_ROOM_ID,
          wakeOnly: "1",
        },
      },
    });

    // Catching-up peer still receives all 3 full frames from the DO ring buffer
    const catchUpPeer = await upgradePeer(fcmHarness.env, VALID_ROOM_ID);
    assert.equal(catchUpPeer.clientSocket.receivedFromPeer.length, 3);
    assert.equal(catchUpPeer.clientSocket.receivedFromPeer[2], maxRelayFrame);
  });

  it("relays end-to-end AES-256-GCM frames derived from shared HKDF secret", async () => {
    const sharedSecret = globalThis.crypto.getRandomValues(new Uint8Array(32));
    const desktopKeys = await deriveTestRoomAndKey(sharedSecret);
    const androidKeys = await deriveTestRoomAndKey(sharedSecret);
    assert.equal(desktopKeys.roomId, androidKeys.roomId);

    const desktopConn = await upgradePeer(harness.env, desktopKeys.roomId);
    const { instance } = harness.getRoomEntry(desktopKeys.roomId);

    const enc = new TextEncoder();
    const iv = globalThis.crypto.getRandomValues(new Uint8Array(12));
    const envelope = {
      seq: 1,
      senderId: "desktop-chrome",
      timestampMs: Date.now(),
      event: {
        type: "tab_handoff",
        url: "https://focal.app/continuity",
        title: "Focal Continuity",
        spaceId: "personal",
      },
    };
    const ctWithTag = new Uint8Array(
      await globalThis.crypto.subtle.encrypt(
        {
          name: "AES-GCM",
          iv,
          additionalData: enc.encode(desktopKeys.roomId),
          tagLength: 128,
        },
        desktopKeys.symKey,
        enc.encode(JSON.stringify(envelope))
      )
    );
    const wireBytes = new Uint8Array(12 + ctWithTag.byteLength);
    wireBytes.set(iv, 0);
    wireBytes.set(ctWithTag, 12);
    const wireFrame = Buffer.from(wireBytes).toString("base64url");

    await instance.webSocketMessage(desktopConn.serverSocket, wireFrame);

    // Android comes online later, receives ring-buffer catch-up frame, and decrypts it
    const androidConn = await upgradePeer(harness.env, androidKeys.roomId);
    assert.equal(androidConn.clientSocket.receivedFromPeer.length, 1);

    const rxBytes = new Uint8Array(
      Buffer.from(androidConn.clientSocket.receivedFromPeer[0], "base64url")
    );
    const ptBuffer = await globalThis.crypto.subtle.decrypt(
      {
        name: "AES-GCM",
        iv: rxBytes.slice(0, 12),
        additionalData: enc.encode(androidKeys.roomId),
        tagLength: 128,
      },
      androidKeys.symKey,
      rxBytes.slice(12)
    );
    const decrypted = JSON.parse(new TextDecoder().decode(ptBuffer));
    assert.deepEqual(decrypted, envelope);
  });
});
