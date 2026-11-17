import assert from "node:assert/strict";
import nodeCrypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

import {
  DebouncedPresencePublisher,
  tabsToPresenceItems,
} from "../dist/background.js";
import {
  AES_GCM_IV_BYTE_LENGTH,
  AES_GCM_TAG_BYTE_LENGTH,
  FocalCryptoError,
  FocalReplayError,
  HKDF_INFO_AEAD_KEY,
  HKDF_INFO_ROOM_ID,
  HKDF_INFO_SAS,
  HKDF_SALT,
  ReplayTracker,
  SAS_WORDS,
  base64UrlToBytes,
  bytesToBase64Url,
  deriveFocalKeys,
  encryptEnvelope,
  decryptEnvelope,
  formatPairingUri,
  formatSasBytes,
  generatePairingSecret,
  parsePairingUri,
} from "../dist/crypto.js";
import {
  FocalSyncClient,
  WS_CONNECTING_READY_STATE,
  WS_OPEN_READY_STATE,
  buildRoomWebSocketUrl,
  computeBackoffDelayMs,
} from "../dist/sync-client.js";
import {
  cleanUrl,
  cleanUrlDetailed,
  extractDisplayDomain,
  isShareableHttpUrl,
} from "../dist/url-cleaner.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

describe("Focal Bridge E2EE Crypto & Wire Spec", () => {
  it("matches RFC 5869 HKDF-SHA256 byte-for-byte against an independent oracle", async () => {
    const fixedSecret = new Uint8Array(32);
    for (let i = 0; i < 32; i++) {
      fixedSecret[i] = (i * 7 + 13) & 0xff;
    }

    const derived = await deriveFocalKeys(fixedSecret);

    const expectedRoomIdBytes = new Uint8Array(
      nodeCrypto.hkdfSync(
        "sha256",
        fixedSecret,
        Buffer.from(HKDF_SALT, "utf8"),
        Buffer.from(HKDF_INFO_ROOM_ID, "utf8"),
        32
      )
    );
    const expectedSymKeyBytes = new Uint8Array(
      nodeCrypto.hkdfSync(
        "sha256",
        fixedSecret,
        Buffer.from(HKDF_SALT, "utf8"),
        Buffer.from(HKDF_INFO_AEAD_KEY, "utf8"),
        32
      )
    );
    const expectedSasBytes = new Uint8Array(
      nodeCrypto.hkdfSync(
        "sha256",
        fixedSecret,
        Buffer.from(HKDF_SALT, "utf8"),
        Buffer.from(HKDF_INFO_SAS, "utf8"),
        4
      )
    );

    assert.equal(derived.roomId.length, 64);
    assert.match(derived.roomId, /^[0-9a-f]{64}$/);
    assert.equal(
      derived.roomId,
      Buffer.from(expectedRoomIdBytes).toString("hex")
    );
    assert.deepEqual(derived.symKeyBytes, expectedSymKeyBytes);
    assert.deepEqual(derived.sasBytes, expectedSasBytes);
  });

  it("formats SAS bytes using the exact 64-word list and 2-digit zero-padded suffix", () => {
    assert.equal(SAS_WORDS.length, 64);
    assert.equal(SAS_WORDS[0], "amber");
    assert.equal(SAS_WORDS[39], "focal");
    assert.equal(SAS_WORDS[63], "obsidian");

    // High bits in bytes 0..2 must be masked with & 63; byte 3 mod 100 zero-padded
    const sampleA = new Uint8Array([0b1100_0000, 39, 63, 7]);
    assert.equal(formatSasBytes(sampleA), "amber · focal · obsidian · 07");

    const sampleB = new Uint8Array([1, 43, 58, 249]); // 249 % 100 === 49
    assert.equal(formatSasBytes(sampleB), "aperture · glacier · lumen · 49");
  });

  it("round-trips and strictly validates focal-pair://v1 pairing URIs", () => {
    const secret = generatePairingSecret();
    const uri = formatPairingUri(secret);
    assert.ok(uri.startsWith("focal-pair://v1?secret="));
    const encodedSecret = uri.slice("focal-pair://v1?secret=".length);
    assert.equal(encodedSecret.length, 43);
    assert.ok(!encodedSecret.includes("="), "secret must be unpadded base64url");

    const parsed = parsePairingUri(uri);
    assert.deepEqual(parsed.secret, secret);

    // Reject padded base64, wrong scheme, and wrong secret byte length
    const paddedSecret = Buffer.from(secret).toString("base64");
    assert.throws(
      () => parsePairingUri(`focal-pair://v1?secret=${paddedSecret}`),
      FocalCryptoError
    );
    assert.throws(
      () => parsePairingUri("https://focal.app/v1?secret=abc"),
      FocalCryptoError
    );
    assert.throws(
      () =>
        parsePairingUri(
          `focal-pair://v1?secret=${bytesToBase64Url(new Uint8Array(16))}`
        ),
      FocalCryptoError
    );
  });

  it("rejects cleartext ws:// and http:// relay URLs by default and allows loopback only when allowInsecureLocalhost is true", () => {
    const secret = generatePairingSecret();

    // Secure wss:// and https:// relays succeed by default
    const secureWss = parsePairingUri(
      formatPairingUri(secret, { relayUrl: "wss://relay.focal.app" })
    );
    assert.equal(secureWss.relayUrl, "wss://relay.focal.app");

    const secureHttps = parsePairingUri(
      formatPairingUri(secret, { relayUrl: "https://relay.focal.app" })
    );
    assert.equal(secureHttps.relayUrl, "https://relay.focal.app");

    // Cleartext remote and LAN relays (ws://attacker.com, ws://192.168.1.1, http://...) must ALWAYS be rejected
    for (const badRelay of [
      "ws://attacker.com",
      "ws://attacker.com:8080/v1",
      "ws://192.168.1.1",
      "ws://192.168.1.1:9000",
      "http://attacker.com",
      "http://192.168.1.1:8080",
    ]) {
      const badUri = formatPairingUri(secret, { relayUrl: badRelay });
      assert.throws(() => parsePairingUri(badUri), FocalCryptoError);
      assert.throws(
        () => parsePairingUri(badUri, { allowInsecureLocalhost: true }),
        FocalCryptoError
      );
    }

    // Loopback ws://localhost and ws://127.0.0.1 are rejected by default, allowed ONLY with allowInsecureLocalhost: true
    for (const localRelay of [
      "ws://localhost:8787",
      "ws://127.0.0.1:8787",
      "http://localhost:8787",
      "http://127.0.0.1:8787",
    ]) {
      const localUri = formatPairingUri(secret, { relayUrl: localRelay });
      assert.throws(() => parsePairingUri(localUri), FocalCryptoError);
      assert.throws(
        () => parsePairingUri(localUri, { allowInsecureLocalhost: false }),
        FocalCryptoError
      );
      const allowed = parsePairingUri(localUri, {
        allowInsecureLocalhost: true,
      });
      assert.equal(allowed.relayUrl, localRelay);
    }
  });

  it("encrypts and decrypts AES-256-GCM wire frames compatible with raw IV||ciphertext||tag", async () => {
    const secret = generatePairingSecret();
    const keys = await deriveFocalKeys(secret);
    const tracker = new ReplayTracker();

    const envelope = {
      seq: 1,
      senderId: "android-pixel9",
      timestampMs: 1_763_400_000_000,
      event: {
        type: "tab_handoff",
        url: "https://developer.android.com/jetpack/compose",
        title: "Jetpack Compose",
        spaceId: "work",
      },
    };

    const wireFrame = await encryptEnvelope(envelope, keys.symKey, keys.roomId);
    assert.ok(!wireFrame.includes("="));

    // Independently decrypt via Node's OpenSSL AES-256-GCM to verify exact wire layout
    const rawWire = base64UrlToBytes(wireFrame);
    const iv = rawWire.subarray(0, AES_GCM_IV_BYTE_LENGTH);
    const tag = rawWire.subarray(rawWire.byteLength - AES_GCM_TAG_BYTE_LENGTH);
    const ciphertext = rawWire.subarray(
      AES_GCM_IV_BYTE_LENGTH,
      rawWire.byteLength - AES_GCM_TAG_BYTE_LENGTH
    );

    const decipher = nodeCrypto.createDecipheriv(
      "aes-256-gcm",
      keys.symKeyBytes,
      iv
    );
    decipher.setAAD(Buffer.from(keys.roomId, "utf8"));
    decipher.setAuthTag(tag);
    const manualPlaintext = Buffer.concat([
      decipher.update(ciphertext),
      decipher.final(),
    ]).toString("utf8");
    assert.deepEqual(JSON.parse(manualPlaintext), envelope);

    // Decrypt via WebCrypto implementation
    const decrypted = await decryptEnvelope(
      wireFrame,
      keys.symKey,
      keys.roomId,
      tracker
    );
    assert.deepEqual(decrypted, envelope);
    assert.equal(tracker.getHighestSeq("android-pixel9"), 1);
  });

  it("rejects AAD roomId tampering and ciphertext bit flips without mutating replay state", async () => {
    const keysA = await deriveFocalKeys(generatePairingSecret());
    const keysB = await deriveFocalKeys(generatePairingSecret());
    const tracker = new ReplayTracker();

    const frame = await encryptEnvelope(
      {
        seq: 5,
        senderId: "android-1",
        timestampMs: Date.now(),
        event: {
          type: "presence_tabs",
          deviceName: "Pixel 9 Pro",
          platform: "android",
          tabs: [],
        },
      },
      keysA.symKey,
      keysA.roomId
    );

    // Wrong AAD (different roomId with same symKey) must fail GCM tag verification
    await assert.rejects(
      () => decryptEnvelope(frame, keysA.symKey, keysB.roomId, tracker),
      FocalCryptoError
    );
    assert.equal(
      tracker.getHighestSeq("android-1"),
      undefined,
      "Replay state must not advance when AEAD authentication fails"
    );

    // Flipping 1 bit in ciphertext must fail GCM tag verification
    const tamperedBytes = base64UrlToBytes(frame);
    tamperedBytes[15] ^= 0x01;
    const tamperedFrame = bytesToBase64Url(tamperedBytes);
    await assert.rejects(
      () => decryptEnvelope(tamperedFrame, keysA.symKey, keysA.roomId, tracker),
      FocalCryptoError
    );
    assert.equal(tracker.getHighestSeq("android-1"), undefined);
  });

  it("rejects duplicate and stale sequence numbers per senderId", async () => {
    const keys = await deriveFocalKeys(generatePairingSecret());
    const tracker = new ReplayTracker();

    const makeFrame = (senderId, seq) =>
      encryptEnvelope(
        {
          seq,
          senderId,
          timestampMs: Date.now(),
          event: {
            type: "spaces_snapshot",
            spaces: [],
            updatedAtMs: Date.now(),
          },
        },
        keys.symKey,
        keys.roomId
      );

    const f1 = await makeFrame("android-a", 1);
    const f2 = await makeFrame("android-a", 2);
    const fOther = await makeFrame("desktop-b", 1);

    await decryptEnvelope(f2, keys.symKey, keys.roomId, tracker);

    // Exact replay (seq 2 == 2) rejected
    await assert.rejects(
      () => decryptEnvelope(f2, keys.symKey, keys.roomId, tracker),
      FocalReplayError
    );

    // Stale out-of-order replay (seq 1 < 2) rejected
    await assert.rejects(
      () => decryptEnvelope(f1, keys.symKey, keys.roomId, tracker),
      FocalReplayError
    );

    // Different senderId maintains an independent sequence watermark
    const otherDecrypted = await decryptEnvelope(
      fOther,
      keys.symKey,
      keys.roomId,
      tracker
    );
    assert.equal(otherDecrypted.seq, 1);
  });
});

describe("URL Tracker Cleaner", () => {
  it("strips utm_*, fbclid, gclid, msclkid, mc_eid, igshid, si, _hsenc, vero_id while keeping real params", () => {
    const dirty =
      "https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=shareTrack99&utm_source=newsletter&utm_medium=email&fbclid=IwAR123&gclid=Cj0KCQ&t=42s#comments";
    const result = cleanUrlDetailed(dirty);

    assert.equal(
      result.cleanUrl,
      "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s#comments"
    );
    assert.equal(result.wasModified, true);
    assert.deepEqual(result.strippedParams, [
      "si",
      "utm_source",
      "utm_medium",
      "fbclid",
      "gclid",
    ]);

    assert.equal(
      cleanUrl(
        "https://example.org/article?mc_eid=deadbeef&msclkid=9988&igshid=abc&_hsenc=p2ANqtz&vero_id=77"
      ),
      "https://example.org/article"
    );
    assert.equal(extractDisplayDomain(dirty), "youtube.com");
  });

  it("preserves untouched URLs verbatim when no tracking parameters are stripped", () => {
    const bareOrigin = "https://example.com";
    const bareResult = cleanUrlDetailed(bareOrigin);
    assert.equal(bareResult.cleanUrl, "https://example.com");
    assert.equal(bareResult.wasModified, false);
    assert.deepEqual(bareResult.strippedParams, []);
    assert.equal(cleanUrl(bareOrigin), "https://example.com");

    const cleanQueryUrl = "https://example.com?q=rust+wasm&page=2#results";
    const queryResult = cleanUrlDetailed(cleanQueryUrl);
    assert.equal(queryResult.cleanUrl, cleanQueryUrl);
    assert.equal(queryResult.wasModified, false);
    assert.deepEqual(queryResult.strippedParams, []);
  });

  it("accepts only http/https shareable URLs and rejects privileged schemes", () => {
    assert.equal(isShareableHttpUrl("https://focal.app/docs"), true);
    assert.equal(isShareableHttpUrl("http://localhost:8080/test"), true);
    assert.equal(isShareableHttpUrl("javascript:alert(1)"), false);
    assert.equal(isShareableHttpUrl("chrome://extensions"), false);
    assert.equal(isShareableHttpUrl("file:///etc/passwd"), false);
  });
});

describe("FocalSyncClient Offline Queue, Backoff & Encrypted Dispatch", () => {
  it("queues encrypted envelopes while offline, flushes in order on open, and filters replays", async () => {
    const keys = await deriveFocalKeys(generatePairingSecret());
    const createdSockets = [];
    const receivedEnvelopes = [];

    class MockSocket {
      constructor(url) {
        this.url = url;
        this.readyState = WS_CONNECTING_READY_STATE;
        this.sentFrames = [];
        this.onopen = null;
        this.onmessage = null;
        this.onclose = null;
        this.onerror = null;
      }
      send(data) {
        this.sentFrames.push(data);
      }
      close() {
        this.readyState = 3;
        this.onclose?.({ code: 1000 });
      }
      simulateOpen() {
        this.readyState = WS_OPEN_READY_STATE;
        this.onopen?.({});
      }
      simulateMessage(data) {
        this.onmessage?.({ data });
      }
      simulateDrop() {
        this.readyState = 3;
        this.onclose?.({ code: 1006 });
      }
    }

    const client = new FocalSyncClient({
      keys,
      senderId: "desktop-ext-1",
      relayBaseUrl: "https://relay.focal.example",
      baseBackoffMs: 50,
      maxBackoffMs: 200,
      jitterRatio: 0,
      wsFactory: (url) => {
        const sock = new MockSocket(url);
        createdSockets.push(sock);
        return sock;
      },
      onEnvelope: (env) => {
        receivedEnvelopes.push(env);
      },
    });

    // Queue 2 events BEFORE connecting
    const handoffDispatch = await client.sendTabHandoff({
      url: "https://news.ycombinator.com/item?id=1&utm_source=x",
      title: "HN",
      spaceId: "reading",
    });
    assert.equal(handoffDispatch.sentImmediately, false);
    assert.equal(client.getQueuedCount(), 1);

    await client.sendSpacesSnapshot([
      { id: "personal", label: "Personal", bookmarks: [] },
    ]);
    assert.equal(client.getQueuedCount(), 2);

    client.connect();
    assert.equal(createdSockets.length, 1);
    assert.equal(
      createdSockets[0].url,
      buildRoomWebSocketUrl("wss://relay.focal.example", keys.roomId)
    );

    // Open socket -> queued frames flush immediately in FIFO seq order
    createdSockets[0].simulateOpen();
    assert.equal(client.getStatus(), "connected");
    assert.equal(client.getQueuedCount(), 0);
    assert.equal(createdSockets[0].sentFrames.length, 2);

    const sent0 = await decryptEnvelope(
      createdSockets[0].sentFrames[0],
      keys.symKey,
      keys.roomId
    );
    const sent1 = await decryptEnvelope(
      createdSockets[0].sentFrames[1],
      keys.symKey,
      keys.roomId
    );
    assert.equal(sent0.seq, 1);
    assert.equal(sent0.event.type, "tab_handoff");
    assert.equal(sent0.event.url, "https://news.ycombinator.com/item?id=1");
    assert.equal(sent1.seq, 2);
    assert.equal(sent1.event.type, "spaces_snapshot");

    // Simulate incoming Android presence frame + duplicate replay + self-echo
    const androidFrame = await encryptEnvelope(
      {
        seq: 1,
        senderId: "android-peer",
        timestampMs: Date.now(),
        event: {
          type: "presence_tabs",
          deviceName: "Pixel 9 Pro",
          platform: "android",
          tabs: [{ id: "t1", url: "https://focal.app", title: "Focal" }],
        },
      },
      keys.symKey,
      keys.roomId
    );

    createdSockets[0].simulateMessage(androidFrame);
    createdSockets[0].simulateMessage(androidFrame); // replay
    createdSockets[0].simulateMessage(createdSockets[0].sentFrames[0]); // self-echo
    await new Promise((r) => setTimeout(r, 25));

    assert.equal(receivedEnvelopes.length, 1);
    assert.equal(receivedEnvelopes[0].event.type, "presence_tabs");
    assert.equal(client.getReplaysIgnoredCount(), 1);

    // Simulate connection drop -> exponential backoff reconnects automatically
    createdSockets[0].simulateDrop();
    assert.equal(client.getStatus(), "reconnecting");
    await new Promise((r) => setTimeout(r, 75));
    assert.equal(createdSockets.length, 2);

    client.disconnect();
    assert.equal(computeBackoffDelayMs(0, 500, 30_000, 0), 500);
    assert.equal(computeBackoffDelayMs(3, 500, 30_000, 0), 4000);
    assert.equal(computeBackoffDelayMs(10, 500, 30_000, 0), 30_000);
  });
});

describe("Desktop Live Open-Tab Presence Publisher", () => {
  it("debounces rapid tab updates, filters incognito/internal pages, and dispatches encrypted presence_tabs", async () => {
    const keys = await deriveFocalKeys(generatePairingSecret());
    const sentFrames = [];

    const client = new FocalSyncClient({
      keys,
      senderId: "desktop-presence-1",
      relayBaseUrl: "wss://relay.focal.example",
      wsFactory: () => ({
        readyState: WS_OPEN_READY_STATE,
        onopen: null,
        onmessage: null,
        onclose: null,
        onerror: null,
        send(data) {
          sentFrames.push(data);
        },
        close() {},
      }),
    });
    client.connect();

    let currentTabs = [
      {
        id: 10,
        url: "chrome://extensions",
        title: "Extensions",
        active: false,
      },
      {
        id: 11,
        url: "https://secret.example/private",
        title: "Incognito Tab",
        incognito: true,
        active: false,
      },
      {
        id: 12,
        url: "https://example.com?utm_source=twitter",
        title: "Example Clean",
        active: false,
        lastAccessed: 1_763_400_000_123.7,
      },
      {
        id: 13,
        url: "https://focal.app",
        title: "Focal Browser",
        active: true,
        lastAccessed: 1_763_400_005_000.2,
      },
    ];

    const items = tabsToPresenceItems(currentTabs, "work");
    assert.equal(items.length, 2);
    assert.equal(items[0].id, "desktop-tab-13");
    assert.equal(items[0].url, "https://focal.app");
    assert.equal(items[0].active, true);
    assert.equal(items[0].lastActiveMs, 1_763_400_005_000);
    assert.equal(items[1].id, "desktop-tab-12");
    assert.equal(items[1].url, "https://example.com/");
    assert.equal(items[1].lastActiveMs, 1_763_400_000_124);

    const publisher = new DebouncedPresencePublisher({
      debounceMs: 30,
      queryTabs: async () => currentTabs,
      getActiveSpaceId: () => "work",
      getDeviceName: () => "Focal Bridge Desktop",
      getSyncClient: () => client,
      isPaired: () => true,
    });

    // Rapid burst of 4 tab events coalesces into 1 presence_tabs frame
    publisher.schedule();
    publisher.schedule();
    publisher.schedule();
    publisher.schedule();
    await new Promise((r) => setTimeout(r, 70));

    assert.equal(sentFrames.length, 1);
    const decoded = await decryptEnvelope(
      sentFrames[0],
      keys.symKey,
      keys.roomId
    );
    assert.equal(decoded.event.type, "presence_tabs");
    assert.equal(decoded.event.platform, "desktop");
    assert.equal(decoded.event.tabs.length, 2);
    assert.equal(decoded.event.tabs[0].url, "https://focal.app");

    // Unchanged tab list is deduplicated (no duplicate frame sent)
    const duplicatePublished = await publisher.publishNow();
    assert.equal(duplicatePublished, false);
    assert.equal(sentFrames.length, 1);

    // Tab change dispatches updated presence_tabs frame
    currentTabs = [
      {
        id: 14,
        url: "https://developer.mozilla.org",
        title: "MDN",
        active: true,
        lastAccessed: 1_763_400_010_000,
      },
    ];
    publisher.schedule();
    await new Promise((r) => setTimeout(r, 70));
    assert.equal(sentFrames.length, 2);

    publisher.reset();
    client.disconnect();
  });
});

describe("MV3 Manifest Least-Privilege Guardrails", () => {
  it("declares Manifest V3 with minimal permissions and zero broad host_permissions", () => {
    const manifestPath = path.resolve(__dirname, "../manifest.json");
    const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));

    assert.equal(manifest.manifest_version, 3);
    assert.deepEqual(
      [...manifest.permissions].sort(),
      ["activeTab", "alarms", "contextMenus", "storage", "tabs"]
    );
    assert.equal(manifest.host_permissions, undefined);
    assert.equal(manifest.optional_host_permissions, undefined);
  });
});
