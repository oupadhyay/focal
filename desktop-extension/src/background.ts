/**
 * Focal Bridge — Manifest V3 Service Worker (`background.ts`).
 *
 * Responsibilities:
 * - Right-click context menus ("Send Link to Focal Android", "Send Page to Focal Android")
 * - Keyboard shortcut command (`send-active-tab`)
 * - `chrome.alarms` MV3 service-worker keepalive & offline queue flush
 * - Persistent encrypted state management in `chrome.storage.local`
 * - Structured message RPC with the 320px Smoked Glass extension popover
 */

import {
  type DerivedFocalKeys,
  type EncryptedEnvelope,
  type PresenceTabItem,
  type SpaceBookmarkItem,
  type SpaceId,
  type SpaceModel,
  type SyncEvent,
  base64UrlToBytes,
  bytesToHex,
  deriveFocalKeys,
  parsePairingUri,
  validateRelayUrl,
} from "./crypto.js";
import {
  DEFAULT_RELAY_BASE_URL,
  FocalSyncClient,
  type PersistedSyncClientState,
  type SyncConnectionStatus,
} from "./sync-client.js";
import {
  cleanUrl,
  cleanUrlDetailed,
  extractDisplayDomain,
  isShareableHttpUrl,
} from "./url-cleaner.js";

const STORAGE_KEY = "focalBridgeStateV1";
const KEEPALIVE_ALARM_NAME = "focal-sync-keepalive";
const KEEPALIVE_PERIOD_MINUTES = 0.5;

export const PRESENCE_DEBOUNCE_MS = 600;
export const MAX_PRESENCE_TABS = 50;

const MENU_SEND_LINK_ID = "focal-send-link";
const MENU_SEND_PAGE_ID = "focal-send-page";
const COMMAND_SEND_ACTIVE_TAB = "send-active-tab";

export interface StoredPairingRecord {
  secretBase64Url: string;
  relayBaseUrl: string;
  pairedAtMs: number;
}

export interface LastHandoffSummary {
  cleanUrl: string;
  domain: string;
  strippedParams: string[];
  sentImmediately: boolean;
  timestampMs: number;
}

export interface StoredBridgeData {
  deviceId: string;
  deviceName: string;
  pairing: StoredPairingRecord | null;
  syncState: PersistedSyncClientState;
  remoteDeviceName: string;
  remoteTabs: PresenceTabItem[];
  spaces: SpaceModel[];
  activeSpaceId: SpaceId;
  lastHandoff: LastHandoffSummary | null;
}

export interface BridgeUiState {
  paired: boolean;
  sas: string | null;
  roomIdShort: string | null;
  relayBaseUrl: string;
  connectionStatus: SyncConnectionStatus;
  queuedCount: number;
  deviceName: string;
  remoteDeviceName: string;
  remoteTabs: PresenceTabItem[];
  spaces: SpaceModel[];
  activeSpaceId: SpaceId;
  lastHandoff: LastHandoffSummary | null;
}

export type BridgePopupRequest =
  | { type: "GET_STATE" }
  | { type: "PREVIEW_PAIRING_URI"; uri: string }
  | { type: "COMPLETE_PAIRING"; uri: string; relayBaseUrl?: string }
  | { type: "UNPAIR" }
  | {
      type: "SEND_TAB_HANDOFF";
      url: string;
      title: string;
      spaceId?: SpaceId;
    }
  | { type: "SET_ACTIVE_SPACE"; spaceId: SpaceId }
  | {
      type: "ADD_SPACE_BOOKMARK";
      spaceId: SpaceId;
      title: string;
      url: string;
    }
  | {
      type: "REMOVE_SPACE_BOOKMARK";
      spaceId: SpaceId;
      bookmarkId: string;
    };

export type BridgePopupResponse =
  | { ok: true; state: BridgeUiState; handoff?: LastHandoffSummary }
  | {
      ok: true;
      preview: { sas: string; roomId: string; relayUrl?: string };
    }
  | { ok: false; error: string };

function generateRandomId(byteLen = 8): string {
  const bytes = new Uint8Array(byteLen);
  globalThis.crypto.getRandomValues(bytes);
  return bytesToHex(bytes);
}

function createDefaultSpaces(): SpaceModel[] {
  return [
    { id: "personal", label: "Personal", bookmarks: [] },
    { id: "work", label: "Work", bookmarks: [] },
    { id: "reading", label: "Reading", bookmarks: [] },
  ];
}

function normalizeSpaces(candidate: SpaceModel[] | undefined): SpaceModel[] {
  const defaults = createDefaultSpaces();
  if (!Array.isArray(candidate)) {
    return defaults;
  }
  const byId = new Map<SpaceId, SpaceBookmarkItem[]>();
  for (const space of candidate) {
    if (
      space &&
      (space.id === "personal" ||
        space.id === "work" ||
        space.id === "reading") &&
      Array.isArray(space.bookmarks)
    ) {
      const validBookmarks = space.bookmarks
        .filter((bm) => bm && isShareableHttpUrl(bm.url))
        .map((bm) => ({
          id:
            typeof bm.id === "string" && bm.id.length > 0
              ? bm.id
              : generateRandomId(6),
          title:
            typeof bm.title === "string" && bm.title.trim().length > 0
              ? bm.title.trim()
              : extractDisplayDomain(bm.url),
          url: cleanUrl(bm.url),
          pinned: Boolean(bm.pinned),
          addedAtMs:
            typeof bm.addedAtMs === "number" && Number.isFinite(bm.addedAtMs)
              ? bm.addedAtMs
              : Date.now(),
        }));
      byId.set(space.id, validBookmarks);
    }
  }
  return defaults.map((def) => ({
    id: def.id,
    label: def.label,
    bookmarks: byId.get(def.id) ?? [],
  }));
}

function createDefaultStoredData(): StoredBridgeData {
  return {
    deviceId: `desktop-${generateRandomId(8)}`,
    deviceName: "Focal Bridge Desktop",
    pairing: null,
    syncState: {
      nextSeq: 1,
      outboundQueue: [],
      replaySnapshot: {},
    },
    remoteDeviceName: "Focal Android",
    remoteTabs: [],
    spaces: createDefaultSpaces(),
    activeSpaceId: "personal",
    lastHandoff: null,
  };
}

let cachedData: StoredBridgeData | null = null;
let cachedKeys: DerivedFocalKeys | null = null;
let syncClient: FocalSyncClient | null = null;
let bootstrapPromise: Promise<void> | null = null;

async function readStorage(): Promise<StoredBridgeData> {
  const raw = await chrome.storage.local.get(STORAGE_KEY);
  const existing = raw[STORAGE_KEY] as Partial<StoredBridgeData> | undefined;
  const defaults = createDefaultStoredData();
  if (!existing || typeof existing !== "object") {
    await chrome.storage.local.set({ [STORAGE_KEY]: defaults });
    return defaults;
  }

  const hydrated: StoredBridgeData = {
    deviceId:
      typeof existing.deviceId === "string" && existing.deviceId.length > 0
        ? existing.deviceId
        : defaults.deviceId,
    deviceName:
      typeof existing.deviceName === "string" && existing.deviceName.length > 0
        ? existing.deviceName
        : defaults.deviceName,
    pairing:
      existing.pairing &&
      typeof existing.pairing.secretBase64Url === "string" &&
      typeof existing.pairing.relayBaseUrl === "string"
        ? existing.pairing
        : null,
    syncState: {
      nextSeq:
        typeof existing.syncState?.nextSeq === "number"
          ? existing.syncState.nextSeq
          : 1,
      outboundQueue: Array.isArray(existing.syncState?.outboundQueue)
        ? existing.syncState.outboundQueue
        : [],
      replaySnapshot:
        existing.syncState?.replaySnapshot &&
        typeof existing.syncState.replaySnapshot === "object"
          ? existing.syncState.replaySnapshot
          : {},
    },
    remoteDeviceName:
      typeof existing.remoteDeviceName === "string" &&
      existing.remoteDeviceName.trim().length > 0
        ? existing.remoteDeviceName
        : "Focal Android",
    remoteTabs: Array.isArray(existing.remoteTabs)
      ? existing.remoteTabs.filter((t) => t && isShareableHttpUrl(t.url))
      : [],
    spaces: normalizeSpaces(existing.spaces),
    activeSpaceId:
      existing.activeSpaceId === "personal" ||
      existing.activeSpaceId === "work" ||
      existing.activeSpaceId === "reading"
        ? existing.activeSpaceId
        : "personal",
    lastHandoff: existing.lastHandoff ?? null,
  };

  return hydrated;
}

async function saveStorage(data: StoredBridgeData): Promise<void> {
  cachedData = data;
  await chrome.storage.local.set({ [STORAGE_KEY]: data });
}

function buildUiState(data: StoredBridgeData): BridgeUiState {
  return {
    paired: Boolean(data.pairing && cachedKeys),
    sas: cachedKeys?.sas ?? null,
    roomIdShort: cachedKeys ? cachedKeys.roomId.slice(0, 12) : null,
    relayBaseUrl: data.pairing?.relayBaseUrl ?? DEFAULT_RELAY_BASE_URL,
    connectionStatus: syncClient?.getStatus() ?? "disconnected",
    queuedCount:
      syncClient?.getQueuedCount() ?? data.syncState.outboundQueue.length,
    deviceName: data.deviceName,
    remoteDeviceName: data.remoteDeviceName,
    remoteTabs: data.remoteTabs,
    spaces: data.spaces,
    activeSpaceId: data.activeSpaceId,
    lastHandoff: data.lastHandoff,
  };
}

function broadcastStateToPopup(): void {
  if (!cachedData) {
    return;
  }
  const payload = {
    type: "BRIDGE_STATE_PUSH",
    state: buildUiState(cachedData),
  };
  chrome.runtime.sendMessage(payload).catch(() => {
    // Popup is closed; ignore expected "Receiving end does not exist" error
  });
}

async function handleInboundEnvelope(
  envelope: EncryptedEnvelope<SyncEvent>
): Promise<void> {
  const data = await ensureBootstrapped();
  const event = envelope.event;

  switch (event.type) {
    case "presence_tabs": {
      if (
        typeof event.deviceName === "string" &&
        event.deviceName.trim().length > 0
      ) {
        data.remoteDeviceName = event.deviceName.trim();
      }
      if (Array.isArray(event.tabs)) {
        data.remoteTabs = event.tabs
          .filter((tab) => tab && isShareableHttpUrl(tab.url))
          .slice(0, 50)
          .map((tab) => ({
            id:
              typeof tab.id === "string" && tab.id.length > 0
                ? tab.id
                : generateRandomId(6),
            url: cleanUrl(tab.url),
            title:
              typeof tab.title === "string" && tab.title.trim().length > 0
                ? tab.title.trim()
                : extractDisplayDomain(tab.url),
            ...(tab.spaceId ? { spaceId: tab.spaceId } : {}),
            ...(typeof tab.active === "boolean" ? { active: tab.active } : {}),
            ...(typeof tab.lastActiveMs === "number"
              ? { lastActiveMs: tab.lastActiveMs }
              : {}),
          }));
      }
      await saveStorage(data);
      broadcastStateToPopup();
      break;
    }

    case "spaces_snapshot": {
      data.spaces = normalizeSpaces(event.spaces);
      await saveStorage(data);
      broadcastStateToPopup();
      break;
    }

    case "tab_handoff": {
      if (isShareableHttpUrl(event.url)) {
        const safeUrl = cleanUrl(event.url);
        await chrome.tabs.create({ url: safeUrl, active: true });
      }
      break;
    }
  }
}

export interface BrowserTabMetadata {
  id?: number;
  url?: string;
  title?: string;
  active?: boolean;
  incognito?: boolean;
  lastAccessed?: number;
}

/**
 * Converts raw browser tabs into sanitized `PresenceTabItem[]` entries,
 * excluding incognito tabs and non-HTTP(S) internal pages, stripping tracking
 * parameters, rounding `lastActiveMs` to a strict integer, and capping at 50 tabs.
 */
export function tabsToPresenceItems(
  tabs: readonly BrowserTabMetadata[],
  activeSpaceId: SpaceId
): PresenceTabItem[] {
  const eligible = tabs.filter(
    (tab) =>
      Boolean(tab) &&
      tab.incognito !== true &&
      isShareableHttpUrl(tab.url)
  );

  eligible.sort((a, b) => {
    const activeDiff = Number(Boolean(b.active)) - Number(Boolean(a.active));
    if (activeDiff !== 0) {
      return activeDiff;
    }
    const lastAccessedDiff = (b.lastAccessed ?? 0) - (a.lastAccessed ?? 0);
    if (lastAccessedDiff !== 0) {
      return lastAccessedDiff;
    }
    return (a.id ?? 0) - (b.id ?? 0);
  });

  return eligible.slice(0, MAX_PRESENCE_TABS).map((tab) => {
    const cleaned = cleanUrl(tab.url!);
    const rawTitle = typeof tab.title === "string" ? tab.title.trim() : "";
    const lastAccessed = tab.lastAccessed;
    return {
      id:
        typeof tab.id === "number" && Number.isSafeInteger(tab.id)
          ? `desktop-tab-${tab.id}`
          : generateRandomId(6),
      url: cleaned,
      title: rawTitle.length > 0 ? rawTitle : extractDisplayDomain(cleaned),
      spaceId: activeSpaceId,
      active: Boolean(tab.active),
      ...(typeof lastAccessed === "number" &&
      Number.isFinite(lastAccessed) &&
      lastAccessed > 0
        ? { lastActiveMs: Math.round(lastAccessed) }
        : {}),
    };
  });
}

export interface DebouncedPresencePublisherOptions {
  debounceMs?: number;
  queryTabs: () => Promise<readonly BrowserTabMetadata[]>;
  getActiveSpaceId: () => SpaceId;
  getDeviceName: () => string;
  getSyncClient: () => FocalSyncClient | null;
  isPaired: () => boolean;
}

/**
 * Coalesces rapid browser tab lifecycle events (`onUpdated`, `onActivated`,
 * `onRemoved`) and deduplicates unchanged open-tab snapshots before dispatching
 * encrypted `presence_tabs` envelopes via `FocalSyncClient.sendPresenceTabs(...)`.
 */
export class DebouncedPresencePublisher {
  private readonly debounceMs: number;
  private readonly options: DebouncedPresencePublisherOptions;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private lastSignature: string | null = null;

  constructor(options: DebouncedPresencePublisherOptions) {
    this.debounceMs = Math.max(10, options.debounceMs ?? PRESENCE_DEBOUNCE_MS);
    this.options = options;
  }

  reset(): void {
    this.cancelPending();
    this.lastSignature = null;
  }

  cancelPending(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  schedule(): void {
    if (!this.options.isPaired()) {
      return;
    }
    this.cancelPending();
    this.timer = setTimeout(() => {
      this.timer = null;
      void this.publishNow();
    }, this.debounceMs);
  }

  async publishNow(opts?: { force?: boolean }): Promise<boolean> {
    this.cancelPending();
    if (!this.options.isPaired()) {
      return false;
    }
    const client = this.options.getSyncClient();
    if (!client) {
      return false;
    }

    let rawTabs: readonly BrowserTabMetadata[];
    try {
      rawTabs = await this.options.queryTabs();
    } catch {
      return false;
    }

    const activeClient = this.options.getSyncClient();
    if (!this.options.isPaired() || !activeClient) {
      return false;
    }

    const activeSpaceId = this.options.getActiveSpaceId();
    const deviceName = this.options.getDeviceName();
    const items = tabsToPresenceItems(rawTabs, activeSpaceId);
    const signature = JSON.stringify({
      deviceName,
      tabs: items.map((t) => ({
        id: t.id,
        url: t.url,
        title: t.title,
        spaceId: t.spaceId ?? null,
        active: Boolean(t.active),
      })),
    });

    if (!opts?.force && signature === this.lastSignature) {
      return false;
    }

    this.lastSignature = signature;
    await activeClient.sendPresenceTabs(items, deviceName);
    return true;
  }
}

const presencePublisher = new DebouncedPresencePublisher({
  debounceMs: PRESENCE_DEBOUNCE_MS,
  queryTabs: async () => {
    if (typeof chrome === "undefined" || !chrome.tabs?.query) {
      return [];
    }
    return chrome.tabs.query({});
  },
  getActiveSpaceId: () => cachedData?.activeSpaceId ?? "personal",
  getDeviceName: () => cachedData?.deviceName ?? "Focal Bridge Desktop",
  getSyncClient: () => syncClient,
  isPaired: () => Boolean(cachedData?.pairing && syncClient),
});

function triggerDebouncedDesktopPresence(): void {
  void (async () => {
    const data = await ensureBootstrapped();
    if (data.pairing && syncClient) {
      presencePublisher.schedule();
    }
  })();
}

async function startSyncClientForPairing(data: StoredBridgeData): Promise<void> {
  presencePublisher.reset();
  if (syncClient) {
    syncClient.disconnect();
    syncClient = null;
  }
  cachedKeys = null;

  if (!data.pairing) {
    return;
  }

  const validatedRelayBaseUrl = validateRelayUrl(data.pairing.relayBaseUrl);
  const secretBytes = base64UrlToBytes(data.pairing.secretBase64Url);
  const keys = await deriveFocalKeys(secretBytes);
  cachedKeys = keys;

  syncClient = new FocalSyncClient({
    keys,
    senderId: data.deviceId,
    relayBaseUrl: validatedRelayBaseUrl,
    initialState: data.syncState,
    onEnvelope: async (envelope) => {
      await handleInboundEnvelope(envelope);
    },
    onStatusChange: (status) => {
      broadcastStateToPopup();
      if (status === "connected") {
        presencePublisher.schedule();
      }
    },
    onPersistState: async (nextSyncState) => {
      if (!cachedData) {
        return;
      }
      cachedData.syncState = nextSyncState;
      await saveStorage(cachedData);
    },
  });

  syncClient.connect();
}

async function ensureBootstrapped(): Promise<StoredBridgeData> {
  if (cachedData) {
    return cachedData;
  }
  if (!bootstrapPromise) {
    bootstrapPromise = (async () => {
      const loaded = await readStorage();
      cachedData = loaded;
      if (loaded.pairing) {
        try {
          await startSyncClientForPairing(loaded);
        } catch {
          loaded.pairing = null;
          await saveStorage(loaded);
        }
      }
    })().finally(() => {
      bootstrapPromise = null;
    });
  }
  await bootstrapPromise;
  return cachedData!;
}

async function dispatchUrlHandoff(
  rawUrl: string,
  rawTitle?: string,
  spaceId?: SpaceId
): Promise<LastHandoffSummary> {
  const data = await ensureBootstrapped();
  if (!data.pairing || !syncClient) {
    throw new Error("Pair Focal Bridge with Focal Android first");
  }
  if (!isShareableHttpUrl(rawUrl)) {
    throw new Error("Only HTTP and HTTPS web pages can be sent to Focal Android");
  }

  const cleaned = cleanUrlDetailed(rawUrl);
  const title =
    rawTitle && rawTitle.trim().length > 0
      ? rawTitle.trim()
      : extractDisplayDomain(cleaned.cleanUrl);

  const result = await syncClient.sendTabHandoff({
    url: cleaned.cleanUrl,
    title,
    spaceId: spaceId ?? data.activeSpaceId,
    sourceDeviceName: data.deviceName,
  });

  const summary: LastHandoffSummary = {
    cleanUrl: cleaned.cleanUrl,
    domain: extractDisplayDomain(cleaned.cleanUrl),
    strippedParams: cleaned.strippedParams,
    sentImmediately: result.sentImmediately,
    timestampMs: Date.now(),
  };

  data.lastHandoff = summary;
  await saveStorage(data);
  broadcastStateToPopup();
  return summary;
}

function registerContextMenus(): void {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({
      id: MENU_SEND_LINK_ID,
      title: "Send Link to Focal Android",
      contexts: ["link"],
    });
    chrome.contextMenus.create({
      id: MENU_SEND_PAGE_ID,
      title: "Send Page to Focal Android",
      contexts: ["page"],
    });
  });
}

function ensureKeepaliveAlarm(): void {
  chrome.alarms.create(KEEPALIVE_ALARM_NAME, {
    periodInMinutes: KEEPALIVE_PERIOD_MINUTES,
  });
}

export function registerBackgroundListeners(): void {
  chrome.runtime.onInstalled.addListener(() => {
    registerContextMenus();
    ensureKeepaliveAlarm();
    void ensureBootstrapped();
  });

  chrome.runtime.onStartup.addListener(() => {
    ensureKeepaliveAlarm();
    void ensureBootstrapped();
  });

  chrome.alarms.onAlarm.addListener((alarm) => {
    if (alarm.name !== KEEPALIVE_ALARM_NAME) {
      return;
    }
    void (async () => {
      await ensureBootstrapped();
      syncClient?.ensureAlive();
      presencePublisher.schedule();
    })();
  });

  chrome.tabs?.onUpdated?.addListener((_tabId, changeInfo) => {
    if (
      changeInfo.status === "complete" ||
      changeInfo.url !== undefined ||
      changeInfo.title !== undefined
    ) {
      triggerDebouncedDesktopPresence();
    }
  });

  chrome.tabs?.onActivated?.addListener(() => {
    triggerDebouncedDesktopPresence();
  });

  chrome.tabs?.onRemoved?.addListener(() => {
    triggerDebouncedDesktopPresence();
  });

  chrome.contextMenus.onClicked.addListener((info, tab) => {
    void (async () => {
      try {
        if (info.menuItemId === MENU_SEND_LINK_ID && info.linkUrl) {
          await dispatchUrlHandoff(
            info.linkUrl,
            info.selectionText ?? extractDisplayDomain(info.linkUrl)
          );
        } else if (info.menuItemId === MENU_SEND_PAGE_ID) {
          const targetUrl = info.pageUrl ?? tab?.url;
          if (targetUrl) {
            await dispatchUrlHandoff(targetUrl, tab?.title);
          }
        }
      } catch {
        // Errors on context-menu click when unpaired or on restricted schemes are safely ignored
      }
    })();
  });

  chrome.commands.onCommand.addListener((command) => {
    if (command !== COMMAND_SEND_ACTIVE_TAB) {
      return;
    }
    void (async () => {
      try {
        const [activeTab] = await chrome.tabs.query({
          active: true,
          currentWindow: true,
        });
        if (activeTab?.url) {
          await dispatchUrlHandoff(activeTab.url, activeTab.title);
        }
      } catch {
        // Ignore if invoked on internal browser page or while unpaired
      }
    })();
  });

  chrome.runtime.onMessage.addListener(
    (
      message: BridgePopupRequest,
      _sender,
      sendResponse: (response: BridgePopupResponse) => void
    ) => {
      if (!message || typeof message !== "object" || !("type" in message)) {
        return false;
      }

      void (async (): Promise<BridgePopupResponse> => {
        try {
          const data = await ensureBootstrapped();

          switch (message.type) {
            case "GET_STATE": {
              syncClient?.ensureAlive();
              return { ok: true, state: buildUiState(data) };
            }

            case "PREVIEW_PAIRING_URI": {
              const parsed = parsePairingUri(message.uri);
              const keys = await deriveFocalKeys(parsed.secret);
              return {
                ok: true,
                preview: {
                  sas: keys.sas,
                  roomId: keys.roomId,
                  ...(parsed.relayUrl ? { relayUrl: parsed.relayUrl } : {}),
                },
              };
            }

            case "COMPLETE_PAIRING": {
              const parsed = parsePairingUri(message.uri);
              const explicitRelay = message.relayBaseUrl?.trim();
              const chosenRelay = validateRelayUrl(
                (explicitRelay && explicitRelay !== DEFAULT_RELAY_BASE_URL
                  ? explicitRelay
                  : parsed.relayUrl) ||
                  explicitRelay ||
                  DEFAULT_RELAY_BASE_URL
              );

              data.pairing = {
                secretBase64Url: parsed.secretBase64Url,
                relayBaseUrl: chosenRelay,
                pairedAtMs: Date.now(),
              };
              data.syncState = {
                nextSeq: 1,
                outboundQueue: [],
                replaySnapshot: {},
              };
              data.remoteTabs = [];

              await saveStorage(data);
              await startSyncClientForPairing(data);
              ensureKeepaliveAlarm();

              if (syncClient) {
                await syncClient.sendSpacesSnapshot(data.spaces);
                await presencePublisher.publishNow({ force: true });
              }

              return { ok: true, state: buildUiState(data) };
            }

            case "UNPAIR": {
              presencePublisher.reset();
              if (syncClient) {
                syncClient.disconnect();
                syncClient = null;
              }
              cachedKeys = null;
              data.pairing = null;
              data.syncState = {
                nextSeq: 1,
                outboundQueue: [],
                replaySnapshot: {},
              };
              data.remoteTabs = [];
              data.lastHandoff = null;
              await saveStorage(data);
              return { ok: true, state: buildUiState(data) };
            }

            case "SEND_TAB_HANDOFF": {
              const handoff = await dispatchUrlHandoff(
                message.url,
                message.title,
                message.spaceId
              );
              return {
                ok: true,
                state: buildUiState(data),
                handoff,
              };
            }

            case "SET_ACTIVE_SPACE": {
              if (
                message.spaceId === "personal" ||
                message.spaceId === "work" ||
                message.spaceId === "reading"
              ) {
                data.activeSpaceId = message.spaceId;
                await saveStorage(data);
                presencePublisher.schedule();
              }
              return { ok: true, state: buildUiState(data) };
            }

            case "ADD_SPACE_BOOKMARK": {
              if (!isShareableHttpUrl(message.url)) {
                throw new Error("Only HTTP(S) pages can be saved to Spaces");
              }
              const clean = cleanUrl(message.url);
              const targetSpace = data.spaces.find(
                (s) => s.id === message.spaceId
              );
              if (!targetSpace) {
                throw new Error("Unknown Space");
              }
              const alreadyExists = targetSpace.bookmarks.some(
                (bm) => bm.url === clean
              );
              if (!alreadyExists) {
                targetSpace.bookmarks.unshift({
                  id: generateRandomId(6),
                  title:
                    message.title.trim() || extractDisplayDomain(clean),
                  url: clean,
                  pinned: false,
                  addedAtMs: Date.now(),
                });
                await saveStorage(data);
                if (syncClient) {
                  await syncClient.sendSpacesSnapshot(data.spaces);
                }
              }
              return { ok: true, state: buildUiState(data) };
            }

            case "REMOVE_SPACE_BOOKMARK": {
              const targetSpace = data.spaces.find(
                (s) => s.id === message.spaceId
              );
              if (targetSpace) {
                const before = targetSpace.bookmarks.length;
                targetSpace.bookmarks = targetSpace.bookmarks.filter(
                  (bm) => bm.id !== message.bookmarkId
                );
                if (targetSpace.bookmarks.length !== before) {
                  await saveStorage(data);
                  if (syncClient) {
                    await syncClient.sendSpacesSnapshot(data.spaces);
                  }
                }
              }
              return { ok: true, state: buildUiState(data) };
            }
          }
        } catch (err) {
          return {
            ok: false,
            error: err instanceof Error ? err.message : "Operation failed",
          };
        }
      })().then(sendResponse);

      return true;
    }
  );
}

if (
  typeof globalThis.chrome !== "undefined" &&
  Boolean(globalThis.chrome?.runtime?.onInstalled)
) {
  registerBackgroundListeners();
}
