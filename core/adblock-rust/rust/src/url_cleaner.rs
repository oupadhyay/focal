//! `"Where Does This Go"` URL canonicalizer.
//!
//! Strips 95+ known cross-site tracking, ad-click attribution, and email-marketing query
//! parameters (`utm_*`, `fbclid`, `gclid`, `dclid`, `msclkid`, `mc_eid`, `igshid`, `si`,
//! `twclid`, `yclid`, `_hsenc`, `_hsmi`, `mkt_tok`, `oly_anon_id`, `oly_enc_id`, `vero_id`,
//! `wickedid`, `spm`, etc.) while preserving legitimate query parameters, custom ports,
//! paths, percent-encoding, and `#fragments`.

use once_cell::sync::Lazy;
use std::collections::HashSet;
use url::Url;

/// Result of canonicalizing a potentially tracker-decorated URL.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CleanedUrlResult {
    /// Canonical URL with known tracking query parameters removed.
    pub clean_url: String,
    /// Lowercase canonical names of stripped query parameters, in encounter order.
    pub stripped_params: Vec<String>,
}

/// Prefix families unconditionally used for campaign/ad attribution tracking.
const TRACKING_PARAM_PREFIXES: &[&str] = &[
    "utm_", // Urchin / Google Analytics campaign parameters (utm_source, utm_medium, ...)
    "mtm_", // Matomo campaign parameters (mtm_campaign, mtm_source, mtm_kwd, ...)
    "hsa_", // HubSpot Ads parameters (hsa_acc, hsa_cam, hsa_grp, hsa_ad, ...)
];

/// Curated set of 90+ exact known tracking query parameter names (all lowercase).
///
/// Intentionally excludes ambiguous short functional parameters (`id`, `q`, `s`, `p`,
/// `page`, `v`, `t`, `lang`, `sort`, `tab`, `ref`, `code`, `state`, `token`, `url`)
/// so legitimate site navigation, search queries, OAuth callbacks, and video timestamps
/// never break.
static TRACKING_PARAMS: Lazy<HashSet<&'static str>> = Lazy::new(|| {
    HashSet::from([
        // Google Ads / DoubleClick / YouTube / Search / Analytics
        "gclid",
        "dclid",
        "gbraid",
        "wbraid",
        "gclsrc",
        "gad_source",
        "gad_campaignid",
        "srsltid",
        "_ga",
        "_gl",
        "_gac",
        "ef_id",
        "s_kwcid",
        "si",
        // Meta (Facebook / Instagram / Threads)
        "fbclid",
        "igshid",
        "igsh",
        "fb_action_ids",
        "fb_action_types",
        "fb_source",
        "fb_ref",
        "fb_comment_id",
        "action_object_map",
        "action_type_map",
        "action_ref_map",
        "__cft__[0]",
        "__tn__",
        // Microsoft / Bing
        "msclkid",
        // X / Twitter
        "twclid",
        // Yahoo / Yandex
        "yclid",
        "ymclid",
        "_openstat",
        "guccounter",
        "guce_referrer",
        "guce_referrer_sig",
        // TikTok / Snapchat / Pinterest / LinkedIn / Reddit / Quora
        "ttclid",
        "sccid",
        "epik",
        "li_fat_id",
        "trk",
        "trkinfo",
        "lipi",
        "rdt_cid",
        "qclid",
        // Mailchimp
        "mc_cid",
        "mc_eid",
        // HubSpot
        "_hsenc",
        "_hsmi",
        "__hssc",
        "__hstc",
        "__hsfp",
        "hsctatracking",
        // Marketo
        "mkt_tok",
        // Omeda / Olytics
        "oly_anon_id",
        "oly_enc_id",
        // Vero
        "vero_id",
        "vero_conv",
        // Wicked Reports
        "wickedid",
        // Klaviyo / Drip / MailerLite / Omnisend / Blueshift / Squarespace
        "_ke",
        "_kx",
        "__s",
        "dm_i",
        "ml_subscriber",
        "ml_subscriber_hash",
        "oft_id",
        "oft_k",
        "oft_lk",
        "oft_d",
        "oft_c",
        "oft_ck",
        "oft_ids",
        "oft_sk",
        "ss_source",
        "ss_campaign_id",
        "ss_email_id",
        "ss_view_id",
        "ss_attrib",
        "bsft_aaid",
        "bsft_clkid",
        "bsft_eid",
        "bsft_ek",
        "bsft_lx",
        "bsft_mid",
        "bsft_mime_type",
        "bsft_tv",
        "bsft_uid",
        // Matomo / Piwik legacy
        "pk_campaign",
        "pk_kwd",
        "pk_source",
        "pk_medium",
        "pk_cid",
        "pk_content",
        "pk_cpn",
        // Outbrain / Taboola / Native Ad Networks / Affiliates
        "oborigurl",
        "dicbo",
        "tblci",
        "ndclid",
        "vmcid",
        "zanpid",
        "irclickid",
        "irgwc",
        "rb_clickid",
        "unicorn_click_id",
        "nb_klid",
        // E-commerce & Marketplace Trackers (Taobao / AliExpress / Alibaba)
        "spm",
        "scm",
        "algo_pvid",
        "algo_exp_id",
        "btsid",
        "ws_ab_test",
        "aff_fcid",
        "aff_fsk",
        "aff_platform",
        "aff_trace_key",
        // Mobile Attribution (Branch / AppsFlyer) & TripleWhale
        "_branch_match_id",
        "_branch_referrer",
        "af_js_web",
        "af_xp",
        "tw_source",
        "tw_campaign",
        "tw_term",
        "tw_content",
        "tw_adid",
    ])
});

/// Returns `true` if `param_name` is a known tracking query parameter.
#[inline]
pub fn is_tracking_param(param_name: &str) -> bool {
    if param_name.is_empty() {
        return false;
    }
    let lower = param_name.to_ascii_lowercase();
    for prefix in TRACKING_PARAM_PREFIXES {
        if lower.starts_with(prefix) && lower.len() > prefix.len() {
            return true;
        }
    }
    TRACKING_PARAMS.contains(lower.as_str())
}

/// Returns the number of exact tracking parameter definitions registered (excluding wildcard prefixes).
pub fn registered_exact_tracker_param_count() -> usize {
    TRACKING_PARAMS.len()
}

/// Strips known tracking query parameters from `raw_url`, returning `(clean_url, stripped_param_names)`.
///
/// - Preserves legitimate query parameters in their original order and raw encoding.
/// - Preserves custom ports (`:8443`), full paths, credentials, and `#fragments`.
/// - Gracefully returns `(raw_url.to_string(), vec![])` for non-HTTP(S) schemes or malformed inputs.
pub fn clean_url(raw_url: &str) -> (String, Vec<String>) {
    let trimmed = raw_url.trim();
    if trimmed.is_empty() {
        return (raw_url.to_string(), Vec::new());
    }

    let mut parsed = match Url::parse(trimmed) {
        Ok(u) => u,
        Err(_) => return (raw_url.to_string(), Vec::new()),
    };

    if !matches!(parsed.scheme(), "http" | "https") {
        return (trimmed.to_string(), Vec::new());
    }

    let raw_query = match parsed.query() {
        Some(q) if !q.is_empty() => q.to_owned(),
        _ => return (trimmed.to_string(), Vec::new()),
    };

    let mut kept_segments: Vec<&str> = Vec::new();
    let mut stripped_params: Vec<String> = Vec::new();

    for segment in raw_query.split('&') {
        if segment.is_empty() {
            continue;
        }
        let raw_key = segment.split('=').next().unwrap_or("");
        let decoded_key = url::form_urlencoded::parse(raw_key.as_bytes())
            .next()
            .map(|(k, _)| k.into_owned())
            .unwrap_or_else(|| raw_key.to_string());

        if is_tracking_param(&decoded_key) {
            stripped_params.push(decoded_key.to_ascii_lowercase());
        } else {
            kept_segments.push(segment);
        }
    }

    if stripped_params.is_empty() {
        return (trimmed.to_string(), Vec::new());
    }

    if kept_segments.is_empty() {
        parsed.set_query(None);
    } else {
        let rebuilt_query = kept_segments.join("&");
        parsed.set_query(Some(&rebuilt_query));
    }

    (parsed.into(), stripped_params)
}

/// Structured convenience wrapper around [`clean_url`].
pub fn clean_tracking_url(raw_url: &str) -> CleanedUrlResult {
    let (clean_url, stripped_params) = clean_url(raw_url);
    CleanedUrlResult {
        clean_url,
        stripped_params,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn has_more_than_sixty_known_tracking_parameters() {
        assert!(
            registered_exact_tracker_param_count() >= 90,
            "Expected >= 90 exact tracker params, got {}",
            registered_exact_tracker_param_count()
        );
    }

    #[test]
    fn strips_utm_and_click_ids_while_preserving_legitimate_params_port_path_and_fragment() {
        let raw = "https://news.example.com:8443/2026/11/review/index.html?id=9812&utm_source=newsletter&utm_medium=email&fbclid=IwAR3xYz&page=2&gclid=Cj0KCQ#section-verdict";
        let (clean, stripped) = clean_url(raw);

        assert_eq!(
            clean,
            "https://news.example.com:8443/2026/11/review/index.html?id=9812&page=2#section-verdict"
        );
        assert_eq!(
            stripped,
            vec!["utm_source", "utm_medium", "fbclid", "gclid"]
        );
    }

    #[test]
    fn removes_question_mark_cleanly_when_all_query_params_are_trackers() {
        let raw = "https://www.youtube.com/watch?si=9a8b7c6d5e&utm_campaign=share#t=42s";
        let (clean, stripped) = clean_url(raw);

        assert_eq!(clean, "https://www.youtube.com/watch#t=42s");
        assert_eq!(stripped, vec!["si", "utm_campaign"]);
    }

    #[test]
    fn strips_all_prompt_specified_tracker_families_case_insensitively() {
        let raw = "https://shop.example.org/item?\
            UTM_TERM=shoes&\
            dclid=dc1&\
            msclkid=ms1&\
            mc_eid=mc1&\
            igshid=ig1&\
            twclid=tw1&\
            yclid=yc1&\
            _hsenc=hs1&\
            _hsmi=hs2&\
            mkt_tok=mk1&\
            oly_anon_id=oa1&\
            oly_enc_id=oe1&\
            vero_id=vr1&\
            wickedid=wk1&\
            spm=a2g0o.detail&\
            sku=SKU-77";
        let result = clean_tracking_url(raw);

        assert_eq!(result.clean_url, "https://shop.example.org/item?sku=SKU-77");
        assert_eq!(
            result.stripped_params,
            vec![
                "utm_term",
                "dclid",
                "msclkid",
                "mc_eid",
                "igshid",
                "twclid",
                "yclid",
                "_hsenc",
                "_hsmi",
                "mkt_tok",
                "oly_anon_id",
                "oly_enc_id",
                "vero_id",
                "wickedid",
                "spm",
            ]
        );
    }

    #[test]
    fn preserves_untouched_urls_verbatim_and_keeps_flag_params_and_encoding() {
        let untouched = "https://docs.example.com/search?q=rust+jni&debug&redirect=https%3A%2F%2Fexample.org%2Fcb#top";
        let (clean, stripped) = clean_url(untouched);
        assert_eq!(clean, untouched);
        assert!(stripped.is_empty());

        let mixed = "https://docs.example.com/search?q=rust+jni&debug&utm_source=x&redirect=https%3A%2F%2Fexample.org%2Fcb#top";
        let (clean_mixed, stripped_mixed) = clean_url(mixed);
        assert_eq!(clean_mixed, untouched);
        assert_eq!(stripped_mixed, vec!["utm_source"]);
    }

    #[test]
    fn handles_non_http_and_malformed_urls_safely_without_panicking() {
        let mailto = "mailto:privacy@focal.app?subject=Hello&utm_source=keep_for_mailto";
        let (clean_mailto, stripped_mailto) = clean_url(mailto);
        assert_eq!(clean_mailto, mailto);
        assert!(stripped_mailto.is_empty());

        let invalid = "not a valid url ?utm_source=foo";
        let (clean_invalid, stripped_invalid) = clean_url(invalid);
        assert_eq!(clean_invalid, invalid);
        assert!(stripped_invalid.is_empty());

        let empty = "";
        let (clean_empty, stripped_empty) = clean_url(empty);
        assert_eq!(clean_empty, "");
        assert!(stripped_empty.is_empty());
    }
}
