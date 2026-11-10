//! Native video-file scanner for the chadmpv Android app.
//!
//! The scanner walks a directory tree with `std::fs::read_dir`, filters by video
//! extension and returns an array of `"path\u{1}size\u{1}mtimeSeconds"` strings
//! to Kotlin via raw JNI. No external crates are used.
//!
//! JNI is accessed through the raw function-table pointer so that the crate can
//! be built without the `jni` crate. Function-table indices are the standard
//! ones from `jni.h` (verified against the NDK 28 sysroot).

use std::ffi::{c_char, c_void, CStr, CString};
use std::fs;
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
pub extern "system" fn Java_live_mehiz_mpvkt_utils_storage_NativeVideoScanner_nativeScanVideos(
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::symlink;

    fn temp_root(tag: &str) -> PathBuf {
        let mut path = std::env::temp_dir();
        path.push(format!("chadscan-test-{}-{}", tag, std::process::id()));
        let _ = fs::remove_dir_all(&path);
        fs::create_dir_all(&path).expect("create temp root");
        path
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
}
