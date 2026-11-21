#![forbid(unsafe_code)]

#[cfg(all(target_os = "android", target_pointer_width = "64"))]
mod android;
#[cfg(all(target_os = "android", target_pointer_width = "64"))]
pub use android::FilyEngine;

/// Why a request failed. Only [FilyError::Backend] means the backend itself failed, so only it justifies
/// retrying on the CPU; a cancel or an over-long prompt would fail there too.
#[derive(Debug)]
pub enum FilyError {
    Cancelled,
    Limit(String),
    Backend(String),
}

impl From<String> for FilyError {
    fn from(detail: String) -> Self {
        Self::Backend(detail)
    }
}

impl From<FilyError> for String {
    fn from(error: FilyError) -> Self {
        match error {
            FilyError::Cancelled => "Fily request cancelled".into(),
            FilyError::Limit(detail) | FilyError::Backend(detail) => detail,
        }
    }
}

pub struct Completion {
    pub text: String,
    pub tokens: u32,
    pub first_token_ms: u64,
    pub elapsed_ms: u64,
    pub gpu: bool,
}

#[cfg(not(all(target_os = "android", target_pointer_width = "64")))]
pub struct FilyEngine;

#[cfg(not(all(target_os = "android", target_pointer_width = "64")))]
impl FilyEngine {
    pub fn load(_: String, _: bool, _: String) -> Result<Self, String> {
        Err("Fily inference is available in the Android arm64 and x86-64 app".into())
    }

    pub fn complete(&self, _: String, _: String, _: u32) -> Result<Completion, FilyError> {
        Err(FilyError::Backend("Fily inference requires the Android app".into()))
    }

    pub fn cancel(&self) {}
}
