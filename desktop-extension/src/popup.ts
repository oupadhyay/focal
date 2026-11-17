/**
 * Focal Bridge — 320px Smoked Glass Popover Controller (`popup.ts`).
 *
 * Manages zero-account pairing + live SAS verification, 1-click clean URL
 * handoff to Focal Android, live Android open tabs presence, and
 * `Personal / Work / Reading` Space bookmark synchronization.
 */

import type {
  BridgePopupRequest,
  BridgePopupResponse,
  BridgeUiState,
} from "./background.js";
import type { SpaceId } from "./crypto.js";
import { extractDisplayDomain, isShareableHttpUrl } from "./url-cleaner.js";

interface ActiveTabSnapshot {
  url: string | null;
  title: string;
  shareable: boolean;
}

function getElement<T extends HTMLElement>(id: string): T {
  const el = document.getElementById(id);
  if (!el) {
    throw new Error(`Missing expected DOM element #${id}`);
  }
  return el as T;
}

const appRoot = getElement<HTMLElement>("app-root");
const statusDot = getElement<HTMLElement>("status-dot");
const connectionPill = getElement<HTMLElement>("connection-pill");
const unpairBtn = getElement<HTMLButtonElement>("unpair-btn");

const pairingView = getElement<HTMLElement>("pairing-view");
const pairingForm = getElement<HTMLFormElement>("pairing-form");
const pairingUriInput = getElement<HTMLInputElement>("pairing-uri-input");
const relayUrlInput = getElement<HTMLInputElement>("relay-url-input");
const sasPreviewBadge = getElement<HTMLElement>("sas-preview-badge");
const sasPreviewText = getElement<HTMLElement>("sas-preview-text");
const pairingError = getElement<HTMLElement>("pairing-error");
const pairSubmitBtn = getElement<HTMLButtonElement>("pair-submit-btn");

const continuityView = getElement<HTMLElement>("continuity-view");
const pairedSasText = getElement<HTMLElement>("paired-sas-text");
const sendActiveTabBtn = getElement<HTMLButtonElement>("send-active-tab-btn");
const sendCtaLabel = getElement<HTMLElement>("send-cta-label");
const activeTabDomain = getElement<HTMLElement>("active-tab-domain");
const handoffFeedback = getElement<HTMLElement>("handoff-feedback");
const remoteTabsHeading = getElement<HTMLElement>("remote-tabs-heading");
const syncQueueMeta = getElement<HTMLElement>("sync-queue-meta");
const remoteTabsList = getElement<HTMLUListElement>("remote-tabs-list");
const saveToSpaceBtn = getElement<HTMLButtonElement>("save-to-space-btn");
const spaceBookmarksList = getElement<HTMLUListElement>("space-bookmarks-list");

const spaceTabButtons = Array.from(
  document.querySelectorAll<HTMLButtonElement>("[data-space-tab]")
);

let currentState: BridgeUiState | null = null;
let currentActiveTab: ActiveTabSnapshot = {
  url: null,
  title: "Current browser tab",
  shareable: false,
};

async function sendBridgeRequest(
  request: BridgePopupRequest
): Promise<BridgePopupResponse> {
  return chrome.runtime.sendMessage(request) as Promise<BridgePopupResponse>;
}

async function queryCurrentBrowserTab(): Promise<ActiveTabSnapshot> {
  try {
    const [tab] = await chrome.tabs.query({
      active: true,
      currentWindow: true,
    });
    if (tab?.url && isShareableHttpUrl(tab.url)) {
      return {
        url: tab.url,
        title: tab.title?.trim() || extractDisplayDomain(tab.url),
        shareable: true,
      };
    }
    return {
      url: tab?.url ?? null,
      title: "Internal browser page (open an HTTP/S site)",
      shareable: false,
    };
  } catch {
    return {
      url: null,
      title: "Active tab unavailable",
      shareable: false,
    };
  }
}

function showErrorBanner(el: HTMLElement, message: string | null): void {
  if (!message) {
    el.textContent = "";
    el.classList.add("hidden");
    return;
  }
  el.textContent = message;
  el.classList.remove("hidden");
}

function renderConnectionHeader(state: BridgeUiState): void {
  if (!state.paired) {
    statusDot.classList.remove("live");
    connectionPill.textContent = "Zero-Account E2EE";
    unpairBtn.classList.add("hidden");
    return;
  }

  unpairBtn.classList.remove("hidden");

  if (state.connectionStatus === "connected") {
    statusDot.classList.add("live");
    connectionPill.textContent = "Live · E2EE";
  } else if (
    state.connectionStatus === "connecting" ||
    state.connectionStatus === "reconnecting"
  ) {
    statusDot.classList.remove("live");
    connectionPill.textContent =
      state.queuedCount > 0
        ? `Syncing (${state.queuedCount})`
        : "Connecting…";
  } else {
    statusDot.classList.remove("live");
    connectionPill.textContent =
      state.queuedCount > 0
        ? `Queued (${state.queuedCount})`
        : "Offline · E2EE";
  }
}

function renderRemoteAndroidTabs(state: BridgeUiState): void {
  const count = state.remoteTabs.length;
  remoteTabsHeading.textContent = `Live on ${state.remoteDeviceName} (${count})`;
  syncQueueMeta.textContent =
    state.queuedCount > 0
      ? `${state.queuedCount} queued`
      : state.connectionStatus === "connected"
        ? "Live WS"
        : "Standby";

  remoteTabsList.replaceChildren();

  if (count === 0) {
    const empty = document.createElement("li");
    empty.className = "empty-row";
    empty.textContent = "No live tabs reported by Focal Android yet.";
    remoteTabsList.appendChild(empty);
    return;
  }

  for (const tab of state.remoteTabs) {
    const li = document.createElement("li");
    li.className = "item-row";

    const openBtn = document.createElement("button");
    openBtn.type = "button";
    openBtn.className = "item-open-btn";
    openBtn.addEventListener("click", () => {
      if (isShareableHttpUrl(tab.url)) {
        void chrome.tabs.create({ url: tab.url, active: true });
      }
    });

    const titleSpan = document.createElement("span");
    titleSpan.className = "item-title";
    titleSpan.textContent = tab.title;

    const domainSpan = document.createElement("span");
    domainSpan.className = "item-domain";
    domainSpan.textContent = extractDisplayDomain(tab.url);

    openBtn.append(titleSpan, domainSpan);

    const actions = document.createElement("div");
    actions.className = "item-actions";
    const arrow = document.createElement("span");
    arrow.className = "item-glyph";
    arrow.textContent = "↗";
    actions.appendChild(arrow);

    li.append(openBtn, actions);
    remoteTabsList.appendChild(li);
  }
}

function renderSpacesSection(state: BridgeUiState): void {
  const activeSpaceId = state.activeSpaceId;
  appRoot.dataset["space"] = activeSpaceId;

  for (const btn of spaceTabButtons) {
    const isSelected = btn.dataset["spaceTab"] === activeSpaceId;
    btn.classList.toggle("active", isSelected);
    btn.setAttribute("aria-selected", String(isSelected));
  }

  saveToSpaceBtn.disabled = !currentActiveTab.shareable;

  const activeSpace =
    state.spaces.find((s) => s.id === activeSpaceId) ?? state.spaces[0];
  const bookmarks = activeSpace?.bookmarks ?? [];

  spaceBookmarksList.replaceChildren();

  if (bookmarks.length === 0) {
    const empty = document.createElement("li");
    empty.className = "empty-row";
    empty.textContent = `No saved links in ${activeSpace?.label ?? "this Space"}.`;
    spaceBookmarksList.appendChild(empty);
    return;
  }

  for (const bm of bookmarks) {
    const li = document.createElement("li");
    li.className = "item-row";

    const openBtn = document.createElement("button");
    openBtn.type = "button";
    openBtn.className = "item-open-btn";
    openBtn.addEventListener("click", () => {
      if (isShareableHttpUrl(bm.url)) {
        void chrome.tabs.create({ url: bm.url, active: true });
      }
    });

    const titleSpan = document.createElement("span");
    titleSpan.className = "item-title";
    titleSpan.textContent = bm.title;

    const domainSpan = document.createElement("span");
    domainSpan.className = "item-domain";
    domainSpan.textContent = extractDisplayDomain(bm.url);

    openBtn.append(titleSpan, domainSpan);

    const actions = document.createElement("div");
    actions.className = "item-actions";

    const removeBtn = document.createElement("button");
    removeBtn.type = "button";
    removeBtn.className = "icon-remove-btn";
    removeBtn.title = "Remove from Space";
    removeBtn.setAttribute("aria-label", `Remove ${bm.title}`);
    removeBtn.textContent = "×";
    removeBtn.addEventListener("click", () => {
      void (async () => {
        const res = await sendBridgeRequest({
          type: "REMOVE_SPACE_BOOKMARK",
          spaceId: activeSpaceId,
          bookmarkId: bm.id,
        });
        if (res.ok && "state" in res) {
          renderUi(res.state);
        }
      })();
    });

    actions.appendChild(removeBtn);
    li.append(openBtn, actions);
    spaceBookmarksList.appendChild(li);
  }
}

function renderUi(state: BridgeUiState): void {
  currentState = state;
  renderConnectionHeader(state);

  if (!state.paired) {
    pairingView.classList.remove("hidden");
    continuityView.classList.add("hidden");
    return;
  }

  pairingView.classList.add("hidden");
  continuityView.classList.remove("hidden");

  pairedSasText.textContent = state.sas ?? "—";
  sendCtaLabel.textContent = `Send Active Tab to ${state.remoteDeviceName}`;

  if (currentActiveTab.shareable && currentActiveTab.url) {
    sendActiveTabBtn.disabled = false;
    activeTabDomain.textContent = `${extractDisplayDomain(currentActiveTab.url)} · ${currentActiveTab.title}`;
  } else {
    sendActiveTabBtn.disabled = true;
    activeTabDomain.textContent = currentActiveTab.title;
  }

  renderRemoteAndroidTabs(state);
  renderSpacesSection(state);
}

// Live SAS verification preview on Pairing URI input
pairingUriInput.addEventListener("input", () => {
  showErrorBanner(pairingError, null);
  const candidateUri = pairingUriInput.value.trim();
  if (candidateUri.length === 0) {
    sasPreviewBadge.classList.add("hidden");
    return;
  }

  void (async () => {
    const res = await sendBridgeRequest({
      type: "PREVIEW_PAIRING_URI",
      uri: candidateUri,
    });
    if (res.ok && "preview" in res) {
      sasPreviewText.textContent = res.preview.sas;
      sasPreviewBadge.classList.remove("hidden");
      if (res.preview.relayUrl) {
        relayUrlInput.value = res.preview.relayUrl;
      }
    } else {
      sasPreviewBadge.classList.add("hidden");
    }
  })();
});

pairingForm.addEventListener("submit", (event) => {
  event.preventDefault();
  showErrorBanner(pairingError, null);

  const uri = pairingUriInput.value.trim();
  const relayBaseUrl = relayUrlInput.value.trim();
  pairSubmitBtn.disabled = true;

  void (async () => {
    try {
      const res = await sendBridgeRequest({
        type: "COMPLETE_PAIRING",
        uri,
        relayBaseUrl,
      });
      if (!res.ok) {
        showErrorBanner(pairingError, res.error);
        return;
      }
      if ("state" in res) {
        pairingUriInput.value = "";
        sasPreviewBadge.classList.add("hidden");
        renderUi(res.state);
      }
    } finally {
      pairSubmitBtn.disabled = false;
    }
  })();
});

unpairBtn.addEventListener("click", () => {
  void (async () => {
    const res = await sendBridgeRequest({ type: "UNPAIR" });
    if (res.ok && "state" in res) {
      handoffFeedback.classList.add("hidden");
      renderUi(res.state);
    }
  })();
});

sendActiveTabBtn.addEventListener("click", () => {
  if (!currentActiveTab.shareable || !currentActiveTab.url) {
    return;
  }
  sendActiveTabBtn.disabled = true;

  void (async () => {
    try {
      const res = await sendBridgeRequest({
        type: "SEND_TAB_HANDOFF",
        url: currentActiveTab.url!,
        title: currentActiveTab.title,
        spaceId: currentState?.activeSpaceId,
      });
      if (!res.ok) {
        handoffFeedback.textContent = res.error;
        handoffFeedback.classList.add("error");
        handoffFeedback.classList.remove("hidden");
        return;
      }
      if ("state" in res) {
        renderUi(res.state);
        if (res.handoff) {
          const strippedCount = res.handoff.strippedParams.length;
          const deliveryNote = res.handoff.sentImmediately
            ? "Sent"
            : "Queued offline";
          const trackerNote =
            strippedCount > 0
              ? ` · stripped ${strippedCount} tracker${strippedCount === 1 ? "" : "s"}`
              : "";
          handoffFeedback.classList.remove("error");
          handoffFeedback.textContent = `${deliveryNote} ${res.handoff.domain}${trackerNote}`;
          handoffFeedback.classList.remove("hidden");
        }
      }
    } finally {
      sendActiveTabBtn.disabled = !currentActiveTab.shareable;
    }
  })();
});

for (const btn of spaceTabButtons) {
  btn.addEventListener("click", () => {
    const spaceId = btn.dataset["spaceTab"] as SpaceId | undefined;
    if (!spaceId) {
      return;
    }
    void (async () => {
      const res = await sendBridgeRequest({
        type: "SET_ACTIVE_SPACE",
        spaceId,
      });
      if (res.ok && "state" in res) {
        renderUi(res.state);
      }
    })();
  });
}

saveToSpaceBtn.addEventListener("click", () => {
  if (!currentActiveTab.shareable || !currentActiveTab.url || !currentState) {
    return;
  }
  void (async () => {
    const res = await sendBridgeRequest({
      type: "ADD_SPACE_BOOKMARK",
      spaceId: currentState.activeSpaceId,
      title: currentActiveTab.title,
      url: currentActiveTab.url!,
    });
    if (res.ok && "state" in res) {
      renderUi(res.state);
    }
  })();
});

chrome.runtime.onMessage.addListener(
  (message: { type?: string; state?: BridgeUiState }) => {
    if (message?.type === "BRIDGE_STATE_PUSH" && message.state) {
      renderUi(message.state);
    }
  }
);

void (async () => {
  currentActiveTab = await queryCurrentBrowserTab();
  const res = await sendBridgeRequest({ type: "GET_STATE" });
  if (res.ok && "state" in res) {
    renderUi(res.state);
  }
})();
