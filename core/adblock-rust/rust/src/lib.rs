//! `focal_adblock` — Focal's native Rust ad/tracker blocker, pre-paint cosmetic CSS engine,
//! and `"Where Does This Go"` URL canonicalizer over zero-unwind JNI.
//!
//! Built on Brave's `adblock` engine (compiled in multi-threaded `Send + Sync` mode without
//! `single-thread`) for `< 0.2ms` subresource interception inside Android WebView's background
//! Chromium I/O worker pool while preserving Focal's 120Hz (`8.33ms`) frame budget.

pub mod jni_bridge;
pub mod url_cleaner;

pub use url_cleaner::{
    clean_tracking_url, clean_url, is_tracking_param, registered_exact_tracker_param_count,
    CleanedUrlResult,
};

use adblock::engine::DeserializationError;
use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::resources::{MimeType, PermissionMask, Resource, ResourceType};
use adblock::Engine;
use std::collections::BTreeSet;
use std::fmt;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::RwLock;

/// Embedded starter EasyList + EasyPrivacy + Peter Lowe + uBlock Origin baseline rules.
///
/// Provides immediate zero-network-fetch protection on cold install before the background
/// filter updater downloads or deserializes an expanded `.dat` snapshot.
pub const STARTER_FILTER_RULES: &[&str] = &[
    // --- Major Ad Exchanges, Programmatic SSPs & Ad Servers (EasyList / Peter Lowe) ---
    "||doubleclick.net^",
    "||googlesyndication.com^",
    "||googleadservices.com^",
    "||adservice.google.com^",
    "||moatads.com^",
    "||adnxs.com^",
    "||adsrvr.org^",
    "||criteo.com^",
    "||criteo.net^",
    "||rubiconproject.com^",
    "||pubmatic.com^",
    "||openx.net^",
    "||casalemedia.com^",
    "||indexexchange.com^",
    "||smartadserver.com^",
    "||adform.net^",
    "||amazon-adsystem.com^",
    "||media.net^",
    "||yieldmo.com^",
    "||sharethrough.com^",
    "||triplelift.com^",
    "||33across.com^",
    "||sovrn.com^",
    "||lijit.com^",
    "||bidswitch.net^",
    "||teads.tv^",
    "||taboola.com^",
    "||trc.taboola.com^",
    "||outbrain.com^",
    "||widgets.outbrain.com^",
    "||mgid.com^",
    "||revcontent.com^",
    "||zedo.com^",
    "||serving-sys.com^",
    "||2mdn.net^",
    "||adroll.com^",
    "||PopAds.net^",
    // --- Cross-Site Analytics, Session Replay & Behavioral Trackers (EasyPrivacy) ---
    "||google-analytics.com^",
    "||googletagmanager.com^",
    "||googletagservices.com^",
    "||scorecardresearch.com^",
    "||quantserve.com^",
    "||hotjar.com^",
    "||static.hotjar.com^",
    "||fullstory.com^",
    "||rs.fullstory.com^",
    "||mouseflow.com^",
    "||crazyegg.com^",
    "||clarity.ms^",
    "||mixpanel.com^",
    "||api-js.mixpanel.com^",
    "||cdn.segment.com^",
    "||api.segment.io^",
    "||amplitude.com^",
    "||cdn.amplitude.com^",
    "||heapanalytics.com^",
    "||logrocket.io^",
    "||bugsnag.com^$third-party",
    "||bat.bing.com^",
    "||connect.facebook.net/*/fbevents.js$script,third-party",
    "||facebook.com/tr^$image,ping,xmlhttprequest,third-party",
    "||analytics.tiktok.com^",
    "||static.ads-twitter.com^",
    "||ads-api.twitter.com^",
    "||snap.licdn.com^",
    "||px.ads.linkedin.com^",
    "||sc-static.net/scevent.min.js$script,third-party",
    "||ct.pinterest.com^",
    "||events.redditmedia.com^",
    "||alb.reddit.com/snoo.gif",
    "||omtrdc.net^$third-party",
    "||2o7.net^$third-party",
    "||demdex.net^$third-party",
    "||everesttech.net^$third-party",
    "||krxd.net^$third-party",
    "||bluekai.com^$third-party",
    "||exelator.com^$third-party",
    "||rlcdn.com^$third-party",
    "||tapad.com^$third-party",
    "||branch.io^$third-party",
    "||app.link^$ping,xmlhttprequest,third-party",
    // --- Generic Path / Resource-Specific Network Rules ---
    "/pagead/js/adsbygoogle.js",
    "/ads/ad-banner.$image,script,subdocument",
    "/adserver/impression?$image,ping,xmlhttprequest,third-party",
    "/pixel/tracker.gif$image,third-party",
    // --- Pre-Paint Cosmetic Hide Rules (Generic + Attribute Selectors) ---
    "##.ad-slot",
    "##.ad-banner",
    "##.ad-container",
    "##.advertisement",
    "##.sponsored-content",
    "##.taboola-widget",
    "##.outbrain-tm",
    "###ad-leaderboard",
    "###google_ads_frame",
    "##[id^=\"google_ads_iframe\"]",
    "##[data-ad-slot]",
    "##[data-ad-client]",
    "##ins.adsbygoogle",
    "##iframe[src*=\"doubleclick.net\"]",
];

/// Outcome of evaluating a subresource or navigation URL against [`FocalAdblockEngine`].
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct BlockDecision {
    /// `true` when the request should be blocked (or replaced by `redirect_body`).
    pub matched: bool,
    /// `true` when matched by an `$important` rule (cannot be overridden by `@@` exceptions).
    pub important: bool,
    /// Optional data-URI / stub payload when matched by a `$redirect=` rule.
    pub redirect_body: Option<String>,
    /// `true` when an `@@` exception rule explicitly allowlisted the request.
    pub has_exception: bool,
}

impl BlockDecision {
    /// Returns an allow decision (`matched = false`).
    #[inline]
    pub const fn allow() -> Self {
        Self {
            matched: false,
            important: false,
            redirect_body: None,
            has_exception: false,
        }
    }
}

/// Errors returned when deserializing binary `.dat` filter snapshots.
#[derive(Debug)]
pub enum AdblockError {
    /// Provided `.dat` byte slice was empty.
    EmptyDatBuffer,
    /// Underlying Brave `adblock` FlatBuffer / SeaHash verification failed.
    DeserializeFailed(DeserializationError),
}

impl fmt::Display for AdblockError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            AdblockError::EmptyDatBuffer => write!(f, "serialized .dat buffer is empty"),
            AdblockError::DeserializeFailed(err) => {
                write!(f, "failed to deserialize adblock .dat buffer: {err}")
            }
        }
    }
}

impl std::error::Error for AdblockError {}

struct EngineState {
    engine: Engine,
    generic_classes: Vec<String>,
    generic_ids: Vec<String>,
}

/// Thread-safe native ad/tracker blocking and cosmetic CSS engine.
///
/// Safe to share across WebView's concurrent background network I/O threads (`Send + Sync`).
/// Network and cosmetic queries acquire a shared read lock on the inner [`adblock::Engine`],
/// while `.dat` or custom-rule hot reloads swap the [`EngineState`] under a brief write lock.
pub struct FocalAdblockEngine {
    state: RwLock<EngineState>,
    blocked_count: AtomicU64,
}

impl Default for FocalAdblockEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl FocalAdblockEngine {
    /// Creates a new [`FocalAdblockEngine`] initialized with [`STARTER_FILTER_RULES`] and
    /// built-in `$redirect` surrogate stubs.
    pub fn new() -> Self {
        Self::from_rules(&[], true)
    }

    /// Creates a new [`FocalAdblockEngine`] from custom Adblock Plus / uBlock rules, optionally
    /// layered on top of [`STARTER_FILTER_RULES`].
    pub fn from_rules(custom_rules: &[&str], include_starter: bool) -> Self {
        let state = build_engine_state(custom_rules, include_starter);
        Self {
            state: RwLock::new(state),
            blocked_count: AtomicU64::new(0),
        }
    }

    /// Creates a [`FocalAdblockEngine`] directly from Brave's compact binary `.dat` format.
    pub fn from_serialized_dat(dat: &[u8]) -> Result<Self, AdblockError> {
        if dat.is_empty() {
            return Err(AdblockError::EmptyDatBuffer);
        }
        let mut engine = Engine::default();
        engine
            .deserialize(dat)
            .map_err(AdblockError::DeserializeFailed)?;
        engine.use_resources(default_redirect_resources());

        let (generic_classes, generic_ids) = extract_generic_class_id_probes(STARTER_FILTER_RULES);
        Ok(Self {
            state: RwLock::new(EngineState {
                engine,
                generic_classes,
                generic_ids,
            }),
            blocked_count: AtomicU64::new(0),
        })
    }

    /// Atomically replaces the active filter engine from a serialized `.dat` snapshot without
    /// resetting cumulative `blocked_count` or blocking concurrent readers until swap time.
    pub fn load_serialized_dat(&self, dat: &[u8]) -> Result<(), AdblockError> {
        if dat.is_empty() {
            return Err(AdblockError::EmptyDatBuffer);
        }
        let mut next_engine = Engine::default();
        next_engine
            .deserialize(dat)
            .map_err(AdblockError::DeserializeFailed)?;
        next_engine.use_resources(default_redirect_resources());

        let (generic_classes, generic_ids) = extract_generic_class_id_probes(STARTER_FILTER_RULES);
        let mut guard = self
            .state
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        *guard = EngineState {
            engine: next_engine,
            generic_classes,
            generic_ids,
        };
        Ok(())
    }

    /// Serializes the active filter engine into Brave's `.dat` binary cache format.
    pub fn serialize_dat(&self) -> Vec<u8> {
        let guard = self
            .state
            .read()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        guard.engine.serialize()
    }

    /// Rebuilds and hot-swaps the active engine with `custom_rules` (and optionally starter rules).
    pub fn reload_with_rules(&self, custom_rules: &[&str], include_starter: bool) {
        let next_state = build_engine_state(custom_rules, include_starter);
        let mut guard = self
            .state
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        *guard = next_state;
    }

    /// Evaluates whether a network subresource or navigation request should be blocked.
    ///
    /// - `req_url`: Full URL of the network request (e.g. `https://doubleclick.net/ad.js`).
    /// - `source_url`: Document / frame origin initiating the request (used for `$third-party` and `$domain=`).
    /// - `resource_type`: Content policy type (`"script"`, `"image"`, `"xmlhttprequest"`, `"sub_frame"`, `"stylesheet"`, `"other"`, etc.).
    pub fn check_network_request(
        &self,
        req_url: &str,
        source_url: &str,
        resource_type: &str,
    ) -> BlockDecision {
        let trimmed_req = req_url.trim();
        if trimmed_req.is_empty() || !is_supported_network_scheme(trimmed_req) {
            return BlockDecision::allow();
        }

        let normalized_type = normalize_resource_type(resource_type);
        let effective_source = source_url.trim();

        let Ok(request) = Request::new(trimmed_req, effective_source, normalized_type, "get")
        else {
            return BlockDecision::allow();
        };

        let result = {
            let guard = self
                .state
                .read()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            guard.engine.check_network_request(&request)
        };

        let matched = result.should_block();
        if matched {
            self.blocked_count.fetch_add(1, Ordering::Relaxed);
        }

        BlockDecision {
            matched,
            important: result.important,
            redirect_body: if matched { result.redirect } else { None },
            has_exception: result.exception.is_some(),
        }
    }

    /// Computes pre-paint CSS hide rules for `url`, formatted as one rule per line:
    /// `selector { display: none !important; }`
    ///
    /// Isolating each selector into its own CSS rule guarantees that an exotic selector
    /// unsupported by an older System WebView never invalidates sibling selectors in the sheet.
    pub fn cosmetic_css_for_url(&self, url: &str) -> String {
        let trimmed = url.trim();
        if trimmed.is_empty() || !is_http_or_https(trimmed) {
            return String::new();
        }

        let selectors: BTreeSet<String> = {
            let guard = self
                .state
                .read()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            let resources = guard.engine.url_cosmetic_resources(trimmed);
            let mut combined: BTreeSet<String> = resources
                .hide_selectors
                .into_iter()
                .filter(|s| !s.trim().is_empty())
                .collect();

            if !resources.generichide {
                let generic_matches = guard.engine.hidden_class_id_selectors(
                    &guard.generic_classes,
                    &guard.generic_ids,
                    &resources.exceptions,
                );
                for sel in generic_matches {
                    if !sel.trim().is_empty() {
                        combined.insert(sel);
                    }
                }
            }
            combined
        };

        format_hide_selectors_css(selectors.iter().map(String::as_str))
    }

    /// Queries additional generic CSS hide selectors dynamically when DOM classes/IDs are observed.
    ///
    /// Evaluates `adblock::Engine::hidden_class_id_selectors` against the engine's serialized
    /// `CosmeticFilterCache` while honoring `$generichide` and domain `#@#` exceptions, ensuring
    /// generic cosmetic rules outside [`STARTER_FILTER_RULES`] work seamlessly after `.dat` reloads.
    pub fn hidden_class_id_selectors(
        &self,
        url: &str,
        classes: &[&str],
        ids: &[&str],
    ) -> Vec<String> {
        let trimmed = url.trim();
        if trimmed.is_empty() || !is_http_or_https(trimmed) {
            return Vec::new();
        }
        let clean_classes: Vec<&str> = classes
            .iter()
            .map(|c| c.trim())
            .filter(|c| !c.is_empty())
            .collect();
        let clean_ids: Vec<&str> = ids
            .iter()
            .map(|i| i.trim())
            .filter(|i| !i.is_empty())
            .collect();
        if clean_classes.is_empty() && clean_ids.is_empty() {
            return Vec::new();
        }

        let raw_selectors = {
            let guard = self
                .state
                .read()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            let resources = guard.engine.url_cosmetic_resources(trimmed);
            if resources.generichide {
                return Vec::new();
            }
            guard
                .engine
                .hidden_class_id_selectors(&clean_classes, &clean_ids, &resources.exceptions)
        };

        let deduped: BTreeSet<String> = raw_selectors
            .into_iter()
            .filter(|s| !s.trim().is_empty())
            .collect();
        deduped.into_iter().collect()
    }

    /// Computes newline-delimited `selector { display: none !important; }` rules for observed
    /// DOM `classes` and `ids` on `url` (or `""` when none match or `$generichide` applies).
    pub fn cosmetic_css_for_classes_and_ids(
        &self,
        url: &str,
        classes: &[&str],
        ids: &[&str],
    ) -> String {
        let selectors = self.hidden_class_id_selectors(url, classes, ids);
        format_hide_selectors_css(selectors.iter().map(String::as_str))
    }

    /// Total number of network requests blocked by this engine instance since creation or reset.
    #[inline]
    pub fn blocked_count(&self) -> u64 {
        self.blocked_count.load(Ordering::Relaxed)
    }

    /// Resets the cumulative atomic blocked counter back to `0`.
    #[inline]
    pub fn reset_blocked_count(&self) {
        self.blocked_count.store(0, Ordering::Relaxed);
    }
}

fn build_engine_state(custom_rules: &[&str], include_starter: bool) -> EngineState {
    let mut filter_set = FilterSet::new(false);
    let mut all_rules_for_probes: Vec<&str> = Vec::new();

    if include_starter {
        let starter_list = STARTER_FILTER_RULES.join("\n");
        filter_set.add_filter_list(starter_list, ParseOptions::default());
        all_rules_for_probes.extend_from_slice(STARTER_FILTER_RULES);
    }
    if !custom_rules.is_empty() {
        let custom_list = custom_rules.join("\n");
        filter_set.add_filter_list(custom_list, ParseOptions::default());
        all_rules_for_probes.extend_from_slice(custom_rules);
    }

    let mut engine = Engine::new_with_filter_set(filter_set);
    engine.use_resources(default_redirect_resources());

    let (generic_classes, generic_ids) = extract_generic_class_id_probes(&all_rules_for_probes);
    EngineState {
        engine,
        generic_classes,
        generic_ids,
    }
}

/// Extracts leading class (`##.foo`) and ID (`###bar`) tokens from generic cosmetic rules so
/// pre-paint `cosmetic_css_for_url` can emit baseline class/ID hide selectors while still
/// honoring domain `#@#` exceptions and `$generichide` via `adblock::Engine::hidden_class_id_selectors`.
fn extract_generic_class_id_probes(rules: &[&str]) -> (Vec<String>, Vec<String>) {
    let mut classes = BTreeSet::new();
    let mut ids = BTreeSet::new();

    for raw_rule in rules {
        let rule = raw_rule.trim();
        if let Some(rest) = rule.strip_prefix("##.") {
            let ident = take_css_ident(rest);
            if !ident.is_empty() {
                classes.insert(ident.to_string());
            }
        } else if let Some(rest) = rule.strip_prefix("###") {
            let ident = take_css_ident(rest);
            if !ident.is_empty() {
                ids.insert(ident.to_string());
            }
        }
    }

    (classes.into_iter().collect(), ids.into_iter().collect())
}

fn format_hide_selectors_css<'a, I>(selectors: I) -> String
where
    I: IntoIterator<Item = &'a str>,
{
    let mut iter = selectors.into_iter().peekable();
    if iter.peek().is_none() {
        return String::new();
    }
    let mut css = String::new();
    for (idx, selector) in iter.enumerate() {
        if idx > 0 {
            css.push('\n');
        }
        css.push_str(selector);
        css.push_str(" { display: none !important; }");
    }
    css
}

#[inline]
fn take_css_ident(input: &str) -> &str {
    let end = input
        .find(|c: char| !(c.is_ascii_alphanumeric() || c == '-' || c == '_'))
        .unwrap_or(input.len());
    &input[..end]
}

#[inline]
fn is_supported_network_scheme(url: &str) -> bool {
    let bytes = url.as_bytes();
    url_starts_with_ignore_case(bytes, b"https://")
        || url_starts_with_ignore_case(bytes, b"http://")
        || url_starts_with_ignore_case(bytes, b"wss://")
        || url_starts_with_ignore_case(bytes, b"ws://")
}

#[inline]
fn is_http_or_https(url: &str) -> bool {
    let bytes = url.as_bytes();
    url_starts_with_ignore_case(bytes, b"https://")
        || url_starts_with_ignore_case(bytes, b"http://")
}

#[inline]
fn url_starts_with_ignore_case(haystack: &[u8], prefix_lower: &[u8]) -> bool {
    haystack.len() >= prefix_lower.len()
        && haystack[..prefix_lower.len()].eq_ignore_ascii_case(prefix_lower)
}

#[inline]
fn normalize_resource_type(raw: &str) -> &str {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        "other"
    } else {
        trimmed
    }
}

/// Standard lightweight surrogate resources for `$redirect=` filter rules.
fn default_redirect_resources() -> [Resource; 5] {
    [
        Resource {
            name: "noop.js".to_string(),
            aliases: vec!["noopjs".to_string()],
            kind: ResourceType::Mime(MimeType::ApplicationJavascript),
            // "(function(){})();"
            content: "KGZ1bmN0aW9uKCl7fSkoKTs=".to_string(),
            dependencies: vec![],
            permission: PermissionMask::default(),
        },
        Resource {
            name: "noop.txt".to_string(),
            aliases: vec!["nooptext".to_string(), "empty".to_string()],
            kind: ResourceType::Mime(MimeType::TextPlain),
            content: String::new(),
            dependencies: vec![],
            permission: PermissionMask::default(),
        },
        Resource {
            name: "noop.json".to_string(),
            aliases: vec!["noopjson".to_string()],
            kind: ResourceType::Mime(MimeType::ApplicationJson),
            // "{}"
            content: "e30=".to_string(),
            dependencies: vec![],
            permission: PermissionMask::default(),
        },
        Resource {
            name: "noop.html".to_string(),
            aliases: vec!["noopframe".to_string()],
            kind: ResourceType::Mime(MimeType::TextHtml),
            // "<!DOCTYPE html><html></html>"
            content: "PCFET0NUWVBFIGh0bWw+PGh0bWw+PC9odG1sPg==".to_string(),
            dependencies: vec![],
            permission: PermissionMask::default(),
        },
        Resource {
            name: "1x1.gif".to_string(),
            aliases: vec!["1x1-transparent.gif".to_string()],
            kind: ResourceType::Mime(MimeType::ImageGif),
            // Standard 43-byte 1x1 transparent GIF
            content: "R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==".to_string(),
            dependencies: vec![],
            permission: PermissionMask::default(),
        },
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::thread;

    #[test]
    fn engine_is_send_and_sync() {
        fn assert_send_sync<T: Send + Sync>() {}
        assert_send_sync::<FocalAdblockEngine>();
    }

    #[test]
    fn starter_rules_block_known_ad_and_tracker_networks_and_increment_counter() {
        let engine = FocalAdblockEngine::new();
        assert_eq!(engine.blocked_count(), 0);

        let blocked_cases = [
            (
                "https://securepubads.g.doubleclick.net/tag/js/gpt.js",
                "https://www.theverge.com/tech",
                "script",
            ),
            (
                "https://www.google-analytics.com/g/collect?v=2&tid=G-1234",
                "https://blog.example.com/post",
                "xmlhttprequest",
            ),
            (
                "https://connect.facebook.net/en_US/fbevents.js",
                "https://shop.example.com/checkout",
                "script",
            ),
            (
                "https://trc.taboola.com/example-publisher/load.js",
                "https://news.example.com/world",
                "script",
            ),
            (
                "https://static.hotjar.com/c/hotjar-999999.js?sv=6",
                "https://saas.example.com/pricing",
                "script",
            ),
        ];

        for (req, src, rtype) in blocked_cases {
            let decision = engine.check_network_request(req, src, rtype);
            assert!(decision.matched, "Expected {req} to be blocked");
        }
        assert_eq!(engine.blocked_count(), blocked_cases.len() as u64);

        // Legitimate first-party and CDN requests must NOT be blocked or increment counter.
        let allowed_cases = [
            (
                "https://www.theverge.com/static/app.bundle.js",
                "https://www.theverge.com/tech",
                "script",
            ),
            (
                "https://images.unsplash.com/photo-1234?w=1200",
                "https://blog.example.com/post",
                "image",
            ),
        ];

        for (req, src, rtype) in allowed_cases {
            let decision = engine.check_network_request(req, src, rtype);
            assert!(!decision.matched, "Expected {req} to be allowed");
        }
        assert_eq!(engine.blocked_count(), blocked_cases.len() as u64);

        engine.reset_blocked_count();
        assert_eq!(engine.blocked_count(), 0);
    }

    #[test]
    fn respects_third_party_vs_first_party_matching() {
        let custom_rules = &[
            "||analytics-cdn.example.net/sdk.js$script,third-party",
            "||firstparty-only-ad.example.org/promo.png$~third-party,image",
        ];
        let engine = FocalAdblockEngine::from_rules(custom_rules, false);

        // Cross-origin (third-party): blocked!
        let third_party_decision = engine.check_network_request(
            "https://analytics-cdn.example.net/sdk.js",
            "https://reader-news.com/article/42",
            "script",
        );
        assert!(third_party_decision.matched);

        // Same eTLD+1 (first-party subdomain -> parent domain): allowed!
        let first_party_decision = engine.check_network_request(
            "https://analytics-cdn.example.net/sdk.js",
            "https://app.example.net/dashboard",
            "script",
        );
        assert!(!first_party_decision.matched);

        // ~third-party rule: blocked ONLY on first-party, allowed on third-party!
        let fp_promo = engine.check_network_request(
            "https://firstparty-only-ad.example.org/promo.png",
            "https://www.example.org/home",
            "image",
        );
        assert!(fp_promo.matched);

        let tp_promo = engine.check_network_request(
            "https://firstparty-only-ad.example.org/promo.png",
            "https://external-blog.io/",
            "image",
        );
        assert!(!tp_promo.matched);
    }

    #[test]
    fn handles_exception_allowlists_important_overrides_and_redirects() {
        let rules = &[
            "||ads.partner.example.com^",
            "@@||ads.partner.example.com/allowlisted-consent.js$script",
            "||ads.partner.example.com/forced-beacon.js$script,important",
            "@@||ads.partner.example.com/forced-beacon.js$script",
            "||tracker.example.com/analytics.js$script,redirect=noopjs",
        ];
        let engine = FocalAdblockEngine::from_rules(rules, false);

        // Normal path on blocked host -> blocked
        let normal_block = engine.check_network_request(
            "https://ads.partner.example.com/banner.js",
            "https://site.example.org/",
            "script",
        );
        assert!(normal_block.matched);
        assert!(!normal_block.important);

        // @@ exception allowlist -> allowed, with has_exception = true
        let allowlisted = engine.check_network_request(
            "https://ads.partner.example.com/allowlisted-consent.js",
            "https://site.example.org/",
            "script",
        );
        assert!(!allowlisted.matched);
        assert!(allowlisted.has_exception);

        // $important rule overrides @@ exception!
        let important_block = engine.check_network_request(
            "https://ads.partner.example.com/forced-beacon.js",
            "https://site.example.org/",
            "script",
        );
        assert!(important_block.matched);
        assert!(important_block.important);

        // $redirect=noopjs populates redirect_body with a JS data URI surrogate
        let redirected = engine.check_network_request(
            "https://tracker.example.com/analytics.js",
            "https://site.example.org/",
            "script",
        );
        assert!(redirected.matched);
        let body = redirected
            .redirect_body
            .expect("Expected redirect_body for $redirect=noopjs");
        assert!(
            body.starts_with("data:application/javascript;base64,"),
            "Unexpected redirect body: {body}"
        );
    }

    #[test]
    fn generates_cosmetic_css_with_domain_scoping_and_exceptions() {
        let rules = &[
            "##.ad-slot",
            "###ad-top-banner",
            "##[data-ad-unit]",
            "dailyplanet.example.com##.paywall-upsell",
            "allowlisted.example.com#@#.ad-slot",
            "@@||cleanview.example.com^$generichide",
            "cleanview.example.com##.site-specific-sponsor",
        ];
        let engine = FocalAdblockEngine::from_rules(rules, false);

        // Standard site gets generic rules + attribute rules, NOT dailyplanet's rule
        let generic_css = engine.cosmetic_css_for_url("https://techblog.org/review");
        assert!(generic_css.contains(".ad-slot { display: none !important; }"));
        assert!(generic_css.contains("#ad-top-banner { display: none !important; }"));
        assert!(generic_css.contains("[data-ad-unit] { display: none !important; }"));
        assert!(!generic_css.contains(".paywall-upsell"));

        // Subdomain of dailyplanet.example.com gets both generic AND domain-specific rule
        let planet_css =
            engine.cosmetic_css_for_url("https://edition.dailyplanet.example.com/story/1");
        assert!(planet_css.contains(".paywall-upsell { display: none !important; }"));
        assert!(planet_css.contains(".ad-slot { display: none !important; }"));

        // allowlisted.example.com has #@#.ad-slot exception -> omits .ad-slot, keeps others
        let excepted_css = engine.cosmetic_css_for_url("https://allowlisted.example.com/index.html");
        assert!(!excepted_css.contains(".ad-slot { display: none !important; }"));
        assert!(excepted_css.contains("#ad-top-banner { display: none !important; }"));

        // cleanview.example.com has $generichide -> omits generic rules, keeps domain-specific rule
        let generichide_css = engine.cosmetic_css_for_url("https://cleanview.example.com/article");
        assert!(!generichide_css.contains(".ad-slot { display: none !important; }"));
        assert!(!generichide_css.contains("[data-ad-unit] { display: none !important; }"));
        assert!(generichide_css.contains(".site-specific-sponsor { display: none !important; }"));
    }

    #[test]
    fn serializes_and_deserializes_dat_snapshots_and_rejects_corrupt_bytes() {
        let custom_engine = FocalAdblockEngine::from_rules(
            &[
                "||custom-ad-network.example.io^",
                "journal.example.com##.floating-video-ad",
            ],
            false,
        );
        let dat_bytes = custom_engine.serialize_dat();
        assert!(!dat_bytes.is_empty());

        // Cold load from .dat
        let restored =
            FocalAdblockEngine::from_serialized_dat(&dat_bytes).expect("valid .dat must load");
        assert!(
            restored
                .check_network_request(
                    "https://custom-ad-network.example.io/bid.json",
                    "https://journal.example.com/",
                    "xmlhttprequest"
                )
                .matched
        );
        let restored_css = restored.cosmetic_css_for_url("https://journal.example.com/live");
        assert!(restored_css.contains(".floating-video-ad { display: none !important; }"));

        // Hot-swap into an existing engine + verify corrupt .dat fails safely without losing rules
        let live_engine = FocalAdblockEngine::from_rules(&[], false);
        assert!(
            !live_engine
                .check_network_request(
                    "https://custom-ad-network.example.io/bid.json",
                    "https://journal.example.com/",
                    "xmlhttprequest"
                )
                .matched
        );
        live_engine
            .load_serialized_dat(&dat_bytes)
            .expect("hot-swap .dat must succeed");

        assert!(live_engine.load_serialized_dat(&[]).is_err());
        assert!(live_engine.load_serialized_dat(b"corrupt-not-a-valid-dat").is_err());

        // Engine still enforces previously loaded .dat after failed load!
        assert!(
            live_engine
                .check_network_request(
                    "https://custom-ad-network.example.io/bid.json",
                    "https://journal.example.com/",
                    "xmlhttprequest"
                )
                .matched
        );
    }

    #[test]
    fn ignores_non_network_and_malformed_urls_gracefully() {
        let engine = FocalAdblockEngine::new();
        for non_http in [
            "",
            "   ",
            "about:blank",
            "data:text/html,<h1>Hello</h1>",
            "blob:https://example.com/550e8400-e29b-41d4-a716-446655440000",
            "javascript:void(0)",
            ":::not_a_url:::",
        ] {
            let decision = engine.check_network_request(non_http, "https://example.com", "other");
            assert!(!decision.matched);
            assert_eq!(engine.cosmetic_css_for_url(non_http), "");
        }
        assert_eq!(engine.blocked_count(), 0);
    }

    #[test]
    fn concurrent_multithreaded_queries_are_race_free_and_count_accurately() {
        let engine = Arc::new(FocalAdblockEngine::new());
        let threads: Vec<_> = (0..6)
            .map(|_| {
                let shared = Arc::clone(&engine);
                thread::spawn(move || {
                    for _ in 0..100 {
                        assert!(
                            shared
                                .check_network_request(
                                    "https://ad.doubleclick.net/ddm/ad/123",
                                    "https://news.example.com/",
                                    "image",
                                )
                                .matched
                        );
                        assert!(
                            !shared
                                .check_network_request(
                                    "https://news.example.com/assets/logo.svg",
                                    "https://news.example.com/",
                                    "image",
                                )
                                .matched
                        );
                        let css = shared.cosmetic_css_for_url("https://news.example.com/");
                        assert!(css.contains(".ad-slot { display: none !important; }"));
                    }
                })
            })
            .collect();

        for handle in threads {
            handle.join().expect("worker must not panic");
        }
        assert_eq!(engine.blocked_count(), 600);
    }

    #[test]
    fn hidden_class_id_selectors_resolves_non_starter_generic_rules_after_dat_reload() {
        // Generic class/ID rules NOT present in STARTER_FILTER_RULES, plus domain exceptions & $generichide
        let source_engine = FocalAdblockEngine::from_rules(
            &[
                "##.dynamic-sponsor-card",
                "###interstitial-ad-modal",
                "##.promo-rail > .sticky-box",
                "allowlisted.example.com#@#.dynamic-sponsor-card",
                "@@||cleanview.example.com^$generichide",
            ],
            false,
        );
        let dat_bytes = source_engine.serialize_dat();

        // 1. Cold-load from .dat snapshot
        let cold_restored =
            FocalAdblockEngine::from_serialized_dat(&dat_bytes).expect("valid .dat must load");

        // 2. Hot-swap into an unrelated live engine
        let hot_reloaded = FocalAdblockEngine::from_rules(&[], false);
        hot_reloaded
            .load_serialized_dat(&dat_bytes)
            .expect("hot-swap .dat must load");

        for engine in [&cold_restored, &hot_reloaded] {
            let css = engine.cosmetic_css_for_classes_and_ids(
                "https://reader-magazine.example.org/story",
                &[
                    "dynamic-sponsor-card",
                    "promo-rail",
                    "dynamic-sponsor-card", // duplicate token must be deduplicated
                    "benign-article-body",
                    "   ",
                ],
                &["interstitial-ad-modal", "article-heading", ""],
            );

            assert!(css.contains(".dynamic-sponsor-card { display: none !important; }"));
            assert!(css.contains("#interstitial-ad-modal { display: none !important; }"));
            assert!(css.contains(".promo-rail > .sticky-box { display: none !important; }"));
            assert!(!css.contains("benign-article-body"));
            assert!(!css.contains("article-heading"));
            assert_eq!(
                css.matches(".dynamic-sponsor-card { display: none !important; }")
                    .count(),
                1,
                "Duplicate DOM class tokens must not produce duplicate CSS rules"
            );

            // Domain #@# exception suppresses .dynamic-sponsor-card while keeping #interstitial-ad-modal
            let excepted_css = engine.cosmetic_css_for_classes_and_ids(
                "https://allowlisted.example.com/story",
                &["dynamic-sponsor-card", "promo-rail"],
                &["interstitial-ad-modal"],
            );
            assert!(!excepted_css.contains(".dynamic-sponsor-card"));
            assert!(excepted_css.contains("#interstitial-ad-modal { display: none !important; }"));
            assert!(
                excepted_css.contains(".promo-rail > .sticky-box { display: none !important; }")
            );

            // $generichide disables all generic class/ID rules on cleanview.example.com
            let generichide_css = engine.cosmetic_css_for_classes_and_ids(
                "https://cleanview.example.com/story",
                &["dynamic-sponsor-card", "promo-rail"],
                &["interstitial-ad-modal"],
            );
            assert_eq!(generichide_css, "");

            // Empty tokens or non-HTTP schemes safely return empty string
            assert_eq!(
                engine.cosmetic_css_for_classes_and_ids("https://example.org/", &[], &[]),
                ""
            );
            assert_eq!(
                engine.cosmetic_css_for_classes_and_ids(
                    "about:blank",
                    &["dynamic-sponsor-card"],
                    &["interstitial-ad-modal"]
                ),
                ""
            );
        }
    }
}
