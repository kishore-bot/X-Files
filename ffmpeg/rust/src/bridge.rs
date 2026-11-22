//! JNI entry points for FfmpegLibrary and FfmpegAudioDecoder. Each export mirrors a function of the former C++ bridge.
//! No panic may unwind into the JVM, so every body runs under `guard`.

#![allow(non_snake_case)]

use std::ffi::{CString, c_void};
use std::panic::{self, AssertUnwindSafe};
use std::ptr;

use jni::objects::{JByteArray, JByteBuffer, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};

use crate::android_log;
use crate::decoder::{Decoder, Sink};
use crate::error::DECODER_ERROR_OTHER;
use crate::ffi;
use crate::format::OutputEncoding;
use crate::version;

const JNI_VERSION_1_6: jint = 0x0001_0006;
const JNI_ERR: jint = -1;

const DECODER_CLASS: &str = "androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder";
const GROW_OUTPUT_BUFFER_NAME: &str = "growOutputBuffer";
const GROW_OUTPUT_BUFFER_SIG: &str = "(Landroidx/media3/decoder/SimpleDecoderOutputBuffer;I)Ljava/nio/ByteBuffer;";

/// Runs `body`, turning a panic into `fallback` so that nothing unwinds across the FFI boundary.
fn guard<T>(fallback: T, body: impl FnOnce() -> T) -> T {
    panic::catch_unwind(AssertUnwindSafe(body)).unwrap_or_else(|_| {
        android_log::error("Panic in the FFmpeg JNI bridge.");
        fallback
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(vm: *mut jni::sys::JavaVM, _reserved: *mut c_void) -> jint {
    guard(JNI_ERR, || {
        // SAFETY: the JVM passes the VM pointer it created, which stays valid for the library's lifetime.
        let Ok(vm) = (unsafe { JavaVM::from_raw(vm) }) else {
            android_log::error("JNI_OnLoad: no JavaVM");
            return JNI_ERR;
        };
        let Ok(mut env) = vm.get_env() else {
            android_log::error("JNI_OnLoad: GetEnv failed");
            return JNI_ERR;
        };
        // The Java methods the native code calls back into are checked once, here, so a mismatched build fails to
        // load rather than failing later during decoding.
        let class = env.find_class(DECODER_CLASS);
        let method = class.and_then(|class| env.get_method_id(&class, GROW_OUTPUT_BUFFER_NAME, GROW_OUTPUT_BUFFER_SIG));
        if method.is_err() {
            android_log::error("JNI_OnLoad: growOutputBuffer lookup failed");
            return JNI_ERR;
        }
        JNI_VERSION_1_6
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_ffmpegGetVersion<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    guard(ptr::null_mut(), || {
        // SAFETY: avcodec_version reads a constant from the loaded libavcodec.
        let packed = unsafe { ffi::avcodec_version() };
        env.new_string(version::libavcodec_ident(packed)).map_or(ptr::null_mut(), |s| s.into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_ffmpegGetInputBufferPaddingSize<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jint {
    ffi::AV_INPUT_BUFFER_PADDING_SIZE as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_androidx_media3_decoder_ffmpeg_FfmpegLibrary_ffmpegHasDecoder<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    codec_name: JString<'local>,
) -> jboolean {
    guard(false as jboolean, || !find_decoder(&mut env, &codec_name).is_null() as jboolean)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_ffmpegInitialize<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    codec_name: JString<'local>,
    extra_data: JByteArray<'local>,
    output_float: jboolean,
    raw_sample_rate: jint,
    raw_channel_count: jint,
) -> jlong {
    guard(0, || {
        let codec = find_decoder(&mut env, &codec_name);
        if codec.is_null() {
            android_log::error("Codec not found.");
            return 0;
        }
        let Ok(extra) = read_extra_data(&mut env, &extra_data) else {
            return 0;
        };
        let encoding = OutputEncoding::from_output_float(output_float != 0);
        Decoder::open(codec, extra.as_deref(), encoding, raw_sample_rate, raw_channel_count).map_or(0, into_handle)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_ffmpegDecode<'local>(
    mut env: JNIEnv<'local>,
    this: JObject<'local>,
    context: jlong,
    input_data: JByteBuffer<'local>,
    input_size: jint,
    decoder_output_buffer: JObject<'local>,
    output_data: JByteBuffer<'local>,
    output_size: jint,
) -> jint {
    guard(DECODER_ERROR_OTHER, || {
        let Some(decoder) = decoder_mut(context) else {
            android_log::error("Context must be non-NULL.");
            return -1;
        };
        if input_data.is_null() || decoder_output_buffer.is_null() || output_data.is_null() {
            android_log::error("Input and output buffers must be non-NULL.");
            return -1;
        }
        if input_size < 0 {
            android_log::error(&format!("Invalid input buffer size: {input_size}."));
            return -1;
        }
        if output_size < 0 {
            android_log::error(&format!("Invalid output buffer length: {output_size}"));
            return -1;
        }
        let input = direct_address(&env, &input_data);
        if input.is_null() && input_size > 0 {
            android_log::error("Input buffer is not a direct ByteBuffer.");
            return -1;
        }
        let output = direct_address(&env, &output_data);
        if output.is_null() {
            android_log::error("Output buffer is not a direct ByteBuffer.");
            return -1;
        }

        let mut grow = |required: usize| grow_output_buffer(&mut env, &this, &decoder_output_buffer, required);
        let mut sink = Sink { base: output, capacity: output_size as usize, grow: &mut grow };
        decoder.decode(input, input_size, &mut sink)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_ffmpegGetChannelCount<'local>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
    context: jlong,
) -> jint {
    guard(-1, || match decoder_mut(context) {
        Some(decoder) => decoder.channel_count(),
        None => {
            android_log::error("Context must be non-NULL.");
            -1
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_ffmpegGetSampleRate<'local>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
    context: jlong,
) -> jint {
    guard(-1, || match decoder_mut(context) {
        Some(decoder) => decoder.sample_rate(),
        None => {
            android_log::error("Context must be non-NULL.");
            -1
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_ffmpegReset<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    context: jlong,
    extra_data: JByteArray<'local>,
) -> jlong {
    guard(0, || reset(&mut env, context, &extra_data))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_ffmpegRelease<'local>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
    context: jlong,
) {
    guard((), || {
        if context != 0 {
            // SAFETY: a non-zero handle came from into_handle and Java clears it right after this call, so the box
            // is freed exactly once.
            drop(unsafe { Box::from_raw(context as *mut Decoder) });
        }
    })
}

/// Resets the decoder for a seek. TrueHD cannot be flushed, so its context is recreated and the handle changes.
/// Returns the handle to use from now on, or 0 on failure.
fn reset(env: &mut JNIEnv, handle: jlong, extra_data: &JByteArray) -> jlong {
    let Some(decoder) = decoder_mut(handle) else {
        android_log::error("Tried to reset without a context.");
        return 0;
    };
    let codec_id = decoder.codec_id();
    if codec_id != ffi::AV_CODEC_ID_TRUEHD {
        decoder.flush();
        return handle;
    }
    let encoding = decoder.encoding();

    // SAFETY: the handle came from into_handle and is released here, exactly once. `decoder` is not used after this.
    drop(unsafe { Box::from_raw(handle as *mut Decoder) });
    // SAFETY: avcodec_find_decoder takes only an id and returns a static description.
    let codec = unsafe { ffi::avcodec_find_decoder(codec_id) };
    if codec.is_null() {
        android_log::error(&format!("Unexpected error finding codec {codec_id}."));
        return 0;
    }
    let Ok(extra) = read_extra_data(env, extra_data) else {
        return 0;
    };
    Decoder::open(codec, extra.as_deref(), encoding, -1, -1).map_or(0, into_handle)
}

/// Looks up a decoder by name. Returns null for a null or unknown name.
fn find_decoder(env: &mut JNIEnv, codec_name: &JString) -> *const ffi::AVCodec {
    if codec_name.is_null() {
        return ptr::null();
    }
    let Ok(java) = env.get_string(codec_name) else {
        return ptr::null();
    };
    let Ok(name) = CString::new(String::from(java)) else {
        return ptr::null();
    };
    // SAFETY: name is a NUL-terminated string that lives for the call.
    unsafe { ffi::avcodec_find_decoder_by_name(name.as_ptr()) }
}

/// Copies the codec setup bytes out of Java. `Ok(None)` means Java passed null.
fn read_extra_data(env: &mut JNIEnv, extra_data: &JByteArray) -> Result<Option<Vec<u8>>, ()> {
    if extra_data.is_null() {
        return Ok(None);
    }
    env.convert_byte_array(extra_data).map(Some).map_err(|_| ())
}

/// The address of a direct buffer's memory, or null if it is not direct.
fn direct_address(env: &JNIEnv, buffer: &JByteBuffer) -> *mut u8 {
    env.get_direct_buffer_address(buffer).unwrap_or(ptr::null_mut())
}

/// Asks Java to replace the output buffer with one of at least `required` bytes. Returns its address, or null.
fn grow_output_buffer(env: &mut JNIEnv, this: &JObject, decoder_output_buffer: &JObject, required: usize) -> *mut u8 {
    let args = [JValue::Object(decoder_output_buffer), JValue::Int(required as jint)];
    let grown = env
        .call_method(this, GROW_OUTPUT_BUFFER_NAME, GROW_OUTPUT_BUFFER_SIG, &args)
        .and_then(|value| value.l());
    match grown {
        Ok(buffer) => direct_address(env, &JByteBuffer::from(buffer)),
        Err(_) => {
            android_log::error("growOutputBuffer() failed");
            let _ = env.exception_describe();
            ptr::null_mut()
        }
    }
}

fn into_handle(decoder: Decoder) -> jlong {
    Box::into_raw(Box::new(decoder)) as jlong
}

/// Borrows the decoder behind a handle, or returns None for 0.
fn decoder_mut<'a>(handle: jlong) -> Option<&'a mut Decoder> {
    if handle == 0 {
        return None;
    }
    // SAFETY: a non-zero handle is a Box<Decoder> pointer from into_handle that has not been released. Java calls
    // into one decoder from one thread at a time, so this is the only live borrow.
    Some(unsafe { &mut *(handle as *mut Decoder) })
}
