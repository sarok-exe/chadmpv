//! Native video-file scanner for the chadmpv Android app.
//!
//! The scanner walks a directory tree with `std::fs::read_dir`, filters by video
//! extension and returns an array of `"path\u{1}size\u{1}mtimeSeconds"` strings
//! to Kotlin via raw JNI. No external crates are used.
//!
//! JNI is accessed through the raw function-table pointer so that the crate can
//! be built without the `jni` crate. Function-table indices are the standard
//! ones from `jni.h` (verified against the NDK 28 sysroot).

use std::collections::{HashMap, HashSet};
use std::ffi::{c_char, c_void, CStr, CString};
use std::fs;
use std::io;
use std::os::raw::c_int;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;

type JObject = *mut c_void;
type JString = JObject;
type JClass = JObject;
type JObjectArray = JObject;
type JSize = c_int;
type JBoolean = u8;

/// Standard JNI function-table indices (see `struct JNINativeInterface`).
const JNI_FIND_CLASS: usize = 6;
const JNI_EXCEPTION_CLEAR: usize = 17;
const JNI_DELETE_LOCAL_REF: usize = 23;
const JNI_NEW_STRING_UTF: usize = 167;
const JNI_GET_STRING_UTF_CHARS: usize = 169;
const JNI_RELEASE_STRING_UTF_CHARS: usize = 170;
const JNI_NEW_OBJECT_ARRAY: usize = 172;
const JNI_SET_OBJECT_ARRAY_ELEMENT: usize = 174;
const JNI_EXCEPTION_CHECK: usize = 228;

type FnFindClass = unsafe extern "system" fn(*mut c_void, *const c_char) -> JClass;
type FnExceptionClear = unsafe extern "system" fn(*mut c_void);
type FnDeleteLocalRef = unsafe extern "system" fn(*mut c_void, JObject);
type FnNewStringUtf = unsafe extern "system" fn(*mut c_void, *const c_char) -> JString;
type FnGetStringUtfChars =
    unsafe extern "system" fn(*mut c_void, JString, *mut JBoolean) -> *const c_char;
type FnReleaseStringUtfChars = unsafe extern "system" fn(*mut c_void, JString, *const c_char);
type FnNewObjectArray =
    unsafe extern "system" fn(*mut c_void, JSize, JClass, JObject) -> JObjectArray;
type FnSetObjectArrayElement = unsafe extern "system" fn(*mut c_void, JObjectArray, JSize, JObject);
type FnExceptionCheck = unsafe extern "system" fn(*mut c_void) -> JBoolean;

/// Read entry `index` out of the JNI function table.
///
/// `env` is the first argument of a native method, i.e. a pointer whose first
/// word is the table of function pointers (`JNINativeInterface*`).
#[inline]
unsafe fn jni_fn<T: Copy>(env: *mut c_void, index: usize) -> T {
    let table: *const *const c_void = *(env as *const *const *const c_void);
    let raw: *const c_void = *table.add(index);
    std::mem::transmute_copy(&raw)
}

const VIDEO_EXTENSIONS: &[&str] = &[
    "mkv", "mp4", "m4v", "webm", "avi", "mov", "ts", "m2ts", "mpg", "mpeg", "flv", "wmv", "3gp",
    "ogv", "vob", "divx", "rmvb", "asf",
];

fn is_video_file(name: &str) -> bool {
    let Some(ext) = Path::new(name).extension().and_then(|e| e.to_str()) else {
        return false;
    };
    let ext = ext.to_ascii_lowercase();
    VIDEO_EXTENSIONS.iter().any(|candidate| *candidate == ext)
}

fn should_skip_dir(name: &str, parent_name: Option<&str>) -> bool {
    if name.starts_with('.') {
        return true;
    }
    if name.eq_ignore_ascii_case("LOST.DIR") {
        return true;
    }
    if let Some(parent) = parent_name {
        if parent.eq_ignore_ascii_case("Android")
            && (name.eq_ignore_ascii_case("data") || name.eq_ignore_ascii_case("obb"))
        {
            return true;
        }
    }
    false
}

/// Recursively collect `(absolute_path, size, mtime_seconds)`, sorted by mtime
/// descending (newest first). Unreadable entries/directories are skipped.
fn scan_directory(root: &Path) -> Vec<(String, u64, i64)> {
    let mut results: Vec<(String, u64, i64)> = Vec::new();
    let mut stack: Vec<PathBuf> = vec![root.to_path_buf()];

    while let Some(dir) = stack.pop() {
        let Ok(entries) = fs::read_dir(&dir) else {
            continue;
        };

        for entry in entries.flatten() {
            let path = entry.path();
            let Ok(file_type) = entry.file_type() else {
                continue;
            };

            // Never follow symlinks (a symlinked file or directory is skipped).
            if file_type.is_symlink() {
                continue;
            }

            if file_type.is_dir() {
                let file_name = entry.file_name();
                let name = file_name.to_string_lossy();
                let parent_name = dir.file_name().map(|n| n.to_string_lossy());
                if should_skip_dir(&name, parent_name.as_deref()) {
                    continue;
                }
                stack.push(path);
            } else if file_type.is_file() {
                let file_name = entry.file_name();
                if !is_video_file(&file_name.to_string_lossy()) {
                    continue;
                }
                // `DirEntry::metadata` does not traverse symlinks on Unix.
                let Ok(metadata) = entry.metadata() else {
                    continue;
                };
                if !metadata.is_file() {
                    continue;
                }
                let mtime = metadata
                    .modified()
                    .ok()
                    .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                    .map(|d| d.as_secs() as i64)
                    .unwrap_or(0);
                results.push((path.to_string_lossy().into_owned(), metadata.len(), mtime));
            }
        }
    }

    // Newest first; path ascending as a deterministic tie-breaker.
    results.sort_by(|a, b| b.2.cmp(&a.2).then_with(|| a.0.cmp(&b.0)));
    results
}

// ---------------------------------------------------------------------------
// Incremental scanning
//
// The cache keeps one row per visited directory (its mtime at scan time) and
// one row per video file (keyed by its containing directory). On a warm scan an
// unchanged directory is never passed to `read_dir`: its cached file rows are
// reused as-is and recursion continues into the child directories the previous
// run recorded. Only new/changed directories are listed again. Directories that
// are absent from the new walk are dropped, which is how deletions propagate.
// ---------------------------------------------------------------------------

/// One cached video file row. `path` is the absolute path as a display string.
#[derive(Clone, Debug, PartialEq, Eq)]
struct CachedFile {
    path: String,
    size: u64,
    mtime: i64,
}

/// Parsed on-disk cache. `files` is keyed by containing directory and `children`
/// by parent directory so the warm path can rebuild the tree without reading
/// directory entries.
#[derive(Default)]
struct ScanCache {
    dirs: HashMap<PathBuf, i64>,
    files: HashMap<PathBuf, Vec<CachedFile>>,
    children: HashMap<PathBuf, Vec<PathBuf>>,
}

/// Instrumentation used by the unit tests/benchmark to prove the warm path
/// avoids `read_dir`.
#[derive(Default, Debug, Clone, Copy)]
struct ScanStats {
    /// Directories whose entries had to be re-listed with `read_dir`.
    dirs_listed: usize,
    /// Directories reused wholesale from the cache (no `read_dir`).
    dirs_reused: usize,
}

fn dir_mtime(path: &Path) -> Option<i64> {
    fs::metadata(path)
        .ok()
        .and_then(|m| m.modified().ok())
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_secs() as i64)
}

/// Parse the cache file. Any malformed line is silently ignored: the cache is
/// disposable and the next scan simply rewrites it.
fn load_cache(path: &Path) -> ScanCache {
    let mut cache = ScanCache::default();
    let Ok(text) = fs::read_to_string(path) else {
        return cache;
    };

    for line in text.lines() {
        let mut fields = line.split('\t');
        match fields.next() {
            Some("D") => {
                let (Some(dir), Some(mtime)) = (fields.next(), fields.next()) else {
                    continue;
                };
                if fields.next().is_some() || dir.is_empty() {
                    continue;
                }
                let Ok(mtime) = mtime.parse::<i64>() else {
                    continue;
                };
                cache.dirs.insert(PathBuf::from(dir), mtime);
            }
            Some("F") => {
                let (Some(file), Some(size), Some(mtime)) =
                    (fields.next(), fields.next(), fields.next())
                else {
                    continue;
                };
                if fields.next().is_some() || file.is_empty() {
                    continue;
                }
                let (Ok(size), Ok(mtime)) = (size.parse::<u64>(), mtime.parse::<i64>()) else {
                    continue;
                };
                let Some(parent) = Path::new(file).parent() else {
                    continue;
                };
                cache
                    .files
                    .entry(parent.to_path_buf())
                    .or_default()
                    .push(CachedFile {
                        path: file.to_string(),
                        size,
                        mtime,
                    });
            }
            _ => continue,
        }
    }

    // Parent -> child edges derived from the cached directory rows.
    for dir in cache.dirs.keys() {
        if let Some(parent) = dir.parent() {
            cache
                .children
                .entry(parent.to_path_buf())
                .or_default()
                .push(dir.clone());
        }
    }

    cache
}

fn cache_tmp_path(cache_path: &Path) -> PathBuf {
    let mut tmp = cache_path.as_os_str().to_os_string();
    tmp.push(".tmp");
    PathBuf::from(tmp)
}

/// Write the cache atomically (`<cache_path>.tmp` then rename). Callers ignore
/// the result: a failed cache write never fails the scan.
fn write_cache(cache_path: &Path, cache: &ScanCache) -> io::Result<()> {
    let mut out = String::new();
    for (dir, mtime) in &cache.dirs {
        out.push_str("D\t");
        out.push_str(&dir.to_string_lossy());
        out.push('\t');
        out.push_str(&mtime.to_string());
        out.push('\n');
    }
    for files in cache.files.values() {
        for file in files {
            out.push_str("F\t");
            out.push_str(&file.path);
            out.push('\t');
            out.push_str(&file.size.to_string());
            out.push('\t');
            out.push_str(&file.mtime.to_string());
            out.push('\n');
        }
    }

    let tmp = cache_tmp_path(cache_path);
    fs::write(&tmp, out.as_bytes())?;
    fs::rename(&tmp, cache_path)?;
    Ok(())
}

fn scan_incremental_with_stats(
    root: &Path,
    cache_path: &Path,
    cache: &ScanCache,
) -> (Vec<(String, u64, i64)>, ScanStats) {
    let mut results: Vec<(String, u64, i64)> = Vec::new();
    let mut next = ScanCache::default();
    let mut stats = ScanStats::default();
    let mut visited: HashSet<PathBuf> = HashSet::new();
    let mut stack: Vec<PathBuf> = vec![root.to_path_buf()];

    while let Some(dir) = stack.pop() {
        if !visited.insert(dir.clone()) {
            continue;
        }

        let current_mtime = dir_mtime(&dir);
        if let (Some(current), Some(&cached)) = (current_mtime, cache.dirs.get(&dir)) {
            if current == cached {
                // Warm hit: reuse the cached rows and only walk the children the
                // previous run already discovered. No `read_dir` call here.
                stats.dirs_reused += 1;
                next.dirs.insert(dir.clone(), cached);
                if let Some(files) = cache.files.get(&dir) {
                    for file in files {
                        results.push((file.path.clone(), file.size, file.mtime));
                    }
                    next.files.insert(dir.clone(), files.clone());
                }
                if let Some(children) = cache.children.get(&dir) {
                    stack.extend(children.iter().cloned());
                }
                continue;
            }
        }

        // Cold / changed directory: list it once and refresh this directory's rows.
        let Ok(entries) = fs::read_dir(&dir) else {
            continue;
        };
        stats.dirs_listed += 1;
        let new_mtime = current_mtime.or_else(|| dir_mtime(&dir)).unwrap_or(0);
        next.dirs.insert(dir.clone(), new_mtime);

        let parent_name = dir.file_name().map(|n| n.to_string_lossy().into_owned());
        let mut dir_files: Vec<CachedFile> = Vec::new();

        for entry in entries.flatten() {
            let path = entry.path();
            let Ok(file_type) = entry.file_type() else {
                continue;
            };

            // Never follow symlinks (a symlinked file or directory is skipped).
            if file_type.is_symlink() {
                continue;
            }

            if file_type.is_dir() {
                let file_name = entry.file_name();
                let name = file_name.to_string_lossy();
                if should_skip_dir(&name, parent_name.as_deref()) {
                    continue;
                }
                stack.push(path);
            } else if file_type.is_file() {
                let file_name = entry.file_name();
                if !is_video_file(&file_name.to_string_lossy()) {
                    continue;
                }
                // `DirEntry::metadata` does not traverse symlinks on Unix.
                let Ok(metadata) = entry.metadata() else {
                    continue;
                };
                if !metadata.is_file() {
                    continue;
                }
                let mtime = metadata
                    .modified()
                    .ok()
                    .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                    .map(|d| d.as_secs() as i64)
                    .unwrap_or(0);
                let path_string = path.to_string_lossy().into_owned();
                dir_files.push(CachedFile {
                    path: path_string.clone(),
                    size: metadata.len(),
                    mtime,
                });
                results.push((path_string, metadata.len(), mtime));
            }
        }

        if !dir_files.is_empty() {
            next.files.insert(dir.clone(), dir_files);
        }
    }

    // Best-effort cache refresh; a failed write must not fail the scan.
    let _ = write_cache(cache_path, &next);

    // Newest first; path ascending as a deterministic tie-breaker (same as the
    // full scan).
    results.sort_by(|a, b| b.2.cmp(&a.2).then_with(|| a.0.cmp(&b.0)));
    (results, stats)
}

fn scan_incremental(root: &Path, cache_path: &Path, cache: &ScanCache) -> Vec<(String, u64, i64)> {
    scan_incremental_with_stats(root, cache_path, cache).0
}

unsafe fn read_jstring(env: *mut c_void, value: JString) -> Option<String> {
    if value.is_null() {
        return None;
    }
    let get: FnGetStringUtfChars = jni_fn(env, JNI_GET_STRING_UTF_CHARS);
    let release: FnReleaseStringUtfChars = jni_fn(env, JNI_RELEASE_STRING_UTF_CHARS);

    let chars = get(env, value, std::ptr::null_mut());
    if chars.is_null() {
        let clear: FnExceptionClear = jni_fn(env, JNI_EXCEPTION_CLEAR);
        clear(env);
        return None;
    }
    let text = CStr::from_ptr(chars).to_string_lossy().into_owned();
    release(env, value, chars);
    Some(text)
}

unsafe fn build_string_array(env: *mut c_void, rows: &[(String, u64, i64)]) -> JObjectArray {
    let find_class: FnFindClass = jni_fn(env, JNI_FIND_CLASS);
    let class_name = b"java/lang/String\0";
    let string_class = find_class(env, class_name.as_ptr() as *const c_char);
    if string_class.is_null() {
        let clear: FnExceptionClear = jni_fn(env, JNI_EXCEPTION_CLEAR);
        clear(env);
        return std::ptr::null_mut();
    }

    let new_array: FnNewObjectArray = jni_fn(env, JNI_NEW_OBJECT_ARRAY);
    let set_element: FnSetObjectArrayElement = jni_fn(env, JNI_SET_OBJECT_ARRAY_ELEMENT);
    let new_utf: FnNewStringUtf = jni_fn(env, JNI_NEW_STRING_UTF);
    let delete_local: FnDeleteLocalRef = jni_fn(env, JNI_DELETE_LOCAL_REF);
    let exception_check: FnExceptionCheck = jni_fn(env, JNI_EXCEPTION_CHECK);
    let clear_exception: FnExceptionClear = jni_fn(env, JNI_EXCEPTION_CLEAR);

    let len = rows.len().min(c_int::MAX as usize) as JSize;
    let array = new_array(env, len, string_class, std::ptr::null_mut());
    if array.is_null() {
        if exception_check(env) != 0 {
            clear_exception(env);
        }
        delete_local(env, string_class);
        return std::ptr::null_mut();
    }

    for (index, (path, size, mtime)) in rows.iter().take(len as usize).enumerate() {
        let row = format!("{}\u{1}{}\u{1}{}", path, size, mtime);
        let Ok(c_row) = CString::new(row) else {
            continue;
        };
        let j_row = new_utf(env, c_row.as_ptr());
        if j_row.is_null() {
            // Typically OOM; stop and return the (possibly partial) array.
            break;
        }
        set_element(env, array, index as JSize, j_row);
        delete_local(env, j_row);
    }

    // Never leave a pending JNI exception behind when handing control to Kotlin.
    if exception_check(env) != 0 {
        clear_exception(env);
    }
    delete_local(env, string_class);
    array
}

/// JNI entry point: `NativeVideoScanner.nativeScanVideos(root: String)`.
///
/// Returns `Array<String>` or an empty array on failure. Any panic is caught so
/// it cannot unwind across the FFI boundary.
#[no_mangle]
pub extern "system" fn Java_live_sarok_chadmpv_utils_storage_NativeVideoScanner_nativeScanVideos(
    env: *mut c_void,
    _class: JObject,
    root: JString,
) -> JObjectArray {
    let outcome = catch_unwind(AssertUnwindSafe(|| unsafe {
        let Some(root_path) = read_jstring(env, root) else {
            return build_string_array(env, &[]);
        };
        if root_path.is_empty() {
            return build_string_array(env, &[]);
        }
        let rows = scan_directory(Path::new(&root_path));
        build_string_array(env, &rows)
    }));

    match outcome {
        Ok(array) => array,
        Err(_) => catch_unwind(AssertUnwindSafe(|| unsafe { build_string_array(env, &[]) }))
            .unwrap_or(std::ptr::null_mut()),
    }
}

/// JNI entry point: `NativeVideoScanner.nativeScanIncremental(root, cachePath)`.
///
/// Reuses/refreshes the on-disk cache at `cache_path` and returns
/// `Array<String>` newest-first, or `null` on failure. Any panic is caught so it
/// cannot unwind across the FFI boundary and no JNI exception is left pending.
#[no_mangle]
pub extern "system" fn Java_live_sarok_chadmpv_utils_storage_NativeVideoScanner_nativeScanIncremental(
    env: *mut c_void,
    _class: JObject,
    root: JString,
    cache_path: JString,
) -> JObjectArray {
    let outcome = catch_unwind(AssertUnwindSafe(|| unsafe {
        let Some(root_path) = read_jstring(env, root) else {
            return std::ptr::null_mut();
        };
        let Some(cache_path) = read_jstring(env, cache_path) else {
            return std::ptr::null_mut();
        };
        if root_path.is_empty() || cache_path.is_empty() {
            return std::ptr::null_mut();
        }

        let cache = load_cache(Path::new(&cache_path));
        let rows = scan_incremental(Path::new(&root_path), Path::new(&cache_path), &cache);
        build_string_array(env, &rows)
    }));

    match outcome {
        Ok(array) => array,
        Err(_) => {
            // Swallow any pending JNI exception (from the failed scan) and report
            // failure as `null`.
            let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
                let clear: FnExceptionClear = jni_fn(env, JNI_EXCEPTION_CLEAR);
                clear(env);
            }));
            std::ptr::null_mut()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::symlink;
    use std::time::{Duration, Instant};

    fn temp_root(tag: &str) -> PathBuf {
        let mut path = std::env::temp_dir();
        path.push(format!("chadscan-test-{}-{}", tag, std::process::id()));
        let _ = fs::remove_dir_all(&path);
        fs::create_dir_all(&path).expect("create temp root");
        path
    }

    /// Cache lives outside the scanned tree: writing it inside would change the
    /// root's mtime and defeat the incremental reuse we are testing.
    fn temp_cache(tag: &str) -> PathBuf {
        let mut path = std::env::temp_dir();
        path.push(format!("chadscan-cache-{}-{}.tsv", tag, std::process::id()));
        let _ = fs::remove_file(&path);
        let _ = fs::remove_file(cache_tmp_path(&path));
        path
    }

    fn write_video(path: &Path, contents: &[u8]) {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).unwrap();
        }
        fs::write(path, contents).unwrap();
    }

    #[test]
    fn extensions_are_case_insensitive() {
        assert!(is_video_file("movie.MKV"));
        assert!(is_video_file("clip.mp4"));
        assert!(is_video_file("show.m2ts"));
        assert!(!is_video_file("notes.txt"));
        assert!(!is_video_file("no_extension"));
        assert!(!is_video_file("archive.mkv.zip"));
    }

    #[test]
    fn skips_hidden_and_android_data_dirs() {
        assert!(should_skip_dir(".thumbnails", Some("DCIM")));
        assert!(should_skip_dir("LOST.DIR", Some("")));
        assert!(should_skip_dir("data", Some("Android")));
        assert!(should_skip_dir("obb", Some("Android")));
        assert!(!should_skip_dir("data", Some("Movies")));
        assert!(!should_skip_dir("Movies", Some("Android")));
        assert!(!should_skip_dir("DCIM", Some("")));
    }

    #[test]
    fn scans_recursively_and_skips_excluded_entries() {
        let root = temp_root("scan");
        fs::create_dir_all(root.join("Movies/Nested")).unwrap();
        fs::create_dir_all(root.join(".hidden")).unwrap();
        fs::create_dir_all(root.join("Android/data")).unwrap();
        fs::create_dir_all(root.join("Android/obb")).unwrap();
        fs::create_dir_all(root.join("LOST.DIR")).unwrap();

        fs::write(root.join("Movies/a.MKV"), b"x").unwrap();
        fs::write(root.join("Movies/Nested/b.mp4"), b"yy").unwrap();
        fs::write(root.join("Movies/note.txt"), b"zzz").unwrap();
        fs::write(root.join(".hidden/c.mp4"), b"q").unwrap();
        fs::write(root.join("Android/data/d.mp4"), b"q").unwrap();
        fs::write(root.join("Android/obb/e.mp4"), b"q").unwrap();
        fs::write(root.join("LOST.DIR/f.mp4"), b"q").unwrap();
        symlink(root.join("Movies/a.MKV"), root.join("Movies/link.mkv")).unwrap();

        let rows = scan_directory(&root);
        let names: Vec<String> = rows
            .iter()
            .map(|(path, _, _)| {
                Path::new(path)
                    .file_name()
                    .unwrap()
                    .to_string_lossy()
                    .into_owned()
            })
            .collect();

        assert_eq!(rows.len(), 2, "unexpected entries: {names:?}");
        assert!(names.contains(&"a.MKV".to_string()));
        assert!(names.contains(&"b.mp4".to_string()));
        assert!(rows.iter().all(|(_, size, _)| *size > 0));
        assert!(rows.windows(2).all(|pair| pair[0].2 >= pair[1].2));

        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn cache_round_trip_reuses_unchanged_dirs() {
        let root = temp_root("incremental-round-trip");
        let cache_path = temp_cache("incremental-round-trip");
        write_video(&root.join("Movies/a.mkv"), b"aaa");
        write_video(&root.join("Movies/Nested/b.mp4"), b"bbbb");
        write_video(&root.join("Shows/c.webm"), b"cc");

        // Cold scan: every directory (root, Movies, Movies/Nested, Shows) is listed.
        let (cold_rows, cold_stats) =
            scan_incremental_with_stats(&root, &cache_path, &load_cache(&cache_path));
        assert_eq!(cold_rows.len(), 3);
        assert_eq!(cold_stats.dirs_listed, 4);
        assert_eq!(cold_stats.dirs_reused, 0);

        // The cache file must have been written and parse back.
        assert!(cache_path.exists(), "cache file was not written");
        let warm_cache = load_cache(&cache_path);
        assert_eq!(warm_cache.dirs.len(), 4);
        assert_eq!(warm_cache.files.values().map(Vec::len).sum::<usize>(), 3);

        // Warm scan: nothing changed, so no directory may be re-listed.
        let (warm_rows, warm_stats) = scan_incremental_with_stats(&root, &cache_path, &warm_cache);
        assert_eq!(
            warm_stats.dirs_listed, 0,
            "warm scan must not call read_dir on any unchanged directory"
        );
        assert_eq!(warm_stats.dirs_reused, cold_stats.dirs_listed);
        assert_eq!(cold_rows, warm_rows);

        let _ = fs::remove_dir_all(&root);
        let _ = fs::remove_file(&cache_path);
    }

    #[test]
    fn incremental_scan_propagates_deletions() {
        let root = temp_root("incremental-delete");
        let cache_path = temp_cache("incremental-delete");
        write_video(&root.join("DCIM/keep.mp4"), b"keep");
        write_video(&root.join("DCIM/remove.mp4"), b"remove");

        let (cold_rows, _) =
            scan_incremental_with_stats(&root, &cache_path, &load_cache(&cache_path));
        assert_eq!(cold_rows.len(), 2);

        // Directory mtimes are cached with second resolution; wait long enough
        // for the deletion to be visible in the recorded mtime.
        std::thread::sleep(Duration::from_millis(1100));
        fs::remove_file(root.join("DCIM/remove.mp4")).unwrap();

        // Only DCIM changed; root is reused from cache.
        let (warm_rows, warm_stats) =
            scan_incremental_with_stats(&root, &cache_path, &load_cache(&cache_path));
        assert_eq!(warm_stats.dirs_listed, 1);
        assert_eq!(warm_stats.dirs_reused, 1);
        assert_eq!(warm_rows.len(), 1);
        assert!(warm_rows[0].0.ends_with("keep.mp4"));

        // The deletion sticks: a third run reuses everything.
        let (third_rows, third_stats) =
            scan_incremental_with_stats(&root, &cache_path, &load_cache(&cache_path));
        assert_eq!(third_stats.dirs_listed, 0);
        assert_eq!(third_rows.len(), 1);

        let _ = fs::remove_dir_all(&root);
        let _ = fs::remove_file(&cache_path);
    }

    #[test]
    fn malformed_cache_lines_are_ignored() {
        let root = temp_root("incremental-malformed");
        let cache_path = temp_cache("incremental-malformed");
        write_video(&root.join("Videos/clip.mkv"), b"clip");

        let (cold_rows, _) =
            scan_incremental_with_stats(&root, &cache_path, &load_cache(&cache_path));
        assert_eq!(cold_rows.len(), 1);

        // Corrupt a copy of the cache with junk lines; it must still parse.
        let mut text = fs::read_to_string(&cache_path).unwrap();
        text.push_str("garbage\nD\t\nD\tonly-two-fields\nF\tnope\nF\t/tmp/x.txt\tNaN\t1\n");
        let corrupt = temp_cache("incremental-malformed-corrupt");
        fs::write(&corrupt, text).unwrap();

        let parsed = load_cache(&corrupt);
        assert_eq!(parsed.dirs.len(), 2, "only the two valid D rows survive");
        assert_eq!(parsed.files.values().map(Vec::len).sum::<usize>(), 1);

        // A full rescan with the corrupted cache still produces correct results.
        let (rows, _) = scan_incremental_with_stats(&root, &cache_path, &parsed);
        assert_eq!(rows.len(), 1);

        let _ = fs::remove_dir_all(&root);
        let _ = fs::remove_file(&cache_path);
        let _ = fs::remove_file(&corrupt);
    }

    /// Synthetic-tree benchmark. Run with:
    /// `cargo test --release benchmark_cold_vs_warm_scan -- --nocapture`
    #[test]
    fn benchmark_cold_vs_warm_scan() {
        const DIRS: usize = 2000;
        const VIDEOS: usize = 200;

        let root = temp_root("bench");
        let cache_path = temp_cache("bench");

        for i in 0..DIRS {
            fs::create_dir_all(root.join(format!("d{}/sub{}", i % 50, i))).unwrap();
        }
        for i in 0..VIDEOS {
            write_video(
                &root.join(format!("d{}/sub{}/video{i}.mp4", i % 50, i)),
                b"x",
            );
        }

        let cold_start = Instant::now();
        let (cold_rows, cold_stats) =
            scan_incremental_with_stats(&root, &cache_path, &load_cache(&cache_path));
        let cold_elapsed = cold_start.elapsed();

        let warm_start = Instant::now();
        let (warm_rows, warm_stats) =
            scan_incremental_with_stats(&root, &cache_path, &load_cache(&cache_path));
        let warm_elapsed = warm_start.elapsed();

        println!(
            "benchmark dirs={} videos={} cold={:?} (listed={}) warm={:?} \
             (listed={}, reused={}) rows={}/{}",
            DIRS,
            VIDEOS,
            cold_elapsed,
            cold_stats.dirs_listed,
            warm_elapsed,
            warm_stats.dirs_listed,
            warm_stats.dirs_reused,
            cold_rows.len(),
            warm_rows.len(),
        );

        assert_eq!(cold_rows, warm_rows);
        assert_eq!(cold_stats.dirs_listed, DIRS + 51); // root + 50 `dN` + 2000 `subN`
        assert_eq!(warm_stats.dirs_listed, 0);

        let _ = fs::remove_dir_all(&root);
        let _ = fs::remove_file(&cache_path);
    }
}
