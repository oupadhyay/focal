package app.focal.engine.webview

import java.util.Locale

/**
 * Pure JavaScript & CSS builders for Element Zapper, Bottom Chrome Inset Clearance,
 * and Global Privacy Control (`navigator.globalPrivacyControl`).
 *
 * All functions avoid Android framework runtime dependencies so they are directly
 * verifiable in pure JVM unit tests.
 */
object ElementZapperScripts {

    const val ZAPPER_STYLE_ID: String = "focal-element-zapper-style"
    const val BOTTOM_INSET_STYLE_ID: String = "focal-bottom-inset-style"
    const val ZAPPER_BRIDGE_EVENT_NAME: String = "__focal_zapper_event"

    private const val MAX_SELECTOR_LENGTH = 240
    private const val MAX_SELECTOR_COUNT = 128
    private const val MAX_BOTTOM_INSET_PX = 600

    private val SCRIPT_BLOCK_REGEX = Regex(
        "<\\s*script\\b[^>]*>[\\s\\S]*?<\\s*/\\s*script\\s*>",
        RegexOption.IGNORE_CASE,
    )
    private val DANGEROUS_HTML_TAG_REGEX = Regex(
        "</?\\s*(?:style|script|iframe|svg|object|embed|link|meta)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val DANGEROUS_CSS_TOKEN_REGEX = Regex(
        "(?:javascript\\s*:|expression\\s*\\(|url\\s*\\(|@import\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val CONTROL_CHARS_REGEX = Regex("[\\u0000-\\u001F\\u007F\\u2028\\u2029]")
    private val WHITESPACE_RUN_REGEX = Regex("\\s+")

    /**
     * Document-start script that exposes `navigator.globalPrivacyControl` gated on
     * `window.__focalGpcEnabled` (defaulting to `true` when this script is installed).
     */
    const val GLOBAL_PRIVACY_CONTROL_JS: String = """
(function() {
  if (location.protocol !== 'https:' && location.protocol !== 'http:') return;
  if (typeof window.__focalGpcEnabled === 'undefined') {
    window.__focalGpcEnabled = true;
  }
  var desc = {
    get: function() { return window.__focalGpcEnabled === true; },
    configurable: true,
    enumerable: true
  };
  try {
    var proto = (typeof Navigator !== 'undefined' && Navigator.prototype) ? Navigator.prototype : null;
    if (proto) {
      Object.defineProperty(proto, 'globalPrivacyControl', desc);
      return;
    }
  } catch (_) {}
  try {
    Object.defineProperty(navigator, 'globalPrivacyControl', desc);
  } catch (_) {}
})();
"""

    /**
     * Live toggle script for `navigator.globalPrivacyControl` on an already loaded document.
     */
    fun buildGpcToggleScript(enabled: Boolean): String {
        val flag = if (enabled) "true" else "false"
        return if (enabled) {
            "window.__focalGpcEnabled = true;\n$GLOBAL_PRIVACY_CONTROL_JS"
        } else {
            "(function() { window.__focalGpcEnabled = $flag; })();"
        }
    }

    /**
     * Sanitizes a user-zapped or persisted CSS selector so it cannot inject CSS rule blocks,
     * break out of `<style>` tags, or break JavaScript string literals.
     *
     * Returns `null` if the selector is empty, targets the entire document root, or contains
     * disallowed CSS rule/at-rule/script injection tokens.
     */
    fun sanitizeSelector(raw: String): String? {
        if (raw.isBlank()) return null

        // Strip HTML tag breakout payloads (e.g. `</style><script>...</script>`) first.
        var cleaned = raw
            .replace(SCRIPT_BLOCK_REGEX, "")
            .replace(DANGEROUS_HTML_TAG_REGEX, "")
            .replace(CONTROL_CHARS_REGEX, " ")
            .replace("`", "")

        // Reject CSS rule/at-rule injection or dangerous CSS functions outright so a single
        // malformed entry cannot corrupt or escape the combined CSS selector list.
        if (
            cleaned.contains('{') ||
            cleaned.contains('}') ||
            cleaned.contains(';') ||
            cleaned.contains('@') ||
            cleaned.contains('<') ||
            DANGEROUS_CSS_TOKEN_REGEX.containsMatchIn(cleaned)
        ) {
            return null
        }

        cleaned = cleaned
            .replace(WHITESPACE_RUN_REGEX, " ")
            .trim()
            .trimEnd(',', '>', '+', '~')
            .trim()

        if (cleaned.isEmpty() || cleaned.length > MAX_SELECTOR_LENGTH) return null

        val lower = cleaned.lowercase(Locale.US)
        if (lower == "html" || lower == "body" || lower == "*" || lower == ":root") return null
        if (!hasBalancedDelimiters(cleaned)) return null

        return cleaned
    }

    /**
     * Injects or updates `<style id="focal-element-zapper-style">` hiding every sanitized
     * selector in [selectors] via `display: none !important; visibility: hidden !important;`.
     */
    fun buildHiddenSelectorsCssScript(selectors: List<String>): String {
        val sanitized = selectors
            .asSequence()
            .mapNotNull(::sanitizeSelector)
            .distinct()
            .take(MAX_SELECTOR_COUNT)
            .toList()

        if (sanitized.isEmpty()) {
            return """
                (function() {
                  var style = document.getElementById('$ZAPPER_STYLE_ID');
                  if (style) {
                    style.textContent = "";
                  }
                })();
            """.trimIndent()
        }

        val css = "${sanitized.joinToString(", ")} { display: none !important; visibility: hidden !important; }"
        val encodedCss = encodeJsStringLiteral(css)
        return """
            (function() {
              var id = '$ZAPPER_STYLE_ID';
              var style = document.getElementById(id);
              if (!style) {
                style = document.createElement('style');
                style.id = id;
                (document.head || document.documentElement).appendChild(style);
              }
              style.textContent = $encodedCss;
            })();
        """.trimIndent()
    }

    /**
     * Installs (`active == true`) or tears down (`active == false`) the lightweight capture-phase
     * Element Zapper overlay (`window.__focalZapperActive`).
     *
     * Posts `{ type: "element_zapped", selector }` through `window.focalBridge` if available, or
     * via the scoped `ZAPPER_BRIDGE_EVENT_NAME` CustomEvent forwarded by the private bootstrap
     * closure in `WebViewSession`.
     */
    fun buildZapperModeToggleScript(active: Boolean): String {
        if (!active) {
            return """
                (function() {
                  window.__focalZapperActive = false;
                  var h = window.__focalZapperHandlers;
                  if (h) {
                    document.removeEventListener('pointerdown', h.onPointerDown, true);
                    document.removeEventListener('pointermove', h.onPointerMove, true);
                    document.removeEventListener('pointercancel', h.onPointerCancel, true);
                    document.removeEventListener('click', h.onClick, true);
                    window.__focalZapperHandlers = null;
                  }
                  var box = document.getElementById('focal-zapper-highlight');
                  if (box && box.parentNode) {
                    box.parentNode.removeChild(box);
                  }
                })();
            """.trimIndent()
        }

        return """
            (function() {
              var prev = window.__focalZapperHandlers;
              if (prev) {
                document.removeEventListener('pointerdown', prev.onPointerDown, true);
                document.removeEventListener('pointermove', prev.onPointerMove, true);
                document.removeEventListener('pointercancel', prev.onPointerCancel, true);
                document.removeEventListener('click', prev.onClick, true);
                window.__focalZapperHandlers = null;
              }
              window.__focalZapperActive = true;

              function ensureHighlightBox() {
                var box = document.getElementById('focal-zapper-highlight');
                if (!box) {
                  box = document.createElement('div');
                  box.id = 'focal-zapper-highlight';
                  box.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;border:2px solid #F4725E;background:rgba(244,114,94,0.16);border-radius:6px;box-sizing:border-box;display:none;transition:opacity 120ms ease;';
                  (document.documentElement || document.body).appendChild(box);
                }
                return box;
              }

              function hideHighlight() {
                var box = document.getElementById('focal-zapper-highlight');
                if (box) box.style.display = 'none';
              }

              function showHighlight(el) {
                if (!el || !el.getBoundingClientRect) return;
                var rect = el.getBoundingClientRect();
                if (rect.width <= 0 || rect.height <= 0) return;
                var box = ensureHighlightBox();
                box.style.left = rect.left + 'px';
                box.style.top = rect.top + 'px';
                box.style.width = rect.width + 'px';
                box.style.height = rect.height + 'px';
                box.style.display = 'block';
              }

              function cssEsc(ident) {
                if (typeof CSS !== 'undefined' && typeof CSS.escape === 'function') {
                  return CSS.escape(ident);
                }
                return String(ident).replace(/([^\w-])/g, '\\${'$'}1');
              }

              function isUnique(sel) {
                try {
                  return document.querySelectorAll(sel).length === 1;
                } catch (_) {
                  return false;
                }
              }

              function computeSelector(target) {
                if (!target || target.nodeType !== 1) return '';
                var rootTag = (target.tagName || '').toLowerCase();
                if (!rootTag || rootTag === 'html' || rootTag === 'body') return '';

                if (target.id) {
                  var idSel = '#' + cssEsc(target.id);
                  if (isUnique(idSel)) return idSel;
                }

                var parts = [];
                var cur = target;
                var depth = 0;
                while (cur && cur.nodeType === 1 && depth < 4) {
                  var tag = (cur.tagName || '').toLowerCase();
                  if (!tag || tag === 'html' || tag === 'body') break;

                  if (cur.id) {
                    var ancId = '#' + cssEsc(cur.id);
                    if (isUnique(ancId)) {
                      parts.unshift(ancId);
                      break;
                    }
                  }

                  var seg = tag;
                  if (cur.classList && cur.classList.length > 0) {
                    var classes = [];
                    for (var i = 0; i < cur.classList.length && classes.length < 2; i++) {
                      var c = cur.classList[i];
                      if (c && c.indexOf('focal-') !== 0 && c.length <= 48) {
                        classes.push('.' + cssEsc(c));
                      }
                    }
                    if (classes.length > 0) {
                      seg += classes.join('');
                    }
                  }

                  if (depth === 0 && parts.length === 0 && isUnique(seg)) {
                    return seg;
                  }

                  var parent = cur.parentElement;
                  if (parent) {
                    var sameTagCount = 0;
                    var nth = 1;
                    for (var j = 0; j < parent.children.length; j++) {
                      var sib = parent.children[j];
                      if (sib.tagName === cur.tagName) {
                        sameTagCount++;
                        if (sib === cur) nth = sameTagCount;
                      }
                    }
                    if (sameTagCount > 1) {
                      seg += ':nth-of-type(' + nth + ')';
                    }
                  }

                  parts.unshift(seg);
                  var candidate = parts.join(' > ');
                  if (isUnique(candidate)) {
                    return candidate;
                  }
                  cur = parent;
                  depth++;
                }
                return parts.join(' > ');
              }

              function emitZapped(selector) {
                var payload = JSON.stringify({ type: 'element_zapped', selector: selector });
                if (window.focalBridge && typeof window.focalBridge.postMessage === 'function') {
                  window.focalBridge.postMessage(payload);
                } else {
                  window.dispatchEvent(new CustomEvent('$ZAPPER_BRIDGE_EVENT_NAME', {
                    detail: { selector: selector }
                  }));
                }
              }

              var downX = 0;
              var downY = 0;

              function resolveTarget(ev) {
                var el = ev.target;
                if (el && el.nodeType === 3) el = el.parentElement;
                if (!el || el.nodeType !== 1) return null;
                if (el.id === 'focal-zapper-highlight') return null;
                var tag = (el.tagName || '').toLowerCase();
                if (tag === 'html' || tag === 'body') return null;
                return el;
              }

              var handlers = {
                onPointerDown: function(ev) {
                  if (!window.__focalZapperActive) return;
                  downX = ev.clientX || 0;
                  downY = ev.clientY || 0;
                  var el = resolveTarget(ev);
                  if (el) showHighlight(el);
                },
                onPointerMove: function(ev) {
                  if (!window.__focalZapperActive) return;
                  var dx = Math.abs((ev.clientX || 0) - downX);
                  var dy = Math.abs((ev.clientY || 0) - downY);
                  if (dx > 10 || dy > 10) hideHighlight();
                },
                onPointerCancel: function() {
                  hideHighlight();
                },
                onClick: function(ev) {
                  if (!window.__focalZapperActive) return;
                  ev.preventDefault();
                  ev.stopPropagation();
                  ev.stopImmediatePropagation();
                  hideHighlight();

                  var el = resolveTarget(ev);
                  if (!el) return;

                  var selector = computeSelector(el);
                  if (selector) {
                    try {
                      var style = document.getElementById('$ZAPPER_STYLE_ID');
                      if (!style) {
                        style = document.createElement('style');
                        style.id = '$ZAPPER_STYLE_ID';
                        (document.head || document.documentElement).appendChild(style);
                      }
                      style.textContent = (style.textContent ? style.textContent + '\n' : '') +
                        selector + ' { display: none !important; visibility: hidden !important; }';
                    } catch (_) {
                      el.style.setProperty('display', 'none', 'important');
                      el.style.setProperty('visibility', 'hidden', 'important');
                    }
                    try { emitZapped(selector); } catch (_) {}
                  } else {
                    el.style.setProperty('display', 'none', 'important');
                    el.style.setProperty('visibility', 'hidden', 'important');
                  }
                }
              };

              window.__focalZapperHandlers = handlers;
              document.addEventListener('pointerdown', handlers.onPointerDown, true);
              document.addEventListener('pointermove', handlers.onPointerMove, true);
              document.addEventListener('pointercancel', handlers.onPointerCancel, true);
              document.addEventListener('click', handlers.onClick, true);
            })();
        """.trimIndent()
    }

    /**
     * Injects CSS variables (`--focal-bottom-inset`, `scroll-padding-bottom`) and applies
     * bottom clearance only when [insetPx] > 0 and the page is neither fullscreen nor a
     * viewport-locked fixed web app (`overflow-y: hidden` / `position: fixed`).
     */
    fun buildBottomInsetCssScript(insetPx: Int): String {
        val safePx = insetPx.coerceIn(0, MAX_BOTTOM_INSET_PX)
        val rootCss = ":root { --focal-bottom-inset: ${safePx}px; scroll-padding-bottom: ${safePx}px; }"
        if (safePx == 0) {
            return """
                (function() {
                  var id = '$BOTTOM_INSET_STYLE_ID';
                  var style = document.getElementById(id);
                  if (!style) {
                    style = document.createElement('style');
                    style.id = id;
                    (document.head || document.documentElement).appendChild(style);
                  }
                  style.textContent = ${encodeJsStringLiteral(rootCss)};
                })();
            """.trimIndent()
        }

        val clearanceCss =
            " html:not(:fullscreen) body:not(:fullscreen) { margin-bottom: ${safePx}px !important; }"
        return """
            (function() {
              var id = '$BOTTOM_INSET_STYLE_ID';
              var style = document.getElementById(id);
              if (!style) {
                style = document.createElement('style');
                style.id = id;
                (document.head || document.documentElement).appendChild(style);
              }
              var css = ${encodeJsStringLiteral(rootCss)};
              var isLockedApp = false;
              try {
                if (document.fullscreenElement || document.webkitFullscreenElement) {
                  isLockedApp = true;
                } else if (document.body) {
                  var bodyStyle = window.getComputedStyle(document.body);
                  var htmlStyle = document.documentElement ? window.getComputedStyle(document.documentElement) : null;
                  if (bodyStyle && (bodyStyle.overflowY === 'hidden' || bodyStyle.position === 'fixed')) {
                    isLockedApp = true;
                  } else if (htmlStyle && htmlStyle.overflowY === 'hidden') {
                    isLockedApp = true;
                  }
                }
              } catch (_) {}
              if (!isLockedApp) {
                css += ${encodeJsStringLiteral(clearanceCss)};
              }
              style.textContent = css;
            })();
        """.trimIndent()
    }

    private fun hasBalancedDelimiters(selector: String): Boolean {
        var brackets = 0
        var parens = 0
        var inSingle = false
        var inDouble = false
        var escaped = false

        for (ch in selector) {
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (inSingle) {
                if (ch == '\'') inSingle = false
                continue
            }
            if (inDouble) {
                if (ch == '"') inDouble = false
                continue
            }
            when (ch) {
                '\'' -> inSingle = true
                '"' -> inDouble = true
                '[' -> brackets++
                ']' -> {
                    brackets--
                    if (brackets < 0) return false
                }
                '(' -> parens++
                ')' -> {
                    parens--
                    if (parens < 0) return false
                }
            }
        }
        return !inSingle && !inDouble && brackets == 0 && parens == 0
    }

    private fun encodeJsStringLiteral(value: String): String = buildString(value.length + 16) {
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
                '`' -> append("\\u0060")
                '<' -> append("\\u003c")
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
}
