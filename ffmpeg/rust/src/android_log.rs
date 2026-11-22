//! Android logcat output under the "ffmpeg_jni" tag, as the former C++ bridge logged.

use std::ffi::{CStr, CString, c_char, c_int};

const TAG: &CStr = c"ffmpeg_jni";
const ANDROID_LOG_DEBUG: c_int = 3;
const ANDROID_LOG_ERROR: c_int = 6;

#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

pub fn error(message: &str) {
    write(ANDROID_LOG_ERROR, message);
}

pub fn debug(message: &str) {
    write(ANDROID_LOG_DEBUG, message);
}

fn write(priority: c_int, message: &str) {
    // An interior NUL cannot be represented in a C string, so it is replaced rather than dropping the message.
    let Ok(text) = CString::new(message.replace('\0', " ")) else {
        return;
    };
    // SAFETY: both strings are NUL-terminated and live for the call.
    unsafe { __android_log_write(priority, TAG.as_ptr(), text.as_ptr()) };
}
