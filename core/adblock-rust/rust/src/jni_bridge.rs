//! Production-hardened JNI C-ABI bridge for `app.focal.adblock.RustAdblockBridge`.
//!
//! Safety invariants enforced at every FFI boundary:
//! 1. **Zero Unwind Across FFI**: Every `Java_app_focal_adblock_RustAdblockBridge_*` entrypoint
//!    is wrapped in [`ffi_catch_unwind`] (`std::panic::catch_unwind`), preventing Rust panics
//!    from triggering Undefined Behavior across the C/JNI stack frame.
//! 2. **Generation-Safe Opaque Engine Handles**: Instead of casting raw pointers to `jlong`
//!    (which risks use-after-free or double-free on concurrent teardown), engine instances live
//!    inside a thread-safe handle table (`RwLock<HashMap<jlong, Arc<FocalAdblockEngine>>>`).
//!    Worker threads clone `Arc<FocalAdblockEngine>` and release the table lock immediately,
//!    guaranteeing lock-free concurrent matching and zero use-after-free even if
//!    `nativeDestroyEngine` races with an in-flight `shouldInterceptRequest` worker.
//! 3. **Null-Safe JNI Inputs**: All `JString` and `JByteArray` references are checked with
//!    `.is_null()` prior to conversion.
//! 4. **Bounded `O(1)` JNI Local Reference Hygiene**: Every per-element `JString` allocated while
//!    populating `jobjectArray` results (`nativeCleanUrl`, `nativeCheckNetworkRequest`) is
//!    explicitly released via `env.delete_local_ref(...)` immediately after
//!    `set_object_array_element`, preventing ART local reference table overflows on URLs with
//!    dozens of query parameters.

use crate::url_cleaner::clean_url;
use crate::FocalAdblockEngine;
use jni::objects::{JByteArray, JClass, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jlong, jobjectArray, jsize, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, RwLock};

static NEXT_ENGINE_HANDLE: AtomicI64 = AtomicI64::new(1);

static ENGINE_REGISTRY: Lazy<RwLock<HashMap<jlong, Arc<FocalAdblockEngine>>>> =
    Lazy::new(|| RwLock::new(HashMap::new()));

/// Executes `f` inside `std::panic::catch_unwind`, returning `fallback` if a panic occurs.
#[inline]
pub fn ffi_catch_unwind<T, F>(fallback: T, f: F) -> T
where
    F: FnOnce() -> T,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(value) => value,
        Err(_) => fallback,
    }
}

/// Registers an [`Arc<FocalAdblockEngine>`] in the handle registry and returns a positive `jlong` handle.
pub fn register_engine_handle(engine: FocalAdblockEngine) -> jlong {
    let handle = NEXT_ENGINE_HANDLE.fetch_add(1, Ordering::Relaxed);
    if handle <= 0 {
        return 0;
    }
    let mut guard = ENGINE_REGISTRY
        .write()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    guard.insert(handle, Arc::new(engine));
    handle
}

/// Looks up a registered [`Arc<FocalAdblockEngine>`] by opaque handle without holding the lock during work.
#[inline]
pub fn lookup_engine_handle(handle: jlong) -> Option<Arc<FocalAdblockEngine>> {
    if handle <= 0 {
        return None;
    }
    let guard = ENGINE_REGISTRY
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    guard.get(&handle).cloned()
}

/// Removes an engine handle from the registry. Returns `true` if a live handle was removed.
/// Calling this multiple times with the same handle or with `<= 0` is a safe no-op.
pub fn destroy_engine_handle(handle: jlong) -> bool {
    if handle <= 0 {
        return false;
    }
    let mut guard = ENGINE_REGISTRY
        .write()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    guard.remove(&handle).is_some()
}

/// Returns the number of currently registered live engine handles (useful for leak diagnostics).
pub fn active_engine_handle_count() -> usize {
    ENGINE_REGISTRY
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .len()
}

/// Safely extracts a UTF-8 Rust `String` from a potentially-null `JString`.
#[inline]
fn read_nullable_jstring(env: &mut JNIEnv<'_>, input: &JString<'_>) -> Option<String> {
    if input.is_null() {
        return None;
    }
    env.get_string(input).ok().map(Into::into)
}

/// Allocates a Java `String` or returns `null` on JNI allocation failure.
#[inline]
fn into_raw_jstring(env: &JNIEnv<'_>, value: &str) -> jstring {
    match env.new_string(value) {
        Ok(s) => s.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

/// Splits a tab-delimited class or ID token payload into trimmed, non-empty slices.
#[inline]
fn parse_tab_separated_tokens(raw: &str) -> Vec<&str> {
    if raw.is_empty() {
        return Vec::new();
    }
    raw.split('\t')
        .map(str::trim)
        .filter(|token| !token.is_empty())
        .collect()
}

/// Minimal abstraction over JNI `String[]` construction so local-reference table hygiene
/// (`set_object_array_element` + `delete_local_ref` on every loop iteration) can be verified
/// deterministically in host `cargo test` runs as well as on Android ART.
trait JniStringArrayOps {
    type ArrayRef;
    type StringRef;

    fn alloc_string_array(&mut self, len: jsize) -> Result<Self::ArrayRef, ()>;
    fn alloc_string(&mut self, value: &str) -> Result<Self::StringRef, ()>;
    fn set_array_element(
        &mut self,
        array: &Self::ArrayRef,
        index: jsize,
        value: &Self::StringRef,
    ) -> Result<(), ()>;
    fn delete_string_local_ref(&mut self, value: Self::StringRef);
    fn delete_array_local_ref(&mut self, array: Self::ArrayRef);
}

impl<'local> JniStringArrayOps for JNIEnv<'local> {
    type ArrayRef = JObjectArray<'local>;
    type StringRef = JString<'local>;

    #[inline]
    fn alloc_string_array(&mut self, len: jsize) -> Result<Self::ArrayRef, ()> {
        self.new_object_array(len, "java/lang/String", JObject::null())
            .map_err(|_| ())
    }

    #[inline]
    fn alloc_string(&mut self, value: &str) -> Result<Self::StringRef, ()> {
        self.new_string(value).map_err(|_| ())
    }

    #[inline]
    fn set_array_element(
        &mut self,
        array: &Self::ArrayRef,
        index: jsize,
        value: &Self::StringRef,
    ) -> Result<(), ()> {
        self.set_object_array_element(array, index, value)
            .map_err(|_| ())
    }

    #[inline]
    fn delete_string_local_ref(&mut self, value: Self::StringRef) {
        let _ = self.delete_local_ref(value);
    }

    #[inline]
    fn delete_array_local_ref(&mut self, array: Self::ArrayRef) {
        let _ = self.delete_local_ref(array);
    }
}

/// Populates a `String[]` of length `len` while releasing each temporary element `JString`
/// local reference immediately after `set_object_array_element`, keeping JNI local ref usage `O(1)`.
fn build_nullable_string_array_releasing_locals<'a, O, I>(
    ops: &mut O,
    len: usize,
    elements: I,
) -> Option<O::ArrayRef>
where
    O: JniStringArrayOps,
    I: IntoIterator<Item = Option<&'a str>>,
{
    let jlen = jsize::try_from(len).ok()?;
    let array = ops.alloc_string_array(jlen).ok()?;

    for (idx, maybe_val) in elements.into_iter().enumerate() {
        let Some(val) = maybe_val else {
            continue;
        };
        let Ok(jidx) = jsize::try_from(idx) else {
            ops.delete_array_local_ref(array);
            return None;
        };
        let Ok(elem_jstr) = ops.alloc_string(val) else {
            ops.delete_array_local_ref(array);
            return None;
        };
        let set_res = ops.set_array_element(&array, jidx, &elem_jstr);
        ops.delete_string_local_ref(elem_jstr);
        if set_res.is_err() {
            ops.delete_array_local_ref(array);
            return None;
        }
    }

    Some(array)
}

/// Creates a new [`FocalAdblockEngine`] preloaded with starter ad/tracker/cosmetic rules.
/// Returns an opaque handle `> 0` on success, or `0` on failure/panic.
#[no_mangle]
pub extern "system" fn Java_app_focal_adblock_RustAdblockBridge_nativeCreateEngine(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jlong {
    ffi_catch_unwind(0, || {
        let engine = FocalAdblockEngine::new();
        register_engine_handle(engine)
    })
}

/// Creates a new [`FocalAdblockEngine`] directly from a serialized Brave `.dat` snapshot (`< 5ms`)
/// without compiling text starter rules first. Returns handle `> 0` on success, or `0` if invalid.
#[no_mangle]
pub extern "system" fn Java_app_focal_adblock_RustAdblockBridge_nativeCreateEngineFromDat(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    dat_bytes: JByteArray<'_>,
) -> jlong {
    ffi_catch_unwind(0, || {
        if dat_bytes.is_null() {
            return 0;
        }
        let Ok(bytes) = env.convert_byte_array(&dat_bytes) else {
            return 0;
        };
        let Ok(engine) = FocalAdblockEngine::from_serialized_dat(&bytes) else {
            return 0;
        };
        register_engine_handle(engine)
    })
}

/// Destroys a previously created engine handle. Safe to call with `0` or already-destroyed handles.
#[no_mangle]
pub extern "system" fn Java_app_focal_adblock_RustAdblockBridge_nativeDestroyEngine(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    engine_handle: jlong,
) {
    ffi_catch_unwind((), || {
        let _ = destroy_engine_handle(engine_handle);
    });
}

/// Hot-swaps the engine's backing filter set from Brave `.dat` serialized bytes.
/// Returns `JNI_TRUE` on success, or `JNI_FALSE` if `dat_bytes` is null/corrupt or handle is invalid.
#[no_mangle]
pub extern "system" fn Java_app_focal_adblock_RustAdblockBridge_nativeLoadSerializedDat(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    engine_handle: jlong,
    dat_bytes: JByteArray<'_>,
) -> jboolean {
    ffi_catch_unwind(JNI_FALSE, || {
        if dat_bytes.is_null() {
            return JNI_FALSE;
        }
        let Some(engine) = lookup_engine_handle(engine_handle) else {
            return JNI_FALSE;
        };
        let Ok(bytes) = env.convert_byte_array(&dat_bytes) else {
            return JNI_FALSE;
        };
        if bytes.is_empty() {
            return JNI_FALSE;
        }
        match engine.load_serialized_dat(&bytes) {
            Ok(()) => JNI_TRUE,
            Err(_) => JNI_FALSE,
        }
    })
}

/// Serializes the active engine's filter set into Brave's compact `.dat` binary format.
/// Returns `null` if the handle is invalid.
#[no_mangle]
pub extern "system" fn Java_app_focal_adblock_RustAdblockBridge_nativeSerializeDat(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    engine_handle: jlong,
) -> jbyteArray {
    ffi_catch_unwind(ptr::null_mut(), || {
        let Some(engine) = lookup_engine_handle(engine_handle) else {
            return ptr::null_mut();
        };
        let bytes = engine.serialize_dat();
        match env.byte_array_from_slice(&bytes) {
            Ok(arr) => arr.into_raw(),
            Err(_) => ptr::null_mut(),
        }
    })
}

/// Reloads the engine with newline-delimited Adblock Plus / uBlock filter rules.
#[no_mangle]
pub extern "system" fn Java_app_focal_adblock_RustAdblockBridge_nativeLoadCustomRules(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    engine_handle: jlong,
    rules_text: JString<'_>,
    include_starter: jboolean,
) -> jboolean {
    ffi_catch_unwind(JNI_FALSE, || {
        let Some(engine) = lookup_engine_handle(engine_handle) else {
            return JNI_FALSE;
        };
        let raw_rules = read_nullable_jstring(&mut env, &rules_text).unwrap_or_default();
        let lines: Vec<&str> = raw_rules.lines().collect();
        engine.reload_with_rules(&lines, include_starter != JNI_FALSE);
        JNI_TRUE
    })
}

/// Zero-allocation fast-path boolean check for `WebViewClient.shouldInterceptRequest`.
#[no_mangle]
pub extern "system" fn Java_app_focal_adblock_RustAdblockBridge_nativeShouldBlock(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    engine_handle: jlong,
    request_url: JString<'_>,
    source_url: JString<'_>,
    resource_type: JString<'_>,
) -> jboolean {
    ffi_catch_unwind(JNI_FALSE, || {
        let Some(engine) = lookup_engine_handle(engine_handle) else {
            return JNI_FALSE;
        };
        let Some(req_url) = read_nullable_jstring(&mut env, &request_url) else {
            return JNI_FALSE;
        };
        let src_url = read_nullable_jstring(&mut env, &source_url).unwrap_or_default();
        let res_type =
            read_nullable_jstring(&mut env, &resource_type).unwrap_or_else(|| "other".to_string());

        if engine
            .check_network_request(&req_url, &src_url, &res_type)
            .matched
        {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

/// Rich network decision check returning `null` when allowed (zero JNI heap allocation on the
/// common allow path), or a 2-element `String[]` `[importantFlag ("1"|"0"), redirectBodyOrNull]`
/// when blocked.
#[no_mangle]
pub extern "system" fn Java_app_focal_adblock_RustAdblockBridge_nativeCheckNetworkRequest(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    engine_handle: jlong,
    request_url: JString<'_>,
    source_url: JString<'_>,
    resource_type: JString<'_>,
) -> jobjectArray {
    ffi_catch_unwind(ptr::null_mut(), || {
        let Some(engine) = lookup_engine_handle(engine_handle) else {
            return ptr::null_mut();
        };
        let Some(req_url) = read_nullable_jstring(&mut env, &request_url) else {
            return ptr::null_mut();
        };
        let src_url = read_nullable_jstring(&mut env, &source_url).unwrap_or_default();
        let res_type =
            read_nullable_jstring(&mut env, &resource_type).unwrap_or_else(|| "other".to_string());

        let decision = engine.check_network_request(&req_url, &src_url, &res_type);
        if !decision.matched {
            return ptr::null_mut();
        }

        let important_flag = if decision.important { "1" } else { "0" };
        let slots = [Some(important_flag), decision.redirect_body.as_deref()];
        match build_nullable_string_array_releasing_locals(&mut env, slots.len(), slots) {
            Some(array) => array.into_raw(),
            None => ptr::null_mut(),
        }
    })
}

/// Computes pre-paint cosmetic hide CSS rules (`selector { display: none !important; }`) for `url`.
#[no_mangle]
pub extern "system" fn Java_app_focal_adblock_RustAdblockBridge_nativeGetCosmeticCss(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    engine_handle: jlong,
    url: JString<'_>,
) -> jstring {
    ffi_catch_unwind(ptr::null_mut(), || {
        let Some(engine) = lookup_engine_handle(engine_handle) else {
            return into_raw_jstring(&env, "");
        };
        let Some(page_url) = read_nullable_jstring(&mut env, &url) else {
            return into_raw_jstring(&env, "");
        };
        let css = engine.cosmetic_css_for_url(&page_url);
        into_raw_jstring(&env, &css)
    })
}

/// Evaluates generic cosmetic hide rules against tab-separated DOM `classes` and `ids` observed
/// on `url`, returning `selector { display: none !important; }` rules (or `""` when none match).
#[no_mangle]
pub extern "system" fn Java_app_focal_adblock_RustAdblockBridge_nativeHiddenClassIdSelectors(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    engine_handle: jlong,
    url: JString<'_>,
    classes_tab_separated: JString<'_>,
    ids_tab_separated: JString<'_>,
) -> jstring {
    ffi_catch_unwind(ptr::null_mut(), || {
        let Some(engine) = lookup_engine_handle(engine_handle) else {
            return into_raw_jstring(&env, "");
        };
        let Some(page_url) = read_nullable_jstring(&mut env, &url) else {
            return into_raw_jstring(&env, "");
        };
        let raw_classes =
            read_nullable_jstring(&mut env, &classes_tab_separated).unwrap_or_default();
        let raw_ids = read_nullable_jstring(&mut env, &ids_tab_separated).unwrap_or_default();

        let classes = parse_tab_separated_tokens(&raw_classes);
        let ids = parse_tab_separated_tokens(&raw_ids);
        if classes.is_empty() && ids.is_empty() {
            return into_raw_jstring(&env, "");
        }

        let css = engine.cosmetic_css_for_classes_and_ids(&page_url, &classes, &ids);
        into_raw_jstring(&env, &css)
    })
}

/// Strips known tracking query parameters from `raw_url`.
/// Returns a `String[]` where index `0` is `cleanUrl` and indices `1..=N` are `strippedParams`.
///
/// Explicitly deletes each temporary `JString` local reference after `set_object_array_element`
/// on every loop iteration so URLs with dozens of query parameters never overflow ART's JNI
/// local reference table.
#[no_mangle]
pub extern "system" fn Java_app_focal_adblock_RustAdblockBridge_nativeCleanUrl(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    raw_url: JString<'_>,
) -> jobjectArray {
    ffi_catch_unwind(ptr::null_mut(), || {
        let input = read_nullable_jstring(&mut env, &raw_url).unwrap_or_default();
        let (clean, stripped) = clean_url(&input);

        let total_len = 1usize.saturating_add(stripped.len());
        let elements =
            std::iter::once(Some(clean.as_str())).chain(stripped.iter().map(|s| Some(s.as_str())));

        match build_nullable_string_array_releasing_locals(&mut env, total_len, elements) {
            Some(array) => array.into_raw(),
            None => ptr::null_mut(),
        }
    })
}

/// Returns the total atomic blocked network request count for `engine_handle`.
#[no_mangle]
pub extern "system" fn Java_app_focal_adblock_RustAdblockBridge_nativeGetBlockedCount(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    engine_handle: jlong,
) -> jlong {
    ffi_catch_unwind(0, || {
        let Some(engine) = lookup_engine_handle(engine_handle) else {
            return 0;
        };
        let count = engine.blocked_count();
        i64::try_from(count).unwrap_or(i64::MAX)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;
    use std::thread;

    /// Simulated ART JNI local reference frame enforcing a strict local-ref capacity ceiling
    /// and tracking live/peak/deleted local references across array construction loops.
    struct BoundedLocalRefEnv {
        max_local_refs: usize,
        next_ref_id: u64,
        live_refs: HashSet<u64>,
        string_values: HashMap<u64, String>,
        arrays: HashMap<u64, Vec<Option<String>>>,
        peak_live_refs: usize,
        deleted_string_refs: usize,
        deleted_array_refs: usize,
        fail_alloc_on_call: Option<usize>,
        fail_set_on_index: Option<jsize>,
        string_alloc_calls: usize,
    }

    impl BoundedLocalRefEnv {
        fn with_capacity(max_local_refs: usize) -> Self {
            Self {
                max_local_refs,
                next_ref_id: 1,
                live_refs: HashSet::new(),
                string_values: HashMap::new(),
                arrays: HashMap::new(),
                peak_live_refs: 0,
                deleted_string_refs: 0,
                deleted_array_refs: 0,
                fail_alloc_on_call: None,
                fail_set_on_index: None,
                string_alloc_calls: 0,
            }
        }

        fn register_local_ref(&mut self) -> Result<u64, ()> {
            if self.live_refs.len() >= self.max_local_refs {
                return Err(()); // Simulated ART local reference table overflow!
            }
            let id = self.next_ref_id;
            self.next_ref_id += 1;
            self.live_refs.insert(id);
            self.peak_live_refs = self.peak_live_refs.max(self.live_refs.len());
            Ok(id)
        }
    }

    impl JniStringArrayOps for BoundedLocalRefEnv {
        type ArrayRef = u64;
        type StringRef = u64;

        fn alloc_string_array(&mut self, len: jsize) -> Result<Self::ArrayRef, ()> {
            let len_usize = usize::try_from(len).map_err(|_| ())?;
            let id = self.register_local_ref()?;
            self.arrays.insert(id, vec![None; len_usize]);
            Ok(id)
        }

        fn alloc_string(&mut self, value: &str) -> Result<Self::StringRef, ()> {
            self.string_alloc_calls += 1;
            if self.fail_alloc_on_call == Some(self.string_alloc_calls) {
                return Err(());
            }
            let id = self.register_local_ref()?;
            self.string_values.insert(id, value.to_owned());
            Ok(id)
        }

        fn set_array_element(
            &mut self,
            array: &Self::ArrayRef,
            index: jsize,
            value: &Self::StringRef,
        ) -> Result<(), ()> {
            assert!(
                self.live_refs.contains(array),
                "array local ref must still be live"
            );
            assert!(
                self.live_refs.contains(value),
                "element JString local ref must still be live during SetObjectArrayElement"
            );
            if self.fail_set_on_index == Some(index) {
                return Err(());
            }
            let s = self.string_values.get(value).cloned().ok_or(())?;
            let slots = self.arrays.get_mut(array).ok_or(())?;
            let idx = usize::try_from(index).map_err(|_| ())?;
            slots[idx] = Some(s);
            Ok(())
        }

        fn delete_string_local_ref(&mut self, value: Self::StringRef) {
            assert!(
                self.live_refs.remove(&value),
                "element JString local ref must be deleted exactly once"
            );
            self.string_values.remove(&value);
            self.deleted_string_refs += 1;
        }

        fn delete_array_local_ref(&mut self, array: Self::ArrayRef) {
            assert!(
                self.live_refs.remove(&array),
                "array local ref must be deleted exactly once on error cleanup"
            );
            self.arrays.remove(&array);
            self.deleted_array_refs += 1;
        }
    }

    #[test]
    fn ffi_catch_unwind_intercepts_panics_and_returns_fallback() {
        let ok_result = ffi_catch_unwind(-1_i64, || 42_i64);
        assert_eq!(ok_result, 42);

        let str_panic_result = ffi_catch_unwind(JNI_FALSE, || -> jboolean {
            panic!("simulated unexpected panic inside FFI boundary");
        });
        assert_eq!(str_panic_result, JNI_FALSE);

        let dynamic_panic_result = ffi_catch_unwind(0_i64, || -> jlong {
            let idx = std::hint::black_box(99usize);
            let slice = std::hint::black_box([1, 2, 3]);
            slice[idx] // runtime out-of-bounds panic caught by catch_unwind
        });
        assert_eq!(dynamic_panic_result, 0);
    }

    #[test]
    fn handle_registry_rejects_invalid_handles_and_is_idempotent_on_destroy() {
        assert!(lookup_engine_handle(0).is_none());
        assert!(lookup_engine_handle(-55).is_none());
        assert!(!destroy_engine_handle(0));
        assert!(!destroy_engine_handle(-1));

        let handle = register_engine_handle(FocalAdblockEngine::new());
        assert!(handle > 0);
        assert!(lookup_engine_handle(handle).is_some());

        // First destroy succeeds; second destroy is a safe no-op (no double-free).
        assert!(destroy_engine_handle(handle));
        assert!(lookup_engine_handle(handle).is_none());
        assert!(!destroy_engine_handle(handle));
    }

    #[test]
    fn concurrent_lookup_during_destroy_never_causes_use_after_free() {
        let handle = register_engine_handle(FocalAdblockEngine::new());
        let arc_before_destroy = lookup_engine_handle(handle).expect("handle must exist");

        let workers: Vec<_> = (0..8)
            .map(|_| {
                let engine_ref = Arc::clone(&arc_before_destroy);
                thread::spawn(move || {
                    for _ in 0..50 {
                        let _ = lookup_engine_handle(handle);
                        let decision = engine_ref.check_network_request(
                            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
                            "https://news.example.com/story",
                            "script",
                        );
                        assert!(decision.matched);
                    }
                })
            })
            .collect();

        // Destroy handle while worker threads are actively checking URLs on cloned Arcs.
        assert!(destroy_engine_handle(handle));

        for worker in workers {
            worker.join().expect("worker thread must not panic");
        }
        assert!(arc_before_destroy.blocked_count() >= 400);
    }

    #[test]
    fn local_ref_array_builder_deletes_element_refs_every_iteration_for_large_tracker_urls() {
        // Build a URL with 80 tracking query parameters + 2 legitimate query parameters.
        let mut query_parts = vec!["id=42".to_string()];
        for i in 0..80 {
            query_parts.push(format!("utm_custom_{i}=val_{i}"));
        }
        query_parts.push("page=3".to_string());
        let raw_url = format!("https://focal.app/article?{}#top", query_parts.join("&"));

        let (clean, stripped) = clean_url(&raw_url);
        assert_eq!(clean, "https://focal.app/article?id=42&page=3#top");
        assert_eq!(stripped.len(), 80);

        // Enforce a tiny 2-slot local-reference ceiling (1 array ref + 1 active element JString).
        // Without `delete_local_ref` on every loop iteration, this would fail on iteration 2!
        let mut fake_env = BoundedLocalRefEnv::with_capacity(2);
        let total_len = 1 + stripped.len();
        let elements =
            std::iter::once(Some(clean.as_str())).chain(stripped.iter().map(|s| Some(s.as_str())));

        let array_ref =
            build_nullable_string_array_releasing_locals(&mut fake_env, total_len, elements)
                .expect("81-element String[] must succeed within 2 JNI local-ref slots");

        assert_eq!(fake_env.peak_live_refs, 2);
        assert_eq!(fake_env.deleted_string_refs, 81);
        // Only the returned outer array local ref remains live for JNI return into Java/Kotlin.
        assert_eq!(fake_env.live_refs.len(), 1);
        assert!(fake_env.live_refs.contains(&array_ref));

        let built_slots = fake_env.arrays.get(&array_ref).expect("array must exist");
        assert_eq!(built_slots.len(), 81);
        assert_eq!(built_slots[0].as_deref(), Some(clean.as_str()));
        assert_eq!(built_slots[1].as_deref(), Some("utm_custom_0"));
        assert_eq!(built_slots[80].as_deref(), Some("utm_custom_79"));
    }

    #[test]
    fn local_ref_array_builder_supports_nullable_slots_and_cleans_up_on_mid_loop_failure() {
        // 1. Nullable slot encoding (`nativeCheckNetworkRequest`: `["1", null]`)
        let mut env_ok = BoundedLocalRefEnv::with_capacity(2);
        let decision_slots = [Some("1"), None];
        let arr_id =
            build_nullable_string_array_releasing_locals(&mut env_ok, 2, decision_slots)
                .expect("nullable 2-slot array must succeed");
        assert_eq!(
            env_ok.arrays.get(&arr_id).unwrap(),
            &vec![Some("1".to_string()), None]
        );
        assert_eq!(env_ok.deleted_string_refs, 1);
        assert_eq!(env_ok.live_refs.len(), 1);

        // 2. Simulated JNI OOM during string allocation on element #12 cleans up array ref too
        let mut env_oom = BoundedLocalRefEnv::with_capacity(4);
        env_oom.fail_alloc_on_call = Some(12);
        let many = (0..30).map(|_| Some("param"));
        assert!(build_nullable_string_array_releasing_locals(&mut env_oom, 30, many).is_none());
        assert_eq!(env_oom.live_refs.len(), 0);
        assert_eq!(env_oom.deleted_array_refs, 1);

        // 3. Simulated SetObjectArrayElement error on index #5 deletes both element & array refs
        let mut env_set_err = BoundedLocalRefEnv::with_capacity(4);
        env_set_err.fail_set_on_index = Some(5);
        let many_again = (0..10).map(|_| Some("param"));
        assert!(
            build_nullable_string_array_releasing_locals(&mut env_set_err, 10, many_again)
                .is_none()
        );
        assert_eq!(env_set_err.live_refs.len(), 0);
        assert_eq!(env_set_err.deleted_string_refs, 6);
        assert_eq!(env_set_err.deleted_array_refs, 1);
    }

    #[test]
    fn parse_tab_separated_tokens_and_dat_backed_handle_resolve_dynamic_cosmetic_css() {
        assert!(parse_tab_separated_tokens("").is_empty());
        assert!(parse_tab_separated_tokens("  \t \t ").is_empty());
        assert_eq!(
            parse_tab_separated_tokens("ad-box\t  sponsor-tile \t\tpromo-card"),
            vec!["ad-box", "sponsor-tile", "promo-card"]
        );

        let compiled = FocalAdblockEngine::from_rules(
            &["##.tab-encoded-ad", "###tab-encoded-banner"],
            false,
        );
        let dat = compiled.serialize_dat();
        let restored = FocalAdblockEngine::from_serialized_dat(&dat).expect("valid .dat");
        let handle = register_engine_handle(restored);

        let engine = lookup_engine_handle(handle).expect("registered handle must resolve");
        let classes = parse_tab_separated_tokens("article-prose\ttab-encoded-ad");
        let ids = parse_tab_separated_tokens("tab-encoded-banner\thero-header");
        let css = engine.cosmetic_css_for_classes_and_ids(
            "https://magazine.example.com/feature",
            &classes,
            &ids,
        );
        assert!(css.contains(".tab-encoded-ad { display: none !important; }"));
        assert!(css.contains("#tab-encoded-banner { display: none !important; }"));
        assert!(destroy_engine_handle(handle));
    }
}
