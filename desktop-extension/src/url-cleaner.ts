/**
 * Focal Bridge — Client-Side Tracker Query Parameter Canonicalizer.
 *
 * Mirrors Focal Android's `"Where Does This Go"` URL canonicalizer to strip
 * 95+ known ad-click, social-share, newsletter, and cross-site tracking
 * parameters before encrypting and dispatching URLs over E2EE handoff/sync.
 */

export interface CleanUrlResult {
  cleanUrl: string;
  originalUrl: string;
  strippedParams: string[];
  wasModified: boolean;
}

const TRACKING_PARAM_PREFIXES: readonly string[] = [
  "utm_",
  "mtm_",
  "pk_",
  "hsa_",
  "gad_",
  "itm_",
] as const;

const EXACT_TRACKING_PARAMS: ReadonlySet<string> = new Set([
  // Google / YouTube / DoubleClick / Ads
  "gclid",
  "dclid",
  "gbraid",
  "wbraid",
  "gclsrc",
  "srsltid",
  "si",
  "ved",
  "ei",
  "uact",
  "sxsrf",
  "_ga",
  "_gl",
  "_gac",
  // Meta / Facebook / Instagram / Threads
  "fbclid",
  "igshid",
  "igsh",
  "fb_action_ids",
  "fb_action_types",
  "fb_source",
  "fb_ref",
  "action_object_map",
  "action_type_map",
  "action_ref_map",
  // Microsoft / Bing / LinkedIn
  "msclkid",
  "li_fat_id",
  "trk",
  "trkinfo",
  "lipi",
  "licu",
  // Mailchimp / HubSpot / Marketo / Vero / Klaviyo / Drip / Omnisend
  "mc_cid",
  "mc_eid",
  "_hsenc",
  "_hsmi",
  "__hssc",
  "__hstc",
  "__hsfp",
  "hsctatracking",
  "mkt_tok",
  "vero_id",
  "vero_conv",
  "_kx",
  "__s",
  "oly_anon_id",
  "oly_enc_id",
  "otc",
  "wickedid",
  "vgo_ee",
  "dm_i",
  // X / TikTok / Reddit / Pinterest / Snapchat / Branch
  "twclid",
  "ttclid",
  "sccid",
  "epik",
  "rdt_cid",
  "ref_src",
  "ref_url",
  "_branch_match_id",
  "_branch_referrer",
  "$deep_link",
  // Affiliate / E-commerce / Regional Analytics
  "yclid",
  "ymclid",
  "_openstat",
  "zanpid",
  "irclickid",
  "awc",
  "sscid",
  "ef_id",
  "s_kwcid",
  "rb_clickid",
  "spm",
  "scm",
  "algo_expid",
  "algo_pvid",
  "btsid",
  "ws_ab_test",
  "guccounter",
  "guce_referrer",
  "guce_referrer_sig",
]);

/**
 * Returns true if the query parameter name is a known tracking identifier.
 */
export function isTrackingQueryParam(paramName: string): boolean {
  const normalized = paramName.trim().toLowerCase();
  if (normalized.length === 0) {
    return false;
  }
  if (EXACT_TRACKING_PARAMS.has(normalized)) {
    return true;
  }
  for (const prefix of TRACKING_PARAM_PREFIXES) {
    if (normalized.startsWith(prefix) && normalized.length > prefix.length) {
      return true;
    }
  }
  return false;
}

/**
 * Returns true only for safe navigable HTTP(S) URLs suitable for cross-device handoff.
 */
export function isShareableHttpUrl(rawUrl: string | undefined | null): rawUrl is string {
  if (typeof rawUrl !== "string" || rawUrl.trim().length === 0) {
    return false;
  }
  try {
    const parsed = new URL(rawUrl.trim());
    return parsed.protocol === "https:" || parsed.protocol === "http:";
  } catch {
    return false;
  }
}

/**
 * Strips known tracking query parameters from an HTTP(S) URL while preserving
 * functional query parameters, port, path, and hash fragments.
 */
export function cleanUrlDetailed(rawUrl: string): CleanUrlResult {
  const trimmed = rawUrl.trim();
  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    return {
      cleanUrl: rawUrl,
      originalUrl: rawUrl,
      strippedParams: [],
      wasModified: false,
    };
  }

  if (parsed.protocol !== "https:" && parsed.protocol !== "http:") {
    return {
      cleanUrl: trimmed,
      originalUrl: rawUrl,
      strippedParams: [],
      wasModified: false,
    };
  }

  if (parsed.search.length <= 1) {
    return {
      cleanUrl: trimmed,
      originalUrl: rawUrl,
      strippedParams: [],
      wasModified: false,
    };
  }

  const strippedParams: string[] = [];
  const retainedEntries: Array<[string, string]> = [];

  for (const [key, value] of parsed.searchParams.entries()) {
    if (isTrackingQueryParam(key)) {
      strippedParams.push(key);
    } else {
      retainedEntries.push([key, value]);
    }
  }

  if (strippedParams.length === 0) {
    return {
      cleanUrl: trimmed,
      originalUrl: rawUrl,
      strippedParams: [],
      wasModified: false,
    };
  }

  parsed.search = "";
  for (const [key, value] of retainedEntries) {
    parsed.searchParams.append(key, value);
  }

  const clean = parsed.toString();
  return {
    cleanUrl: clean,
    originalUrl: rawUrl,
    strippedParams,
    wasModified: clean !== trimmed,
  };
}

/**
 * Convenience helper returning the cleaned URL string directly.
 */
export function cleanUrl(rawUrl: string): string {
  return cleanUrlDetailed(rawUrl).cleanUrl;
}

/**
 * Extracts a concise human-readable hostname without leading `www.`.
 */
export function extractDisplayDomain(rawUrl: string): string {
  try {
    const parsed = new URL(rawUrl);
    return parsed.hostname.replace(/^www\./i, "");
  } catch {
    return rawUrl;
  }
}
